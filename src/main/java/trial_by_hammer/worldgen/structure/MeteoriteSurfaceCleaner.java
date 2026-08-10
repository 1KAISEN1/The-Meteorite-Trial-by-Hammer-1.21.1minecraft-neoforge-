package trial_by_hammer.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import trial_by_hammer.TrialByHammer;

final class MeteoriteSurfaceCleaner {
    private static final int MAX_SURFACE_SEARCH_DEPTH = 96;

    private MeteoriteSurfaceCleaner() {
    }

    static void clean(
            WorldGenLevel level,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int centerX,
            int centerZ,
            double outerRadius,
            ImprovedNoise boundaryNoise,
            int minimumScanTopY
    ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (MeteoriteImpactShape.biomeCellEdgeDistance(
                        x,
                        z,
                        centerX,
                        centerZ,
                        outerRadius,
                        boundaryNoise
                ) < 0.0D) {
                    continue;
                }

                int topY = Math.min(
                        level.getMaxBuildHeight() - 1,
                        Math.max(
                                level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1,
                                minimumScanTopY
                        )
                );
                int meteoriteSurfaceY = findMeteoriteSurface(level, x, topY, z);
                if (meteoriteSurfaceY == Integer.MIN_VALUE) {
                    continue;
                }

                for (int y = meteoriteSurfaceY + 1; y <= topY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && !state.is(Blocks.CACTUS)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static int findMeteoriteSurface(WorldGenLevel level, int x, int topY, int z) {
        int minY = Math.max(level.getMinBuildHeight(), topY - MAX_SURFACE_SEARCH_DEPTH);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, topY, z);
        for (int y = topY; y >= minY; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (state.is(TrialByHammer.METEORITE_SAND.get())
                    || state.is(TrialByHammer.METEORITE_ORE.get())
                    || state.is(Blocks.OBSIDIAN)) {
                return y;
            }
        }

        return Integer.MIN_VALUE;
    }
}
