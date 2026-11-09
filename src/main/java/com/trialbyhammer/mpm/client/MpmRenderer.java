package com.trialbyhammer.mpm.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.trialbyhammer.mpm.MpmBlade;
import com.trialbyhammer.probe.ForgeProbeBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

/**
 * 粒子渲染器。
 *
 * <p>原型阶段：每个粒子画成一个朝向镜头的方形。不做贴图、不做光照，
 * 只为看清"材料在怎么流"。</p>
 *
 * <p>性能：一万个粒子 × 4 顶点 = 4 万顶点，一帧提交一次，在可接受范围内。</p>
 */
public class MpmRenderer implements BlockEntityRenderer<ForgeProbeBlockEntity> {

    /** 单个粒子的屏幕尺寸。 */
    private static final float PARTICLE_SIZE = 0.010F;

    public MpmRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ForgeProbeBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        MpmBlade blade = blockEntity.blade();
        if (blade == null) {
            return;
        }

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = poseStack.last().pose();

        // 相机位置（世界坐标），用于让每个小方块朝向镜头
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double camX = camera.x - blockEntity.getBlockPos().getX();
        double camY = camera.y - blockEntity.getBlockPos().getY();
        double camZ = camera.z - blockEntity.getBlockPos().getZ();

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i);
            float y = blade.y(i);
            float z = blade.z(i);

            // 朝向镜头的小方块：用相机方向构造右向量与上向量
            float dx = (float) (camX - x);
            float dy = (float) (camY - y);
            float dz = (float) (camZ - z);
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-4F) {
                continue;
            }
            dx /= len;
            dy /= len;
            dz /= len;

            // 右向量 = 世界向上 × 视线方向
            float rx = 0.0F * dz - 1.0F * dy;
            float ry = 1.0F * dx - 0.0F * dz;
            float rz = 0.0F * dy - 0.0F * dx;
            float rlen = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (rlen < 1.0E-4F) {
                continue;
            }
            rx /= rlen;
            ry /= rlen;
            rz /= rlen;

            // 上向量 = 视线方向 × 右向量
            float ux = dy * rz - dz * ry;
            float uy = dz * rx - dx * rz;
            float uz = dx * ry - dy * rx;

            float s = PARTICLE_SIZE;

            // 按高度上色：越靠下越暗，帮助看清形变
            float shade = 0.45F + 0.55F * Math.min(1.0F, z * 2.0F);
            float r = shade;
            float g = shade * 0.45F;
            float b = shade * 0.12F;
            float a = 1.0F;

            float x0 = x - rx * s - ux * s, y0 = y - ry * s - uy * s, z0 = z - rz * s - uz * s;
            float x1 = x + rx * s - ux * s, y1 = y + ry * s - uy * s, z1 = z + rz * s - uz * s;
            float x2 = x + rx * s + ux * s, y2 = y + ry * s + uy * s, z2 = z + rz * s + uz * s;
            float x3 = x - rx * s + ux * s, y3 = y - ry * s + uy * s, z3 = z - rz * s + uz * s;

            consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
            consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
            consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
            consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(ForgeProbeBlockEntity blockEntity) {
        return true;
    }
}
