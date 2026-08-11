package trial_by_hammer.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 保存当前客户端锻造相机目标；只有相机 Mixin 读取这份瞬时状态。
 * Stores the active client forging-camera target; only the camera mixin reads this transient state.
 */
public final class ForgingCameraState {
    // 相对砧块底部采用玩家 1.8 格身高；扣除一格砧面后，镜头离工件约 0.8 格。
    // Use the player's fixed 1.8-block height from the anvil base, leaving about 0.8 blocks above its surface.
    private static final double CAMERA_HEIGHT = 1.80D;
    private static BlockPos anvilPos;

    private ForgingCameraState() {
    }

    public static void activate(BlockPos pos) {
        anvilPos = pos.immutable();
    }

    public static void deactivate() {
        anvilPos = null;
    }

    public static boolean isActive() {
        return anvilPos != null;
    }

    public static Vec3 cameraPosition() {
        BlockPos pos = anvilPos;
        if (pos == null) {
            throw new IllegalStateException("Forging camera is not active");
        }
        return new Vec3(pos.getX() + 0.5D, pos.getY() + CAMERA_HEIGHT, pos.getZ() + 0.5D);
    }
}
