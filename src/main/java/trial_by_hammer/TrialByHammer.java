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
import trial_by_hammer.worldgen.structure.ModStructures;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(TrialByHammer.MODID)
public class TrialByHammer {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "trial_by_hammer";
    // Create a Deferred Register to hold Blocks which will all be registered under the "trial_by_hammer" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "trial_by_hammer" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "trial_by_hammer" namespace
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
            .title(Component.translatable("itemGroup.trial_by_hammer")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> METEORITE_INGOT.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(METEORITE_ORE_ITEM.get());
                output.accept(METEORITE_SAND_ITEM.get());
                output.accept(UNREFINED_METEORITE_ORE.get());
                output.accept(METEORITE_INGOT.get());
                output.accept(ARTISAN_HAMMER.get());
            }).build());

    public TrialByHammer(IEventBus modEventBus) {
        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        ModStructures.register(modEventBus);

    }
}
