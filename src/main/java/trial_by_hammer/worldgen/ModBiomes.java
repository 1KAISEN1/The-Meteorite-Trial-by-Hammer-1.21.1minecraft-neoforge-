package trial_by_hammer.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import trial_by_hammer.TrialByHammer;

public final class ModBiomes {
    public static final ResourceKey<Biome> METEORITE_DESERT = create("meteorite_desert");

    private ModBiomes() {
    }

    private static ResourceKey<Biome> create(String name) {
        return ResourceKey.create(
                Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath(TrialByHammer.MODID, name)
        );
    }
}
