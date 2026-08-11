package trial_by_hammer.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import trial_by_hammer.TrialByHammer;
import trial_by_hammer.content.block.entity.SmithingAnvilBlockEntity;

/** 注册锻造工位需要的方块实体。/ Registers block entities required by forging stations. */
public final class ModBlockEntities {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(
            Registries.BLOCK_ENTITY_TYPE, TrialByHammer.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SmithingAnvilBlockEntity>> SMITHING_ANVIL =
            BLOCK_ENTITIES.register("smithing_anvil", () -> BlockEntityType.Builder.of(
                            SmithingAnvilBlockEntity::new, ModWorkshopBlocks.SMITHING_ANVIL.get())
                    .build(null));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
