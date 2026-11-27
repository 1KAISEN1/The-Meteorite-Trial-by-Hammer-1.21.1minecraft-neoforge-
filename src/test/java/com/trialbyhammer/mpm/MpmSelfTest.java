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
    private static final int PASSES = 2, HITS_PER_PASS = 18;

    public static void main(String[] args) throws Exception {
        boolean narrow = args.length == 1 && args[0].equals("narrow");
        String dir = narrow ? "build/mpm/narrow" : "build/mpm";
        new File(dir).mkdirs();

        MpmBlade blade = new MpmBlade();
        MpmSolver solver = new MpmSolver();

        verifyPressAndTurn();
        double initialVolume = enclosedVolume(blade);
        float lastLength = bounds(blade)[0];
        long totalHitNanos = 0L;
        long lastHitNanos = 0L;
        long[] hitTimes = new long[PASSES * HITS_PER_PASS];
        float worstPlasticVolumeError = 0;
        Mat3 plastic = new Mat3();
        int hitCount = 0;
        check(blade.press(-1, -1, 0.05F, 0.04F, MpmBlade.HammerShape.CROSS_PEEN) == 0,
                "A miss must leave the workpiece alone");
        System.out.println("=== 初始 ===");
        report(blade, initialVolume);
        thicknessProfile(blade);

        // 模拟真实节奏：抽长一轮 → 翻面 → 再抽长一轮。
        // 每锤拍一帧，能看到材料是"怎么"流动的，而不是只看起点终点。
        new File(dir + "/frames").mkdirs();
        int frame = 0;
        renderFrame(blade, dir + "/frames/f" + String.format("%03d", frame) + ".png");
        frame++;

        for (int pass = 0; pass < PASSES; pass++) {
            for (int round = 1; round <= HITS_PER_PASS; round++) {
                // The sculpting replay works the end while it is still within the block,
                // then draws out the body. The narrow replay retains the original footprint.
                float sx;
                if (narrow) {
                    sx = 0.18F + ((round - 1) % 12) * 0.055F;
                } else if (pass == 0 && round <= 4) {
                    sx = 0.896F + (round - 1) * 0.004F;
                } else if (pass == 0) {
                    sx = 0.18F + (round - 5) * 0.048F;
                } else {
                    sx = 0.18F + (round - 1) * 0.041F;
                }
                float sy = pass == 0 ? 0.60F : 0.40F;
                long hitStart = System.nanoTime();
                float pushed = blade.press(sx, sy, 0.05F, narrow ? 0.04F : 0.095F,
                        MpmBlade.HammerShape.CROSS_PEEN);
                check(pushed > 0, "Missed contact on pass " + pass + ", hit " + round);
                solver.step(blade, DT, SUBSTEPS);
                long hitNanos = System.nanoTime() - hitStart;
                totalHitNanos += hitNanos;
                hitTimes[hitCount++] = hitNanos;
                lastHitNanos = hitNanos;

                // 逐锤打印：突变会表现为某一锤的数据跳变
                float[] s = bounds(blade);
                System.out.printf("  p%d h%02d  X %.3f  Y %.3f  Z %.3f  v %.3f%n",
                        pass + 1, round, s[0], s[1], s[2], s[3]);

                check(s[0] > lastLength && s[1] > s[2], "Draw or aspect regression at hit " + round);
                lastLength = s[0];
                for (int i = 0; i < MpmBlade.COUNT; i++) {
                    float error = Math.abs(blade.readPlasticDeform(i, plastic).det() - 1);
                    worstPlasticVolumeError = Math.max(worstPlasticVolumeError, error);
                }
                check(worstPlasticVolumeError < 1e-6F, "Plastic volume drift at hit " + round);
                double drift = Math.abs(enclosedVolume(blade) / initialVolume - 1);
                check(drift < 2e-6, "Geometric volume drift " + drift);
                renderFrame(blade, dir + "/frames/f" + String.format("%03d", frame) + ".png");
                frame++;
            }

            System.out.println("=== 第 " + (pass + 1) + " 轮抽长后 ===");
            report(blade, initialVolume);
            float[] thickness = thicknessProfile(blade);
            if (pass == 1) {
                check(thickness[4] < thickness[0] * (narrow ? 0.95 : 0.85), "Worked edge should be thinner than the back");
                float body = sectionWidth(blade, 0.55F, 0.65F);
                float tip = sectionWidth(blade, 0.95F, 0.99F);
                System.out.printf("  section width: body %.4f  tip %.4f%n", body, tip);
                check(tip < 0.7F * body, "Tip should narrow along the replayed stroke path");
            }

            if (pass == 0) {
                blade.turnOver();
                System.out.println("=== 翻面 ===");
                report(blade, initialVolume);
            }
        }

        System.out.printf("  塑性事件: %d 生效, %d 跳过（形变不够）%n",
                solver.plasticEvents, solver.plasticSkipped);

        // 性能：一锤（press + step）的耗时。决定能不能实时玩。
        System.out.printf("  每锤耗时: 平均 %.2f ms  最后一次 %.2f ms  （%d 锤）%n",
                totalHitNanos / 1.0E6 / Math.max(1, hitCount),
                lastHitNanos / 1.0E6, hitCount);

        // 几何摘要：这些数值以后要喂给属性映射（伤害、攻速、耐久……）
        if (!narrow) {
            check(Math.abs(lastLength - 1.065F) < 0.0005F, "Draw length changed: " + lastLength);
        }
        java.util.Arrays.sort(hitTimes);
        int p95 = (int)Math.ceil(hitCount * 0.95) - 1;
        System.out.printf("  median %.2f ms  p95 %.2f ms  max %.2f ms  peak |Jp-1| %.3e%n",
                (hitTimes[(hitCount - 1) / 2] + hitTimes[hitCount / 2]) / 2e6,
                hitTimes[p95] / 1e6, hitTimes[hitCount - 1] / 1e6, worstPlasticVolumeError);
        System.out.println("  STATE " + stateHash(blade));
        BladeMetrics metrics = BladeMetrics.compute(blade, 8);
        System.out.println("  " + metrics.summary());
        System.out.print("  截面宽度: ");
        for (float w : metrics.sectionWidths) {
            System.out.printf("%.3f ", w);
        }
        System.out.println();
        System.out.print("  截面厚度: ");
        for (float t : metrics.sectionThicknesses) {
            System.out.printf("%.3f ", t);
        }
        System.out.println();

        renderViews(blade, dir + "/shaped");
    }

    /** Fingerprint the unquantized state in material order, outside the hit timer. */
    private static String stateHash(MpmBlade blade) throws java.security.NoSuchAlgorithmException {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var bytes = java.nio.ByteBuffer.allocate(128);
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            bytes.clear();
            bytes.putFloat(blade.x(i)).putFloat(blade.y(i)).putFloat(blade.z(i));
            bytes.putFloat(blade.vx(i)).putFloat(blade.vy(i)).putFloat(blade.vz(i));
            bytes.putFloat(blade.mass(i)).putFloat(blade.plasticStrain(i)).putFloat(blade.pressure[i]);
            bytes.put(blade.material(i));
            for (int k = 0; k < 9; k++) {
                bytes.putFloat(blade.deform(i, k)).putFloat(blade.plasticDeform(i, k));
            }
            digest.update(bytes.array(), 0, bytes.position());
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyPressAndTurn() {
        for (MpmBlade.HammerShape face : MpmBlade.HammerShape.values()) {
            MpmBlade sample = new MpmBlade();
            double volume = enclosedVolume(sample);
            check(sample.press(0.50F, 0.57F, 0.05F, 0.08F, face) > 0, "No contact for " + face);
            float[] flat = bounds(sample);
            float jp = sample.plasticDeform(MpmBlade.COUNT / 2, 4);
            sample.flip();
            float[] upright = bounds(sample);
            check(Math.abs(flat[1] - upright[2]) < 1e-5 && Math.abs(flat[2] - upright[1]) < 1e-5,
                    "Quarter turn must stand the piece on edge");
            check(jp == sample.plasticDeform(MpmBlade.COUNT / 2, 4), "Rigid turn changed plastic state");
            sample.turnOver();
            check(Math.abs(enclosedVolume(sample) / volume - 1) < 2e-6, "Press or turn lost volume");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static float sectionWidth(MpmBlade blade, float from, float to) {
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            left = Math.min(left, blade.x(i));
            right = Math.max(right, blade.x(i));
        }
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        int count = 0;
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float fraction = (blade.x(i) - left) / (right - left);
            if (fraction >= from && fraction <= to) {
                low = Math.min(low, blade.y(i));
                high = Math.max(high, blade.y(i));
                count++;
            }
        }
        check(count >= MpmBlade.SAMPLES_Z, "Section has too few material samples");
        return high - low;
    }

    private static void renderFrame(MpmBlade blade, String path) throws Exception {
        render(blade, path, false);
    }

    /** Mean separation of the two material faces in each original width band.
     * Pairing the samples locally excludes the slope of an unsupported section. */
    private static float[] thicknessProfile(MpmBlade blade) {
        int bands = 5;
        int[] count = new int[bands];
        float[] profile = new float[bands];
        for (int x = 0; x < MpmBlade.SAMPLES_X; x++) {
            for (int y = 0; y < MpmBlade.SAMPLES_Y; y++) {
                int i = (x * MpmBlade.SAMPLES_Y + y) * MpmBlade.SAMPLES_Z;
                int band = y * bands / MpmBlade.SAMPLES_Y;
                profile[band] += Math.abs(blade.z(i) - blade.z(i + MpmBlade.SAMPLES_Z - 1));
                count[band]++;
            }
        }
        System.out.print("  local thickness (material Y, low to high):");
        for (int i = 0; i < bands; i++) {
            profile[i] /= count[i];
            System.out.printf(" %.4f", profile[i]);
        }
        System.out.println();
        return profile;
    }

    private static void renderViews(MpmBlade blade, String prefix) throws Exception {
        render(blade, prefix + "_top.png", false);
        render(blade, prefix + "_side.png", true);
    }

    private static void render(MpmBlade blade, String path, boolean side) throws Exception {
        int w = 820, h = side ? 228 : 340;
        // Reserve the original block and include material that extends past either end.
        float lo = 0, hi = 1;
        float lowV = Float.POSITIVE_INFINITY, highV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            lo = Math.min(lo, blade.x(i)); hi = Math.max(hi, blade.x(i));
            float v = side ? blade.z(i) : blade.y(i);
            lowV = Math.min(lowV, v); highV = Math.max(highV, v);
        }
        float scale = Math.min((w - 48) / (hi - lo), (h - 48) / (highV - lowV));
        float left = (w - (hi - lo) * scale) / 2;
        float mid = (lowV + highV) / 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, w, h);
        if (side) {
            int floor = Math.round(h / 2F - (MpmSolver.FLOOR_Z - mid) * scale);
            int start = Math.round(left + (MpmSolver.ANVIL_X0 - lo) * scale);
            g.setColor(new Color(62, 62, 72));
            g.fillRect(start, floor, Math.round((MpmSolver.ANVIL_X1 - MpmSolver.ANVIL_X0) * scale), h);
        }
        g.setColor(new Color(255, 170, 60));
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float v = side ? blade.z(i) : blade.y(i);
            int x = Math.round(left + (blade.x(i) - lo) * scale);
            int y = Math.round(h / 2F - (v - mid) * scale);
            g.fillRect(x, y, 2, 2);
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

    // Integrate material cells independently of the solver's F and bounding box.
    private static double enclosedVolume(MpmBlade b) {
        int dx = MpmBlade.SAMPLES_Y * MpmBlade.SAMPLES_Z, dy = MpmBlade.SAMPLES_Z;
        double sum = 0;
        for (int x = 0; x < MpmBlade.SAMPLES_X - 1; x++)
            for (int y = 0; y < MpmBlade.SAMPLES_Y - 1; y++)
                for (int z = 0; z < MpmBlade.SAMPLES_Z - 1; z++) {
                    int a = x * dx + y * dy + z, t = a + dx + dy + 1;
                    sum += tetra(b,a,a+dx,a+dx+dy,t) + tetra(b,a,a+dx+dy,a+dy,t)
                            + tetra(b,a,a+dy,a+dy+1,t) + tetra(b,a,a+dy+1,a+1,t)
                            + tetra(b,a,a+1,a+dx+1,t) + tetra(b,a,a+dx+1,a+dx,t);
                }
        return sum / 6;
    }

    private static double tetra(MpmBlade b, int a, int i, int j, int k) {
        double x = b.x(i)-b.x(a), y = b.y(i)-b.y(a), z = b.z(i)-b.z(a);
        double u = b.x(j)-b.x(a), v = b.y(j)-b.y(a), w = b.z(j)-b.z(a);
        double l = b.x(k)-b.x(a), m = b.y(k)-b.y(a), n = b.z(k)-b.z(a);
        return x*(v*n-w*m) + y*(w*l-u*n) + z*(u*m-v*l);
    }

    private static void report(MpmBlade blade, double initialVolume) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float maxSpeed = 0.0F;
        double totalMass = 0.0;
        double volume = 0, fError = 0, fpError = 0;
        Mat3 matrix = new Mat3();
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
            float jacobian = blade.readDeform(i, matrix).det();
            volume += jacobian;
            fError = Math.max(fError, Math.abs(jacobian - 1));
            fpError = Math.max(fpError, Math.abs(blade.readPlasticDeform(i, matrix).det() - 1));

            if (x < 0.0F || x > 1.0F || y < 0.0F || y > 1.0F || z < 0.0F || z > 1.0F) {
                outOfBounds++;
            }
        }

        System.out.printf("  X: %.3f .. %.3f   Y: %.3f .. %.3f   Z: %.3f .. %.3f%n",
                minX, maxX, minY, maxY, minZ, maxZ);
        System.out.printf("  size: %.3f x %.3f x %.3f  speed %.4f  outside-block %d  nan %d  mass %.4e%n",
                maxX - minX, maxY - minY, maxZ - minZ, maxSpeed, outOfBounds, nanCount, totalMass);

        System.out.printf("  mean J %.7f  max |Jp-1| %.7f  enclosed %.9f%n",
                volume / MpmBlade.COUNT, fpError, enclosedVolume(blade));
        check(nanCount == 0 && totalMass == 2.7e8, "Non-finite state or mass loss");
        check(fError < 5e-5 && fpError < 1e-6, "Invalid local material volume");
        check(Math.abs(enclosedVolume(blade) / initialVolume - 1) < 2e-6, "Invalid enclosed volume");
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
