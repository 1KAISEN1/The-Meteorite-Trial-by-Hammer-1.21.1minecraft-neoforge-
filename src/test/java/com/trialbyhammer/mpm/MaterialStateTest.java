package com.trialbyhammer.mpm;

/**
 * 验证温度、硬化的数学。
 *
 * <p>不跑 MPM，也不碰存档（存档依赖 Minecraft 的 CompoundTag，
 * 放在带游戏 classpath 的自测里验证）。这里只测纯函数。</p>
 */
public final class MaterialStateTest {

    public static void main(String[] args) {
        testTemperatureScale();
        testHardening();
        testMaterialTable();
        System.out.println("全部通过");
    }

    private static void testTemperatureScale() {
        MaterialParams steel = Materials.get(Materials.STEEL);

        float cold = steel.temperatureScale(20.0F);
        float forging = steel.temperatureScale(900.0F);
        float nearMelt = steel.temperatureScale(1400.0F);

        System.out.printf("钢的温度系数: 20°C %.3f  900°C %.3f  1400°C %.3f%n",
                cold, forging, nearMelt);

        check(cold > forging, "冷态应该比锻造温度硬");
        check(forging > nearMelt, "接近熔点时应该更软");
        check(nearMelt > 0.0F, "不能软到 0（会失去约束）");
        check(steel.forgeLow() < steel.forgeHigh(), "可锻区间上下沿顺序不对");
    }

    private static void testHardening() {
        MaterialParams steel = Materials.get(Materials.STEEL);

        float fresh = steel.hardenedYield(0.0F);
        float worked = steel.hardenedYield(0.5F);
        float heavilyWorked = steel.hardenedYield(2.0F);

        System.out.printf("钢的硬化屈服: εp=0 %.4f  εp=0.5 %.4f  εp=2.0 %.4f%n",
                fresh, worked, heavilyWorked);

        check(worked > fresh, "锤过之后应该更硬");
        check(heavilyWorked > worked, "硬化应该单调递增");

        // 退火前后
        MpmBlade blade = new MpmBlade();
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            blade.addPlasticStrain(i, 1.0F);
        }
        check(blade.plasticStrain(0) > 0.0F, "塑性应变没有累加");
        blade.anneal();
        check(blade.plasticStrain(0) == 0.0F, "退火没有清零");

        System.out.printf("退火: εp 从 1.0 回到 %.1f%n", blade.plasticStrain(0));
    }

    private static void testMaterialTable() {
        System.out.println("材料表:");
        String[] names = {"空", "铁", "钢", "铜", "青铜", "天外合金"};
        for (int id = 1; id <= 5; id++) {
            MaterialParams p = Materials.get(id);
            System.out.printf("  %-6s Tm=%.0f°C  可锻 %.0f–%.0f°C  屈服 %.3f  K=%.2f n=%.2f%n",
                    names[id], p.meltingPoint(), p.forgeLow(), p.forgeHigh(),
                    p.yieldBase(), p.hardeningK(), p.hardeningN());
        }

        // 铜应该比钢容易烧软
        check(Materials.get(Materials.COPPER).forgeLow()
                        < Materials.get(Materials.STEEL).forgeLow(),
                "铜的可锻温度应该比钢低");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
