package trial_by_hammer.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import trial_by_hammer.TrialByHammer;

/** 客户端渲染使用的版本化工件快照。/ Versioned workpiece snapshot used for client rendering. */
public record WorkpieceSnapshotPayload(
        BlockPos anvilPos,
        UUID workpieceId,
        int revision,
        int offsetXUnits,
        int offsetZUnits,
        int yawStep,
        int rollQuarterTurns,
        int testTemperatureCelsius,
        byte[] geometry) implements CustomPacketPayload {
    // 768×768 求解网格的未压缩上限约为 1.7 MiB；网络上传输的仍是压缩快照。
    // The raw 768×768 solve grid is about 1.7 MiB; the network still carries a compressed snapshot.
    private static final int MAX_GEOMETRY_BYTES = 4_194_304;
    public static final Type<WorkpieceSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TrialByHammer.MODID, "workpiece_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WorkpieceSnapshotPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public WorkpieceSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new WorkpieceSnapshotPayload(
                            buffer.readBlockPos(),
                            buffer.readUUID(),
                            buffer.readVarInt(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readByteArray(MAX_GEOMETRY_BYTES));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, WorkpieceSnapshotPayload payload) {
                    if (payload.geometry.length > MAX_GEOMETRY_BYTES) {
                        throw new IllegalArgumentException("Workpiece snapshot exceeds network limit");
                    }
                    buffer.writeBlockPos(payload.anvilPos);
                    buffer.writeUUID(payload.workpieceId);
                    buffer.writeVarInt(payload.revision);
                    buffer.writeInt(payload.offsetXUnits);
                    buffer.writeInt(payload.offsetZUnits);
                    buffer.writeVarInt(payload.yawStep);
                    buffer.writeVarInt(payload.rollQuarterTurns);
                    buffer.writeVarInt(payload.testTemperatureCelsius);
                    buffer.writeByteArray(payload.geometry);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
