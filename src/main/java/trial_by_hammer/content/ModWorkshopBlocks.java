package trial_by_hammer.content;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import trial_by_hammer.TrialByHammer;

/**
 * 锻造工坊方块的注册清单；当前只提供可辨识的测试占位方块，不提前实现工艺逻辑。
 * Registration list for forging-workshop blocks; currently exposes identifiable test placeholders only, not premature process logic.
 */
public final class ModWorkshopBlocks {
    // 单独注册工坊内容，避免把未来的方块实体和工艺代码塞进主模组入口类。
    // Register workshop content separately so future block entities and process logic do not accumulate in the main mod entry class.
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TrialByHammer.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TrialByHammer.MODID);

    public static final DeferredBlock<Block> FORGE_HEARTH = registerMetalStation("forge_hearth");
    public static final DeferredBlock<Block> SMITHING_ANVIL = registerMetalStation("smithing_anvil");
    public static final DeferredBlock<Block> QUENCHING_BASIN = registerMetalStation("quenching_basin");
    public static final DeferredBlock<Block> HEAT_TREATMENT_FURNACE = registerMetalStation("heat_treatment_furnace");
    public static final DeferredBlock<Block> GRINDING_WHEEL = registerMetalStation("grinding_wheel");
    public static final DeferredBlock<Block> HAFTING_BENCH = registerWoodenStation("hafting_bench");

    public static final DeferredBlock<Block> BLOOMERY_FURNACE = registerMetalStation("bloomery_furnace");
    public static final DeferredBlock<Block> FINERY_HEARTH = registerMetalStation("finery_hearth");
    public static final DeferredBlock<Block> CARBURIZING_CHEST = registerMetalStation("carburizing_chest");
    public static final DeferredBlock<Block> COFUSION_FURNACE = registerMetalStation("cofusion_furnace");

    public static final DeferredBlock<Block> TWISTING_FIXTURE = registerMetalStation("twisting_fixture");
    public static final DeferredBlock<Block> CASTING_MOLD_TABLE = registerWoodenStation("casting_mold_table");

    public static final DeferredBlock<Block> WATER_WHEEL = registerWoodenStation("water_wheel");
    public static final DeferredBlock<Block> DRIVE_SHAFT = registerWoodenStation("drive_shaft");
    public static final DeferredBlock<Block> WATER_BELLOWS = registerWoodenStation("water_bellows");
    public static final DeferredBlock<Block> TRIP_HAMMER = registerMetalStation("trip_hammer");

    public static final DeferredItem<BlockItem> FORGE_HEARTH_ITEM = registerItem("forge_hearth", FORGE_HEARTH);
    public static final DeferredItem<BlockItem> SMITHING_ANVIL_ITEM = registerItem("smithing_anvil", SMITHING_ANVIL);
    public static final DeferredItem<BlockItem> QUENCHING_BASIN_ITEM = registerItem("quenching_basin", QUENCHING_BASIN);
    public static final DeferredItem<BlockItem> HEAT_TREATMENT_FURNACE_ITEM = registerItem("heat_treatment_furnace", HEAT_TREATMENT_FURNACE);
    public static final DeferredItem<BlockItem> GRINDING_WHEEL_ITEM = registerItem("grinding_wheel", GRINDING_WHEEL);
    public static final DeferredItem<BlockItem> HAFTING_BENCH_ITEM = registerItem("hafting_bench", HAFTING_BENCH);

    public static final DeferredItem<BlockItem> BLOOMERY_FURNACE_ITEM = registerItem("bloomery_furnace", BLOOMERY_FURNACE);
    public static final DeferredItem<BlockItem> FINERY_HEARTH_ITEM = registerItem("finery_hearth", FINERY_HEARTH);
    public static final DeferredItem<BlockItem> CARBURIZING_CHEST_ITEM = registerItem("carburizing_chest", CARBURIZING_CHEST);
    public static final DeferredItem<BlockItem> COFUSION_FURNACE_ITEM = registerItem("cofusion_furnace", COFUSION_FURNACE);

    public static final DeferredItem<BlockItem> TWISTING_FIXTURE_ITEM = registerItem("twisting_fixture", TWISTING_FIXTURE);
    public static final DeferredItem<BlockItem> CASTING_MOLD_TABLE_ITEM = registerItem("casting_mold_table", CASTING_MOLD_TABLE);

    public static final DeferredItem<BlockItem> WATER_WHEEL_ITEM = registerItem("water_wheel", WATER_WHEEL);
    public static final DeferredItem<BlockItem> DRIVE_SHAFT_ITEM = registerItem("drive_shaft", DRIVE_SHAFT);
    public static final DeferredItem<BlockItem> WATER_BELLOWS_ITEM = registerItem("water_bellows", WATER_BELLOWS);
    public static final DeferredItem<BlockItem> TRIP_HAMMER_ITEM = registerItem("trip_hammer", TRIP_HAMMER);

    private ModWorkshopBlocks() {
    }

    /**
     * 当前金属工位先共享耐用方块属性；碰撞、朝向和方块实体将在对应工艺实现时分别确定。
     * Metal stations currently share durable placeholder properties; collision, orientation, and block entities will be decided with each process implementation.
     */
    private static DeferredBlock<Block> registerMetalStation(String name) {
        return BLOCKS.registerSimpleBlock(name, BlockBehaviour.Properties.ofFullCopy(Blocks.SMITHING_TABLE));
    }

    /**
     * 木工和水力传动工位暂时使用木质占位属性，后续再按受力和可燃性分别细化。
     * Woodworking and water-power stations temporarily use wooden placeholder properties, to be refined later for load and flammability.
     */
    private static DeferredBlock<Block> registerWoodenStation(String name) {
        return BLOCKS.registerSimpleBlock(name, BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS));
    }

    private static DeferredItem<BlockItem> registerItem(String name, DeferredBlock<Block> block) {
        return ITEMS.registerSimpleBlockItem(name, block);
    }

    /**
     * 将工坊方块和对应物品挂接到模组事件总线。
     * Attaches workshop blocks and their corresponding items to the mod event bus.
     */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
