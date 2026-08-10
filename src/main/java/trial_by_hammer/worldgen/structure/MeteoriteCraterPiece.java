package trial_by_hammer.worldgen.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import trial_by_hammer.TrialByHammer;
import trial_by_hammer.worldgen.ModBiomes;

import java.util.HashMap;
import java.util.Map;

public final class MeteoriteCraterPiece extends StructurePiece {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
    private static final int METEORITE_SAND_DEPTH = 7;
    private static final int FLATTENING_EDGE_WIDTH = 48;
    private static final double MAX_EDGE_VERTICAL_CHANGE_PER_BLOCK = 0.4D;
    private static final int HALO_CLEANUP_REACH = 16;
    private static final ResourceKey<PlacedFeature> CACTUS_PATCH = ResourceKey.create(
            Registries.PLACED_FEATURE,
            ResourceLocation.fromNamespaceAndPath(TrialByHammer.MODID, "patch_cactus_desert_half")
    );

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int radiusX;
    private final int radiusZ;
    private final int depth;
    private final int meteoriteRadius;
    private final float oreChance;
    private final long shapeSeed;
    private final int patchMinChunkX;
    private final int patchMinChunkZ;
    private final int patchMaxChunkX;
    private final int patchMaxChunkZ;

    public MeteoriteCraterPiece(
            int centerX,
            int centerY,
            int centerZ,
            int radiusX,
            int radiusZ,
            int depth,
            int meteoriteRadius,
            float oreChance,
            int maxHeightVariation,
            long shapeSeed,
            int patchMinChunkX,
            int patchMinChunkZ,
            int patchMaxChunkX,
            int patchMaxChunkZ
    ) {
        super(
                ModStructures.METEORITE_CRATER_PIECE.get(),
                0,
                createBoundingBox(
                        centerY,
                        depth,
                        meteoriteRadius,
                        maxHeightVariation,
                        patchMinChunkX,
                        patchMinChunkZ,
                        patchMaxChunkX,
                        patchMaxChunkZ
                )
        );
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.depth = depth;
        this.meteoriteRadius = meteoriteRadius;
        this.oreChance = oreChance;
        this.shapeSeed = shapeSeed;
        this.patchMinChunkX = patchMinChunkX;
        this.patchMinChunkZ = patchMinChunkZ;
        this.patchMaxChunkX = patchMaxChunkX;
        this.patchMaxChunkZ = patchMaxChunkZ;
    }

    public MeteoriteCraterPiece(CompoundTag tag) {
        super(ModStructures.METEORITE_CRATER_PIECE.get(), tag);
        this.centerX = tag.getInt("CenterX");
        this.centerY = tag.getInt("CenterY");
        this.centerZ = tag.getInt("CenterZ");
        this.radiusX = tag.getInt("RadiusX");
        this.radiusZ = tag.getInt("RadiusZ");
        this.depth = tag.getInt("Depth");
        this.meteoriteRadius = tag.getInt("MeteoriteRadius");
        this.oreChance = tag.getFloat("OreChance");
        this.shapeSeed = tag.getLong("ShapeSeed");
        this.patchMinChunkX = tag.contains("PatchMinChunkX") ? tag.getInt("PatchMinChunkX") : this.centerX >> 4;
        this.patchMinChunkZ = tag.contains("PatchMinChunkZ") ? tag.getInt("PatchMinChunkZ") : this.centerZ >> 4;
        this.patchMaxChunkX = tag.contains("PatchMaxChunkX") ? tag.getInt("PatchMaxChunkX") : this.centerX >> 4;
        this.patchMaxChunkZ = tag.contains("PatchMaxChunkZ") ? tag.getInt("PatchMaxChunkZ") : this.centerZ >> 4;
    }

    private static BoundingBox createBoundingBox(
            int centerY,
            int depth,
            int meteoriteRadius,
            int maxHeightVariation,
            int patchMinChunkX,
            int patchMinChunkZ,
            int patchMaxChunkX,
            int patchMaxChunkZ
    ) {
        return new BoundingBox(
                (patchMinChunkX - 1) << 4,
                centerY - depth - meteoriteRadius - 2,
                (patchMinChunkZ - 1) << 4,
                ((patchMaxChunkX + 2) << 4) - 1,
                centerY + maxHeightVariation + 32,
                ((patchMaxChunkZ + 2) << 4) - 1
        );
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("CenterX", this.centerX);
        tag.putInt("CenterY", this.centerY);
        tag.putInt("CenterZ", this.centerZ);
        tag.putInt("RadiusX", this.radiusX);
        tag.putInt("RadiusZ", this.radiusZ);
        tag.putInt("Depth", this.depth);
        tag.putInt("MeteoriteRadius", this.meteoriteRadius);
        tag.putFloat("OreChance", this.oreChance);
        tag.putLong("ShapeSeed", this.shapeSeed);
        tag.putInt("PatchMinChunkX", this.patchMinChunkX);
        tag.putInt("PatchMinChunkZ", this.patchMinChunkZ);
        tag.putInt("PatchMaxChunkX", this.patchMaxChunkX);
        tag.putInt("PatchMaxChunkZ", this.patchMaxChunkZ);
    }

    @Override
    public void postProcess(
            WorldGenLevel level,
            StructureManager structureManager,
            ChunkGenerator generator,
            RandomSource random,
            BoundingBox chunkBox,
            ChunkPos chunkPos,
            BlockPos pivot
    ) {
        ImprovedNoise boundaryNoise = MeteoriteImpactShape.createBoundaryNoise(this.shapeSeed);
        if (!isInsidePatch(chunkPos)) {
            cleanPatchNearChunk(level, chunkPos, boundaryNoise);
            return;
        }

        if (!replaceChunkBiome(level, chunkPos, boundaryNoise)) {
            return;
        }

        ImprovedNoise edgeNoise = new ImprovedNoise(RandomSource.create(this.shapeSeed));
        double outerRadius = impactOuterRadius();
        int minX = Math.max(chunkPos.getMinBlockX(), this.patchMinChunkX << 4);
        int maxX = Math.min(chunkPos.getMaxBlockX(), ((this.patchMaxChunkX + 1) << 4) - 1);
        int minZ = Math.max(chunkPos.getMinBlockZ(), this.patchMinChunkZ << 4);
        int maxZ = Math.min(chunkPos.getMaxBlockZ(), ((this.patchMaxChunkZ + 1) << 4) - 1);
        Map<Long, Integer> terrainHeights = new HashMap<>((maxX - minX + 3) * (maxZ - minZ + 3));
        for (int sampleX = minX - 1; sampleX <= maxX + 1; sampleX++) {
            for (int sampleZ = minZ - 1; sampleZ <= maxZ + 1; sampleZ++) {
                terrainHeights.put(
                        BlockPos.asLong(sampleX, 0, sampleZ),
                        findTerrainSurfaceY(level, sampleX, sampleZ)
                );
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double distanceToBoundary = MeteoriteImpactShape.biomeCellEdgeDistance(
                        x,
                        z,
                        this.centerX,
                        this.centerZ,
                        outerRadius,
                        boundaryNoise
                );
                if (distanceToBoundary < 0.0D) {
                    continue;
                }

                int terrainSurfaceY = terrainHeights.get(BlockPos.asLong(x, 0, z));
                int smoothedTerrainY = calculateSmoothedTerrainY(terrainHeights, x, z);
                int targetY = calculateFlattenedSurface(x, z, smoothedTerrainY, edgeNoise, distanceToBoundary);
                double normalizedX = (x - this.centerX) / (double) this.radiusX;
                double normalizedZ = (z - this.centerZ) / (double) this.radiusZ;
                double radialDistance = Math.sqrt(normalizedX * normalizedX + normalizedZ * normalizedZ);
                radialDistance += edgeNoise.noise(x * 0.085D, 0.0D, z * 0.085D) * 0.035D;

                if (radialDistance <= 1.18D) {
                    targetY = calculateCraterSurface(radialDistance);
                }
                reshapeColumn(level, x, z, terrainSurfaceY, targetY);
            }
        }

        placeMeteorite(level, chunkPos, edgeNoise);
        placeCactusPatch(level, generator, random, chunkPos);
        cleanPatchNearChunk(level, chunkPos, boundaryNoise);
    }

    private void cleanPatchNearChunk(WorldGenLevel level, ChunkPos chunkPos, ImprovedNoise boundaryNoise) {
        int patchMinX = this.patchMinChunkX << 4;
        int patchMinZ = this.patchMinChunkZ << 4;
        int patchMaxX = ((this.patchMaxChunkX + 1) << 4) - 1;
        int patchMaxZ = ((this.patchMaxChunkZ + 1) << 4) - 1;
        int minX = Math.max(patchMinX, chunkPos.getMinBlockX() - HALO_CLEANUP_REACH);
        int maxX = Math.min(patchMaxX, chunkPos.getMaxBlockX() + HALO_CLEANUP_REACH);
        int minZ = Math.max(patchMinZ, chunkPos.getMinBlockZ() - HALO_CLEANUP_REACH);
        int maxZ = Math.min(patchMaxZ, chunkPos.getMaxBlockZ() + HALO_CLEANUP_REACH);
        if (minX > maxX || minZ > maxZ) {
            return;
        }

        MeteoriteSurfaceCleaner.clean(
                level,
                minX,
                maxX,
                minZ,
                maxZ,
                this.centerX,
                this.centerZ,
                impactOuterRadius(),
                boundaryNoise,
                this.boundingBox.maxY()
        );
    }

    private boolean isInsidePatch(ChunkPos chunkPos) {
        return chunkPos.x >= this.patchMinChunkX
                && chunkPos.x <= this.patchMaxChunkX
                && chunkPos.z >= this.patchMinChunkZ
                && chunkPos.z <= this.patchMaxChunkZ;
    }

    private boolean replaceChunkBiome(WorldGenLevel level, ChunkPos chunkPos, ImprovedNoise boundaryNoise) {
        Holder<Biome> meteoriteDesert = level.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(ModBiomes.METEORITE_DESERT);
        ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        double outerRadius = impactOuterRadius();
        boolean affectsChunk = false;
        for (int localQuartX = 0; localQuartX < 4 && !affectsChunk; localQuartX++) {
            for (int localQuartZ = 0; localQuartZ < 4; localQuartZ++) {
                int cellCenterX = minBlockX + localQuartX * 4 + 2;
                int cellCenterZ = minBlockZ + localQuartZ * 4 + 2;
                if (MeteoriteImpactShape.edgeDistance(
                        cellCenterX,
                        cellCenterZ,
                        this.centerX,
                        this.centerZ,
                        outerRadius,
                        boundaryNoise
                ) >= 0.0D) {
                    affectsChunk = true;
                    break;
                }
            }
        }
        if (!affectsChunk) {
            return false;
        }

        chunk.fillBiomesFromNoise(
                (quartX, quartY, quartZ, sampler) -> {
                    int cellCenterX = QuartPos.toBlock(quartX) + 2;
                    int cellCenterZ = QuartPos.toBlock(quartZ) + 2;
                    if (MeteoriteImpactShape.edgeDistance(
                            cellCenterX,
                            cellCenterZ,
                            this.centerX,
                            this.centerZ,
                            outerRadius,
                            boundaryNoise
                    ) >= 0.0D) {
                        return meteoriteDesert;
                    }
                    return chunk.getNoiseBiome(quartX, quartY, quartZ);
                },
                level.getLevel().getChunkSource().randomState().sampler()
        );
        chunk.setUnsaved(true);
        return true;
    }

    private double impactOuterRadius() {
        int patchWidth = (this.patchMaxChunkX - this.patchMinChunkX + 1) * 16;
        return patchWidth / 2.0D;
    }

    private int calculateFlattenedSurface(
            int x,
            int z,
            int terrainSurfaceY,
            ImprovedNoise noise,
            double distanceToBoundary
    ) {
        double flatteningStrength = smoothStep(distanceToBoundary / FLATTENING_EDGE_WIDTH);
        double gentleVariation = noise.noise(x * 0.025D, 0.0D, z * 0.025D) * 1.25D;
        double flatTarget = this.centerY + gentleVariation;
        double blendedChange = (flatTarget - terrainSurfaceY) * flatteningStrength;
        double maximumEdgeChange = Math.max(0.0D, distanceToBoundary) * MAX_EDGE_VERTICAL_CHANGE_PER_BLOCK;
        double limitedChange = Math.max(-maximumEdgeChange, Math.min(maximumEdgeChange, blendedChange));
        return (int) Math.round(terrainSurfaceY + limitedChange);
    }

    private static int calculateSmoothedTerrainY(Map<Long, Integer> terrainHeights, int centerX, int centerZ) {
        int sum = 0;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                sum += terrainHeights.get(BlockPos.asLong(centerX + offsetX, 0, centerZ + offsetZ));
            }
        }
        return Math.round(sum / 9.0F);
    }

    private int calculateCraterSurface(double radialDistance) {
        int rimHeight = Math.max(2, this.depth / 3);
        if (radialDistance <= 0.88D) {
            double normalized = radialDistance / 0.88D;
            double bowl = 1.0D - normalized * normalized;
            return this.centerY - (int) Math.round(this.depth * bowl);
        }

        if (radialDistance <= 1.0D) {
            double rise = smoothStep((radialDistance - 0.88D) / 0.12D);
            return this.centerY + (int) Math.round(rimHeight * rise);
        }

        double fall = 1.0D - smoothStep((radialDistance - 1.0D) / 0.18D);
        return this.centerY + (int) Math.round(rimHeight * fall);
    }

    private int findTerrainSurfaceY(WorldGenLevel level, int x, int z) {
        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        int scanBottom = Math.max(level.getMinBuildHeight(), worldSurfaceY - 64);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, worldSurfaceY, z);
        for (int y = worldSurfaceY; y >= scanBottom; y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (isNaturalTerrain(state)) {
                return y;
            }
        }

        return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
    }

    private static boolean isNaturalTerrain(BlockState state) {
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY);
    }

    private void reshapeColumn(WorldGenLevel level, int x, int z, int terrainSurfaceY, int targetY) {
        targetY = Math.max(level.getMinBuildHeight() + 1, Math.min(level.getMaxBuildHeight() - 2, targetY));
        int currentSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        int clearTop = Math.max(currentSurfaceY, targetY);

        for (int y = targetY + 1; y <= clearTop; y++) {
            level.setBlock(new BlockPos(x, y, z), AIR, 2);
        }

        BlockState sand = TrialByHammer.METEORITE_SAND.get().defaultBlockState();
        int normalSandBottom = targetY - METEORITE_SAND_DEPTH + 1;
        int sandBottom = targetY > terrainSurfaceY
                ? Math.min(normalSandBottom, terrainSurfaceY)
                : normalSandBottom;
        BlockState filler = level.getBlockState(new BlockPos(x, Math.max(level.getMinBuildHeight(), terrainSurfaceY - METEORITE_SAND_DEPTH), z));
        if (filler.isAir() || !filler.getFluidState().isEmpty() || filler.is(BlockTags.LOGS) || filler.is(BlockTags.LEAVES)) {
            filler = Blocks.STONE.defaultBlockState();
        }

        for (int y = terrainSurfaceY + 1; y < sandBottom; y++) {
            level.setBlock(new BlockPos(x, y, z), filler, 2);
        }
        for (int y = sandBottom; y <= targetY; y++) {
            if (y > level.getMinBuildHeight()) {
                level.setBlock(new BlockPos(x, y, z), sand, 2);
            }
        }
    }

    private void placeMeteorite(WorldGenLevel level, ChunkPos chunkPos, ImprovedNoise shapeNoise) {
        int offsetX = (int) Math.floorMod(mix64(this.shapeSeed), 5L) - 2;
        int offsetZ = (int) Math.floorMod(mix64(this.shapeSeed ^ 0x9E3779B97F4A7C15L), 5L) - 2;
        int meteoriteCenterX = this.centerX + offsetX;
        int meteoriteCenterZ = this.centerZ + offsetZ;
        int craterBottomY = this.centerY - this.depth;
        int meteoriteCenterY = craterBottomY + Math.max(1, this.meteoriteRadius / 2);
        double verticalRadius = Math.max(2.0D, this.meteoriteRadius * 0.78D);

        int minX = Math.max(chunkPos.getMinBlockX(), meteoriteCenterX - this.meteoriteRadius - 1);
        int maxX = Math.min(chunkPos.getMaxBlockX(), meteoriteCenterX + this.meteoriteRadius + 1);
        int minZ = Math.max(chunkPos.getMinBlockZ(), meteoriteCenterZ - this.meteoriteRadius - 1);
        int maxZ = Math.min(chunkPos.getMaxBlockZ(), meteoriteCenterZ + this.meteoriteRadius + 1);
        int minY = Math.max(this.boundingBox.minY(), meteoriteCenterY - (int) Math.ceil(verticalRadius) - 1);
        int maxY = Math.min(this.boundingBox.maxY(), meteoriteCenterY + (int) Math.ceil(verticalRadius) + 1);
        BlockState ore = TrialByHammer.METEORITE_ORE.get().defaultBlockState();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double nx = (x - meteoriteCenterX) / (double) this.meteoriteRadius;
                    double ny = (y - meteoriteCenterY) / verticalRadius;
                    double nz = (z - meteoriteCenterZ) / (double) this.meteoriteRadius;
                    double distance = nx * nx + ny * ny + nz * nz;
                    double noise = shapeNoise.noise(x * 0.19D, y * 0.19D, z * 0.19D) * 0.12D;
                    if (distance + noise <= 1.0D) {
                        BlockState state = hashToUnit(this.shapeSeed, x, y, z) < this.oreChance ? ore : OBSIDIAN;
                        level.setBlock(new BlockPos(x, y, z), state, 2);
                    }
                }
            }
        }
    }

    private static void placeCactusPatch(WorldGenLevel level, ChunkGenerator generator, RandomSource random, ChunkPos chunkPos) {
        level.registryAccess()
                .registryOrThrow(Registries.PLACED_FEATURE)
                .getHolderOrThrow(CACTUS_PATCH)
                .value()
                .placeWithBiomeCheck(level, generator, random, chunkPos.getWorldPosition());
    }

    private static double smoothStep(double value) {
        double clamped = Math.max(0.0D, Math.min(1.0D, value));
        return clamped * clamped * (3.0D - 2.0D * clamped);
    }

    private static double hashToUnit(long seed, int x, int y, int z) {
        long mixed = seed;
        mixed ^= (long) x * 0x632BE59BD9B4E019L;
        mixed ^= (long) y * 0x9E3779B97F4A7C15L;
        mixed ^= (long) z * 0x94D049BB133111EBL;
        return (mix64(mixed) >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
