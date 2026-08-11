package trial_by_hammer.network;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import trial_by_hammer.content.block.entity.SmithingAnvilBlockEntity;
import trial_by_hammer.forging.WorkpieceSavedData;
import trial_by_hammer.forging.WorkpieceSavedData.Entry;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;
import trial_by_hammer.menu.ForgingMenu;
import trial_by_hammer.network.ForgingActionPayload.Action;

/** 注册并处理锻造专用的服务端权威网络动作。/ Registers and handles server-authoritative forging payloads. */
public final class ModNetworking {
    private static final int PLANE_UNITS_PER_BLOCK = 4096;

    private ModNetworking() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetworking::registerPayloads);
    }

    public static void sendSnapshot(ServerPlayer player, SmithingAnvilBlockEntity anvil) {
        WorkpieceSnapshotPayload snapshot = createSnapshot((ServerLevel)player.level(), anvil);
        if (snapshot != null) {
            PacketDistributor.sendToPlayer(player, snapshot);
        }
    }

    public static void sendSnapshotToTracking(ServerLevel level, SmithingAnvilBlockEntity anvil) {
        WorkpieceSnapshotPayload snapshot = createSnapshot(level, anvil);
        if (snapshot != null) {
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(anvil.getBlockPos()), snapshot);
        }
    }

    public static void sendStateToTracking(ServerLevel level, SmithingAnvilBlockEntity anvil) {
        WorkpieceStatePayload state = createState(level, anvil);
        if (state != null) {
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(anvil.getBlockPos()), state);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("3");
        registrar.playToServer(ForgingActionPayload.TYPE, ForgingActionPayload.STREAM_CODEC, ModNetworking::handleAction);
        registrar.playToClient(
                WorkpieceSnapshotPayload.TYPE, WorkpieceSnapshotPayload.STREAM_CODEC, ModNetworking::handleSnapshot);
        registrar.playToClient(
                WorkpieceStatePayload.TYPE, WorkpieceStatePayload.STREAM_CODEC, ModNetworking::handleState);
        registrar.playToClient(
                ThicknessMeasurementPayload.TYPE,
                ThicknessMeasurementPayload.STREAM_CODEC,
                ModNetworking::handleMeasurement);
    }

    private static void handleAction(ForgingActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof ForgingMenu menu)
                || !menu.getAnvilPos().equals(payload.anvilPos())
                || !menu.stillValid(player)
                || !(player.level().getBlockEntity(payload.anvilPos()) instanceof SmithingAnvilBlockEntity anvil)
                || !anvil.hasWorkpiece()) {
            return;
        }

        ServerLevel level = player.serverLevel();
        if (payload.action() == Action.SYNC_REQUEST) {
            sendSnapshot(player, anvil);
            return;
        }
        if (payload.action() == Action.TONGS_MOVE) {
            anvil.moveTo(
                    (double)payload.planeXUnits() / PLANE_UNITS_PER_BLOCK,
                    (double)payload.planeZUnits() / PLANE_UNITS_PER_BLOCK);
            sendStateToTracking(level, anvil);
            return;
        }
        if (payload.action() == Action.TONGS_ROTATE) {
            anvil.rotateYaw(Integer.signum(payload.value()));
            sendStateToTracking(level, anvil);
            return;
        }
        if (payload.action() == Action.TONGS_FLIP) {
            anvil.flip();
            sendStateToTracking(level, anvil);
            return;
        }

        UUID id = anvil.workpieceId().orElse(null);
        if (id == null) {
            return;
        }
        WorkpieceSavedData data = WorkpieceSavedData.get(level);
        Entry entry = data.getOrCreateIronBillet(id);
        if (payload.expectedRevision() != entry.revision()) {
            sendSnapshot(player, anvil);
            return;
        }

        if (payload.action() == Action.SET_TEST_TEMPERATURE) {
            data.setTestTemperature(id, payload.value());
            sendStateToTracking(level, anvil);
            return;
        }

        GridTarget target = projectToGrid(anvil, payload.planeXUnits(), payload.planeZUnits());
        if (!target.valid()) {
            return;
        }
        if (payload.action() == Action.MEASURE) {
            int volumeUnits = data.measure(id, target.x(), target.z());
            PacketDistributor.sendToPlayer(
                    player, new ThicknessMeasurementPayload(payload.anvilPos(), entry.revision(), volumeUnits));
            return;
        }
        if (payload.action() != Action.HAMMER) {
            return;
        }

        data.hammer(
                id,
                target.fromTop(),
                target.x(),
                target.z(),
                Math.max(1, Math.min(10, payload.value())));
        sendSnapshotToTracking(level, anvil);
    }

    private static GridTarget projectToGrid(SmithingAnvilBlockEntity anvil, int planeXUnits, int planeZUnits) {
        double worldX = (double)planeXUnits / PLANE_UNITS_PER_BLOCK - anvil.offsetX();
        double worldZ = (double)planeZUnits / PLANE_UNITS_PER_BLOCK - anvil.offsetZ();
        double radians = Math.toRadians(anvil.yawDegrees());
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double localLength = cosine * worldX + sine * worldZ;
        double horizontalCross = -sine * worldX + cosine * worldZ;
        int x = coordinateToCell(localLength);

        return switch (anvil.rollQuarterTurns()) {
            case 0 -> new GridTarget(x, coordinateToCell(horizontalCross), true, true);
            case 2 -> new GridTarget(x, coordinateToCell(-horizontalCross), false, true);
            default -> GridTarget.INVALID;
        };
    }

    private static int coordinateToCell(double coordinate) {
        return (int)Math.floor(coordinate / LayeredSlabWorkpiece.CELL_SIZE
                + (double)LayeredSlabWorkpiece.GRID_SIZE / 2.0D);
    }

    private static WorkpieceSnapshotPayload createSnapshot(ServerLevel level, SmithingAnvilBlockEntity anvil) {
        UUID id = anvil.workpieceId().orElse(null);
        if (id == null) {
            return null;
        }
        Entry entry = WorkpieceSavedData.get(level).getOrCreateIronBillet(id);
        return new WorkpieceSnapshotPayload(
                anvil.getBlockPos(),
                id,
                entry.revision(),
                anvil.offsetXUnits(),
                anvil.offsetZUnits(),
                anvil.yawStep(),
                anvil.rollQuarterTurns(),
                entry.testTemperatureCelsius(),
                entry.geometry().encode());
    }

    private static WorkpieceStatePayload createState(ServerLevel level, SmithingAnvilBlockEntity anvil) {
        UUID id = anvil.workpieceId().orElse(null);
        if (id == null) {
            return null;
        }
        Entry entry = WorkpieceSavedData.get(level).getOrCreateIronBillet(id);
        return new WorkpieceStatePayload(
                anvil.getBlockPos(),
                id,
                entry.revision(),
                anvil.offsetXUnits(),
                anvil.offsetZUnits(),
                anvil.yawStep(),
                anvil.rollQuarterTurns(),
                entry.testTemperatureCelsius());
    }

    private static void handleSnapshot(WorkpieceSnapshotPayload payload, IPayloadContext context) {
        if (!(context.player().level().getBlockEntity(payload.anvilPos()) instanceof SmithingAnvilBlockEntity anvil)) {
            return;
        }
        try {
            anvil.applyClientSnapshot(
                    payload.workpieceId(),
                    payload.revision(),
                    payload.offsetXUnits(),
                    payload.offsetZUnits(),
                    payload.yawStep(),
                    payload.rollQuarterTurns(),
                    payload.testTemperatureCelsius(),
                    LayeredSlabWorkpiece.decode(payload.geometry()));
        } catch (IOException ignored) {
            // 无效快照保持不可见，等待服务器后续版本重发。/ Keep invalid snapshots invisible until a later server resend.
        }
    }

    private static void handleState(WorkpieceStatePayload payload, IPayloadContext context) {
        if (context.player().level().getBlockEntity(payload.anvilPos()) instanceof SmithingAnvilBlockEntity anvil) {
            anvil.applyClientState(
                    payload.workpieceId(),
                    payload.revision(),
                    payload.offsetXUnits(),
                    payload.offsetZUnits(),
                    payload.yawStep(),
                    payload.rollQuarterTurns(),
                    payload.testTemperatureCelsius());
        }
    }

    private static void handleMeasurement(ThicknessMeasurementPayload payload, IPayloadContext context) {
        if (context.player().level().getBlockEntity(payload.anvilPos()) instanceof SmithingAnvilBlockEntity anvil) {
            anvil.applyClientMeasurement(payload.revision(), payload.volumeUnits());
        }
    }

    private record GridTarget(int x, int z, boolean fromTop, boolean faceAccessible) {
        private static final GridTarget INVALID = new GridTarget(-1, -1, true, false);

        private boolean valid() {
            return this.faceAccessible
                    && this.x >= 0
                    && this.x < LayeredSlabWorkpiece.GRID_SIZE
                    && this.z >= 0
                    && this.z < LayeredSlabWorkpiece.GRID_SIZE;
        }
    }
}
