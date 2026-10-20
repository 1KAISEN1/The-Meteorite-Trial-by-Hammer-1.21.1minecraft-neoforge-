package com.trialbyhammer.probe;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * 锻造探针方块：右键 = 在点击位置敲一锤，潜行右键 = 重置。
 *
 * <p>它存在的唯一目的是把"锤击 → 形变 → 渲染"这条链路跑通，
 * 验证 1.21.1 上动态网格的可行性。正式实现会替换成多方块锻造台。</p>
 */
public class ForgeProbeBlock extends BaseEntityBlock {

    /** 1.21 要求每个方块提供 codec，这里没有数据驱动需求，用最简单的实现。 */
    public static final MapCodec<ForgeProbeBlock> CODEC = simpleCodec(ForgeProbeBlock::new);

    public ForgeProbeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ForgeProbeBlockEntity(pos, state);
    }

    /**
     * 方块本体不可见，全部交给 {@link com.trialbyhammer.probe.client.ForgeProbeRenderer}
     * 渲染。这样我们就完全控制了几何，不会被原版方块模型限制住。
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        // 只在服务端改数据，客户端等同步包。避免两端各算一次导致不一致。
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ForgeProbeBlockEntity probe)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            probe.reset();
            return InteractionResult.CONSUME;
        }

        // 把世界坐标换算成方块局部坐标，再交给方块实体。
        Vec3 hit = hitResult.getLocation();
        probe.strike(
                (float) (hit.x - pos.getX()),
                (float) (hit.z - pos.getZ()));
        return InteractionResult.CONSUME;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /** 探针方块没有碰撞体，玩家可以走进去观察网格。
     *  正式锻造台会恢复完整碰撞。 */
    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }
}
