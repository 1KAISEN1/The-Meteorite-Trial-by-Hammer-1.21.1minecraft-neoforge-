package com.trialbyhammer.mpm;

/**
 * MPM 需要的 3×3 矩阵运算：极分解与应力。
 *
 * <ul>
 *   <li><b>极分解</b>：把形变矩阵 F 拆成"旋转 R × 拉伸 S"，旋转不产生应力。</li>
 *   <li><b>应力</b>：由 F 算材料内部应力，这是体积守恒与各向异性的来源。</li>
 * </ul>
 *
 * <p>极分解用 Higham 迭代：R ← (R + R⁻ᵀ)/2，几次就收敛。比完整 SVD 便宜。</p>
 */
public final class MpmMath {

    /** 极分解迭代次数。3 次足够稳定。 */
    private static final int POLAR_ITERATIONS = 3;

    private MpmMath() {
    }

    /**
     * 极分解：F = R · S，只返回旋转部分 R。
     */
    public static void polarDecompose(Mat3 f, Mat3 outR) {
        outR.set(f);

        Mat3 inv = new Mat3();
        Mat3 sum = new Mat3();

        for (int i = 0; i < POLAR_ITERATIONS; i++) {
            inv.set(outR).invert().transpose();
            sum.set(outR).add(inv).scale(0.5F);
            outR.set(sum);
        }
    }

    /**
     * Cauchy 应力（固定 corotated 模型）：
     *
     * <pre>
     * σ = 2μ(F − R) + λ(J − 1)·J·F⁻ᵀ
     * </pre>
     *
     * 第一项是形状恢复力（各向异性来源），第二项是体积恢复力（体积守恒来源）。
     */
    public static void stress(Mat3 f, Mat3 outSigma, float mu, float lambda) {
        Mat3 r = new Mat3();
        polarDecompose(f, r);

        float j = f.det();

        Mat3 invT = new Mat3();
        invT.set(f).invert().transpose();

        outSigma.set(f).sub(r).scale(2.0F * mu);

        invT.scale(lambda * (j - 1.0F) * j);
        outSigma.add(invT);
    }

    /**
     * 理想塑性：把应力截断到屈服面内。
     *
     * <p>应力超过屈服强度就按比例缩回去。这样材料不会无限回弹，锤下去就定型。</p>
     *
     * @return 是否发生了塑性流动（用于累积硬化）
     */
    public static boolean clampToYield(Mat3 sigma, float yield) {
        float mag = sigma.frobenius();
        if (mag <= yield || mag < 1.0E-9F) {
            return false;
        }
        sigma.scale(yield / mag);
        return true;
    }
}
