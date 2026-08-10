package trial_by_hammer.worldgen;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.ParameterUtils.Continentalness;
import terrablender.api.ParameterUtils.Depth;
import terrablender.api.ParameterUtils.Erosion;
import terrablender.api.ParameterUtils.Humidity;
import terrablender.api.ParameterUtils.ParameterPointListBuilder;
import terrablender.api.ParameterUtils.Temperature;
import terrablender.api.ParameterUtils.Weirdness;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.VanillaParameterOverlayBuilder;
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
        VanillaParameterOverlayBuilder builder = new VanillaParameterOverlayBuilder();

        new ParameterPointListBuilder()
                .temperature(Temperature.span(Temperature.WARM, Temperature.HOT))
                .humidity(Humidity.ARID)
                .continentalness(Continentalness.INLAND)
                .erosion(Erosion.FULL_RANGE)
                .depth(Depth.SURFACE)
                .weirdness(Weirdness.FULL_RANGE)
                .build()
                .forEach(point -> builder.add(point, ModBiomes.METEORITE_DESERT));

        builder.build().forEach(mapper);
    }
}
