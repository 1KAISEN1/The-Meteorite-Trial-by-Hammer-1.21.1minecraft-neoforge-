package com.trialbyhammer.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.trialbyhammer.probe.ForgeProbeBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * 探针方块的渲染器。
 *
 * <p>每帧根据方块实体里的锤击记录重算高度场并提交顶点。
 * 单个方块约 121 个采样点、几百个三角形，CPU 开销可以忽略；
 * 如果以后要同时渲染几十个锻造台，再考虑缓存网格或改用 GPU 形变。</p>
 */
public class ForgeProbeRenderer implements BlockEntityRenderer<ForgeProbeBlockEntity> {

    /**
     * 临时贴图。探针阶段借用原版铁块，正式实现会换成陨铁的专用贴图。
     * 注意路径必须是完整的 "textures/..."，不是 atlas 里的简写。
     */
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");

    public ForgeProbeRenderer(BlockEntityRendererProvider.Context context) {
        // 目前不需要烘焙模型或加载额外资源，构造器留空。
    }

    @Override
    public void render(ForgeProbeBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        float[][] heights = SoftMetalMesh.heightField(blockEntity.strikes());
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        SoftMetalMesh.emit(consumer, poseStack.last(), heights, packedLight, blockEntity.temperature());
    }

    /** 方块没有实体模型，视锥剔除会误判，直接关掉。 */
    @Override
    public boolean shouldRenderOffScreen(ForgeProbeBlockEntity blockEntity) {
        return true;
    }
}
