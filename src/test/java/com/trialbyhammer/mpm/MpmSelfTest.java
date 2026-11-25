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

        // 模拟真实节奏：抽长一轮 → 翻面 → 再抽长一轮。
        // 每锤拍一帧，能看到材料是"怎么"流动的，而不是只看起点终点。
        new File("build/mpm/frames").mkdirs();
        int frame = 0;
        renderFrame(blade, "build/mpm/frames/f" + String.format("%03d", frame) + ".png");
        frame++;

        for (int pass = 0; pass < 2; pass++) {
            for (int round = 1; round <= 18; round++) {
                float sx = 0.18F + ((round - 1) % 12) * 0.055F;
                blade.press(sx, 0.60F, 0.02F, 0.04F, MpmBlade.HammerShape.CROSS_PEEN);
                solver.step(blade, DT, SUBSTEPS);

                // 逐锤打印：突变会表现为某一锤的数据跳变
                float[] s = bounds(blade);
                System.out.printf("  p%d h%02d  X %.3f  Y %.3f  Z %.3f  v %.3f%n",
                        pass + 1, round, s[0], s[1], s[2], s[3]);

                renderFrame(blade, "build/mpm/frames/f" + String.format("%03d", frame) + ".png");
                frame++;
            }

            System.out.println("=== 第 " + (pass + 1) + " 轮抽长后 ===");
            report(blade);
            thicknessProfile(blade);

            if (pass == 0) {
                blade.flip();
                System.out.println("=== 翻面 ===");
                report(blade);
            }
        }

        System.out.printf("  塑性事件: %d 生效, %d 跳过（形变不够）%n",
                solver.plasticEvents, solver.plasticSkipped);
        renderViews(blade, "build/mpm/shaped");
    }

    /** 单帧：俯视图，用于拼成过程动画。 */
    private static void renderFrame(MpmBlade blade, String path) throws Exception {
        int size = 400;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, size, size);

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            int px = (int) (blade.x(i) * size);
            int py = (int) ((1.0F - blade.y(i)) * size);
            if (px < 0 || px >= size || py < 0 || py >= size) {
                continue;
            }
            g.setColor(new Color(255, 170, 60));
            g.fillRect(px, py, 2, 2);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(path));
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
            if (px < 0 || px >= size || py < 0 || py >= size) {
                continue;
            }
            // 钳制到 0..255：材料掉出画布时 z 可能为负，不钳会抛异常
            int shade = clamp255(100 + (int) (150 * Math.min(1.0F, Math.max(0.0F, blade.z(i) * 2.0F))));
            g.setColor(new Color(clamp255(shade + 80), clamp255(shade / 2 + 30), 25));
            g.fillRect(px, py, 2, 2);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
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

    /** 紧凑的包围盒 + 最大速度，用于逐锤诊断。 */
    private static float[] bounds(MpmBlade blade) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float maxSpeed = 0.0F;

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            float vx = blade.vx(i), vy = blade.vy(i), vz = blade.vz(i);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            maxSpeed = Math.max(maxSpeed, (float) Math.sqrt(vx * vx + vy * vy + vz * vz));
        }
        return new float[]{maxX - minX, maxY - minY, maxZ - minZ, maxSpeed};
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

        // 悬空段诊断：X 超过铁砧范围的部分，平均 Z 应该明显低于铁砧面
        double hangZ = 0.0;
        int hangCount = 0;
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            if (blade.x(i) > MpmSolver.ANVIL_X1) {
                hangZ += blade.z(i);
                hangCount++;
            }
        }
        if (hangCount > 0) {
            System.out.printf("  悬空段 X>%.2f: %d 个粒子, 平均 Z=%.3f（铁砧面 %.2f）%n",
                    MpmSolver.ANVIL_X1, hangCount, hangZ / hangCount, MpmSolver.FLOOR_Z);
        } else {
            System.out.println("  悬空段: 没有粒子越过砧边");
        }
    }
}
