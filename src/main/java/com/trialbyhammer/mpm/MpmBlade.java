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

    /** 粒子数。alpha 阶段先堆料，验证可行性优先于性能。 */
    public static final int COUNT = 20_000;

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

    /**
     * 塑性形变 Fp，同样是 3×3 矩阵，按行展开。
     *
     * <p>总形变 F 拆成 F = Fe · Fp：
     * Fe 是弹性部分（会回弹），Fp 是塑性部分（永久保留）。
     * 没有这个拆分，金属就会像弹簧一样锤完弹回去。</p>
     */
    private final float[] plasticDeform = new float[COUNT * 9];

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
        final int nx = 100, ny = 25, nz = 8;
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

                    plasticDeform[i * 9] = 1.0F;
                    plasticDeform[i * 9 + 4] = 1.0F;
                    plasticDeform[i * 9 + 8] = 1.0F;

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
     * 锤击形状：圆头、平头、横锤。
     *
     * <p>真实锻造里锤头形状决定材料往哪流：
     * 圆头锤是一个点，平锤是一个面，横锤是一条线（窄面）。
     * 横锤的窄面制造局部高压，金属沿垂直于线的方向流开，是"抽长"的主力。</p>
     */
    public enum HammerShape {
        /** 圆头：近似圆形的作用区。 */
        ROUND(1.0F, 1.0F),
        /** 平头：正方形作用区，压得均匀。 */
        FLAT(1.0F, 1.0F),
        /** 横锤：沿 X 的一条窄线，强制材料沿 Y 流动。 */
        CROSS_PEEN(2.2F, 0.35F);

        final float scaleX;
        final float scaleY;

        HammerShape(float scaleX, float scaleY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    /**
     * 锤面压入：模拟真实锤子"占空间"而不是"给速度"。
     *
     * <p>之前是给作用区内的粒子加一个速度脉冲，结果脉冲被 MPM 的网格平均、
     * 被应力抵抗、被阻尼吃掉，材料只是轻轻抖一下。</p>
     *
     * <p>真实锤子是一个位移边界：锤面压进去，材料必须让开。
     * 这里改成：</p>
     * <ol>
     *   <li>先找到作用区当前的最高点，确定锤面高度</li>
     *   <li>锤面以上的粒子被直接压到锤面（位移约束）</li>
     *   <li>被压开的体积给一个向外的速度，让它流向四周（体积守恒）</li>
     * </ol>
     *
     * @param sx       落点 X
     * @param sy       落点 Y
     * @param depth    压入深度（占 Z 的比例）
     * @param radius   基准影响半径
     * @param shape    锤头形状
     * @return 实际压下去的体积（相对量），可用于相机震动、音效等
     */
    public float press(float sx, float sy, float depth, float radius, HammerShape shape) {
        float rx = radius * shape.scaleX;
        float ry = radius * shape.scaleY;

        // 1. 找作用区最高点
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < COUNT; i++) {
            float dx = (px[i] - sx) / rx;
            float dy = (py[i] - sy) / ry;
            if (dx * dx + dy * dy > 1.0F) {
                continue;
            }
            if (pz[i] > maxZ) {
                maxZ = pz[i];
            }
        }
        if (maxZ < -1.0E6F) {
            return 0.0F;
        }

        // 2. 锤面高度
        float surface = maxZ - depth;
        float pushed = 0.0F;

        for (int i = 0; i < COUNT; i++) {
            float dx = (px[i] - sx) / rx;
            float dy = (py[i] - sy) / ry;
            float d2 = dx * dx + dy * dy;
            if (d2 > 1.0F) {
                continue;
            }
            if (pz[i] <= surface) {
                continue;
            }

            // 3. 位移约束：压到锤面
            float over = pz[i] - surface;
            pz[i] = surface;
            pushed += over;

            // 4. 体积重分配：压掉的高度直接变成水平位移。
            // 不等应力传播——锤击是确定的几何操作，压多少就往外推多少。
            float ox = px[i] - sx;
            float oy = py[i] - sy;
            float r = (float) Math.sqrt(ox * ox + oy * oy);
            float push = over * 0.2F;

            if (r > 1.0E-6F) {
                px[i] += (ox / r) * push;
                py[i] += (oy / r) * push;
            } else {
                // 正中锤心：用索引哈希给个确定方向，避免除零（不用随机）
                float a = hash01(i, 7) * 6.2831853F;
                px[i] += (float) Math.cos(a) * push;
                py[i] += (float) Math.sin(a) * push;
            }

            // 5. 被压过的粒子会冷作硬化
            plasticStrain[i] = Math.min(10.0F, plasticStrain[i] + over * 2.0F);
        }

        return pushed;
    }

    /**
     * 在局部坐标 (sx, sy) 附近锤一下，方向由调用方给出。
     *
     * @param sx      落点 X（0..1）
     * @param sy      落点 Y（0..1）
     * @param dirX    锤击方向 X
     * @param dirY    锤击方向 Y
     * @param dirZ    锤击方向 Z
     * @param radius  基准影响半径（0..1）
     * @param force   力度，直接作为速度增量（已按距离衰减）
     * @param shape   锤头形状
     */
    public void strike(float sx, float sy,
                       float dirX, float dirY, float dirZ,
                       float radius, float force, HammerShape shape) {
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

        // 椭圆作用区：横锤在 X 方向拉长、Y 方向压窄
        float rx = radius * shape.scaleX;
        float ry = radius * shape.scaleY;

        for (int i = 0; i < COUNT; i++) {
            float dx = px[i] - sx;
            float dy = py[i] - sy;

            // 归一化到椭圆坐标系
            float ex = dx / rx;
            float ey = dy / ry;
            float d2 = ex * ex + ey * ey;
            if (d2 > 1.0F) {
                continue;
            }
            // 平滑衰减：中心满力，边缘为零
            float w = 1.0F - d2;
            w = w * w;

            float impulse = force * w;
            vx[i] += dirX * impulse;
            vy[i] += dirY * impulse;
            vz[i] += dirZ * impulse;

            // 锤头的楔形效应：压入时把周围材料向外挤开。
            // 没有这一项，横向形变永远是零，应力也就不会产生横向力。
            float radial = (float) Math.sqrt(dx * dx + dy * dy);
            if (radial > 1.0E-6F) {
                float spread = impulse * 0.0F;  // 诊断：关掉楔形横向分量
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

    /**
     * 翻面：把工件绕长轴（X）旋转 90°。
     *
     * <p>现实中抽长会让工件变宽，铁匠会把工件翻过来锤窄，反复交替。
     * 没有翻面，材料只会越锤越宽，做不出细长的刀身。</p>
     *
     * <p>旋转 90° 后，厚度方向变成宽度方向，所以下一次抽长压的是
     * 原来的"宽"，这样两个方向轮流被压薄，最终形成长条。</p>
     */
    public void flip() {
        // 以包围盒中心为旋转轴，避免工件整体漂移
        float sumY = 0.0F, sumZ = 0.0F;
        for (int i = 0; i < COUNT; i++) {
            sumY += py[i];
            sumZ += pz[i];
        }
        float cy = sumY / COUNT;
        float cz = sumZ / COUNT;

        for (int i = 0; i < COUNT; i++) {
            float dy = py[i] - cy;
            float dz = pz[i] - cz;

            // 绕 X 轴 90°：y' = cz - dz, z' = cy + dy
            py[i] = cy - dz;
            pz[i] = cz + dy;

            float vyi = vy[i];
            float vzi = vz[i];
            vy[i] = -vzi;
            vz[i] = vyi;

            // 形变矩阵跟着转：F' = R · F，R 是绕 X 轴 90° 的旋转
            //   row0' =  row0
            //   row1' = -row2
            //   row2' =  row1
            float f10 = deform[i * 9 + 3], f11 = deform[i * 9 + 4], f12 = deform[i * 9 + 5];
            float f20 = deform[i * 9 + 6], f21 = deform[i * 9 + 7], f22 = deform[i * 9 + 8];

            deform[i * 9 + 3] = -f20;
            deform[i * 9 + 4] = -f21;
            deform[i * 9 + 5] = -f22;

            deform[i * 9 + 6] = f10;
            deform[i * 9 + 7] = f11;
            deform[i * 9 + 8] = f12;

            // 塑性形变也要跟着转，否则翻面后"永久变形"的方向就错了
            float p10 = plasticDeform[i * 9 + 3], p11 = plasticDeform[i * 9 + 4], p12 = plasticDeform[i * 9 + 5];
            float p20 = plasticDeform[i * 9 + 6], p21 = plasticDeform[i * 9 + 7], p22 = plasticDeform[i * 9 + 8];

            plasticDeform[i * 9 + 3] = -p20;
            plasticDeform[i * 9 + 4] = -p21;
            plasticDeform[i * 9 + 5] = -p22;

            plasticDeform[i * 9 + 6] = p10;
            plasticDeform[i * 9 + 7] = p11;
            plasticDeform[i * 9 + 8] = p12;
        }
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

    /** 塑性形变 Fp 的分量（行主序 0..8）。 */
    public float plasticDeform(int i, int k) { return plasticDeform[i * 9 + k]; }

    public void setPlasticDeform(int i, int k, float value) { plasticDeform[i * 9 + k] = value; }

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

    /**
     * 存档量化范围。
     *
     * <p>锻造会把材料拉出方块边界（抽长时能到 -0.2 或 1.4），
     * 所以不能假设 [0,1]。[−0.5, 1.5] 留足了余量。</p>
     */
    private static final float SAVE_MIN = -0.5F;
    private static final float SAVE_RANGE = 2.0F;

    private byte[] packPositions() {
        // 每个坐标量化到 16 位，覆盖 [SAVE_MIN, SAVE_MIN+SAVE_RANGE]
        byte[] out = new byte[COUNT * 6];
        for (int i = 0; i < COUNT; i++) {
            putShort(out, i * 6, px[i]);
            putShort(out, i * 6 + 2, py[i]);
            putShort(out, i * 6 + 4, pz[i]);
        }
        return out;
    }

    private static void putShort(byte[] out, int offset, float v) {
        float t = (v - SAVE_MIN) / SAVE_RANGE;
        int q = Math.max(0, Math.min(65535, Math.round(t * 65535.0F)));
        out[offset] = (byte) (q >> 8);
        out[offset + 1] = (byte) q;
    }

    /** 反量化，和 {@link #putShort} 配套。 */
    public static float unpackShort(byte[] in, int offset) {
        int q = ((in[offset] & 0xFF) << 8) | (in[offset + 1] & 0xFF);
        return SAVE_MIN + (q / 65535.0F) * SAVE_RANGE;
    }
}
