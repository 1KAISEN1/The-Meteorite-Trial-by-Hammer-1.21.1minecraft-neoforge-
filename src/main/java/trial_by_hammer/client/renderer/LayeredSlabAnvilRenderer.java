package trial_by_hammer.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import trial_by_hammer.content.block.entity.SmithingAnvilBlockEntity;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;

/**
 * 缓存连续板坯表面，并通过原版不透明实体管线渲染。
 * Caches a continuous slab surface and renders it through the vanilla opaque entity pipeline.
 */
public final class LayeredSlabAnvilRenderer implements BlockEntityRenderer<SmithingAnvilBlockEntity> {
    private static final ResourceLocation IRON_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/block/iron_block.png");

    private final Map<SmithingAnvilBlockEntity, CachedModel> modelCache = new WeakHashMap<>();

    public LayeredSlabAnvilRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            SmithingAnvilBlockEntity anvil,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay) {
        LayeredSlabWorkpiece geometry = anvil.clientGeometry();
        if (geometry == null) {
            return;
        }

        CachedModel cached = this.modelCache.get(anvil);
        if (cached == null || cached.revision() != anvil.clientRevision()) {
            cached = bake(anvil.clientRevision(), geometry);
            this.modelCache.put(anvil, cached);
        }

        poseStack.pushPose();
        poseStack.translate(
                0.5D + anvil.offsetX(),
                cached.liftForRoll(anvil.rollQuarterTurns()),
                0.5D + anvil.offsetZ());
        poseStack.mulPose(Axis.YP.rotationDegrees(-anvil.yawDegrees()));
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F * anvil.rollQuarterTurns()));
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entitySolid(IRON_TEXTURE));
        // 工件位于砧面上方，不能使用可能被实体方块遮暗的铁砧内部采样点。
        // The workpiece sits above the anvil and must not use the possibly occluded light sample inside it.
        int workpieceLight = anvil.getLevel() == null
                ? packedLight
                : LevelRenderer.getLightColor(anvil.getLevel(), anvil.getBlockPos().above());
        renderMesh(cached.mesh(), poseStack.last(), consumer, workpieceLight, packedOverlay);
        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(SmithingAnvilBlockEntity anvil) {
        // 板坯旋转并偏移后会越出单方块，扩大包围盒以免摊薄边缘被视锥裁掉。
        // A rotated and offset slab exceeds one block, so expand the box to retain spread edges.
        return new AABB(anvil.getBlockPos()).inflate(1.5D, 1.0D, 1.5D);
    }

    private static CachedModel bake(int revision, LayeredSlabWorkpiece geometry) {
        SmoothedSlabMesh.Mesh mesh = SmoothedSlabMesh.bake(geometry);
        return new CachedModel(revision, mesh, mesh.maxY(), mesh.minZ(), mesh.maxZ());
    }

    private static void renderMesh(
            SmoothedSlabMesh.Mesh mesh,
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay) {
        float[] vertices = mesh.vertices();
        for (int offset = 0; offset < vertices.length; offset += SmoothedSlabMesh.FLOATS_PER_VERTEX) {
            consumer.addVertex(pose, vertices[offset], vertices[offset + 1], vertices[offset + 2])
                    .setColor(0xFFFFFFFF)
                    .setUv(vertices[offset + 6], vertices[offset + 7])
                    .setOverlay(packedOverlay)
                    .setLight(packedLight)
                    .setNormal(pose, vertices[offset + 3], vertices[offset + 4], vertices[offset + 5]);
        }
    }

    private record CachedModel(
            int revision, SmoothedSlabMesh.Mesh mesh, float maxY, float minZ, float maxZ) {
        private float liftForRoll(int rollQuarterTurns) {
            return switch (Math.floorMod(rollQuarterTurns, 4)) {
                case 0 -> 1.005F;
                case 1 -> 1.005F - this.minZ;
                case 2 -> 1.005F + this.maxY;
                default -> 1.005F + this.maxZ;
            };
        }
    }
}
