package com.trialbyhammer.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.trialbyhammer.probe.ForgeProbeBlockEntity;

import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.List;

/**
 * 软金属网格生成器。
 *
 * <p>把"锤击记录"变成"可见的几何"。这是整个软体金属效果的核心，
 * 也是探针要验证的东西：一个纯函数式的形变模型到底够不够"软"。</p>
 *
 * <p>模型：把金属顶面离散成 (SEGMENTS+1)² 个采样点，每次锤击在局部
 * 施加一个高斯凹陷。高斯的好处是天然平滑、没有硬边，参数少（深度 + 半径），
 * 而且完全确定——同样的锤击序列永远得到同一个形状。</p>
 *
 * <p>刻意不做的事：</p>
 * <ul>
 *   <li>不做实时物理模拟。物理会让结果依赖帧率，破坏可复现性。</li>
 *   <li>不做体积守恒的精确求解。视觉上只需要"凹陷边缘微微隆起"，
 *       精确求解的复杂度不值这个价。</li>
 * </ul>
 */
public final class SoftMetalMesh {

    /** 顶面细分数。10 段 = 121 个采样点，对单个方块来说足够细腻。 */
    public static final int SEGMENTS = 10;

    /** 金属块的基准厚度（方块高度的比例）。 */
    private static final float BASE_HEIGHT = 0.35F;

    /** 凹陷最深不能穿透底面，留一点余量。 */
    private static final float MIN_HEIGHT = 0.04F;

    private SoftMetalMesh() {
    }

    /**
     * 计算高度场。返回 [SEGMENTS+1][SEGMENTS+1]，索引顺序为 [x][z]。
     *
     * <p>多个凹陷直接叠加。真实的塑性变形不该是简单叠加（后一锤会改变前一锤
     * 的形状），但在这个精度下叠加的观感已经足够，而且计算量小一个数量级。</p>
     */
    public static float[][] heightField(List<ForgeProbeBlockEntity.Strike> strikes) {
        int n = SEGMENTS + 1;
        float[][] heights = new float[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                float u = i / (float) SEGMENTS;
                float v = j / (float) SEGMENTS;
                float y = BASE_HEIGHT;

                for (ForgeProbeBlockEntity.Strike strike : strikes) {
                    float du = u - strike.u();
                    float dv = v - strike.v();
                    float distanceSq = du * du + dv * dv;
                    float sigmaSq = strike.radius() * strike.radius();
                    // 高斯核：距离越近压得越深，边缘平滑衰减到零。
                    y -= strike.depth() * (float) Math.exp(-distanceSq / (2.0F * sigmaSq));
                }

                heights[i][j] = Math.max(MIN_HEIGHT, y);
            }
        }
        return heights;
    }

    /**
     * 按高度场生成并提交顶点。
     *
     * @param consumer    目标顶点消费者
     * @param pose        当前变换（已平移到方块原点）
     * @param heights     高度场
     * @param packedLight 打包的光照值
     * @param temperature 0..1，用来把颜色从冷灰推到炽橙
     */
    public static void emit(VertexConsumer consumer, PoseStack.Pose pose, float[][] heights,
                            int packedLight, float temperature) {
        int n = heights.length;
        float step = 1.0F / (n - 1);
        int alpha = 255;

        // 冷 → 热的颜色插值。冷色不要用纯灰，偏一点紫更接近陨铁调色板。
        float heat = Math.max(0.0F, Math.min(1.0F, temperature));
        int red = (int) (255.0F * (0.36F + 0.64F * heat));
        int green = (int) (255.0F * (0.33F + 0.16F * heat));
        int blue = (int) (255.0F * (0.38F * (1.0F - heat) + 0.12F * heat));

        // ---- 顶面 ----
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < n - 1; j++) {
                float x0 = i * step;
                float x1 = (i + 1) * step;
                float z0 = j * step;
                float z1 = (j + 1) * step;

                float y00 = heights[i][j];
                float y10 = heights[i + 1][j];
                float y11 = heights[i + 1][j + 1];
                float y01 = heights[i][j + 1];

                // 法线用中心差分近似：n = (-dh/dx, 1, -dh/dz)，再归一化。
                float dhdx = (hAt(heights, i + 1, j) - hAt(heights, i - 1, j)) / (2.0F * step);
                float dhdz = (hAt(heights, i, j + 1) - hAt(heights, i, j - 1)) / (2.0F * step);
                float nx = -dhdx;
                float ny = 1.0F;
                float nz = -dhdz;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= len;
                ny /= len;
                nz /= len;

                quad(consumer, pose,
                        x1, y10, z0,
                        x1, y11, z1,
                        x0, y01, z1,
                        x0, y00, z0,
                        nx, ny, nz,
                        packedLight, alpha, red, green, blue,
                        i * step, j * step, step);
            }
        }

        // ---- 四周侧面 ----
        emitSide(consumer, pose, heights, 0, 0, 0, 1, false, packedLight, alpha, red, green, blue, step);
        emitSide(consumer, pose, heights, n - 1, 0, 0, 1, false, packedLight, alpha, red, green, blue, step);
        emitSide(consumer, pose, heights, 0, 0, 1, 0, false, packedLight, alpha, red, green, blue, step);
        emitSide(consumer, pose, heights, 0, n - 1, 1, 0, false, packedLight, alpha, red, green, blue, step);
    }

    /** 取高度，越界时夹住边缘值（相当于把边界当作"墙"处理）。 */
    private static float hAt(float[][] heights, int i, int j) {
        int n = heights.length;
        int ci = Math.max(0, Math.min(n - 1, i));
        int cj = Math.max(0, Math.min(n - 1, j));
        return heights[ci][cj];
    }

    /**
     * 画一条边的侧面。从高度场边缘顶点垂直到 y=0。
     *
     * @param fixedI 固定的一侧索引
     * @param fixedJ 固定的一侧索引
     * @param axisI  沿哪个轴展开（1 = 沿 i 方向）
     */
    private static void emitSide(VertexConsumer consumer, PoseStack.Pose pose, float[][] heights,
                                 int fixedI, int fixedJ, int axisI, int axisJ, boolean flip,
                                 int light, int alpha, int red, int green, int blue, float step) {
        int n = heights.length;
        float normalX = axisI != 0 ? -1.0F : 0.0F;
        float normalZ = axisJ != 0 ? -1.0F : 0.0F;

        for (int k = 0; k < n - 1; k++) {
            int i0 = fixedI + axisI * k;
            int i1 = fixedI + axisI * (k + 1);
            int j0 = fixedJ + axisJ * k;
            int j1 = fixedJ + axisJ * (k + 1);

            float x0 = i0 * step;
            float x1 = i1 * step;
            float z0 = j0 * step;
            float z1 = j1 * step;
            float y0 = heights[i0][j0];
            float y1 = heights[i1][j1];

            quad(consumer, pose,
                    x0, y0, z0,
                    x0, 0.0F, z0,
                    x1, 0.0F, z1,
                    x1, y1, z1,
                    normalX, 0.0F, normalZ,
                    light, alpha, red, green, blue,
                    k * step, 0.0F, step);
        }
    }

    /** 提交一个四边形。顶点顺序按逆时针给出，法线由调用方传入。 */
    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz,
                             int light, int alpha, int red, int green, int blue,
                             float u, float v, float step) {
        vertex(consumer, pose, x0, y0, z0, nx, ny, nz, light, alpha, red, green, blue, u, v);
        vertex(consumer, pose, x1, y1, z1, nx, ny, nz, light, alpha, red, green, blue, u, v + step);
        vertex(consumer, pose, x2, y2, z2, nx, ny, nz, light, alpha, red, green, blue, u + step, v + step);
        vertex(consumer, pose, x3, y3, z3, nx, ny, nz, light, alpha, red, green, blue, u + step, v);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose,
                               float x, float y, float z,
                               float nx, float ny, float nz,
                               int light, int alpha, int red, int green, int blue,
                               float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
