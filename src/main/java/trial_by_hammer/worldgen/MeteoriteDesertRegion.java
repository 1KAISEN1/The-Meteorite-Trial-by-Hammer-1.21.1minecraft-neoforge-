package trial_by_hammer.worldgen;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.ModifiedVanillaOverworldBuilder;
import terrablender.api.Region;
import terrablender.api.RegionType;
import trial_by_hammer.TrialByHammer;

import java.util.function.Consumer;

public final class MeteoriteDesertRegion extends Region {
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath(
            TrialByHammer.MODID,
            "meteorite_desert_region"
    );
    private static final int WEIGHT = 2;

    public MeteoriteDesertRegion() {
        super(NAME, RegionType.OVERWORLD, WEIGHT);
    }

    @Override
    public void addBiomes(Registry<Biome> biomeRegistry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        ModifiedVanillaOverworldBuilder builder = new ModifiedVanillaOverworldBuilder();
        builder.replaceBiome(Biomes.PLAINS, ModBiomes.METEORITE_DESERT);

        builder.build().forEach(mapper);
    }
}
