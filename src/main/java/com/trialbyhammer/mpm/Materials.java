package com.trialbyhammer.mpm;

/**
 * 材料参数表。
 *
 * <p>下标与 {@link MpmBlade#material(int)} 存的字节值对应。
 * 0 保留给"空"，所以实体材料从 1 开始。</p>
 *
 * <p><b>数值说明</b>：熔点和再结晶温度取自真实金属；
 * 屈服、硬化、韧性是归一化单位下的估计值，需要实测标定。
 * 天外合金（陨铁）没有现实对应，按"比钢硬、比钢脆"设定。</p>
 *
 * <p><b>钢不是一种材料，是一个区间</b>：碳含量从 0.05% 到 2.1%，
 * 硬度上升、韧性下降、熔点下降。用 {@link #steel(float)} 按碳含量生成。</p>
 */
public final class Materials {

    public static final int EMPTY = 0;
    public static final int IRON = 1;
    public static final int STEEL = 2;
    public static final int COPPER = 3;
    public static final int BRONZE = 4;
    public static final int METEORITE = 5;

    private static final MaterialParams[] TABLE = new MaterialParams[6];

    static {
        TABLE[EMPTY] = new MaterialParams(1500.0F, 500.0F, 0.12F, 0.5F, 0.2F, 1.0F, 0.5F, 0xFFFFFF);

        // 铁：熔点 1538°C，再结晶约 450°C。软、韧、好锤。
        TABLE[IRON] = new MaterialParams(
                1538.0F, 450.0F,
                0.12F,  // 基础屈服
                0.60F,  // K
                0.22F,  // n：纯铁硬化明显
                2.20F,  // 韧性高：能锤很多下
                0.80F,  // 导热
                0xC8C8D0);

        // 钢：默认取中碳（0.45%），实际用 steel(carbon) 生成
        TABLE[STEEL] = steel(0.45F);

        // 铜：熔点 1085°C，再结晶仅 200°C——很容易退火。极韧。
        TABLE[COPPER] = new MaterialParams(
                1085.0F, 200.0F,
                0.06F,  // 软
                0.35F,  // K
                0.30F,  // n：铜的硬化很快
                2.60F,  // 韧性最高
                3.90F,  // 导热极好
                0xE09050);

        // 青铜：熔点约 950°C（锡含量不同差异大）
        TABLE[BRONZE] = new MaterialParams(
                950.0F, 250.0F,
                0.09F,
                0.45F,
                0.25F,
                1.80F,  // 比铜脆一点
                0.50F,
                0xD0A060);

        // 天外合金：设定值，比钢硬、比钢脆
        TABLE[METEORITE] = new MaterialParams(
                1620.0F, 620.0F,
                0.22F,
                1.20F,
                0.15F,
                0.60F,  // 韧性低：难加工
                0.35F,
                0x9A8CC0);
    }

    private Materials() {
    }

    /**
     * 按碳含量生成钢的参数。
     *
     * <p>碳含量 0 → 纯铁，2.0% → 超高碳钢。真实规律：</p>
     * <ul>
     *   <li>碳越高，熔点越低（Fe-C 相图）</li>
     *   <li>碳越高，硬度越高（屈服、K 上升）</li>
     *   <li>碳越高，韧性越低（能锤的次数变少）</li>
     *   <li>碳越高，导热越差</li>
     * </ul>
     *
     * @param carbon 碳含量（0.0–2.1，单位是质量百分比 / 100）
     */
    public static MaterialParams steel(float carbon) {
        float t = Math.max(0.0F, Math.min(1.0F, carbon / 2.0F));

        return new MaterialParams(
                1538.0F - 200.0F * t,   // 熔点 1538 → 1338
                450.0F + 150.0F * t,    // 再结晶 450 → 600
                0.10F + 0.15F * t,      // 屈服 0.10 → 0.25
                0.60F + 1.00F * t,      // K 0.60 → 1.60
                0.22F - 0.05F * t,      // n 0.22 → 0.17
                2.00F - 1.70F * t,      // 韧性 2.00 → 0.30
                0.80F - 0.40F * t,      // 导热 0.80 → 0.40
                tintForCarbon(t));
    }

    /** 碳越高，颜色越偏蓝灰（视觉提示）。 */
    private static int tintForCarbon(float t) {
        int r = (int) (0xD0 - 40 * t);
        int g = (int) (0xD8 - 20 * t);
        int b = (int) (0xE0 + 10 * t);
        return (r << 16) | (g << 8) | b;
    }

    /** 取材料参数；越界返回铁的参数，避免空指针。 */
    public static MaterialParams get(int id) {
        if (id < 0 || id >= TABLE.length || TABLE[id] == null) {
            return TABLE[IRON];
        }
        return TABLE[id];
    }
}
