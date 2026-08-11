package trial_by_hammer.forging.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece.DeformationParameters;

/**
 * 用守恒的连续面积映射近似一次热锻锤击产生的面内塑性流动。
 * Approximates the in-plane plastic flow of one hot-forging blow with a conservative continuous area map.
 */
final class ContinuumPlasticFlowSolver {
    static final int HAMMER_HALF_LENGTH_CELLS = LayeredSlabWorkpiece.HAMMER_FACE_HALF_LENGTH_CELLS;
    static final int HAMMER_HALF_WIDTH_CELLS = LayeredSlabWorkpiece.HAMMER_FACE_HALF_WIDTH_CELLS;
    static final int FLOW_FALLOFF_LENGTH_CELLS = 8 * LayeredSlabWorkpiece.RESOLUTION_SCALE;
    static final int FLOW_FALLOFF_WIDTH_CELLS = 6 * LayeredSlabWorkpiece.RESOLUTION_SCALE;

    private static final double MINIMUM_EFFECTIVE_RESPONSE = 0.02D;
    private static final double MAXIMUM_LENGTHWISE_STRAIN = 0.025D;
    private static final double MAXIMUM_CROSSWISE_STRAIN = 0.045D;
    private static final double MASS_EPSILON = 1.0E-8D;

    private ContinuumPlasticFlowSolver() {
    }

    static SolveResult solve(
            byte[] materials,
            short[] thicknessUnits,
            int centerX,
            int centerZ,
            int force,
            DeformationParameters deformation) {
        if (!inside(centerX, centerZ)) {
            return SolveResult.NO_CHANGE;
        }

        byte contactMaterial = contactMaterial(materials, thicknessUnits, centerX, centerZ);
        if (contactMaterial == LayeredSlabWorkpiece.EMPTY) {
            return SolveResult.NO_CHANGE;
        }

        int clampedForce = Math.max(1, Math.min(10, force));
        double forceResponse = Math.pow(clampedForce / 10.0D, 1.15D);
        double materialResponse = clamp01(deformation.plasticity())
                * (0.55D + 0.45D * clamp01(deformation.ductility()));
        double effectiveResponse = forceResponse * materialResponse;
        if (effectiveResponse < MINIMUM_EFFECTIVE_RESPONSE) {
            return SolveResult.NO_CHANGE;
        }

        // 锤面摩擦会消耗部分可用于横向流动的做功，但不会把影响区硬截成几圈格子。
        // Tool friction consumes part of the work available for lateral flow without hard-clipping the influence zone.
        double frictionResponse = 1.0D - 0.28D * clamp01(deformation.interfaceFriction());
        double lengthwiseStrain = MAXIMUM_LENGTHWISE_STRAIN * effectiveResponse * frictionResponse;
        double crosswiseStrain = MAXIMUM_CROSSWISE_STRAIN * effectiveResponse * frictionResponse;

        int cellCount = LayeredSlabWorkpiece.GRID_SIZE * LayeredSlabWorkpiece.GRID_SIZE;
        double[] transportedMass = new double[cellCount];
        long totalTransportedMass = 0L;

        for (int x = 0; x < LayeredSlabWorkpiece.GRID_SIZE; x++) {
            for (int z = 0; z < LayeredSlabWorkpiece.GRID_SIZE; z++) {
                int cell = index(x, z);
                int thickness = Short.toUnsignedInt(thicknessUnits[cell]);
                if (thickness == 0 || materials[cell] != contactMaterial) {
                    continue;
                }

                MappedCell mapped = mapCell(
                        x,
                        z,
                        centerX,
                        centerZ,
                        lengthwiseStrain,
                        crosswiseStrain);
                if (!mapped.insideGrid()
                        || !deposit(mapped, thickness, contactMaterial, materials, transportedMass)) {
                    // 当前单层原型不能让两种材料占据同一柱，也不能把质量推出求解域。
                    // The single-layer prototype cannot overlap two materials or push mass outside the solve domain.
                    return SolveResult.NO_CHANGE;
                }
                totalTransportedMass += thickness;
            }
        }

        QuantizedMass quantized = quantize(transportedMass, totalTransportedMass);
        if (quantized == null) {
            return SolveResult.NO_CHANGE;
        }

        byte[] nextMaterials = materials.clone();
        short[] nextThickness = thicknessUnits.clone();
        long absoluteDifference = 0L;
        for (int cell = 0; cell < cellCount; cell++) {
            int oldThickness = Short.toUnsignedInt(thicknessUnits[cell]);
            boolean oldTransportedMaterial = materials[cell] == contactMaterial;
            int newThickness = quantized.units()[cell];
            if (!oldTransportedMaterial && oldThickness > 0) {
                if (newThickness > 0) {
                    return SolveResult.NO_CHANGE;
                }
                continue;
            }

            if (newThickness > 0) {
                nextMaterials[cell] = contactMaterial;
                nextThickness[cell] = (short)newThickness;
            } else {
                nextMaterials[cell] = LayeredSlabWorkpiece.EMPTY;
                nextThickness[cell] = 0;
            }
            absoluteDifference += Math.abs((long)newThickness - oldThickness);
        }

        int movedVolume = (int)Math.min(Integer.MAX_VALUE, absoluteDifference / 2L);
        if (movedVolume == 0) {
            return SolveResult.NO_CHANGE;
        }

        System.arraycopy(nextMaterials, 0, materials, 0, cellCount);
        System.arraycopy(nextThickness, 0, thicknessUnits, 0, cellCount);
        return new SolveResult(true, movedVolume);
    }

    /**
     * 将一个材料格的四条边映射到连续位移场，再按目标格重叠面积分配质量。
     * Maps all four cell boundaries through the continuous displacement field, then distributes mass by target-cell overlap.
     */
    private static MappedCell mapCell(
            int x,
            int z,
            int centerX,
            int centerZ,
            double lengthwiseStrain,
            double crosswiseStrain) {
        double left = mapLengthCoordinate(
                x - 0.5D, z, centerX, centerZ, lengthwiseStrain);
        double right = mapLengthCoordinate(
                x + 0.5D, z, centerX, centerZ, lengthwiseStrain);
        double near = mapWidthCoordinate(
                z - 0.5D, x, centerX, centerZ, crosswiseStrain);
        double far = mapWidthCoordinate(
                z + 0.5D, x, centerX, centerZ, crosswiseStrain);
        return new MappedCell(
                Math.min(left, right),
                Math.max(left, right),
                Math.min(near, far),
                Math.max(near, far));
    }

    private static double mapLengthCoordinate(
            double coordinate,
            double crossCoordinate,
            int centerX,
            int centerZ,
            double strain) {
        double delta = coordinate - centerX;
        double crossWeight = falloffShape(
                Math.abs(crossCoordinate - centerZ),
                HAMMER_HALF_WIDTH_CELLS,
                FLOW_FALLOFF_WIDTH_CELLS);
        return coordinate + Math.copySign(
                strain
                        * integratedFalloff(
                                Math.abs(delta),
                                HAMMER_HALF_LENGTH_CELLS,
                                FLOW_FALLOFF_LENGTH_CELLS)
                        * crossWeight,
                delta);
    }

    private static double mapWidthCoordinate(
            double coordinate,
            double lengthCoordinate,
            int centerX,
            int centerZ,
            double strain) {
        double delta = coordinate - centerZ;
        double lengthWeight = falloffShape(
                Math.abs(lengthCoordinate - centerX),
                HAMMER_HALF_LENGTH_CELLS,
                FLOW_FALLOFF_LENGTH_CELLS);
        return coordinate + Math.copySign(
                strain
                        * integratedFalloff(
                                Math.abs(delta),
                                HAMMER_HALF_WIDTH_CELLS,
                                FLOW_FALLOFF_WIDTH_CELLS)
                        * lengthWeight,
                delta);
    }

    /**
     * 锤面内应变保持近似恒定，锤面外用三次平滑曲线渐弱到零。
     * Strain stays approximately constant under the face and decays cubically to zero outside it.
     */
    private static double falloffShape(double distance, double faceHalfExtent, double falloff) {
        if (distance <= faceHalfExtent) {
            return 1.0D;
        }
        if (distance >= faceHalfExtent + falloff) {
            return 0.0D;
        }
        double ratio = (distance - faceHalfExtent) / falloff;
        return 1.0D - 3.0D * ratio * ratio + 2.0D * ratio * ratio * ratio;
    }

    /**
     * 对非负应变曲线积分得到位移；积分只会趋于常量，不会在影响区边缘产生反向压缩激波。
     * Integrates the non-negative strain curve into displacement. It approaches a constant and cannot create a reverse-compression shock ring.
     */
    private static double integratedFalloff(double distance, double faceHalfExtent, double falloff) {
        if (distance <= faceHalfExtent) {
            return distance;
        }
        if (distance >= faceHalfExtent + falloff) {
            return faceHalfExtent + falloff * 0.5D;
        }
        double ratio = (distance - faceHalfExtent) / falloff;
        double primitive = ratio - ratio * ratio * ratio + 0.5D * Math.pow(ratio, 4.0D);
        return faceHalfExtent + falloff * primitive;
    }

    private static boolean deposit(
            MappedCell mapped,
            int sourceMass,
            byte contactMaterial,
            byte[] sourceMaterials,
            double[] targetMass) {
        double mappedArea = (mapped.right() - mapped.left()) * (mapped.far() - mapped.near());
        if (mappedArea <= 0.0D) {
            return false;
        }

        int minX = Math.max(0, (int)Math.floor(mapped.left() + 0.5D));
        int maxX = Math.min(
                LayeredSlabWorkpiece.GRID_SIZE - 1,
                (int)Math.floor(Math.nextDown(mapped.right()) + 0.5D));
        int minZ = Math.max(0, (int)Math.floor(mapped.near() + 0.5D));
        int maxZ = Math.min(
                LayeredSlabWorkpiece.GRID_SIZE - 1,
                (int)Math.floor(Math.nextDown(mapped.far()) + 0.5D));

        for (int targetX = minX; targetX <= maxX; targetX++) {
            double overlapX = Math.min(mapped.right(), targetX + 0.5D)
                    - Math.max(mapped.left(), targetX - 0.5D);
            if (overlapX <= 0.0D) {
                continue;
            }
            for (int targetZ = minZ; targetZ <= maxZ; targetZ++) {
                double overlapZ = Math.min(mapped.far(), targetZ + 0.5D)
                        - Math.max(mapped.near(), targetZ - 0.5D);
                if (overlapZ <= 0.0D) {
                    continue;
                }
                int target = index(targetX, targetZ);
                if (sourceMaterials[target] != LayeredSlabWorkpiece.EMPTY
                        && sourceMaterials[target] != contactMaterial) {
                    return false;
                }
                targetMass[target] += sourceMass * overlapX * overlapZ / mappedArea;
            }
        }
        return true;
    }

    private static QuantizedMass quantize(double[] transportedMass, long expectedTotal) {
        int[] units = new int[transportedMass.length];
        List<FractionalCell> residuals = new ArrayList<>();
        long floorTotal = 0L;
        for (int cell = 0; cell < transportedMass.length; cell++) {
            double mass = transportedMass[cell];
            if (mass <= MASS_EPSILON) {
                continue;
            }
            int floor = (int)Math.floor(mass);
            if (floor > LayeredSlabWorkpiece.MAX_THICKNESS_UNITS) {
                return null;
            }
            units[cell] = floor;
            floorTotal += floor;
            residuals.add(new FractionalCell(cell, mass - floor));
        }

        long remainder = expectedTotal - floorTotal;
        if (remainder < 0L || remainder > residuals.size()) {
            return null;
        }
        residuals.sort(Comparator
                .comparingDouble(FractionalCell::fraction).reversed()
                .thenComparingInt(FractionalCell::cell));
        for (int index = 0; index < remainder; index++) {
            int cell = residuals.get(index).cell();
            if (units[cell] >= LayeredSlabWorkpiece.MAX_THICKNESS_UNITS) {
                return null;
            }
            units[cell]++;
        }
        return new QuantizedMass(units);
    }

    private static byte contactMaterial(
            byte[] materials, short[] thicknessUnits, int centerX, int centerZ) {
        byte closestMaterial = LayeredSlabWorkpiece.EMPTY;
        int closestDistance = Integer.MAX_VALUE;
        for (int x = centerX - HAMMER_HALF_LENGTH_CELLS;
                x <= centerX + HAMMER_HALF_LENGTH_CELLS;
                x++) {
            for (int z = centerZ - HAMMER_HALF_WIDTH_CELLS;
                    z <= centerZ + HAMMER_HALF_WIDTH_CELLS;
                    z++) {
                if (!inside(x, z)) {
                    continue;
                }
                int cell = index(x, z);
                if (materials[cell] == LayeredSlabWorkpiece.EMPTY
                        || Short.toUnsignedInt(thicknessUnits[cell]) == 0) {
                    continue;
                }
                int distance = Math.abs(x - centerX) + Math.abs(z - centerZ);
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestMaterial = materials[cell];
                }
            }
        }
        return closestMaterial;
    }

    private static int index(int x, int z) {
        return x * LayeredSlabWorkpiece.GRID_SIZE + z;
    }

    private static boolean inside(int x, int z) {
        return x >= 0
                && x < LayeredSlabWorkpiece.GRID_SIZE
                && z >= 0
                && z < LayeredSlabWorkpiece.GRID_SIZE;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    record SolveResult(boolean changed, int movedVolumeUnits) {
        private static final SolveResult NO_CHANGE = new SolveResult(false, 0);
    }

    private record MappedCell(double left, double right, double near, double far) {
        private boolean insideGrid() {
            double minimum = -0.5D;
            double maximum = LayeredSlabWorkpiece.GRID_SIZE - 0.5D;
            return this.left >= minimum
                    && this.right <= maximum
                    && this.near >= minimum
                    && this.far <= maximum;
        }
    }

    private record FractionalCell(int cell, double fraction) {
    }

    private record QuantizedMass(int[] units) {
    }
}
