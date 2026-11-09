package com.trialbyhammer.mpm;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * MPM 自测：验证"能不能锤出一个形状"。
 *
 * <p>任务：沿刃口方向锤一条线，目标是出现「刀背厚、刃口薄」的坡度。
 * 如果厚度沿 Y 方向形成梯度，说明这套算法可以支撑自由锻刀。</p>
 *
 * <p>用法：{@code .\gradlew.bat mpmSelfTest}</p>
 */
public final class MpmSelfTest {

    private static final float DT = 1.0F / 120.0F;
    private static final int SUBSTEPS = 20;

    public static void main(String[] args) throws Exception {
        new File("build/mpm").mkdirs();

        MpmBlade blade = new MpmBlade();
        MpmSolver solver = new MpmSolver();

        System.out.println("=== 初始 ===");
        report(blade);
        thicknessProfile(blade);

        // 沿刀身锤一排，落点在 Y = 0.62（靠近刃口，刃口在 0.65）
        for (int round = 1; round <= 40; round++) {
            float sx = 0.18F + ((round - 1) % 12) * 0.055F;
            blade.strike(sx, 0.60F, 0.25F, 0.0F, -0.97F, 0.04F, 3.0F);
            solver.step(blade, DT, SUBSTEPS);
        }

        System.out.println("=== 24 锤后 ===");
        report(blade);
        thicknessProfile(blade);

        renderViews(blade, "build/mpm/shaped");
    }

    /**
     * 沿 Y 方向分 8 段，统计每段的**厚度**（该段粒子 Z 的跨度）。
     *
     * <p>如果刃口（Y 大）比刀背（Y 小）明显薄，坡度就出来了。</p>
     */
    private static void thicknessProfile(MpmBlade blade) {
        int bands = 8;
        float[] zMin = new float[bands];
        float[] zMax = new float[bands];
        int[] count = new int[bands];

        java.util.Arrays.fill(zMin, Float.MAX_VALUE);
        java.util.Arrays.fill(zMax, -Float.MAX_VALUE);

        float yMin = 0.30F, yMax = 0.70F;

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float y = blade.y(i);
            if (y < yMin || y >= yMax) {
                continue;
            }
            int b = (int) ((y - yMin) / (yMax - yMin) * bands);
            b = Math.max(0, Math.min(bands - 1, b));
            float z = blade.z(i);
            zMin[b] = Math.min(zMin[b], z);
            zMax[b] = Math.max(zMax[b], z);
            count[b]++;
        }

        System.out.print("  厚度分布（刀背→刃口）: ");
        for (int b = 0; b < bands; b++) {
            float thickness = count[b] > 0 ? zMax[b] - zMin[b] : 0.0F;
            System.out.printf("%.3f ", thickness);
        }
        System.out.println();
    }

    private static void renderViews(MpmBlade blade, String prefix) throws Exception {
        topView(blade, prefix + "_top.png");
        sideView(blade, prefix + "_side.png");
        System.out.println("  images: " + prefix + "_top.png / _side.png");
    }

    private static void topView(MpmBlade blade, String path) throws Exception {
        int size = 600;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, size, size);

        g.setColor(new Color(70, 70, 82));
        g.drawRect(1, 1, size - 2, size - 2);
        g.setColor(new Color(125, 125, 140));
        g.fillRoundRect(size / 2 - 90, 26, 180, 44, 12, 12);

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            int px = (int) (blade.x(i) * size);
            int py = (int) ((1.0F - blade.y(i)) * size);
            int shade = 100 + (int) (150 * Math.min(1.0F, blade.z(i) * 2.0F));
            g.setColor(new Color(Math.min(255, shade + 80), shade / 2 + 30, 25));
            g.fillRect(px, py, 2, 2);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    private static void sideView(MpmBlade blade, String path) throws Exception {
        int w = 600, h = 260;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, w, h);

        // Z 轴放大：只显示铁砧附近 0.28–0.46，厚度变化才看得清
        final float zMin = 0.28F, zMax = 0.46F;
        int anvilY = (int) ((1.0F - (MpmSolver.FLOOR_Z - zMin) / (zMax - zMin)) * h);

        g.setColor(new Color(62, 62, 72));
        g.fillRect(0, anvilY, w, h - anvilY);
        g.setColor(new Color(120, 120, 135));
        g.drawLine(0, anvilY, w, anvilY);

        g.setColor(new Color(125, 125, 140));
        g.fillRect(w / 2 - 55, 12, 110, 32);
        g.fillRect(w / 2 - 10, 44, 20, 74);

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            int px = (int) (blade.x(i) * w);
            int py = (int) ((1.0F - (blade.z(i) - zMin) / (zMax - zMin)) * h);
            g.setColor(new Color(255, 170, 60));
            g.fillRect(px, py, 1, 1);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    private static void report(MpmBlade blade) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float maxSpeed = 0.0F;
        double totalMass = 0.0;
        int nanCount = 0;
        int outOfBounds = 0;

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i);
            float y = blade.y(i);
            float z = blade.z(i);
            float vx = blade.vx(i);
            float vy = blade.vy(i);
            float vz = blade.vz(i);

            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz)) {
                nanCount++;
                continue;
            }

            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);

            float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            maxSpeed = Math.max(maxSpeed, speed);

            totalMass += blade.mass(i);

            if (x < 0.0F || x > 1.0F || y < 0.0F || y > 1.0F || z < 0.0F || z > 1.0F) {
                outOfBounds++;
            }
        }

        System.out.printf("  X: %.3f .. %.3f   Y: %.3f .. %.3f   Z: %.3f .. %.3f%n",
                minX, maxX, minY, maxY, minZ, maxZ);
        System.out.printf("  size: %.3f x %.3f x %.3f  speed %.4f  oob %d  nan %d  mass %.4e%n",
                maxX - minX, maxY - minY, maxZ - minZ, maxSpeed, outOfBounds, nanCount, totalMass);
    }
}
