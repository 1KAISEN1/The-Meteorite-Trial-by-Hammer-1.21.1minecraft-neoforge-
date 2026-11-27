package com.trialbyhammer.probe;

import com.trialbyhammer.mpm.MpmBlade;
import com.trialbyhammer.mpm.MpmSolver;
import com.trialbyhammer.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 锻造探针方块实体：持有一团粒子，右键锤击时跑一次 MPM 求解。
 *
 * <p>原型阶段刻意只在本地跑模拟（单人可玩）。正式实现要把求解放在服务端，
 * 客户端只接收锤击事件后重放，否则多人会各算各的。</p>
 */
public class ForgeProbeBlockEntity extends BlockEntity {

    /** 粒子云。全部状态都在这里。 */
    private final MpmBlade blade = new MpmBlade();

    /** 求解器。网格是临时的，每次锤击清空重算。 */
    private final MpmSolver solver = new MpmSolver();

    /** 每次锤击的子步数。越多越稳，越慢。 */
    private static final int SUBSTEPS = 5;

    /** 子步时长。 */
    private static final float DT = 1.0F / 120.0F;

    /** 当前力度（0–1），原型阶段固定。 */
    private float force = 0.6F;

    public ForgeProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FORGE_PROBE.get(), pos, state);
    }

    /** 供渲染器读取粒子云。 */
    public MpmBlade blade() {
        return blade;
    }

    /**
     * 在局部坐标 (sx, sy) 锤一下，方向由玩家视线决定。
     *
     * @param sx   落点 X（0..1）
     * @param sy   落点 Y（0..1）
     * @param dirX 锤击方向 X
     * @param dirY 锤击方向 Y
     * @param dirZ 锤击方向 Z
     */
    public void hammer(float sx, float sy, float dirX, float dirY, float dirZ) {
        // 位移约束是当前唯一有效的锤击方式：锤面压进去，材料让开。
        // 实测「press only」和「press+strike」结果完全一致，
        // 说明下面的 strike 被 press 覆盖了，不产生额外形变。
        blade.press(sx, sy, Math.abs(dirZ) * force * 0.05F, 0.18F, MpmBlade.HammerShape.ROUND);

        // 速度脉冲模型：保留以备切换，当前不生效。
        // blade.strike(sx, sy, dirX, dirY, dirZ, 0.18F, force * 0.006F,
        //         MpmBlade.HammerShape.ROUND);

        solver.step(blade, DT, SUBSTEPS);
        setChanged();
    }

    /** 回炉：清掉速度，让粒子静止。 */
    public void reset() {
        blade.stop();
        setChanged();
    }

    void thermalTick(net.minecraft.world.level.Level world, BlockPos pos) {
        var support = world.getBlockState(pos.below());
        boolean heating = support.is(net.minecraft.tags.BlockTags.FIRE)
                || support.is(net.minecraft.world.level.block.Blocks.LAVA)
                || ((support.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || support.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock) && support.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                && support.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT));
        if (solver.thermalStep(blade, .05F, heating ? 1300 : 20) && !world.isClientSide) setChanged();
    }

    public void setForce(float force) {
        this.force = Math.max(0.05F, Math.min(1.0F, force));
    }

    // ---- 存档 ----

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("blade", blade.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag bladeTag = tag.getCompound("blade");
        if (!bladeTag.isEmpty()) {
            blade.load(bladeTag);
        }
    }
}
