package com.trialbyhammer.probe;

import com.trialbyhammer.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 锻造探针方块实体：保存"这块金属被怎么敲过"。
 *
 * <p>设计要点（同时也是正式锻造系统的预演）：</p>
 * <ul>
 *   <li><b>只存锤击参数，不存网格。</b>网格是渲染时的纯函数产物，
 *       存档里存几千个顶点既浪费又不可复现。</li>
 *   <li><b>形状是确定的。</b>同样的锤击序列永远得到同样的形状，
 *       这是世界观文档里"可复现"原则的底线。</li>
 *   <li><b>温度是独立状态。</b>它影响颜色与流动感，但不改变几何，
 *       这样调参时可以把"看起来热"和"敲成什么样"分开调。</li>
 * </ul>
 */
public class ForgeProbeBlockEntity extends BlockEntity {

    /** 锤击记录上限。超出后丢最旧的，防止刷右键把 NBT 撑爆。 */
    private static final int MAX_STRIKES = 96;

    private final List<Strike> strikes = new ArrayList<>();

    /** 0 = 冷（灰暗），1 = 炽热（亮橙）。初始给满，方便第一次右键就能看到效果。 */
    private float temperature = 1.0F;

    /**
     * 一次锤击。单位为方块局部坐标：u/v 在 [0,1]，depth 是世界单位下的凹陷深度。
     *
     * @param u     局部 X
     * @param v     局部 Z
     * @param depth 凹陷深度
     * @param radius 影响半径，越大越"软"
     */
    public record Strike(float u, float v, float depth, float radius) {
    }

    public ForgeProbeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FORGE_PROBE.get(), pos, state);
    }

    /**
     * 在局部坐标 (u, v) 敲一锤。
     *
     * <p>温度每次下降一点，用来演示"敲得越多、金属越冷、越难变形"的趋势。
     * 正式的熔炼系统会用连续的热量模型替代这个占位逻辑。</p>
     */
    public void strike(float u, float v) {
        if (strikes.size() >= MAX_STRIKES) {
            strikes.remove(0);
        }
        // 温度越低，同样一锤压得越浅——这是"热金属更软"的最简表达。
        float depth = 0.10F + 0.10F * temperature;
        strikes.add(new Strike(clamp01(u), clamp01(v), depth, 0.22F));
        temperature = Math.max(0.0F, temperature - 0.04F);
        markUpdated();
    }

    /** 把金属重新加热并清空所有锤击，回到初始态。 */
    public void reset() {
        strikes.clear();
        temperature = 1.0F;
        markUpdated();
    }

    /** 当前锤击记录（只读视图，避免外部误改）。 */
    public List<Strike> strikes() {
        return List.copyOf(strikes);
    }

    public float temperature() {
        return temperature;
    }

    // ---- 同步 ----

    /**
     * 数据变化后必须同时做两件事：标记脏数据（进存档）、发方块更新（给客户端）。
     * 少任何一步都会出现"自己看得到、别人看不到"或"重进世界就还原"的问题。
     */
    private void markUpdated() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ---- 存档 ----

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (Strike strike : strikes) {
            CompoundTag entry = new CompoundTag();
            entry.putFloat("u", strike.u());
            entry.putFloat("v", strike.v());
            entry.putFloat("depth", strike.depth());
            entry.putFloat("radius", strike.radius());
            list.add(entry);
        }
        tag.put("strikes", list);
        tag.putFloat("temperature", temperature);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        strikes.clear();
        ListTag list = tag.getList("strikes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            strikes.add(new Strike(
                    entry.getFloat("u"),
                    entry.getFloat("v"),
                    entry.getFloat("depth"),
                    entry.getFloat("radius")));
        }
        temperature = tag.contains("temperature") ? tag.getFloat("temperature") : 1.0F;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
