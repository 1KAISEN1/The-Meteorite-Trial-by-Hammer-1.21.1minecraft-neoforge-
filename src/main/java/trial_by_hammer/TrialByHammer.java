package trial_by_hammer;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ColorRGBA;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import trial_by_hammer.content.ModWorkshopBlocks;
import trial_by_hammer.worldgen.structure.ModStructures;

// 此值必须与 META-INF/neoforge.mods.toml 中的条目一致 / This value must match an entry in META-INF/neoforge.mods.toml.
@Mod(TrialByHammer.MODID)
public class TrialByHammer {
    // 在统一位置定义模组 ID，供所有代码引用 / Define the mod ID in one shared location for all code to reference.
    public static final String MODID = "trial_by_hammer";
    // 用于注册 trial_by_hammer 命名空间下方块的延迟注册器 / Deferred register for blocks under the trial_by_hammer namespace.
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // 用于注册 trial_by_hammer 命名空间下物品的延迟注册器 / Deferred register for items under the trial_by_hammer namespace.
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // 用于注册 trial_by_hammer 命名空间下创造模式标签页的延迟注册器 / Deferred register for creative-mode tabs under the trial_by_hammer namespace.
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<Block> METEORITE_ORE = BLOCKS.registerSimpleBlock(
            "meteorite_ore", BlockBehaviour.Properties.ofFullCopy(Blocks.OBSIDIAN));
    public static final DeferredItem<BlockItem> METEORITE_ORE_ITEM = ITEMS.registerSimpleBlockItem("meteorite_ore", METEORITE_ORE);

    public static final DeferredBlock<ColoredFallingBlock> METEORITE_SAND = BLOCKS.registerBlock(
            "meteorite_sand",
            properties -> new ColoredFallingBlock(new ColorRGBA(0xD0C3E2), properties),
            BlockBehaviour.Properties.ofFullCopy(Blocks.SAND).mapColor(MapColor.COLOR_MAGENTA));
    public static final DeferredItem<BlockItem> METEORITE_SAND_ITEM = ITEMS.registerSimpleBlockItem(
            "meteorite_sand", METEORITE_SAND);

    public static final DeferredItem<Item> METEORITE_INGOT = ITEMS.registerSimpleItem("meteorite_ingot", new Item.Properties());
    public static final DeferredItem<Item> UNREFINED_METEORITE_ORE = ITEMS.registerSimpleItem(
            "unrefined_meteorite_ore", new Item.Properties());
    public static final DeferredItem<Item> ARTISAN_HAMMER = ITEMS.registerSimpleItem(
            "artisan_hammer", new Item.Properties().stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TRIAL_BY_HAMMER_TAB = CREATIVE_MODE_TABS.register("trial_by_hammer", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.trial_by_hammer")) // 创造模式标签页标题的语言键 / Language key for the creative-mode tab title.
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> METEORITE_INGOT.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(METEORITE_ORE_ITEM.get());
                output.accept(METEORITE_SAND_ITEM.get());
                output.accept(UNREFINED_METEORITE_ORE.get());
                output.accept(METEORITE_INGOT.get());
                output.accept(ARTISAN_HAMMER.get());
                output.accept(ModWorkshopBlocks.FORGE_HEARTH_ITEM.get());
                output.accept(ModWorkshopBlocks.SMITHING_ANVIL_ITEM.get());
                output.accept(ModWorkshopBlocks.QUENCHING_BASIN_ITEM.get());
                output.accept(ModWorkshopBlocks.HEAT_TREATMENT_FURNACE_ITEM.get());
                output.accept(ModWorkshopBlocks.GRINDING_WHEEL_ITEM.get());
                output.accept(ModWorkshopBlocks.HAFTING_BENCH_ITEM.get());
                output.accept(ModWorkshopBlocks.BLOOMERY_FURNACE_ITEM.get());
                output.accept(ModWorkshopBlocks.FINERY_HEARTH_ITEM.get());
                output.accept(ModWorkshopBlocks.CARBURIZING_CHEST_ITEM.get());
                output.accept(ModWorkshopBlocks.COFUSION_FURNACE_ITEM.get());
                output.accept(ModWorkshopBlocks.TWISTING_FIXTURE_ITEM.get());
                output.accept(ModWorkshopBlocks.CASTING_MOLD_TABLE_ITEM.get());
            }).build());

    public TrialByHammer(IEventBus modEventBus) {
        // 将方块延迟注册器挂接到模组事件总线 / Register the block deferred register on the mod event bus.
        BLOCKS.register(modEventBus);
        // 将物品延迟注册器挂接到模组事件总线 / Register the item deferred register on the mod event bus.
        ITEMS.register(modEventBus);
        // 将工坊测试方块及其物品注册到事件总线；工艺行为在后续里程碑单独实现。
        // Register workshop test blocks and items on the event bus; process behavior will be implemented in later milestones.
        ModWorkshopBlocks.register(modEventBus);
        // 将创造模式标签页延迟注册器挂接到模组事件总线 / Register the creative-mode-tab deferred register on the mod event bus.
        CREATIVE_MODE_TABS.register(modEventBus);
        ModStructures.register(modEventBus);

    }
}
