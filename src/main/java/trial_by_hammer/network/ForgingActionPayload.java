package trial_by_hammer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import trial_by_hammer.TrialByHammer;

/** 客户端只提交操作意图，绝不提交修改后的几何。/ The client submits only action intent, never mutated geometry. */
public record ForgingActionPayload(
        BlockPos anvilPos,
        Action action,
        int planeXUnits,
        int planeZUnits,
        int value,
        int expectedRevision) implements CustomPacketPayload {
    public static final Type<ForgingActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TrialByHammer.MODID, "forging_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ForgingActionPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ForgingActionPayload decode(RegistryFriendlyByteBuf buffer) {
                    BlockPos pos = buffer.readBlockPos();
                    Action action = Action.byId(buffer.readUnsignedByte());
                    return new ForgingActionPayload(
                            pos,
                            action,
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, ForgingActionPayload payload) {
                    buffer.writeBlockPos(payload.anvilPos);
                    buffer.writeByte(payload.action.ordinal());
                    buffer.writeInt(payload.planeXUnits);
                    buffer.writeInt(payload.planeZUnits);
                    buffer.writeVarInt(payload.value);
                    buffer.writeVarInt(payload.expectedRevision);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        HAMMER,
        TONGS_MOVE,
        TONGS_ROTATE,
        TONGS_FLIP,
        MEASURE,
        SYNC_REQUEST,
        SET_TEST_TEMPERATURE;

        private static Action byId(int id) {
            Action[] values = values();
            if (id < 0 || id >= values.length) {
                throw new IllegalArgumentException("Unknown forging action " + id);
            }
            return values[id];
        }
    }
}
