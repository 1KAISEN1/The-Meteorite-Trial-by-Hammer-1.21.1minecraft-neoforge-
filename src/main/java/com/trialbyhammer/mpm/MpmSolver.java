package com.trialbyhammer.mpm;

/**
 * MPM（物质点法）求解器——应力版。
 *
 * <p>与"密度压力"版本的区别：这里由每个粒子的形变矩阵 F 算出**应力张量**，
 * 再把应力通过网格传回去。这样：</p>
 * <ul>
 *   <li>体积守恒来自应力的体积项（J−1），不是人为推挤</li>
 *   <li>各向异性来自应力张量的方向性，横锤和竖锤会产生不同变形</li>
 *   <li>塑性来自"应力超过屈服强度就截断"，锤完定型</li>
 *   <li>冷作硬化来自累积塑性应变抬高屈服强度</li>
 * </ul>
 *
 * <p>参考：Stomakhin et al. 2013 的弹性 MPM；Jiang et al. 2016 SIGGRAPH 课程。
 * 本构采用固定 corotated 模型（极分解 + 体积项）。</p>
 */
public final class MpmSolver implements SoftBodySolver {

    @Override
    public String name() {
        return "mpm";
    }

    public static final int NX = 32;
    public static final int NY = 16;
    public static final int NZ = 6;

    /**
     * 网格覆盖的世界范围。比 [0,1] 大得多，让材料向四周延展时够不着边界。
     *
     * <p>网格是 MPM 的计算中介，粒子之间的作用全靠它。数组是有限的，
     * 所以"边界"本质上无法消除，只能推远。</p>
     */
    public static final float GRID_MIN = -0.5F;
    public static final float GRID_SIZE = 2.0F;

    private static final int CELLS = NX * NY * NZ;

    /** 网格：质量、速度、力。每次子步清空重算。 */
    private final float[] gMass = new float[CELLS];
    private final float[] gvx = new float[CELLS];
    private final float[] gvy = new float[CELLS];
    private final float[] gvz = new float[CELLS];
    private final float[] gfx = new float[CELLS];
    private final float[] gfy = new float[CELLS];
    private final float[] gfz = new float[CELLS];

    // ---- 材料参数（归一化单位，可调） ----

    /** 剪切模量：越大越硬，形状越难改变。 */
    private static final float MU = 3.0F;

    /** 体积模量：越大越不可压缩。 */
    private static final float LAMBDA = 50.0F;

    /** 基础屈服强度。应力超过它就开始塑性流动。 */
    private static final float YIELD_BASE = 0.12F;

    /** 硬化系数：累积塑性应变越高，屈服强度越高。 */
    private static final float HARDENING = 3.0F;

    /** 速度上限。 */
    private static final float MAX_SPEED = 1.2F;

    /** 速度阻尼。 */
    private static final float DAMPING = 0.99F;

    /**
     * 重力加速度（归一化坐标，1 单位 = 1 方块 = 1 米）。
     *
     * <p>没有重力时，被锤击推出去的材料会一直漂着，边缘会"翘起来"。</p>
     */
    private static final float GRAVITY = 0.5F;

    /**
     * 钳子夹持区：X 小于这个值的粒子被固定住，模拟夹在台钳里的那一端。
     */
    private static final float CLAMP_X = 0.12F;

    /** 铁砧底面。 */
    public static final float FLOOR_Z = 0.30F;

    /** 初始体积相对质量的系数。绝对值不重要，整体一致即可。 */
    private static final float VOLUME_PER_MASS = 1.0F;

    /** 网格单元体积（对应 GRID_SIZE 的范围）。 */
    private static final float CELL_VOL =
            (GRID_SIZE / NX) * (GRID_SIZE / NY) * (GRID_SIZE / NZ);

    /**
     * 初始包围盒体积。第一次推进时记录，用于判断材料是否被锤松。
     */
    private float restVolume = 0.0F;

    /** 位置投影的强度。太大则抖动，太小则压不实。 */
    private static final float PROJECTION = 0.35F;

    private final Mat3 f = new Mat3();
    private final Mat3 sigma = new Mat3();

    /**
     * 推进一次锤击。
     */
    public void step(MpmBlade blade, float dt, int substeps) {
        for (int s = 0; s < substeps; s++) {
            clearGrid();
            particleToGrid(blade);
            gridUpdate(dt);
            gridToParticle(blade, dt);
            projectVolume(blade);
        }
    }

    /**
     * 体积保持：材料被锤松时，把它整体向质心收一点。
     *
     * <p>用包围盒体积做判断，简单但稳定。密度梯度投影在边界处符号容易出问题，
     * 会把粒子吹飞，所以不用那套。</p>
     */
    private void projectVolume(MpmBlade blade) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        double sumX = 0.0, sumY = 0.0, sumZ = 0.0;

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            sumX += x;
            sumY += y;
            sumZ += z;
        }

        float vol = Math.max(1.0E-6F, (maxX - minX) * (maxY - minY) * (maxZ - minZ));

        if (restVolume <= 0.0F) {
            restVolume = vol;
            return;
        }

        float ratio = vol / restVolume;

        // 双向：膨胀了收缩，压缩了扩张。只单向的话，材料会被锤得越来越紧。
        if (Math.abs(ratio - 1.0F) < 0.08F) {
            return;
        }

        // 每次只纠正一半，避免来回振荡
        float correction = (float) Math.pow(1.0F / ratio, 1.0 / 3.0);
        float scale = 1.0F + (correction - 1.0F) * 0.5F;
        float cx = (float) (sumX / MpmBlade.COUNT);
        float cy = (float) (sumY / MpmBlade.COUNT);
        float cz = (float) (sumZ / MpmBlade.COUNT);

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                continue;
            }
            float nx = cx + (x - cx) * scale;
            float ny = cy + (y - cy) * scale;
            float nz = cz + (z - cz) * scale;
            if (nz < FLOOR_Z) {
                nz = FLOOR_Z;
            }
            blade.setPosition(i, nx, ny, nz);
        }
    }

    private float densityAt(int x, int y, int z) {
        int cx = clampCell(x, NX);
        int cy = clampCell(y, NY);
        int cz = clampCell(z, NZ);
        return gMass[index(cx, cy, cz)] / CELL_VOL;
    }

    private void clearGrid() {
        java.util.Arrays.fill(gMass, 0.0F);
        java.util.Arrays.fill(gvx, 0.0F);
        java.util.Arrays.fill(gvy, 0.0F);
        java.util.Arrays.fill(gvz, 0.0F);
        java.util.Arrays.fill(gfx, 0.0F);
        java.util.Arrays.fill(gfy, 0.0F);
        java.util.Arrays.fill(gfz, 0.0F);
    }

    /**
     * 粒子 → 网格。除了质量与动量，还要把应力传上去：
     *
     * <pre>
     * gridForce -= V0 · σ · ∇w
     * </pre>
     */
    private void particleToGrid(MpmBlade blade) {
        final float[] wx = new float[3];
        final float[] wy = new float[3];
        final float[] wz = new float[3];

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            float m = blade.mass(i);

            loadDeform(blade, i, f);
            MpmMath.stress(f, sigma, MU, LAMBDA);

            // 冷作硬化
            float yield = YIELD_BASE * (1.0F + HARDENING * blade.plasticStrain(i));
            if (MpmMath.clampToYield(sigma, yield)) {
                blade.addPlasticStrain(i, 0.002F);
            }

            float v0 = m * VOLUME_PER_MASS;

            // 网格覆盖 [-0.5, 1.5]，比材料可能扩展的范围大得多，
            // 这样边缘的"网格墙"在实际使用中够不着。
            float gxf = (x - GRID_MIN) / GRID_SIZE * NX;
            float gyf = (y - GRID_MIN) / GRID_SIZE * NY;
            float gzf = (z - GRID_MIN) / GRID_SIZE * NZ;

            int cx = clampCell((int) gxf, NX);
            int cy = clampCell((int) gyf, NY);
            int cz = clampCell((int) gzf, NZ);

            float fx = gxf - cx;
            float fy = gyf - cy;
            float fz = gzf - cz;

            weights(fx, wx);
            weights(fy, wy);
            weights(fz, wz);

            for (int ox = -1; ox <= 1; ox++) {
                int gx = cx + ox;
                if (gx < 0 || gx >= NX) continue;

                for (int oy = -1; oy <= 1; oy++) {
                    int gy = cy + oy;
                    if (gy < 0 || gy >= NY) continue;

                    for (int oz = -1; oz <= 1; oz++) {
                        int gz = cz + oz;
                        if (gz < 0 || gz >= NZ) continue;

                        float w = wx[ox + 1] * wy[oy + 1] * wz[oz + 1];
                        int idx = index(gx, gy, gz);

                        gMass[idx] += w * m;
                        gvx[idx] += w * m * blade.vx(i);
                        gvy[idx] += w * m * blade.vy(i);
                        gvz[idx] += w * m * blade.vz(i);

                        float gwx = dw(fx, ox) * wy[oy + 1] * wz[oz + 1];
                        float gwy = wx[ox + 1] * dw(fy, oy) * wz[oz + 1];
                        float gwz = wx[ox + 1] * wy[oy + 1] * dw(fz, oz);

                        // σ · ∇w（行主序）
                        float sx = sigma.m00 * gwx + sigma.m01 * gwy + sigma.m02 * gwz;
                        float sy = sigma.m10 * gwx + sigma.m11 * gwy + sigma.m12 * gwz;
                        float sz = sigma.m20 * gwx + sigma.m21 * gwy + sigma.m22 * gwz;

                        gfx[idx] -= v0 * sx;
                        gfy[idx] -= v0 * sy;
                        gfz[idx] -= v0 * sz;
                    }
                }
            }
        }
    }

    /** 网格更新：动量折成速度，再叠加上应力产生的加速度。 */
    private void gridUpdate(float dt) {
        for (int i = 0; i < CELLS; i++) {
            float mass = gMass[i];
            if (mass <= 1.0E-9F) {
                continue;
            }
            float inv = 1.0F / mass;
            gvx[i] = gvx[i] * inv + dt * gfx[i] * inv;
            gvy[i] = gvy[i] * inv + dt * gfy[i] * inv;
            gvz[i] = gvz[i] * inv + dt * gfz[i] * inv;
        }
    }

    /** 网格 → 粒子：更新速度与形变矩阵。 */
    private void gridToParticle(MpmBlade blade, float dt) {
        final float[] wx = new float[3];
        final float[] wy = new float[3];
        final float[] wz = new float[3];

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);

            float gxf = (x - GRID_MIN) / GRID_SIZE * NX;
            float gyf = (y - GRID_MIN) / GRID_SIZE * NY;
            float gzf = (z - GRID_MIN) / GRID_SIZE * NZ;

            int cx = clampCell((int) gxf, NX);
            int cy = clampCell((int) gyf, NY);
            int cz = clampCell((int) gzf, NZ);

            float fx = gxf - cx;
            float fy = gyf - cy;
            float fz = gzf - cz;

            weights(fx, wx);
            weights(fy, wy);
            weights(fz, wz);

            float nvx = 0.0F, nvy = 0.0F, nvz = 0.0F;

            // 速度梯度 ∂v_i/∂x_j
            float g00 = 0, g01 = 0, g02 = 0;
            float g10 = 0, g11 = 0, g12 = 0;
            float g20 = 0, g21 = 0, g22 = 0;

            for (int ox = -1; ox <= 1; ox++) {
                int gx = cx + ox;
                if (gx < 0 || gx >= NX) continue;

                for (int oy = -1; oy <= 1; oy++) {
                    int gy = cy + oy;
                    if (gy < 0 || gy >= NY) continue;

                    for (int oz = -1; oz <= 1; oz++) {
                        int gz = cz + oz;
                        if (gz < 0 || gz >= NZ) continue;

                        int idx = index(gx, gy, gz);
                        if (gMass[idx] <= 1.0E-9F) continue;

                        float w = wx[ox + 1] * wy[oy + 1] * wz[oz + 1];
                        nvx += w * gvx[idx];
                        nvy += w * gvy[idx];
                        nvz += w * gvz[idx];

                        float dx = dw(fx, ox) * wy[oy + 1] * wz[oz + 1];
                        float dy = wx[ox + 1] * dw(fy, oy) * wz[oz + 1];
                        float dz = wx[ox + 1] * wy[oy + 1] * dw(fz, oz);

                        g00 += dx * gvx[idx]; g01 += dy * gvx[idx]; g02 += dz * gvx[idx];
                        g10 += dx * gvy[idx]; g11 += dy * gvy[idx]; g12 += dz * gvy[idx];
                        g20 += dx * gvz[idx]; g21 += dy * gvz[idx]; g22 += dz * gvz[idx];
                    }
                }
            }

            nvx = clampSpeed(nvx * DAMPING);
            nvy = clampSpeed(nvy * DAMPING);
            nvz = clampSpeed(nvz * DAMPING);

            // 钳子：靠手柄那一端被夹死，不能动。
            // 没有夹持，锤击会把整块材料推着走，形状守不住。
            if (x < CLAMP_X) {
                blade.setVelocity(i, 0.0F, 0.0F, 0.0F);
                continue;
            }

            // 重力：让被推出去的材料自然落回铁砧，而不是漂在空中
            nvz -= GRAVITY * dt;

            blade.setVelocity(i, nvx, nvy, nvz);

            // 不限制水平方向：材料被锤扁时会向四周延展，撞到"空气墙"很假。
            // 只有铁砧（Z 下限）是实体边界。
            float nx = x + nvx * dt;
            float ny = y + nvy * dt;
            float nz = z + nvz * dt;
            if (nz < FLOOR_Z) {
                nz = FLOOR_Z;
                nvz = 0.0F;
                blade.setVelocity(i, nvx, nvy, nvz);
            }
            blade.setPosition(i, nx, ny, nz);

            updateDeform(blade, i, g00, g01, g02, g10, g11, g12, g20, g21, g22, dt);
        }
    }

    private void updateDeform(MpmBlade blade, int i,
                              float g00, float g01, float g02,
                              float g10, float g11, float g12,
                              float g20, float g21, float g22,
                              float dt) {
        float a00 = 1.0F + g00 * dt, a01 = g01 * dt, a02 = g02 * dt;
        float a10 = g10 * dt, a11 = 1.0F + g11 * dt, a12 = g12 * dt;
        float a20 = g20 * dt, a21 = g21 * dt, a22 = 1.0F + g22 * dt;

        float f00 = blade.deform(i, 0), f01 = blade.deform(i, 1), f02 = blade.deform(i, 2);
        float f10 = blade.deform(i, 3), f11 = blade.deform(i, 4), f12 = blade.deform(i, 5);
        float f20 = blade.deform(i, 6), f21 = blade.deform(i, 7), f22 = blade.deform(i, 8);

        float n00 = a00 * f00 + a01 * f10 + a02 * f20;
        float n01 = a00 * f01 + a01 * f11 + a02 * f21;
        float n02 = a00 * f02 + a01 * f12 + a02 * f22;

        float n10 = a10 * f00 + a11 * f10 + a12 * f20;
        float n11 = a10 * f01 + a11 * f11 + a12 * f21;
        float n12 = a10 * f02 + a11 * f12 + a12 * f22;

        float n20 = a20 * f00 + a21 * f10 + a22 * f20;
        float n21 = a20 * f01 + a21 * f11 + a22 * f21;
        float n22 = a20 * f02 + a21 * f12 + a22 * f22;

        if (!Float.isFinite(n00) || !Float.isFinite(n11) || !Float.isFinite(n22)) {
            n00 = 1.0F; n01 = 0.0F; n02 = 0.0F;
            n10 = 0.0F; n11 = 1.0F; n12 = 0.0F;
            n20 = 0.0F; n21 = 0.0F; n22 = 1.0F;
        }

        blade.setDeform(i, 0, n00); blade.setDeform(i, 1, n01); blade.setDeform(i, 2, n02);
        blade.setDeform(i, 3, n10); blade.setDeform(i, 4, n11); blade.setDeform(i, 5, n12);
        blade.setDeform(i, 6, n20); blade.setDeform(i, 7, n21); blade.setDeform(i, 8, n22);
    }

    /**
     * 不可压缩：每个子步结束后把 F 的行列式拉回 1。
     * 金属被锤只改变形状，不改变体积。
     */
    private void enforceIncompressible(MpmBlade blade) {
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            loadDeform(blade, i, f);
            float det = f.det();
            if (!Float.isFinite(det) || det < 1.0E-6F) {
                blade.setDeform(i, 0, 1.0F); blade.setDeform(i, 1, 0.0F); blade.setDeform(i, 2, 0.0F);
                blade.setDeform(i, 3, 0.0F); blade.setDeform(i, 4, 1.0F); blade.setDeform(i, 5, 0.0F);
                blade.setDeform(i, 6, 0.0F); blade.setDeform(i, 7, 0.0F); blade.setDeform(i, 8, 1.0F);
                continue;
            }
            float s = (float) Math.pow(1.0F / det, 1.0 / 3.0);
            f.scale(s);
            storeDeform(blade, i, f);
        }
    }

    // ---- 矩阵存取（Blade 用行主序存 9 个 float） ----

    private static void loadDeform(MpmBlade blade, int i, Mat3 out) {
        out.m00 = blade.deform(i, 0);
        out.m01 = blade.deform(i, 1);
        out.m02 = blade.deform(i, 2);
        out.m10 = blade.deform(i, 3);
        out.m11 = blade.deform(i, 4);
        out.m12 = blade.deform(i, 5);
        out.m20 = blade.deform(i, 6);
        out.m21 = blade.deform(i, 7);
        out.m22 = blade.deform(i, 8);
    }

    private static void storeDeform(MpmBlade blade, int i, Mat3 m) {
        blade.setDeform(i, 0, m.m00);
        blade.setDeform(i, 1, m.m01);
        blade.setDeform(i, 2, m.m02);
        blade.setDeform(i, 3, m.m10);
        blade.setDeform(i, 4, m.m11);
        blade.setDeform(i, 5, m.m12);
        blade.setDeform(i, 6, m.m20);
        blade.setDeform(i, 7, m.m21);
        blade.setDeform(i, 8, m.m22);
    }

    // ---- B 样条 ----

    private static void weights(float f, float[] out) {
        float u = 1.0F - f;
        out[0] = 0.5F * u * u;
        out[1] = 0.75F - (f - 0.5F) * (f - 0.5F);
        out[2] = 0.5F * f * f;
    }

    private static float dw(float f, int offset) {
        return switch (offset) {
            case -1 -> -(1.0F - f);
            case 0 -> -2.0F * (f - 0.5F);
            default -> f;
        };
    }

    private static int index(int x, int y, int z) {
        return x + y * NX + z * NX * NY;
    }

    private static int clampCell(int v, int max) {
        return Math.max(0, Math.min(max - 1, v));
    }

    private static float clampUnit(float v) {
        return Math.max(-1.0F, Math.min(1.0F, v));
    }

    private static float clampMove(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static float clampSpeed(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return 0.0F;
        }
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }
}
