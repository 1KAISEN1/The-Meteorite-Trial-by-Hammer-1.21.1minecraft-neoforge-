package trial_by_hammer.forging.material;

import java.util.List;

/**
 * 锻造阶段使用的材料响应参数；数值保持独立，便于后续改为数据驱动定义。
 * Material response parameters used by forging; values stay isolated for later data-driven definitions.
 */
public record ForgingMaterialProperties(
        double ductility,
        double interfaceFriction,
        List<PlasticityPoint> plasticityCurve) {
    /**
     * 铁测试工件的暂定曲线，不是最终平衡数值。
     * Provisional curve for the iron test billet, not final balancing data.
     */
    public static final ForgingMaterialProperties TEST_IRON = new ForgingMaterialProperties(
            0.68D,
            0.55D,
            List.of(
                    new PlasticityPoint(20, 0.015D),
                    new PlasticityPoint(450, 0.05D),
                    new PlasticityPoint(700, 0.25D),
                    new PlasticityPoint(900, 0.78D),
                    new PlasticityPoint(1100, 1.0D),
                    new PlasticityPoint(1300, 0.80D)));

    public ForgingMaterialProperties {
        ductility = clamp01(ductility);
        interfaceFriction = clamp01(interfaceFriction);
        plasticityCurve = List.copyOf(plasticityCurve);
        if (plasticityCurve.isEmpty()) {
            throw new IllegalArgumentException("Plasticity curve cannot be empty");
        }
        int previousTemperature = Integer.MIN_VALUE;
        for (PlasticityPoint point : plasticityCurve) {
            if (point.temperatureCelsius() <= previousTemperature) {
                throw new IllegalArgumentException("Plasticity temperatures must be strictly increasing");
            }
            previousTemperature = point.temperatureCelsius();
        }
    }

    public double plasticityAt(int temperatureCelsius) {
        PlasticityPoint first = this.plasticityCurve.getFirst();
        if (temperatureCelsius <= first.temperatureCelsius()) {
            return first.plasticity();
        }
        for (int index = 1; index < this.plasticityCurve.size(); index++) {
            PlasticityPoint high = this.plasticityCurve.get(index);
            if (temperatureCelsius <= high.temperatureCelsius()) {
                PlasticityPoint low = this.plasticityCurve.get(index - 1);
                double progress = (double)(temperatureCelsius - low.temperatureCelsius())
                        / (high.temperatureCelsius() - low.temperatureCelsius());
                return low.plasticity() + (high.plasticity() - low.plasticity()) * progress;
            }
        }
        return this.plasticityCurve.getLast().plasticity();
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record PlasticityPoint(int temperatureCelsius, double plasticity) {
        public PlasticityPoint {
            plasticity = clamp01(plasticity);
        }
    }
}
