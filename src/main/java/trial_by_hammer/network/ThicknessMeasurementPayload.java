package trial_by_hammer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import trial_by_hammer.TrialByHammer;

/** 服务端返回的真实局部厚度读数。/ Server-returned true local thickness measurement. */
public record ThicknessMeasurementPayload(BlockPos anvilPos, int revision, int volumeUnits)
        implements CustomPacketPayload {
    public static final Type<ThicknessMeasurementPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TrialByHammer.MODID, "thickness_measurement"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ThicknessMeasurementPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    ThicknessMeasurementPayload::anvilPos,
                    net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
                    ThicknessMeasurementPayload::revision,
                    net.minecraft.network.codec.ByteBufCodecs.VAR_INT,
                    ThicknessMeasurementPayload::volumeUnits,
                    ThicknessMeasurementPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
