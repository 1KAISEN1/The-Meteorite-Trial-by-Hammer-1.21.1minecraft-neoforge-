package com.trialbyhammer.mpm;

/**
 * 材料参数表。
 *
 * <p>下标与 {@link MpmBlade#material(int)} 存的字节值对应。
 * 0 保留给"空"，所以实体材料从 1 开始。</p>
 *
 * <p><b>数值说明</b>：熔点和再结晶温度取自真实金属；
 * 屈服强度和硬化系数是归一化单位下的估计值，需要实测标定。
 * 天外合金（陨铁）没有现实对应，按"比钢硬、比钢难烧"设定。</p>
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
        TABLE[EMPTY] = new MaterialParams(1500.0F, 500.0F, 0.12F, 0.5F, 0.2F, 0.5F, 0xFFFFFF);

        // 铁：熔点 1538°C，再结晶约 450°C
        TABLE[IRON] = new MaterialParams(
                1538.0F, 450.0F,
                0.12F,  // 基础屈服
                0.60F,  // K
                0.22F,  // n：纯铁硬化明显
                0.80F,  // 导热
                0xC8C8D0);

        // 钢：熔点约 1450°C（随碳含量变化，这里取中碳钢）
        TABLE[STEEL] = new MaterialParams(
                1450.0F, 500.0F,
                0.16F,  // 比铁硬
                0.90F,  // K 更高
                0.18F,  // n
                0.45F,  // 导热比纯铁低
                0xB4C8E0);

        // 铜：熔点 1085°C，再结晶仅 200°C——很容易退火
        TABLE[COPPER] = new MaterialParams(
                1085.0F, 200.0F,
                0.06F,  // 软
                0.35F,  // K
                0.30F,  // n：铜的硬化很快
                3.90F,  // 导热极好
                0xE09050);

        // 青铜：熔点约 950°C（锡含量不同差异大）
        TABLE[BRONZE] = new MaterialParams(
                950.0F, 250.0F,
                0.09F,
                0.45F,
                0.25F,
                0.50F,
                0xD0A060);

        // 天外合金：设定值，比钢硬、比钢难熔
        TABLE[METEORITE] = new MaterialParams(
                1620.0F, 620.0F,
                0.22F,
                1.20F,
                0.15F,
                0.35F,
                0x9A8CC0);
    }

    private Materials() {
    }

    /** 取材料参数；越界返回铁的参数，避免空指针。 */
    public static MaterialParams get(int id) {
        if (id < 0 || id >= TABLE.length || TABLE[id] == null) {
            return TABLE[IRON];
        }
        return TABLE[id];
    }
}
