package trial_by_hammer.worldgen.structure;

import net.minecraft.core.QuartPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

final class MeteoriteImpactShape {
    private static final double BOUNDARY_NOISE_SCALE = 0.018D;
    private static final double MIN_RADIUS_FACTOR = 0.90D;
    private static final long BOUNDARY_SEED_SALT = 0x6A09E667F3BCC909L;

    private MeteoriteImpactShape() {
    }

    static ImprovedNoise createBoundaryNoise(long shapeSeed) {
        return new ImprovedNoise(RandomSource.create(shapeSeed ^ BOUNDARY_SEED_SALT));
    }

    static double edgeDistance(
            int x,
            int z,
            int centerX,
            int centerZ,
            double outerRadius,
            ImprovedNoise boundaryNoise
    ) {
        double noise = boundaryNoise.noise(x * BOUNDARY_NOISE_SCALE, 0.0D, z * BOUNDARY_NOISE_SCALE);
        double radiusFactor = MIN_RADIUS_FACTOR + (noise * 0.5D + 0.5D) * (1.0D - MIN_RADIUS_FACTOR);
        double boundaryRadius = outerRadius * radiusFactor;
        return boundaryRadius - Math.hypot(x - centerX, z - centerZ);
    }

    static double biomeCellEdgeDistance(
            int blockX,
            int blockZ,
            int centerX,
            int centerZ,
            double outerRadius,
            ImprovedNoise boundaryNoise
    ) {
        int cellCenterX = QuartPos.toBlock(QuartPos.fromBlock(blockX)) + 2;
        int cellCenterZ = QuartPos.toBlock(QuartPos.fromBlock(blockZ)) + 2;
        return edgeDistance(cellCenterX, cellCenterZ, centerX, centerZ, outerRadius, boundaryNoise);
    }
}
