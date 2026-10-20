package com.trialbyhammer.probe.client;

import com.trialbyhammer.TrialByHammer;
import com.trialbyhammer.registry.ModBlockEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端专用的事件挂接点。
 *
 * <p>单独成类而不是塞进入口类，原因很简单：入口类在专用服务端也会加载，
 * 而这里的每一个 import 都只存在于客户端发行版里。混在一起会在服务端
 * 启动时触发 NoClassDefFoundError。</p>
 */
@EventBusSubscriber(modid = TrialByHammer.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.FORGE_PROBE.get(), ForgeProbeRenderer::new);
    }
}
