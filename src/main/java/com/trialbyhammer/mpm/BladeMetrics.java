package com.trialbyhammer.mpm;

/**
 * 从粒子云里读出"这把刀长什么样"。
 *
 * <p>锻造的产物是一堆粒子，但属性和战斗要用的是几何量：
 * 长度、宽度、厚度、重心、转动惯量。这个类负责那座桥。</p>
 *
 * <p>对应 ARCHITECTURE 第 13.5 节的属性映射：</p>
 * <ul>
 *   <li>软伤 ← 刃角 + 刀身宽度</li>
 *   <li>硬伤 ← 质量 + 刃角</li>
 *   <li>破甲 ← 刃角 + 质量</li>
 *   <li>攻速 ← 转动惯量</li>
 *   <li>耐久 ← 最薄处厚度</li>
 *   <li>攻击距离 ← 总长度</li>
 *   <li>击退 ← 质量 + 重心靠前</li>
 * </ul>
 */
public final class BladeMetrics {

    /** 总长度（X 方向跨度）。 */
    public final float length;

    /** 总宽度（Y 方向跨度）。 */
    public final float width;

    /** 总厚度（Z 方向跨度）。 */
    public final float thickness;

    /** 总质量。 */
    public final float mass;

    /** 重心（质量加权）。 */
    public final float centerOfMassX;
    public final float centerOfMassY;
    public final float centerOfMassZ;

    /** 绕重心三个轴的转动惯量。 */
    public final float inertiaX;
    public final float inertiaY;
    public final float inertiaZ;

    /** 沿长度方向切成若干段的截面宽度。 */
    public final float[] sectionWidths;

    /** 沿长度方向切成若干段的截面厚度。 */
    public final float[] sectionThicknesses;

    /** 最薄处的厚度——耐久由它决定（最薄的地方先坏）。 */
    public final float minThickness;

    private BladeMetrics(float length, float width, float thickness, float mass,
                         float cx, float cy, float cz,
                         float ix, float iy, float iz,
                         float[] sectionWidths, float[] sectionThicknesses,
                         float minThickness) {
        this.length = length;
        this.width = width;
        this.thickness = thickness;
        this.mass = mass;
        this.centerOfMassX = cx;
        this.centerOfMassY = cy;
        this.centerOfMassZ = cz;
        this.inertiaX = ix;
        this.inertiaY = iy;
        this.inertiaZ = iz;
        this.sectionWidths = sectionWidths;
        this.sectionThicknesses = sectionThicknesses;
        this.minThickness = minThickness;
    }

    /**
     * 从粒子云算几何量。
     *
     * @param blade    粒子云
     * @param sections 沿 X 切几段（建议 8–16）
     */
    public static BladeMetrics compute(MpmBlade blade, int sections) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        double sumMass = 0.0, sumX = 0.0, sumY = 0.0, sumZ = 0.0;

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            float m = blade.mass(i);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            sumMass += m;
            sumX += m * x;
            sumY += m * y;
            sumZ += m * z;
        }

        if (sumMass <= 0.0) {
            return new BladeMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    new float[sections], new float[sections], 0);
        }

        float cx = (float) (sumX / sumMass);
        float cy = (float) (sumY / sumMass);
        float cz = (float) (sumZ / sumMass);

        // 转动惯量：I = Σ m·r²（绕各自轴）
        double ix = 0.0, iy = 0.0, iz = 0.0;
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            float m = blade.mass(i);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            double dx = x - cx, dy = y - cy, dz = z - cz;
            ix += m * (dy * dy + dz * dz);
            iy += m * (dx * dx + dz * dz);
            iz += m * (dx * dx + dy * dy);
        }

        // 沿 X 分段：每段的 Y 跨度（宽）和 Z 跨度（厚）
        float[] secW = new float[sections];
        float[] secT = new float[sections];
        float[] secMinY = new float[sections];
        float[] secMaxY = new float[sections];
        float[] secMinZ = new float[sections];
        float[] secMaxZ = new float[sections];
        java.util.Arrays.fill(secMinY, Float.MAX_VALUE);
        java.util.Arrays.fill(secMaxY, -Float.MAX_VALUE);
        java.util.Arrays.fill(secMinZ, Float.MAX_VALUE);
        java.util.Arrays.fill(secMaxZ, -Float.MAX_VALUE);

        float span = Math.max(1.0E-6F, maxX - minX);
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            int s = (int) ((x - minX) / span * sections);
            s = Math.max(0, Math.min(sections - 1, s));
            secMinY[s] = Math.min(secMinY[s], y);
            secMaxY[s] = Math.max(secMaxY[s], y);
            secMinZ[s] = Math.min(secMinZ[s], z);
            secMaxZ[s] = Math.max(secMaxZ[s], z);
        }

        float thinnest = Float.MAX_VALUE;
        for (int s = 0; s < sections; s++) {
            if (secMaxY[s] < secMinY[s]) {
                secW[s] = 0.0F;
                secT[s] = 0.0F;
                continue;
            }
            secW[s] = secMaxY[s] - secMinY[s];
            secT[s] = secMaxZ[s] - secMinZ[s];
            if (secT[s] > 0.0F) {
                thinnest = Math.min(thinnest, secT[s]);
            }
        }
        if (thinnest == Float.MAX_VALUE) {
            thinnest = 0.0F;
        }

        return new BladeMetrics(
                maxX - minX, maxY - minY, maxZ - minZ,
                (float) sumMass, cx, cy, cz,
                (float) ix, (float) iy, (float) iz,
                secW, secT, thinnest);
    }

    /** 一行摘要，用于日志与自测。 */
    public String summary() {
        return String.format(
                "len %.3f  wid %.3f  thick %.3f  mass %.3e  com (%.3f,%.3f,%.3f)  inertia (%.3e,%.3e,%.3e)  minThick %.3f",
                length, width, thickness, mass,
                centerOfMassX, centerOfMassY, centerOfMassZ,
                inertiaX, inertiaY, inertiaZ, minThickness);
    }
}
