package com.trialbyhammer.mpm;

/**
 * 软体/塑性形变求解器的统一接口。
 *
 * <p>同一个粒子云、同一次锤击，可以喂给不同算法，比较它们的结果与手感。
 * 这样做的好处：算法之间可以互换，测试流水线不用改。</p>
 */
public interface SoftBodySolver {

    /**
     * 推进一次模拟。
     *
     * @param blade    粒子云
     * @param dt       子步时长
     * @param substeps 子步数
     */
    void step(MpmBlade blade, float dt, int substeps);

    /** 算法名，用于报告与文件名。 */
    String name();
}
