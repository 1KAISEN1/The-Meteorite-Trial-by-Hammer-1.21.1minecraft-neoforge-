package trial_by_hammer.forging;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import trial_by_hammer.TrialByHammer;

/** 注册工件物品只需携带的稳定 UUID 引用。/ Registers the stable UUID reference carried by workpiece items. */
public final class ModDataComponents {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(
            Registries.DATA_COMPONENT_TYPE, TrialByHammer.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> WORKPIECE_ID =
            COMPONENTS.registerComponentType("workpiece_id", builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .cacheEncoding());

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
