package trial_by_hammer.worldgen;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import terrablender.api.Regions;
import terrablender.api.SurfaceRuleManager;
import trial_by_hammer.TrialByHammer;

public final class ModWorldGeneration {
    private ModWorldGeneration() {
    }

    public static void register() {
        Regions.register(new MeteoriteDesertRegion());
        SurfaceRuleManager.addSurfaceRules(
                SurfaceRuleManager.RuleCategory.OVERWORLD,
                TrialByHammer.MODID,
                meteoriteDesertSurfaceRules()
        );
    }

    private static SurfaceRules.RuleSource meteoriteDesertSurfaceRules() {
        SurfaceRules.RuleSource sand = state(Blocks.SAND);
        SurfaceRules.RuleSource sandstone = state(Blocks.SANDSTONE);
        SurfaceRules.RuleSource sandLayer = SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, sandstone),
                sand
        );

        return SurfaceRules.ifTrue(
                SurfaceRules.isBiome(ModBiomes.METEORITE_DESERT),
                SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, sandLayer),
                        SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, sandLayer),
                        SurfaceRules.ifTrue(SurfaceRules.VERY_DEEP_UNDER_FLOOR, sandstone)
                )
        );
    }

    private static SurfaceRules.RuleSource state(Block block) {
        return SurfaceRules.state(block.defaultBlockState());
    }
}
