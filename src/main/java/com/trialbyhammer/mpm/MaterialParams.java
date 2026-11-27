package com.trialbyhammer.mpm;

/**
 * 一种可锻金属的物理参数。
 *
 * <p>这些数值决定"这块金属烧到多少度开始软、锤多少下会变硬"。
 * 全部用绝对单位：温度是摄氏度，屈服强度沿用求解器的归一化单位。</p>
 *
 * <p><b>参数来源</b>：真实金属的熔点、再结晶温度是实测值；
 * 屈服强度和硬化系数在归一化单位里没有物理对应，是按"手感接近"估的，
 * 需要进游戏后再标定。</p>
 */
public record MaterialParams(
        /** 熔点（°C）。决定可锻区间的上限。 */
        float meltingPoint,

        /** 再结晶温度（°C）。超过它，冷作硬化会被退火清零。 */
        float recrystallize,

        /** 常温基础屈服强度。 */
        float yieldBase,

        /** Hollomon 硬化系数 K：σ = K · εp^n。 */
        float hardeningK,

        /** Hollomon 硬化指数 n。钢约 0.1–0.25，越大硬化越快。 */
        float hardeningN,

        /**
         * 韧性：断裂前能承受的塑性应变上限。
         *
         * <p>和 {@link #yieldBase}（硬度）一起决定"脆"的程度：
         * 硬度 ÷ 韧性 = 脆性。低碳钢韧性约 2.0（能锤很多下），
         * 高碳钢只有 0.3（锤几下就到底）。</p>
         *
         * <p>v1 不模拟裂纹，超过这个值只是"不再变形"；
         * v2 再让它裂开。</p>
         */
        float toughness,

        /** 导热系数，用于粒子间热传导。 */
        float conductivity,

        /** 色调偏移（RGB 打包），用于烧红时叠加材料色。 */
        int tint
) {

    /**
     * 脆性 = 硬度 / 韧性。派生量，不是独立参数。
     *
     * <p>高 = 硬而脆（易裂），低 = 软而韧（耐锤）。</p>
     */
    public float brittleness() {
        return yieldBase / Math.max(0.01F, toughness);
    }

    /** 这个塑性应变是否已经超过韧性上限。 */
    public boolean isOverworked(float plasticStrain) {
        return plasticStrain > toughness;
    }

    /** 可锻区间下沿：低于这个温度，金属几乎锤不动。 */
    public float forgeLow() {
        return 0.60F * meltingPoint;
    }

    /** 可锻区间上沿：高于这个温度，接近熔化，不该再锤。 */
    public float forgeHigh() {
        return 0.95F * meltingPoint;
    }

    /**
     * 温度对屈服的缩放系数。
     *
     * <p>低温柔软度接近 1（或更高），进入可锻区间后迅速下降，
     * 接近熔点时降到最低但不为 0——为 0 会让求解器失去约束。</p>
     */
    public float temperatureScale(float celsius) {
        float ratio = celsius / meltingPoint;
        if (ratio < .6F) return 100000;
        double u = Math.clamp((ratio - .6) / .35, 0, 1);
        return (float)(.08 + 10 * Math.pow(1-u, 12));
    }

    /**
     * 冷作硬化后的屈服强度。
     *
     * <p>Hollomon 公式 σ = K·εp^n。εp 为 0 时返回基础屈服。</p>
     */
    public float hardenedYield(float plasticStrain) {
        if (plasticStrain <= 0.0F) {
            return 0;
        }
        return hardeningK * (float) Math.pow(plasticStrain, hardeningN);
    }

    /** 综合屈服：温度软化和冷作硬化两者取较大值。 */
    public float effectiveYield(float celsius, float plasticStrain) {
        return Math.max(
                temperatureScale(celsius) * yieldBase,
                hardenedYield(plasticStrain));
    }
    private static final int[] DEGREES = {20, 500, 800, 1000, 1200};
    private static final int[] RGB = {0x171717, 0x821818, 0xff831b, 0xffe352, 0xffffff};

    public int color(float temperature) {
        int k = 1;
        while (k < DEGREES.length - 1 && temperature > DEGREES[k]) k++;
        float u = Math.clamp((temperature-DEGREES[k-1]) / (DEGREES[k]-DEGREES[k-1]), 0, 1);
        int rgb = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = RGB[k-1] >> shift & 255, b = RGB[k] >> shift & 255;
            float tintStrength = .35F * Math.clamp((1200-temperature)/1180, 0, 1);
            float tintScale = 1 - tintStrength * (1 - ((tint >> shift) & 255)/255F);
            rgb |= Math.round((a + u*(b-a))*tintScale) << shift;
        }
        return rgb;
    }
}
