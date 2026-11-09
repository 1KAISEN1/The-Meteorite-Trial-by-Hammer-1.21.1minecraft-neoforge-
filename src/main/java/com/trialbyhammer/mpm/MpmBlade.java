package com.trialbyhammer.mpm;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 一把剑的粒子云。
 *
 * <p>粒子的存在意义：体素网格不能旋转，而旋转大马士革需要材料点真正转动。
 * 所以形状的"真身"是这团粒子，网格只是每次锤击时的临时草稿纸。</p>
 *
 * <p>坐标约定：方块局部坐标 0..1。
 * X = 刀身长度方向，Y = 刀身宽度方向，Z = 厚度方向（锤击方向）。</p>
 */
public final class MpmBlade {

    /** 粒子数。先按 1 万跑，验证稳定性后再逐步下调。 */
    public static final int COUNT = 10_000;

    /**
     * 剑坯在方块内的占位。
     *
     * <p>按真实剑坯的比例：细长、有厚度。不是一块板砖，也不是一张纸。
     * 长度占满八成，宽度约三成，厚度约一成。</p>
     */
    public static final float X_MIN = 0.10F, X_MAX = 0.90F;
    public static final float Y_MIN = 0.35F, Y_MAX = 0.65F;
    public static final float Z_MIN = 0.31F, Z_MAX = 0.41F;

    /** 粒子位置。三个数组而不是对象数组，避免一万个对象带来的 GC 压力。 */
    private final float[] px = new float[COUNT];
    private final float[] py = new float[COUNT];
    private final float[] pz = new float[COUNT];

    /** 粒子速度。 */
    private final float[] vx = new float[COUNT];
    private final float[] vy = new float[COUNT];
    private final float[] vz = new float[COUNT];

    /** 每个粒子的质量（纳桶 nB）。一把剑约 270 mB = 2.7e8 nB。 */
    private final float[] mass = new float[COUNT];

    /**
     * 形变梯度 F，每个粒子一个 3×3 矩阵，按行展开存 9 个 float。
     * 这是塑性、硬度、花纹方向的唯一来源。
     */
    private final float[] deform = new float[COUNT * 9];

    /** 材料 ID，0 = 空（本版本恒非 0）。 */
    private final byte[] material = new byte[COUNT];

    /** 累积塑性应变，用于冷作硬化。 */
    private final float[] plasticStrain = new float[COUNT];

    public MpmBlade() {
        initBlock();
    }

    /**
     * 初始形状：一块长方体坯料。
     *
     * <p>粒子**不是**规则网格排列，而是带抖动的散点。原因：规则排列会让
     * 所有邻居距离完全一致，应力计算产生人为的"晶体"效应，锤击时整块材料
     * 像一块整齐的砖，而不是金属。</p>
     *
     * <p>抖动是确定性的（哈希而非随机数），所以同样的剑胚永远得到同样的分布。</p>
     */
    private void initBlock() {
        // 50 × 25 × 8 = 10,000
        // 厚度方向给到 8 层：只有 2 层的话，粒子云是一张纸，
        // 压不出体积感，也建立不了厚度方向的应力梯度。
        final int nx = 50, ny = 25, nz = 8;
        final float perParticle = 2.7E8F / COUNT; // 约 27,000 nB

        // 一格的实际尺寸，抖动幅度取它的六成
        final float cellX = (X_MAX - X_MIN) / nx;
        final float cellY = (Y_MAX - Y_MIN) / ny;
        final float cellZ = (Z_MAX - Z_MIN) / nz;
        final float jitter = 0.6F;

        int i = 0;
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                for (int iz = 0; iz < nz; iz++) {
                    float fx = (ix + 0.5F) / nx;
                    float fy = (iy + 0.5F) / ny;
                    float fz = (iz + 0.5F) / nz;

                    px[i] = X_MIN + fx * (X_MAX - X_MIN)
                            + (hash01(i, 1) - 0.5F) * cellX * jitter;
                    py[i] = Y_MIN + fy * (Y_MAX - Y_MIN)
                            + (hash01(i, 2) - 0.5F) * cellY * jitter;
                    pz[i] = Z_MIN + fz * (Z_MAX - Z_MIN)
                            + (hash01(i, 3) - 0.5F) * cellZ * jitter;

                    mass[i] = perParticle;
                    material[i] = 1;

                    // 单位矩阵：初始没有形变
                    deform[i * 9] = 1.0F;
                    deform[i * 9 + 4] = 1.0F;
                    deform[i * 9 + 8] = 1.0F;

                    i++;
                }
            }
        }
    }

    /**
     * 确定性哈希，返回 0..1。
     *
     * <p>用它而不是 Random：同样的索引永远得到同样的值，
     * 存档读档、服务端客户端重算都能对上。</p>
     */
    private static float hash01(int i, int salt) {
        int h = i * 374761393 + salt * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    /**
     * 在局部坐标 (sx, sy) 附近锤一下，方向由调用方给出。
     *
     * <p>真实锻造的锤击方向是三维的：可以垂直下锤、斜着削、横着挤。
     * 方向向量不需要归一化，这里会自行归一。</p>
     *
     * @param sx     落点 X（0..1）
     * @param sy     落点 Y（0..1）
     * @param dirX   锤击方向 X
     * @param dirY   锤击方向 Y
     * @param dirZ   锤击方向 Z
     * @param radius 影响半径（0..1）
     * @param force  力度，直接作为速度增量（已按距离衰减）
     */
    public void strike(float sx, float sy,
                       float dirX, float dirY, float dirZ,
                       float radius, float force) {
        // 方向归一化；零向量则默认向下，避免除零
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (len < 1.0E-6F) {
            dirX = 0.0F;
            dirY = 0.0F;
            dirZ = -1.0F;
        } else {
            dirX /= len;
            dirY /= len;
            dirZ /= len;
        }

        float r2 = radius * radius;
        for (int i = 0; i < COUNT; i++) {
            float dx = px[i] - sx;
            float dy = py[i] - sy;
            float d2 = dx * dx + dy * dy;
            if (d2 > r2) {
                continue;
            }
            // 平滑衰减：中心满力，边缘为零
            float w = 1.0F - d2 / r2;
            w = w * w;

            float impulse = force * w;
            vx[i] += dirX * impulse;
            vy[i] += dirY * impulse;
            vz[i] += dirZ * impulse;

            // 锤头的楔形效应：锤子压入时，接触点周围的材料被向外挤开。
            // 没有这一项，粒子只沿锤击方向动，横向形变永远是零——
            // 而横向形变为零，应力就不会产生横向力，形成死锁。
            float radial = (float) Math.sqrt(d2);
            if (radial > 1.0E-6F) {
                float spread = impulse * 0.15F;
                vx[i] += (dx / radial) * spread;
                vy[i] += (dy / radial) * spread;
            }

            // 记录塑性应变：这一锤压下去多少，就积累多少
            plasticStrain[i] += impulse * 0.01F;
        }
    }

    /** 把所有粒子的速度清零（回炉 / 重置时用）。 */
    public void stop() {
        java.util.Arrays.fill(vx, 0.0F);
        java.util.Arrays.fill(vy, 0.0F);
        java.util.Arrays.fill(vz, 0.0F);
    }

    public float x(int i) { return px[i]; }
    public float y(int i) { return py[i]; }
    public float z(int i) { return pz[i]; }

    public float vx(int i) { return vx[i]; }
    public float vy(int i) { return vy[i]; }
    public float vz(int i) { return vz[i]; }

    public void setVelocity(int i, float nx, float ny, float nz) {
        vx[i] = nx;
        vy[i] = ny;
        vz[i] = nz;
    }

    public void setPosition(int i, float nx, float ny, float nz) {
        px[i] = nx;
        py[i] = ny;
        pz[i] = nz;
    }

    public float mass(int i) { return mass[i]; }

    public float plasticStrain(int i) { return plasticStrain[i]; }

    /** 累积塑性应变，用于冷作硬化。 */
    public void addPlasticStrain(int i, float delta) {
        plasticStrain[i] = Math.min(10.0F, plasticStrain[i] + delta);
    }

    /** 退火：把硬化清零，金属重新变软。 */
    public void anneal() {
        java.util.Arrays.fill(plasticStrain, 0.0F);
    }

    public byte material(int i) { return material[i]; }

    public float deform(int i, int k) { return deform[i * 9 + k]; }

    public void setDeform(int i, int k, float value) { deform[i * 9 + k] = value; }

    // ---- 存档 ----

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        // 只存位置与材料；质量与形变可以从位置重建（原型阶段）
        tag.putByteArray("pos", packPositions());
        tag.putByteArray("mat", material.clone());
        return tag;
    }

    private byte[] packPositions() {
        // 每个坐标量化到 16 位（0..65535 对应 0..1），每粒子 6 字节
        byte[] out = new byte[COUNT * 6];
        for (int i = 0; i < COUNT; i++) {
            putShort(out, i * 6, px[i]);
            putShort(out, i * 6 + 2, py[i]);
            putShort(out, i * 6 + 4, pz[i]);
        }
        return out;
    }

    private static void putShort(byte[] out, int offset, float v) {
        int q = Math.max(0, Math.min(65535, Math.round(v * 65535.0F)));
        out[offset] = (byte) (q >> 8);
        out[offset + 1] = (byte) q;
    }
}
