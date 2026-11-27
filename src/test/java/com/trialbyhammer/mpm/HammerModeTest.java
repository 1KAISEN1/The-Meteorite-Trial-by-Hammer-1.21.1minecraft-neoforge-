package com.trialbyhammer.mpm;

/**
 * 对比三种锤击方式，确认 press / strike 到底该用哪个、还是都用。
 *
 * <p>游戏里 {@code ForgeProbeBlockEntity.hammer} 同时调了 press 和 strike，
 * 但自测里只用 press。这个测试把三种组合放在同一条件下跑，看哪个结果对。</p>
 */
public final class HammerModeTest {

    private static final float DT = 1.0F / 120.0F;
    private static final int SUBSTEPS = 20;

    public static void main(String[] args) {
        System.out.println("mode            X      Y      Z     thickness(back->edge)");

        run("press only", 1);
        run("strike only", 2);
        run("press+strike", 3);
    }

    private static void run(String name, int mode) {
        MpmBlade blade = new MpmBlade();
        MpmSolver solver = new MpmSolver();

        for (int pass = 0; pass < 2; pass++) {
            for (int round = 1; round <= 18; round++) {
                float sx = 0.18F + (round - 1) * 0.041F;
                if (sx > 0.8F) {
                    sx = 0.18F + (round - 1) * 0.041F;
                }
                float sy = pass == 0 ? 0.60F : 0.40F;

                if (mode == 1 || mode == 3) {
                    blade.press(sx, sy, 0.05F, 0.095F, MpmBlade.HammerShape.CROSS_PEEN);
                }
                if (mode == 2 || mode == 3) {
                    blade.strike(sx, sy, 0.0F, 0.0F, -1.0F, 0.095F, 0.05F,
                            MpmBlade.HammerShape.CROSS_PEEN);
                }
                solver.step(blade, DT, SUBSTEPS);
            }
            if (pass == 0) {
                blade.turnOver();
            }
        }

        BladeMetrics m = BladeMetrics.compute(blade, 8);
        StringBuilder sb = new StringBuilder();
        for (float t : m.sectionThicknesses) {
            sb.append(String.format("%.3f ", t));
        }
        System.out.printf("%-14s %.3f  %.3f  %.3f   %s%n",
                name, m.length, m.width, m.thickness, sb);
    }
}
