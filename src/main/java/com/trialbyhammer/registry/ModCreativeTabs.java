package com.trialbyhammer.registry;

import com.trialbyhammer.TrialByHammer;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 创造模式物品栏。
 *
 * <p>独立建一个标签页，而不是把内容塞进原版的"杂项"里。
 * 理由：本模组的物品会越来越多，混在杂项里既难找，也容易和其他模组撞位置。</p>
 *
 * <p>标签标题用翻译键 {@code itemGroup.trial_by_hammer}，
 * 不要在代码里写死显示文本——否则以后做本地化还得回来改代码。</p>
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TrialByHammer.MOD_ID);

    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.trial_by_hammer"))
            // 图标用探针方块，等陨铁锭做出来之后换成陨铁锭。
            .icon(() -> new ItemStack(ModItems.FORGE_PROBE.get()))
            .displayItems((parameters, output) -> {
                // 这里按"玩家会怎么找"的顺序排列，而不是按注册顺序。
                output.accept(ModItems.FORGE_PROBE.get());
            })
            .build());

    private ModCreativeTabs() {
    }
}
