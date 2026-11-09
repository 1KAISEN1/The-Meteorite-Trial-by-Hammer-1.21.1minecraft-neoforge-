package com.trialbyhammer.registry;

import com.trialbyhammer.TrialByHammer;

import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册表。
 *
 * <p>方块物品（BlockItem）用 {@code registerSimpleBlockItem} 生成，
 * 它会自动套用方块对应的模型与本地化键，不需要手写 Item 子类。</p>
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(TrialByHammer.MOD_ID);

    /**
     * 探针方块的物品形态。
     *
     * <p>没有物品形态的方块无法出现在创造模式物品栏里，玩家只能靠
     * {@code /setblock} 放置。探针阶段必须让它可获取，否则每次测试都要敲命令。</p>
     */
    public static final DeferredItem<BlockItem> FORGE_PROBE =
            ITEMS.registerSimpleBlockItem("forge_probe", ModBlocks.FORGE_PROBE);

    private ModItems() {
    }
}
