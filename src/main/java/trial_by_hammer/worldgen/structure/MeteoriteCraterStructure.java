package trial_by_hammer.worldgen.structure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public final class MeteoriteCraterStructure extends Structure {
    private static final int MAX_CENTER_HEIGHT_ABOVE_SEA_LEVEL = 24;

    public static final MapCodec<MeteoriteCraterStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            settingsCodec(instance),
            Codec.intRange(8, 64).fieldOf("min_radius").forGetter(structure -> structure.minRadius),
            Codec.intRange(8, 64).fieldOf("max_radius").forGetter(structure -> structure.maxRadius),
            Codec.intRange(3, 24).fieldOf("min_depth").forGetter(structure -> structure.minDepth),
            Codec.intRange(3, 24).fieldOf("max_depth").forGetter(structure -> structure.maxDepth),
            Codec.intRange(2, 12).fieldOf("meteorite_min_radius").forGetter(structure -> structure.meteoriteMinRadius),
            Codec.intRange(2, 12).fieldOf("meteorite_max_radius").forGetter(structure -> structure.meteoriteMaxRadius),
            Codec.floatRange(0.0F, 1.0F).fieldOf("ore_chance").forGetter(structure -> structure.oreChance),
            Codec.intRange(0, 32).fieldOf("max_height_variation").forGetter(structure -> structure.maxHeightVariation),
            Codec.intRange(1, 16).fieldOf("min_patch_size_chunks").forGetter(structure -> structure.minPatchSizeChunks),
            Codec.intRange(1, 16).fieldOf("max_patch_size_chunks").forGetter(structure -> structure.maxPatchSizeChunks)
    ).apply(instance, MeteoriteCraterStructure::new));

    private final int minRadius;
    private final int maxRadius;
    private final int minDepth;
    private final int maxDepth;
    private final int meteoriteMinRadius;
    private final int meteoriteMaxRadius;
    private final float oreChance;
    private final int maxHeightVariation;
    private final int minPatchSizeChunks;
    private final int maxPatchSizeChunks;

    public MeteoriteCraterStructure(
            StructureSettings settings,
            int minRadius,
            int maxRadius,
            int minDepth,
            int maxDepth,
            int meteoriteMinRadius,
            int meteoriteMaxRadius,
            float oreChance,
            int maxHeightVariation,
            int minPatchSizeChunks,
            int maxPatchSizeChunks
    ) {
        super(settings);
        if (minRadius > maxRadius
                || minDepth > maxDepth
                || meteoriteMinRadius > meteoriteMaxRadius
                || minPatchSizeChunks > maxPatchSizeChunks) {
            throw new IllegalArgumentException("Meteorite crater minimum values must not exceed maximum values");
        }

        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.minDepth = minDepth;
        this.maxDepth = maxDepth;
        this.meteoriteMinRadius = meteoriteMinRadius;
        this.meteoriteMaxRadius = meteoriteMaxRadius;
        this.oreChance = oreChance;
        this.maxHeightVariation = maxHeightVariation;
        this.minPatchSizeChunks = minPatchSizeChunks;
        this.maxPatchSizeChunks = maxPatchSizeChunks;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        RandomSource random = context.random();
        long shapeSeed = random.nextLong();
        Optional<ImpactSite> selectedSite = selectImpactSite(context, random, shapeSeed);
        if (selectedSite.isEmpty()) {
            return Optional.empty();
        }

        ImpactSite site = selectedSite.get();
        PatchBounds patch = site.patch();
        int centerX = patch.centerX();
        int centerZ = patch.centerZ();
        int maxRadiusForPatch = Math.max(
                this.minRadius,
                (int) Math.floor((patch.blockSize() / 2.0D - 2.0D) / 1.18D)
        );
        int baseRadius = nextIntInclusive(random, this.minRadius, Math.min(this.maxRadius, maxRadiusForPatch));
        int stretch = Math.max(1, baseRadius / 6);
        int radiusX = clampRadius(baseRadius + random.nextInt(stretch * 2 + 1) - stretch, maxRadiusForPatch);
        int radiusZ = clampRadius(baseRadius + random.nextInt(stretch * 2 + 1) - stretch, maxRadiusForPatch);
        int depth = nextIntInclusive(random, this.minDepth, this.maxDepth);
        int meteoriteRadius = nextIntInclusive(random, this.meteoriteMinRadius, this.meteoriteMaxRadius);

        int centerY = site.surfaceY();
        BlockPos center = new BlockPos(centerX, centerY, centerZ);
        return Optional.of(new GenerationStub(center, builder -> builder.addPiece(new MeteoriteCraterPiece(
                centerX,
                centerY,
                centerZ,
                radiusX,
                radiusZ,
                depth,
                meteoriteRadius,
                this.oreChance,
                this.maxHeightVariation,
                shapeSeed,
                patch.minChunkX(),
                patch.minChunkZ(),
                patch.maxChunkX(),
                patch.maxChunkZ()
        ))));
    }

    private Optional<ImpactSite> selectImpactSite(GenerationContext context, RandomSource random, long shapeSeed) {
        int requestedSize = nextIntInclusive(random, this.minPatchSizeChunks, this.maxPatchSizeChunks);
        ImprovedNoise boundaryNoise = MeteoriteImpactShape.createBoundaryNoise(shapeSeed);
        PatchBounds requestedPatch = createPatch(context, random, requestedSize);
        Optional<ImpactSite> requestedSite = validateImpactSite(context, requestedPatch, boundaryNoise);
        if (requestedSite.isPresent() || requestedSize == this.minPatchSizeChunks) {
            return requestedSite;
        }

        PatchBounds fallbackPatch = createPatch(context, random, this.minPatchSizeChunks);
        return validateImpactSite(context, fallbackPatch, boundaryNoise);
    }

    private static PatchBounds createPatch(GenerationContext context, RandomSource random, int size) {
        int xBias = size % 2 == 0 && random.nextBoolean() ? 1 : 0;
        int zBias = size % 2 == 0 && random.nextBoolean() ? 1 : 0;
        int minChunkX = context.chunkPos().x - size / 2 + xBias;
        int minChunkZ = context.chunkPos().z - size / 2 + zBias;
        return new PatchBounds(
                minChunkX,
                minChunkZ,
                minChunkX + size - 1,
                minChunkZ + size - 1
        );
    }

    private Optional<ImpactSite> validateImpactSite(
            GenerationContext context,
            PatchBounds patch,
            ImprovedNoise boundaryNoise
    ) {
        if (!hasEligibleBiomes(context, patch, boundaryNoise)) {
            return Optional.empty();
        }

        OptionalInt surfaceY = sampleImpactSurfaceY(context, patch, boundaryNoise);
        return surfaceY.isPresent()
                ? Optional.of(new ImpactSite(patch, surfaceY.getAsInt()))
                : Optional.empty();
    }

    private boolean hasEligibleBiomes(
            GenerationContext context,
            PatchBounds patch,
            ImprovedNoise boundaryNoise
    ) {
        int seaLevel = context.chunkGenerator().getSeaLevel();
        double outerRadius = patch.blockSize() / 2.0D;

        if (!passesFastBiomeProbe(context, patch, seaLevel, outerRadius, boundaryNoise)) {
            return false;
        }

        int sampleCount = 0;

        for (int chunkX = patch.minChunkX(); chunkX <= patch.maxChunkX(); chunkX++) {
            for (int chunkZ = patch.minChunkZ(); chunkZ <= patch.maxChunkZ(); chunkZ++) {
                int x = (chunkX << 4) + 8;
                int z = (chunkZ << 4) + 8;
                if (MeteoriteImpactShape.edgeDistance(
                        x,
                        z,
                        patch.centerX(),
                        patch.centerZ(),
                        outerRadius,
                        boundaryNoise
                ) < 0.0D) {
                    continue;
                }

                if (!isEligibleBiome(context, x, z, seaLevel)) {
                    return false;
                }

                sampleCount++;
            }
        }

        return sampleCount > 0;
    }

    private OptionalInt sampleImpactSurfaceY(
            GenerationContext context,
            PatchBounds patch,
            ImprovedNoise boundaryNoise
    ) {
        int seaLevel = context.chunkGenerator().getSeaLevel();
        int maxCenterY = seaLevel + MAX_CENTER_HEIGHT_ABOVE_SEA_LEVEL;
        double outerRadius = patch.blockSize() / 2.0D;
        int sampleSpacing = Math.max(8, patch.blockSize() / 5);
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        List<Integer> heights = new ArrayList<>(25);

        for (int gridX = -2; gridX <= 2; gridX++) {
            for (int gridZ = -2; gridZ <= 2; gridZ++) {
                int x = patch.centerX() + gridX * sampleSpacing;
                int z = patch.centerZ() + gridZ * sampleSpacing;
                if (MeteoriteImpactShape.edgeDistance(
                        x,
                        z,
                        patch.centerX(),
                        patch.centerZ(),
                        outerRadius,
                        boundaryNoise
                ) < 0.0D) {
                    continue;
                }

                int surfaceY = getSurfaceY(context, x, z);
                if (surfaceY < seaLevel + 1) {
                    return OptionalInt.empty();
                }

                heights.add(surfaceY);
                minHeight = Math.min(minHeight, surfaceY);
                maxHeight = Math.max(maxHeight, surfaceY);
                if (maxHeight - minHeight > this.maxHeightVariation) {
                    return OptionalInt.empty();
                }
            }
        }

        if (heights.isEmpty()) {
            return OptionalInt.empty();
        }

        heights.sort(Comparator.naturalOrder());
        int medianHeight = heights.get(heights.size() / 2);
        return medianHeight <= maxCenterY ? OptionalInt.of(medianHeight) : OptionalInt.empty();
    }

    private boolean passesFastBiomeProbe(
            GenerationContext context,
            PatchBounds patch,
            int seaLevel,
            double outerRadius,
            ImprovedNoise boundaryNoise
    ) {
        int probeRadius = Math.max(1, (int) Math.floor(outerRadius * 0.68D));
        int diagonalRadius = Math.max(1, (int) Math.floor(probeRadius / Math.sqrt(2.0D)));
        int[][] offsets = {
                {0, 0},
                {probeRadius, 0}, {-probeRadius, 0}, {0, probeRadius}, {0, -probeRadius},
                {diagonalRadius, diagonalRadius}, {diagonalRadius, -diagonalRadius},
                {-diagonalRadius, diagonalRadius}, {-diagonalRadius, -diagonalRadius}
        };
        for (int[] offset : offsets) {
            int x = patch.centerX() + offset[0];
            int z = patch.centerZ() + offset[1];
            if (MeteoriteImpactShape.edgeDistance(
                    x,
                    z,
                    patch.centerX(),
                    patch.centerZ(),
                    outerRadius,
                    boundaryNoise
            ) < 0.0D) {
                continue;
            }

            if (!isEligibleBiome(context, x, z, seaLevel)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isEligibleBiome(GenerationContext context, int x, int z, int sampleY) {
        Holder<Biome> biome = context.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(sampleY),
                QuartPos.fromBlock(z),
                context.randomState().sampler()
        );
        return context.validBiome().test(biome);
    }

    private static int getSurfaceY(GenerationContext context, int x, int z) {
        return context.chunkGenerator().getFirstOccupiedHeight(
                x,
                z,
                Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(),
                context.randomState()
        ) - 1;
    }

    private static int nextIntInclusive(RandomSource random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private int clampRadius(int radius, int maxRadiusForPatch) {
        return Math.max(this.minRadius, Math.min(Math.min(this.maxRadius, maxRadiusForPatch), radius));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.METEORITE_CRATER.get();
    }

    private record PatchBounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        private int minBlockX() {
            return this.minChunkX << 4;
        }

        private int minBlockZ() {
            return this.minChunkZ << 4;
        }

        private int maxBlockX() {
            return ((this.maxChunkX + 1) << 4) - 1;
        }

        private int maxBlockZ() {
            return ((this.maxChunkZ + 1) << 4) - 1;
        }

        private int centerX() {
            return (this.minBlockX() + this.maxBlockX()) >> 1;
        }

        private int centerZ() {
            return (this.minBlockZ() + this.maxBlockZ()) >> 1;
        }

        private int blockSize() {
            return this.maxBlockX() - this.minBlockX() + 1;
        }
    }

    private record ImpactSite(PatchBounds patch, int surfaceY) {
    }
}
