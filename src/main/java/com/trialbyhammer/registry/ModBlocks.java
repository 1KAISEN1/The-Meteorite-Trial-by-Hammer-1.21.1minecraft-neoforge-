package com.trialbyhammer.registry;

import com.trialbyhammer.TrialByHammer;
import com.trialbyhammer.probe.ForgeProbeBlock;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册表。
 *
 * <p>所有方块集中在这里声明，方便一眼看清模组对外暴露了哪些内容。
 * 注册动作本身发生在模组构造函数里，这里只负责"声明存在"。</p>
 */
public final class ModBlocks {

    /** 方块专属的注册器。命名空间固定为 modid，不要在这里传别的值。 */
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(TrialByHammer.MOD_ID);

    /**
     * 锻造探针方块。
     *
     * <p>这是可丢弃的实验方块，只用于验证"动态形变网格"的渲染管线。
     * 正式实现会换成真正的锻造台多方块结构。</p>
     */
    public static final DeferredBlock<ForgeProbeBlock> FORGE_PROBE = BLOCKS.register(
            "forge_probe",
            () -> new ForgeProbeBlock(BlockBehaviour.Properties.of()
                    .strength(3.5F)
                    .requiresCorrectToolForDrops()));

    private ModBlocks() {
    }
}
