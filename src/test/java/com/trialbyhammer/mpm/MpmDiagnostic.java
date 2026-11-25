package com.trialbyhammer.mpm;

/**
 * 诊断：检查弹塑性分解在"总形变持续累积"时，塑性部分 Fp 是否正确增长。
 *
 * <p>结构和真实 Solver 一致：</p>
 * <pre>
 * F  ← ΔF · F        （总形变由速度梯度累积）
 * Fe = F · Fp⁻¹      （拆出弹性部分）
 * σ  = stress(Fe)
 * 超屈服时返回映射，更新 Fp
 * </pre>
 *
 * <p>判断标准：Fp 应该随每次压缩单调增长，且 det(Fp) ≈ 1。</p>
 */
public final class MpmDiagnostic {

    private static final float MU = 3.0F;
    private static final float LAMBDA = 50.0F;
    private static final float YIELD = 0.12F;

    public static void main(String[] args) {
        Mat3 f = new Mat3();        // 总形变
        Mat3 fp = new Mat3();       // 塑性形变
        Mat3 fe = new Mat3();       // 弹性形变
        Mat3 sigma = new Mat3();
        Mat3 delta = new Mat3();

        System.out.println("step | ||Fp-I|| | det(Fp) | det(Fe) | |sigma| | plastic?");

        for (int step = 1; step <= 10; step++) {
            // 1. 总形变累积：Z 方向再压 10%
            delta.setIdentity();
            delta.m22 = 0.9F;
            f.mul(delta);

            // 2. Fe = F · Fp⁻¹
            Mat3 fpInv = new Mat3();
            fpInv.set(fp).invert();
            fe.set(f).mul(fpInv);

            // 3. 应力
            MpmMath.stress(fe, sigma, MU, LAMBDA);
            float mag = sigma.frobenius();

            // 4. 屈服截断 + 返回映射
            boolean plastic = MpmMath.clampToYield(sigma, YIELD);
            if (plastic) {
                Mat3 r = new Mat3();
                MpmMath.polarDecompose(fe, r);

                Mat3 dev = new Mat3();
                dev.set(fe).sub(r);

                float devMag = dev.frobenius();
                if (devMag > 1.0E-9F) {
                    float scale = YIELD / (2.0F * MU * devMag);
                    if (scale < 1.0F) {
                        dev.scale(scale);
                        fe.set(r).add(dev);

                        // Fp = Fe⁻¹ · F
                        Mat3 feInv = new Mat3();
                        feInv.set(fe).invert();
                        fp.set(feInv).mul(f);

                        // J2 塑性要求塑性流动等容：det(Fp) 必须恒为 1。
                        // 不修正的话 Fp 会一路"压缩"，材料只会被压扁，不可能抽长。
                        float detFp = fp.det();
                        if (detFp > 1.0E-6F) {
                            fp.scale((float) Math.pow(1.0F / detFp, 1.0 / 3.0));
                        }
                    }
                }
            }

            // 5. 报告
            Mat3 diff = new Mat3();
            diff.set(fp).sub(new Mat3());   // Fp - I

            System.out.printf("%4d | %8.5f | %7.4f | %7.4f | %7.4f | %s%n",
                    step, diff.frobenius(), fp.det(), fe.det(), mag, plastic ? "yes" : "no");
        }
    }
}
