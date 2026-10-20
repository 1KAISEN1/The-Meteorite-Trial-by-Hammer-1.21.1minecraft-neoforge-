package com.trialbyhammer;

import com.trialbyhammer.registry.ModBlockEntities;
import com.trialbyhammer.registry.ModBlocks;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trial by Hammer 的模组入口。
 *
 * <p>这个类刻意保持"薄"：只做生命周期接线，不放任何业务逻辑。
 * 后续的熔炼、合金、锻造系统各自建包，通过 {@link IEventBus} 注册，
 * 避免入口类随着功能增加变成上帝类。</p>
 *
 * <p>命名约定：modid 用下划线（{@value #MOD_ID}），显示名用空格，
 * 任何地方都不使用连字符——见项目世界观文档的命名规则。</p>
 */
@Mod(TrialByHammer.MOD_ID)
public final class TrialByHammer {

    /** 模组标识。同时是资源命名空间，改动会破坏所有已有存档与资源引用。 */
    public static final String MOD_ID = "trial_by_hammer";

    /** 模组统一日志出口。用模组名而不是类名，方便玩家在日志里定位。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("Trial by Hammer");

    /**
     * 构造由 NeoForge 在模组加载阶段调用。
     *
     * @param modEventBus 模组专属事件总线，注册物品、方块、配方等都在这里挂接
     * @param modContainer 当前模组容器，用于读取元数据或注册配置
     */
    public TrialByHammer(IEventBus modEventBus, ModContainer modContainer) {
        // 注册顺序没有硬性要求，但保持"方块 → 方块实体"的阅读顺序。
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);

        LOGGER.info("Trial by Hammer 开始加载（{}）", modContainer.getModId());
    }
}
