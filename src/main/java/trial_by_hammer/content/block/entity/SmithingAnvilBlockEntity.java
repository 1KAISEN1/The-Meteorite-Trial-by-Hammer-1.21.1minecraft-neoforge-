package trial_by_hammer.content.block.entity;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import trial_by_hammer.content.ModBlockEntities;
import trial_by_hammer.forging.ModDataComponents;
import trial_by_hammer.forging.WorkpieceSavedData;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;

/**
 * 铁砧只保存已安装物品和姿态；完整材料格由 UUID 世界工件库拥有。
 * The anvil stores only the mounted item and pose; the UUID world repository owns the full material lattice.
 */
public final class SmithingAnvilBlockEntity extends BlockEntity {
    private static final int POSE_UNITS_PER_BLOCK = 4096;
    private static final int YAW_STEPS = 72;
    private static final int MAX_OFFSET_UNITS = 1536;

    private final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
    private int offsetXUnits;
    private int offsetZUnits;
    private int yawStep;
    private int rollQuarterTurns;
    private int clientRevision = -1;
    private int clientMeasuredVolumeUnits = -1;
    private int clientMeasurementRevision = -1;
    private int clientTestTemperatureCelsius = WorkpieceSavedData.DEFAULT_TEST_TEMPERATURE_CELSIUS;
    @Nullable
    private UUID clientGeometryId;
    @Nullable
    private LayeredSlabWorkpiece clientGeometry;

    public SmithingAnvilBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SMITHING_ANVIL.get(), pos, state);
    }

    public boolean hasWorkpiece() {
        return !this.items.getFirst().isEmpty() && this.workpieceId().isPresent();
    }

    public ItemStack workpieceStack() {
        return this.items.getFirst();
    }

    public Optional<UUID> workpieceId() {
        return Optional.ofNullable(this.items.getFirst().get(ModDataComponents.WORKPIECE_ID.get()));
    }

    public void setWorkpiece(ItemStack stack) {
        this.items.set(0, stack);
        this.resetPose();
        this.setChangedAndNotify();
    }

    public ItemStack removeWorkpiece() {
        ItemStack removed = this.items.set(0, ItemStack.EMPTY);
        this.resetPose();
        this.clearClientGeometry();
        this.setChangedAndNotify();
        return removed;
    }

    public double offsetX() {
        return (double)this.offsetXUnits / POSE_UNITS_PER_BLOCK;
    }

    public double offsetZ() {
        return (double)this.offsetZUnits / POSE_UNITS_PER_BLOCK;
    }

    public int yawStep() {
        return this.yawStep;
    }

    public float yawDegrees() {
        return this.yawStep * 5.0F;
    }

    public int rollQuarterTurns() {
        return this.rollQuarterTurns;
    }

    public void moveTo(double offsetX, double offsetZ) {
        this.offsetXUnits = clampOffset((int)Math.round(offsetX * POSE_UNITS_PER_BLOCK));
        this.offsetZUnits = clampOffset((int)Math.round(offsetZ * POSE_UNITS_PER_BLOCK));
        this.setChanged();
    }

    public void rotateYaw(int deltaSteps) {
        this.yawStep = Math.floorMod(this.yawStep + deltaSteps, YAW_STEPS);
        this.setChanged();
    }

    public void flip() {
        this.rollQuarterTurns = Math.floorMod(this.rollQuarterTurns + 1, 4);
        this.setChanged();
    }

    public void applyClientSnapshot(
            UUID id,
            int revision,
            int offsetXUnits,
            int offsetZUnits,
            int yawStep,
            int rollQuarterTurns,
            int testTemperatureCelsius,
            LayeredSlabWorkpiece geometry) {
        UUID mountedId = this.workpieceId().orElse(null);
        // 放置快照可能早于方块实体物品更新到达；空客户端砧可以先接收服务端快照，再由更新标签确认 ID。
        // A placement snapshot may arrive before the block-entity item update; an empty client anvil may
        // accept the server snapshot first and let the later update tag confirm its ID.
        if (mountedId != null && !mountedId.equals(id)) {
            return;
        }
        this.clientGeometryId = id;
        this.clientRevision = revision;
        this.offsetXUnits = clampOffset(offsetXUnits);
        this.offsetZUnits = clampOffset(offsetZUnits);
        this.yawStep = Math.floorMod(yawStep, YAW_STEPS);
        this.rollQuarterTurns = Math.floorMod(rollQuarterTurns, 4);
        this.clientTestTemperatureCelsius = testTemperatureCelsius;
        this.clientGeometry = geometry;
    }

    public void applyClientState(
            UUID id,
            int revision,
            int offsetXUnits,
            int offsetZUnits,
            int yawStep,
            int rollQuarterTurns,
            int testTemperatureCelsius) {
        if (!id.equals(this.clientGeometryId) || revision != this.clientRevision) {
            return;
        }
        this.offsetXUnits = clampOffset(offsetXUnits);
        this.offsetZUnits = clampOffset(offsetZUnits);
        this.yawStep = Math.floorMod(yawStep, YAW_STEPS);
        this.rollQuarterTurns = Math.floorMod(rollQuarterTurns, 4);
        this.clientTestTemperatureCelsius = testTemperatureCelsius;
    }

    @Nullable
    public LayeredSlabWorkpiece clientGeometry() {
        return this.clientGeometry;
    }

    public int clientRevision() {
        return this.clientRevision;
    }

    public void applyClientMeasurement(int revision, int volumeUnits) {
        if (revision == this.clientRevision) {
            this.clientMeasurementRevision = revision;
            this.clientMeasuredVolumeUnits = Math.max(0, volumeUnits);
        }
    }

    public int clientMeasuredVolumeUnits() {
        return this.clientMeasurementRevision == this.clientRevision ? this.clientMeasuredVolumeUnits : -1;
    }

    public int clientTestTemperatureCelsius() {
        return this.clientTestTemperatureCelsius;
    }

    public int offsetXUnits() {
        return this.offsetXUnits;
    }

    public int offsetZUnits() {
        return this.offsetZUnits;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, this.items, true, registries);
        tag.putInt("offset_x", this.offsetXUnits);
        tag.putInt("offset_z", this.offsetZUnits);
        tag.putInt("yaw_step", this.yawStep);
        tag.putInt("roll_quarters", this.rollQuarterTurns);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items.clear();
        ContainerHelper.loadAllItems(tag, this.items, registries);
        UUID loadedId = this.workpieceId().orElse(null);
        if (!Objects.equals(this.clientGeometryId, loadedId)) {
            this.clearClientGeometry();
        }
        this.offsetXUnits = clampOffset(tag.getInt("offset_x"));
        this.offsetZUnits = clampOffset(tag.getInt("offset_z"));
        this.yawStep = Math.floorMod(tag.getInt("yaw_step"), YAW_STEPS);
        this.rollQuarterTurns = Math.floorMod(tag.getInt("roll_quarters"), 4);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void resetPose() {
        this.offsetXUnits = 0;
        this.offsetZUnits = 0;
        this.yawStep = 0;
        this.rollQuarterTurns = 0;
    }

    private void clearClientGeometry() {
        this.clientGeometryId = null;
        this.clientGeometry = null;
        this.clientRevision = -1;
        this.clientMeasuredVolumeUnits = -1;
        this.clientMeasurementRevision = -1;
    }

    private void setChangedAndNotify() {
        this.setChanged();
        if (this.level != null) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    private static int clampOffset(int value) {
        return Math.max(-MAX_OFFSET_UNITS, Math.min(MAX_OFFSET_UNITS, value));
    }
}
