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
        // 0.006 是"一锤给多少速度"的经验值，等实测手感再调
        blade.strike(sx, sy, dirX, dirY, dirZ, 0.18F, force * 0.006F);
        solver.step(blade, DT, SUBSTEPS);
        setChanged();
    }

    /** 回炉：清掉速度，让粒子静止。 */
    public void reset() {
        blade.stop();
        setChanged();
    }

    public void setForce(float force) {
        this.force = Math.max(0.05F, Math.min(1.0F, force));
    }

    // ---- 存档 ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("blade", blade.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 原型阶段暂不恢复粒子（位置重建逻辑待补）
    }
}
