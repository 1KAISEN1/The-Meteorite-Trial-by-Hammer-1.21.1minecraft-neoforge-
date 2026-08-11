package trial_by_hammer.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import trial_by_hammer.client.ForgingCameraState;

/**
 * 在原版相机完成常规计算后固定锻造视角，避免临时相机实体造成插值抖动。
 * Pins the forging view after vanilla camera setup, avoiding interpolation jitter from a temporary camera entity.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void trialByHammer$applyForgingView(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean thirdPersonReverse,
            float partialTick,
            CallbackInfo callbackInfo) {
        if (!ForgingCameraState.isActive()) {
            return;
        }

        Vec3 position = ForgingCameraState.cameraPosition();
        this.setPosition(position.x, position.y, position.z);
        this.setRotation(0.0F, 90.0F, 0.0F);
    }
}
