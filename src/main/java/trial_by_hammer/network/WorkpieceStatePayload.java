package trial_by_hammer.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import trial_by_hammer.TrialByHammer;

/**
 * 只同步工件姿态与测试温度，连续拖拽时不重复发送完整几何。
 * Synchronizes only pose and test temperature so continuous dragging does not resend full geometry.
 */
public record WorkpieceStatePayload(
        BlockPos anvilPos,
        UUID workpieceId,
        int revision,
        int offsetXUnits,
        int offsetZUnits,
        int yawStep,
        int rollQuarterTurns,
        int testTemperatureCelsius) implements CustomPacketPayload {
    public static final Type<WorkpieceStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TrialByHammer.MODID, "workpiece_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkpieceStatePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WorkpieceStatePayload decode(RegistryFriendlyByteBuf buffer) {
                    return new WorkpieceStatePayload(
                            buffer.readBlockPos(),
                            buffer.readUUID(),
                            buffer.readVarInt(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, WorkpieceStatePayload payload) {
                    buffer.writeBlockPos(payload.anvilPos);
                    buffer.writeUUID(payload.workpieceId);
                    buffer.writeVarInt(payload.revision);
                    buffer.writeInt(payload.offsetXUnits);
                    buffer.writeInt(payload.offsetZUnits);
                    buffer.writeVarInt(payload.yawStep);
                    buffer.writeVarInt(payload.rollQuarterTurns);
                    buffer.writeVarInt(payload.testTemperatureCelsius);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
