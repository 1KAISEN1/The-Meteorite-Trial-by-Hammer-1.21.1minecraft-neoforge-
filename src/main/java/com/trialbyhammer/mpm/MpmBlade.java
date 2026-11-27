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
    static final int SAMPLES_X = 100, SAMPLES_Y = 25, SAMPLES_Z = 8;
    public static final int COUNT = SAMPLES_X * SAMPLES_Y * SAMPLES_Z;

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

    /**
     * 每个粒子的温度（绝对摄氏度）。
     *
     * <p>决定两件事：屈服强度（烧红了才锤得动）和颜色（黑体辐射）。</p>
     */
    private final float[] temperature = new float[COUNT];

    /** 整把刀的折叠次数。层数 = 2^folds。重熔归零。 */
    private int folds = 0;

    /** 碳含量（0–1）。只对钢类主体有意义。 */
    private float carbonContent = 0.0F;

    /** 环境温度（°C）。散热时朝它衰减。 */
    public static final float AMBIENT_C = 20.0F;

    // Hydrostatic pressure is independent of the isochoric deformation gradient.
    final float[] pressure = new float[COUNT];
    private final double initialVolume;

    public MpmBlade() {
        initBlock();
        initialVolume = MaterialVolume.measure(this);
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
        // 厚度方向给到 8 层：只有 2 层的话，粒子云是一张纸，
        // 压不出体积感，也建立不了厚度方向的应力梯度。
        final int nx = SAMPLES_X, ny = SAMPLES_Y, nz = SAMPLES_Z;
        final float perParticle = 2.7E8F / COUNT; // 13,500 nB

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
        /** 横锤：沿 Y 的窄面，主要沿 X 抽长。 */
        CROSS_PEEN(0.35F, 2.2F);

        final float scaleX;
        final float scaleY;

        HammerShape(float scaleX, float scaleY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }
    }

    /**
     * Apply a smooth, volume-preserving draw through the full local thickness.
     * A strip stretches along the face normal in the work plane. Material beyond
     * it translates with that strip, so the footprint cannot tear a hole at its rim.
     * Returns the summed downward displacement under the face (zero for a miss).
     */
    public float press(float sx, float sy, float depth, float radius, HammerShape shape) {
        if (!(depth > 0 && radius > 0)) return 0;
        float rx = radius * shape.scaleX, ry = radius * shape.scaleY;
        float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < COUNT; i++) {
            float u = (px[i] - sx) / rx, v = (py[i] - sy) / ry;
            if (Math.abs(u) < 1 && Math.abs(v) < 1) {
                bottom = Math.min(bottom, pz[i]);
                top = Math.max(top, pz[i]);
            }
        }
        if (!(top > bottom)) return 0;

        // Limit a single stroke relative to the remaining local thickness.
        float fraction = Math.min(0.18F, depth / (top - bottom));
        float stretch = 1 / (1 - fraction);
        float xStretch = shape == HammerShape.CROSS_PEEN ? stretch : (float) Math.sqrt(stretch);
        float pushed = drawStrip(px, py, sx, sy, rx, ry, bottom, xStretch - 1, false);
        if (shape != HammerShape.CROSS_PEEN) {
            pushed += drawStrip(py, px, sy, sx, ry, rx, bottom, stretch / xStretch - 1, true);
        }
        projectVolume();
        return pushed;
    }

    private float drawStrip(float[] along, float[] across, float center, float lateralCenter,
                            float radius, float lateralRadius, float bottom, float amount, boolean alongY) {
        float pushed = 0;
        Mat3 oldF = new Mat3(), inverseElastic = new Mat3(), mapped = new Mat3();
        for (int i = 0; i < COUNT; i++) {
            float v = (across[i] - lateralCenter) / lateralRadius;
            float u = (along[i] - center) / radius;
            if (Math.abs(v) >= 1 || (!alongY && u <= -1)) continue;
            u = Math.clamp(u, -1, 1);
            float ux = 1 - u * u, vyWeight = 1 - v * v;
            float b = ux * ux, w = vyWeight * vyWeight;
            // Integral of (1-u^2)^2. Anchor X at the held end; spread Y symmetrically.
            float integral = u * (1 + u * u * (-2.0F / 3 + u * u / 5))
                    + (alongY ? 0 : 8.0F / 15);
            float d = 1 + amount * w * b;
            // Beyond the face only translation and shear remain.
            float h = (pz[i] - bottom) / d;
            along[i] += radius * amount * w * integral;
            float drop = (pz[i] - bottom) - h;
            pz[i] -= drop;
            pushed += Math.max(0, drop);

            // Jacobian in (along, across, z) has diagonal (d, 1, 1/d).
            // Apply the same map to F so the constitutive model sees the stroke.
            float dw = -4 * v * vyWeight / lateralRadius;
            float shear = radius * amount * dw * integral;
            float za = h / d * amount * w * 4 * u * ux / radius;
            float zb = -h / d * amount * dw * b;
            int a = i * 9 + (alongY ? 3 : 0), c = i * 9 + (alongY ? 0 : 3), z = i * 9 + 6;
            if (d == 1 && shear == 0 && za == 0 && zb == 0) continue;
            // press prescribes a permanent stroke. Retain the pre-stroke Fe
            // (including any rigid rotation) when changing the material state.
            oldF.read(deform, i * 9);
            inverseElastic.read(plasticDeform, i * 9).mul(oldF.invert());
            for (int k = 0; k < 3; k++) {
                deform[z+k] = za * deform[a+k] + zb * deform[c+k] + deform[z+k] / d;
                deform[a+k] = d * deform[a+k] + shear * deform[c+k];
            }
            mapped.read(deform, i * 9);
            inverseElastic.mul(mapped);
            inverseElastic.scale((float) (1 / Math.cbrt(inverseElastic.det())));
            inverseElastic.write(plasticDeform, i * 9);
            addPlasticStrain(i, (float) Math.log(d));
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

    /** Stand the workpiece on its edge (the original quarter turn). */
    public void flip() {
        turnAboutX(true);
    }

    /** Present the opposite broad face for a second pass. */
    public void turnOver() {
        turnAboutX(false);
    }

    private void turnAboutX(boolean quarterTurn) {
        double sumY = 0, sumZ = 0;
        for (int i = 0; i < COUNT; i++) {
            sumY += py[i];
            sumZ += pz[i];
        }
        float cy = (float) (sumY / COUNT), cz = (float) (sumZ / COUNT);
        float lowestSupported = Float.POSITIVE_INFINITY;
        for (int i = 0; i < COUNT; i++) {
            float dy = py[i] - cy, dz = pz[i] - cz;
            py[i] = cy - (quarterTurn ? dz : dy);
            pz[i] = cz + (quarterTurn ? dy : -dz);
            if (MpmSolver.onAnvil(px[i], py[i])) lowestSupported = Math.min(lowestSupported, pz[i]);

            float oldVy = vy[i];
            vy[i] = quarterTurn ? -vz[i] : -vy[i];
            vz[i] = quarterTurn ? oldVy : -vz[i];
            // A spatial rotation acts on F and Fe. Fp is in material coordinates.
            for (int k = i * 9 + 3; k < i * 9 + 6; k++) {
                float oldY = deform[k];
                deform[k] = quarterTurn ? -deform[k + 3] : -oldY;
                deform[k + 3] = quarterTurn ? oldY : -deform[k + 3];
            }
        }
        if (lowestSupported < MpmSolver.FLOOR_Z) {
            float lift = MpmSolver.FLOOR_Z - lowestSupported;
            for (int i = 0; i < COUNT; i++) pz[i] += lift;
        }
    }

    void correctWidth(int i, float displacement, float gradientX, float stretch) {
        py[i] += displacement;
        int offset = i * 9;
        for (int k = offset; k < offset + 3; k++) {
            deform[k + 3] = gradientX * deform[k] + stretch * deform[k + 3];
        }
        float iso = (float)(1 / Math.cbrt(stretch));
        for (int k = offset; k < offset + 9; k++) deform[k] *= iso;
        pressure[i] += (MpmSolver.LAMBDA + pressure[i]) * (stretch - 1);
    }

    Mat3 readDeform(int i, Mat3 matrix) {
        return matrix.read(deform, i * 9);
    }

    Mat3 readPlasticDeform(int i, Mat3 matrix) {
        return matrix.read(plasticDeform, i * 9);
    }

    void writeDeform(int i, Mat3 matrix) {
        matrix.write(deform, i * 9);
    }

    void writePlasticDeform(int i, Mat3 matrix) {
        matrix.write(plasticDeform, i * 9);
    }

    void projectVolume() {
        MaterialVolume.project(this, initialVolume);
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

    public float temperature(int i) { return temperature[i]; }

    public void setTemperature(int i, float celsius) {
        temperature[i] = celsius;
    }

    /** 整把刀的折叠次数。层数 = 2^folds。 */
    public int folds() { return folds; }

    public void setFolds(int value) {
        folds = Math.max(0, Math.min(16, value));
    }

    /** 碳含量（0–1），只对钢类主体有意义。 */
    public float carbonContent() { return carbonContent; }

    public void setCarbonContent(float value) {
        carbonContent = Math.max(0.0F, Math.min(1.0F, value));
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

        // 锻造状态：温度、折叠次数、碳含量
        tag.putByteArray("temp", packTemperatures());
        tag.putByteArray("ep", packPlasticStrain());
        tag.putInt("folds", folds);
        tag.putFloat("carbon", carbonContent);
        return tag;
    }

    /**
     * 从存档恢复粒子。
     *
     * <p>只恢复位置和材料，形变矩阵重置为单位阵——塑性历史不存档。
     * 这是原型阶段的取舍：形状能保住（这是玩家最在意的），
     * 但"这块钢被锤过多少次"会丢。以后要保留的话，得把 Fp 也序列化。</p>
     *
     * @return 是否成功恢复（存档格式不对时返回 false，保留默认形状）
     */
    public boolean load(CompoundTag tag) {
        byte[] pos = tag.getByteArray("pos");
        byte[] mat = tag.getByteArray("mat");

        if (pos.length != COUNT * 6 || mat.length != COUNT) {
            return false;
        }

        for (int i = 0; i < COUNT; i++) {
            px[i] = unpackShort(pos, i * 6);
            py[i] = unpackShort(pos, i * 6 + 2);
            pz[i] = unpackShort(pos, i * 6 + 4);
            material[i] = mat[i];

            // 速度清零，形变归位
            vx[i] = 0.0F;
            vy[i] = 0.0F;
            vz[i] = 0.0F;

            for (int k = 0; k < 9; k++) {
                deform[i * 9 + k] = (k % 4 == 0) ? 1.0F : 0.0F;
                plasticDeform[i * 9 + k] = (k % 4 == 0) ? 1.0F : 0.0F;
            }
            plasticStrain[i] = 0.0F;
            temperature[i] = AMBIENT_C;
        }

        // 可选字段：旧存档没有这些，缺了就保持默认
        byte[] temp = tag.getByteArray("temp");
        if (temp.length == COUNT * 4) {
            for (int i = 0; i < COUNT; i++) {
                temperature[i] = unpackFloat(temp, i * 4);
            }
        }
        byte[] ep = tag.getByteArray("ep");
        if (ep.length == COUNT * 4) {
            for (int i = 0; i < COUNT; i++) {
                plasticStrain[i] = unpackFloat(ep, i * 4);
            }
        }
        folds = tag.getInt("folds");
        carbonContent = tag.getFloat("carbon");

        return true;
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

    // ---- 温度 / 塑性应变的打包（4 字节 float，不做量化） ----

    private byte[] packTemperatures() {
        byte[] out = new byte[COUNT * 4];
        for (int i = 0; i < COUNT; i++) {
            packFloat(out, i * 4, temperature[i]);
        }
        return out;
    }

    private byte[] packPlasticStrain() {
        byte[] out = new byte[COUNT * 4];
        for (int i = 0; i < COUNT; i++) {
            packFloat(out, i * 4, plasticStrain[i]);
        }
        return out;
    }

    private static void packFloat(byte[] out, int offset, float v) {
        int bits = Float.floatToIntBits(v);
        out[offset] = (byte) (bits >> 24);
        out[offset + 1] = (byte) (bits >> 16);
        out[offset + 2] = (byte) (bits >> 8);
        out[offset + 3] = (byte) bits;
    }

    private static float unpackFloat(byte[] in, int offset) {
        int bits = ((in[offset] & 0xFF) << 24)
                | ((in[offset + 1] & 0xFF) << 16)
                | ((in[offset + 2] & 0xFF) << 8)
                | (in[offset + 3] & 0xFF);
        return Float.intBitsToFloat(bits);
    }
}
