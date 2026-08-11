package trial_by_hammer.content.block;

import com.mojang.serialization.MapCodec;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import trial_by_hammer.TrialByHammer;
import trial_by_hammer.content.block.entity.SmithingAnvilBlockEntity;
import trial_by_hammer.forging.ModDataComponents;
import trial_by_hammer.forging.WorkpieceSavedData;
import trial_by_hammer.menu.ForgingMenu;
import trial_by_hammer.network.ModNetworking;

/**
 * 安装 UUID 工件并打开服务端权威锻造视图的交互方块。
 * Interaction block that mounts UUID-backed workpieces and opens the server-authoritative forging view.
 */
public final class SmithingAnvilBlock extends BaseEntityBlock implements EntityBlock {
    public static final MapCodec<SmithingAnvilBlock> CODEC = simpleCodec(SmithingAnvilBlock::new);
    private static final Component TITLE = Component.translatable("screen.trial_by_hammer.forging");

    public SmithingAnvilBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // 方块实体基类默认隐藏普通方块模型；锻造砧必须显式渲染自己的模型。
        // BaseEntityBlock hides the normal block model by default; the smithing anvil must opt in.
        return RenderShape.MODEL;
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult) {
        if (!stack.is(TrialByHammer.IRON_WORKPIECE.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof SmithingAnvilBlockEntity anvil) || anvil.hasWorkpiece()) {
            return ItemInteractionResult.FAIL;
        }
        if (!level.isClientSide && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            ItemStack inserted = stack.copyWithCount(1);
            UUID id = inserted.get(ModDataComponents.WORKPIECE_ID.get());
            if (id == null) {
                id = UUID.randomUUID();
                inserted.set(ModDataComponents.WORKPIECE_ID.get(), id);
                WorkpieceSavedData.get(serverLevel).createIronBillet(id);
            } else {
                WorkpieceSavedData.get(serverLevel).getOrCreateIronBillet(id);
            }
            anvil.setWorkpiece(inserted);
            ModNetworking.sendSnapshotToTracking(serverLevel, anvil);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (!(level.getBlockEntity(pos) instanceof SmithingAnvilBlockEntity anvil) || !anvil.hasWorkpiece()) {
                player.displayClientMessage(Component.translatable("message.trial_by_hammer.anvil_empty"), true);
                return InteractionResult.CONSUME;
            }
            if (player.isShiftKeyDown()) {
                ItemStack removed = anvil.removeWorkpiece();
                if (!player.getInventory().add(removed)) {
                    player.drop(removed, false);
                }
                return InteractionResult.CONSUME;
            }
            MenuProvider provider = new SimpleMenuProvider(
                    (containerId, inventory, menuPlayer) -> new ForgingMenu(containerId, inventory, pos),
                    TITLE);
            serverPlayer.openMenu(provider, buffer -> buffer.writeBlockPos(pos));
            ModNetworking.sendSnapshot(serverPlayer, anvil);
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof SmithingAnvilBlockEntity anvil
                && anvil.hasWorkpiece()) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), anvil.removeWorkpiece());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmithingAnvilBlockEntity(pos, state);
    }
}
