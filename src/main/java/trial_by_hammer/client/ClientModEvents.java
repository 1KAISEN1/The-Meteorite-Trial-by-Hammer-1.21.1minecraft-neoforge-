package trial_by_hammer.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import trial_by_hammer.TrialByHammer;
import trial_by_hammer.client.renderer.LayeredSlabAnvilRenderer;
import trial_by_hammer.client.screen.ForgingScreen;
import trial_by_hammer.content.ModBlockEntities;
import trial_by_hammer.menu.ModMenus;

/** 客户端专用注册入口。 / Client-only registration entry point. */
@EventBusSubscriber(modid = TrialByHammer.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.FORGING.get(), ForgingScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.SMITHING_ANVIL.get(), LayeredSlabAnvilRenderer::new);
    }
}
