package com.trialbyhammer.mpmp;

/**
 * 厚度场模型：用一张 2D 网格记录"每格的厚度"，而不是粒子。
 *
 * <p>这是 ARCHITECTURE 最早的方案（第 2、3 节），后来被 D-038 换成了粒子 MPM。
 * 现在把它做出来做对比：</p>
 *
 * <ul>
 *   <li>好处：锤击直接改厚度，形状完全可控，不会像 MPM 那样被应力抹平</li>
 *   <li>代价：做不出截面细节（空心、扭转），只能表达"厚度"</li>
 * </ul>
 *
 * <p>核心规则只有一条：<b>体积守恒</b>。锤薄一寸，材料就流向旁边一寸。</p>
 */
public final class HeightFieldBlade {

    /** 网格分辨率。X 是刀身方向，Y 是刃↔背。 */
    public static final int NX = 64;
    public static final int NY = 32;

    /** 网格覆盖的局部范围。 */
    public static final float X_MIN = 0.10F, X_MAX = 0.90F;
    public static final float Y_MIN = 0.35F, Y_MAX = 0.65F;

    /** 每格的厚度。0 = 没有材料。 */
    private final float[] height = new float[NX * NY];

    /** 初始厚度，用于体积守恒判断。 */
    private static final float BASE_HEIGHT = 0.10F;

    public HeightFieldBlade() {
        java.util.Arrays.fill(height, BASE_HEIGHT);
    }

    public float height(int ix, int iy) {
        return height[ix + iy * NX];
    }

    public float totalVolume() {
        float sum = 0.0F;
        for (float h : height) {
            sum += h;
        }
        return sum;
    }

    /**
     * 锤击：把作用区压薄，压掉的材料流向四周。
     *
     * <p>和 MPM 版本最大的区别：这里是<b>确定性的几何操作</b>，
     * 不存在"应力把变形弹回去"的问题。压多深就是多深。</p>
     *
     * @param sx    落点 X（0..1 局部坐标）
     * @param sy    落点 Y
     * @param depth 压入深度（厚度比例）
     * @param rx    作用区半宽（X）
     * @param ry    作用区半宽（Y）
     */
    public void press(float sx, float sy, float depth, float rx, float ry) {
        // 1. 把落点换算成网格坐标
        float gx = (sx - X_MIN) / (X_MAX - X_MIN) * NX;
        float gy = (sy - Y_MIN) / (Y_MAX - Y_MIN) * NY;

        // 2. 压薄作用区，记录被压掉的体积
        float removed = 0.0F;
        for (int iy = 0; iy < NY; iy++) {
            for (int ix = 0; ix < NX; ix++) {
                float cx = (ix + 0.5F) / NX * (X_MAX - X_MIN) + X_MIN;
                float cy = (iy + 0.5F) / NY * (Y_MAX - Y_MIN) + Y_MIN;

                float ex = (cx - sx) / rx;
                float ey = (cy - sy) / ry;
                float d2 = ex * ex + ey * ey;
                if (d2 > 1.0F) {
                    continue;
                }

                float w = (1.0F - d2) * (1.0F - d2);
                int idx = ix + iy * NX;
                float cut = Math.min(height[idx], height[idx] * depth * w);
                height[idx] -= cut;
                removed += cut;
            }
        }

        // 3. 把压掉的体积分给"作用区外"的格子
        //    按距离衰减，越靠近锤击点分得越多——这就是"材料被挤出去"
        float[] add = new float[NX * NY];
        float addSum = 0.0F;

        for (int iy = 0; iy < NY; iy++) {
            for (int ix = 0; ix < NX; ix++) {
                float cx = (ix + 0.5F) / NX * (X_MAX - X_MIN) + X_MIN;
                float cy = (iy + 0.5F) / NY * (Y_MAX - Y_MIN) + Y_MIN;

                float ex = (cx - sx) / rx;
                float ey = (cy - sy) / ry;
                float d = (float) Math.sqrt(ex * ex + ey * ey);

                if (d <= 1.0F) {
                    continue;   // 作用区内不接收
                }

                // 距离衰减：越远越少，但保证整个平面都有份
                float w = 1.0F / (1.0F + (d - 1.0F) * (d - 1.0F) * 4.0F);
                add[ix + iy * NX] = w;
                addSum += w;
            }
        }

        if (addSum <= 0.0F) {
            return;
        }

        // 4. 按权重分配
        for (int i = 0; i < height.length; i++) {
            if (add[i] > 0.0F) {
                height[i] += removed * (add[i] / addSum);
            }
        }
    }

    /** 轮廓：高度高于阈值的格子算"有材料"。 */
    public boolean isSolid(int ix, int iy) {
        return height(ix, iy) > BASE_HEIGHT * 0.05F;
    }
}
