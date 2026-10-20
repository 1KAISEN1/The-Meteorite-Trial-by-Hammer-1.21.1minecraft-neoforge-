package com.trialbyhammer.registry;

import com.trialbyhammer.TrialByHammer;
import com.trialbyhammer.probe.ForgeProbeBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 方块实体注册表。
 *
 * <p>方块实体类型必须和方块本体在同一个模组生命周期里注册，
 * 并且要显式列出允许挂载的方块。漏写方块会导致游戏在放置时抛异常，
 * 这是 1.21 里最常见的低级错误。</p>
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TrialByHammer.MOD_ID);

    public static final Supplier<BlockEntityType<ForgeProbeBlockEntity>> FORGE_PROBE =
            BLOCK_ENTITIES.register("forge_probe", () -> BlockEntityType.Builder
                    .of(ForgeProbeBlockEntity::new, ModBlocks.FORGE_PROBE.get())
                    .build(null));

    private ModBlockEntities() {
    }
}
