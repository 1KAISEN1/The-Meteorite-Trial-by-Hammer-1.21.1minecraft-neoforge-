package com.trialbyhammer.mpm;

import java.util.Arrays;
import java.util.stream.IntStream;

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

    public static final int NX = 128;
    public static final int NY = 64;
    public static final int NZ = 128;

    /**
     * 网格覆盖的世界范围。比 [0,1] 大得多，让材料向四周延展时够不着边界。
     *
     * <p>网格是 MPM 的计算中介，粒子之间的作用全靠它。数组是有限的，
     * 所以"边界"本质上无法消除，只能推远。</p>
     */
    public static final float GRID_MIN = -2.0F;
    public static final float GRID_SIZE = 4.0F;

    // Keep the full spatial domain but allocate only occupied Z layers.
    private int gridZ0;
    private float[] gMass = new float[0], gvx, gvy, gvz, gfx, gfy, gfz;
    private int[] active = new int[0];
    private int activeCount;

    // ---- 材料参数（归一化单位，可调） ----

    /** 剪切模量：越大越硬，形状越难改变。 */
    private static final float MU = 3.0F;

    /** 体积模量：越大越不可压缩。 */
    static final float LAMBDA = 50.0F;

    /** 速度上限。 */
    private static final float MAX_SPEED = 1.2F;

    /** 速度阻尼。 */
    private static final float DAMPING = 0.99F;

    /**
     * 重力加速度（归一化坐标，1 单位 = 1 方块 = 1 米）。
     *
     * <p>没有重力时，被锤击推出去的材料会一直漂着，边缘会"翘起来"。</p>
     */
    private static final float GRAVITY = 0.12F;

    /**
     * 钳子夹持区：X 小于这个值的粒子被固定住，模拟夹在台钳里的那一端。
     */
    static final float CLAMP_X = 0.12F;

    /** 铁砧表面高度。 */
    public static final float FLOOR_Z = 0.30F;

    /**
     * 铁砧的覆盖范围（水平方向）。
     *
     * <p>铁砧不是无限大的平面。材料伸出边缘后会悬空、下垂，锤击悬空段
     * 才能真正"抽长"——这是锻造的核心动作。</p>
     *
     * <p>X 上限故意不覆盖整个方块：右边那段是"砧外"，材料会伸出去。</p>
     */
    public static final float ANVIL_X0 = 0.10F;
    public static final float ANVIL_X1 = 0.75F;
    public static final float ANVIL_Y0 = 0.30F;
    public static final float ANVIL_Y1 = 0.70F;

    /** 判断某点是否在铁砧上方（有支撑）。 */
    public static boolean onAnvil(float x, float y) {
        return x >= ANVIL_X0 && x <= ANVIL_X1 && y >= ANVIL_Y0 && y <= ANVIL_Y1;
    }

    /** 初始体积相对质量的系数。绝对值不重要，整体一致即可。 */
    private static final float VOLUME_PER_MASS = 1.0F;

    // Fixed partitions keep floating-point reductions independent of CPU count and scheduling.
    private static final int BLOCKS = 6;
    private static final class ParticleBlock {
        final int begin, end;
        final Mat3 f = new Mat3(), fp = new Mat3(), fe = new Mat3(), sigma = new Mat3();
        float[] mass = new float[0], vx, vy, vz, fx, fy, fz;
        int[] nodes;
        int count, events, skipped;
        int x0, y0, z0, height, depth;

        ParticleBlock(int id) {
            begin = MpmBlade.COUNT * id / BLOCKS;
            end = MpmBlade.COUNT * (id + 1) / BLOCKS;
        }

        void prepare(MpmBlade blade) {
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int i = begin; i < end; i++) {
                minX = Math.min(minX, blade.x(i)); maxX = Math.max(maxX, blade.x(i));
                minY = Math.min(minY, blade.y(i)); maxY = Math.max(maxY, blade.y(i));
                minZ = Math.min(minZ, blade.z(i)); maxZ = Math.max(maxZ, blade.z(i));
            }
            x0 = (int)gridCoordinate(minX, NX) - 1;
            y0 = (int)gridCoordinate(minY, NY) - 1;
            z0 = (int)gridCoordinate(minZ, NZ) - 1;
            int width = (int)gridCoordinate(maxX, NX) + 2 - x0;
            height = (int)gridCoordinate(maxY, NY) + 2 - y0;
            depth = (int)gridCoordinate(maxZ, NZ) + 2 - z0;
            reset(width * height * depth);
        }

        int index(int x, int y, int z) {
            // Z is contiguous because it is the innermost stencil loop.
            return ((x - x0) * height + y - y0) * depth + z - z0;
        }

        void reset(int capacity) {
            if (capacity > mass.length) {
                mass = new float[capacity];
                vx = new float[capacity]; vy = new float[capacity]; vz = new float[capacity];
                fx = new float[capacity]; fy = new float[capacity]; fz = new float[capacity];
                nodes = new int[capacity];
            } else {
                for (int k = 0; k < count; k++) {
                    int j = nodes[k];
                    mass[j] = vx[j] = vy[j] = vz[j] = fx[j] = fy[j] = fz[j] = 0;
                }
            }
            count = events = skipped = 0;
        }
    }

    // Cache only sub-yield elastic stress. Pressure is accumulated separately.
    private final float[] elasticStress = new float[MpmBlade.COUNT * 9];
    private final boolean[] belowYield = new boolean[MpmBlade.COUNT];

    private final ParticleBlock[] blocks = IntStream.range(0, BLOCKS)
            .mapToObj(ParticleBlock::new).toArray(ParticleBlock[]::new);

    /** 诊断用：returnMapping 实际被调用（并生效）的次数。 */
    public int plasticEvents = 0;

    /** 诊断用：returnMapping 被调用但 scale >= 1（形变不够）而跳过的次数。 */
    public int plasticSkipped = 0;

    /**
     * 返回映射：应力超过屈服面时，把弹性形变的偏差缩回屈服面，
     * 缩掉的那部分转成塑性形变永久保留。
     *
     * <p>没有这一步，金属就是纯弹性——锤完弹回去，永远定不了型。</p>
     */
    private void returnMapping(MpmBlade blade, int i, float yield, ParticleBlock block) {
        Mat3 f = block.f, fe = block.fe;
        Mat3 r = new Mat3();
        MpmMath.polarDecompose(fe, r);

        Mat3 dev = new Mat3();
        dev.set(fe).sub(r);

        float mag = dev.frobenius();
        if (mag < 1.0E-9F) {
            return;
        }

        // 偏差对应的应力是 2μ·dev，缩放到刚好等于屈服强度
        float scale = yield / (2.0F * MU * mag);
        if (scale >= 1.0F) {
            block.skipped++;
            return;
        }

        block.events++;
        blade.addPlasticStrain(i, mag * (1 - scale));
        dev.scale(scale);
        fe.set(r).add(dev);

        // Fp = Fe⁻¹ · F
        Mat3 fpNew = new Mat3();
        fpNew.set(fe).invert().mul(f);

        // J2 塑性要求塑性流动等容：det(Fp) ≡ 1。
        // 不修正的话 Fp 会一路"压缩"，材料只会被压扁，"抽长"结构上不可能出现。
        float detFp = fpNew.det();
        if (detFp > 1.0E-6F && Float.isFinite(detFp)) {
            fpNew.scale((float)(1 / Math.cbrt(detFp)));
            blade.writePlasticDeform(i, fpNew);
        }
    }

    /**
     * 推进一次锤击。
     */
    public void step(MpmBlade blade, float dt, int substeps) {
        // Explicit elastic waves must travel less than half a cell in one step.
        double waveSpeed = Math.sqrt((LAMBDA + 2 * MU) * VOLUME_PER_MASS);
        double maxDt = 0.5 * GRID_SIZE / (Math.max(NX, Math.max(NY, NZ)) * waveSpeed);
        int subdivisions = Math.max(1, (int) Math.ceil(dt / maxDt));
        final float stepDt = dt / subdivisions;
        for (int s = 0; s < substeps * subdivisions; s++) {
            // Refresh on every external action and at least every fourth internal step.
            boolean reuseStress = (s & 3) != 0;
            Arrays.stream(blocks).parallel().forEach(block -> particleToGrid(blade, block, reuseStress));
            prepareGrid();
            for (ParticleBlock block : blocks) {
                merge(block);
            }
            gridUpdate(stepDt);
            Arrays.stream(blocks).parallel().forEach(block -> {
                gridToParticle(blade, stepDt, block.begin, block.end);
                projectDeform(blade, block.begin, block.end, block.f);
            });
        }
        blade.projectVolume();
    }

    /** Thermal PIC exchange. Both passes use the same mass/conductivity weights.
     * source is the furnace temperature in Celsius; use 20 for air cooling. */
    public boolean thermalStep(MpmBlade blade, float seconds, float source) {
        if (!(seconds > 0) || !Float.isFinite(seconds) || !Float.isFinite(source)) return false;
        float environment = (float)-Math.expm1(-seconds * (source > 20 ? .8 : .004));
        float first = blade.temperature(0);
        int iSame = 1;
        while (iSame < MpmBlade.COUNT && blade.temperature(iSame) == first) iSame++;
        if (iSame == MpmBlade.COUNT) {
            if (source == 20 && first == 20) return false;
            // A uniform field has zero conductive flux; heating and annealing still advance.
            float updated = first + environment * (source - first);
            for (int i = 0; i < MpmBlade.COUNT; i++) {
                blade.setTemperature(i, updated);
                blade.recover(i, seconds);
            }
            return true;
        }
        int lo = NZ, hi = 0;
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            int z = (int)(gridCoordinate(blade.z(i), NZ) - .5F);
            lo = Math.min(lo, z);
            hi = Math.max(hi, z + 2);
        }
        prepareGrid(lo, hi);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < MpmBlade.COUNT; i++) {
                float x = gridCoordinate(blade.x(i), NX) - .5F;
                float y = gridCoordinate(blade.y(i), NY) - .5F;
                float z = gridCoordinate(blade.z(i), NZ) - .5F;
                int ix = (int)x, iy = (int)y, iz = (int)z;
                x -= ix; y -= iy; z -= iz;
                float mix = Math.min(1, seconds * Materials.get(blade.material(i)).conductivity());
                float temp = blade.temperature(i), change = 0;
                for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
                    float w = (dx == 0 ? 1-x : x)*(dy == 0 ? 1-y : y)*(dz == 0 ? 1-z : z);
                    if (w == 0) continue;
                    int node = index(ix+dx, iy+dy, iz+dz);
                    if (pass == 0) {
                        if (gMass[node] == 0) active[activeCount++] = node;
                        float weight = blade.mass(i) * mix * w;
                        gMass[node] += weight;
                        gvx[node] += weight * temp;
                    } else {
                        change += w * (gvx[node] / gMass[node] - temp);
                    }
                }
                if (pass == 1) {
                    temp += mix * change;
                    blade.setTemperature(i, temp + environment * (source - temp));
                    blade.recover(i, seconds);
                }
            }
        }
        return true;
    }

    private void merge(ParticleBlock block) {
        // Merge in fixed material-index order, independent of thread scheduling.
        for (int k = 0; k < block.count; k++) {
            int local = block.nodes[k];
            int row = local / block.depth;
            int j = index(block.x0 + row / block.height, block.y0 + row % block.height,
                    block.z0 + local % block.depth);
            if (gMass[j] == 0) active[activeCount++] = j;
            gMass[j] += block.mass[local];
            gvx[j] += block.vx[local]; gvy[j] += block.vy[local]; gvz[j] += block.vz[local];
            gfx[j] += block.fx[local]; gfy[j] += block.fy[local]; gfz[j] += block.fz[local];
        }
        plasticEvents += block.events;
        plasticSkipped += block.skipped;
    }

    private void prepareGrid() {
        int startZ = NZ, endZ = 0;
        for (ParticleBlock block : blocks) {
            startZ = Math.min(startZ, block.z0);
            endZ = Math.max(endZ, block.z0 + block.depth);
        }
        prepareGrid(startZ, endZ);
    }

    private void prepareGrid(int startZ, int endZ) {
        for (int j = 0; j < activeCount; j++) {
            int i = active[j];
            gMass[i] = gvx[i] = gvy[i] = gvz[i] = gfx[i] = gfy[i] = gfz[i] = 0;
        }
        activeCount = 0;
        gridZ0 = startZ;
        int layers = endZ - gridZ0;
        int capacity = layers * NX * NY;
        if (capacity > gMass.length) {
            int padded = Math.min(NZ, layers + 4) * NX * NY;
            gMass = new float[padded];
            gvx = new float[padded]; gvy = new float[padded]; gvz = new float[padded];
            gfx = new float[padded]; gfy = new float[padded]; gfz = new float[padded];
            active = new int[padded];
        }
    }

    /**
     * 粒子 → 网格。除了质量与动量，还要把应力传上去：
     *
     * <pre>
     * gridForce -= V0 · σ · ∇w
     * </pre>
     */
    private void particleToGrid(MpmBlade blade, ParticleBlock block, boolean reuseStress) {
        block.prepare(blade);
        float[] gMass = block.mass, gvx = block.vx, gvy = block.vy, gvz = block.vz;
        float[] gfx = block.fx, gfy = block.fy, gfz = block.fz;
        Mat3 sigma = block.sigma;
        final float[] wx = new float[3];
        final float[] wy = new float[3];
        final float[] wz = new float[3];

        for (int i = block.begin; i < block.end; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);
            float m = blade.mass(i);

            float vx = blade.vx(i), vy = blade.vy(i), vz = blade.vz(i);
            if (reuseStress && belowYield[i] && vx*vx + vy*vy + vz*vz < 1e-4F) {
                sigma.read(elasticStress, 9 * i);
            } else {
                updateElasticStress(blade, block, i);
                sigma.write(elasticStress, 9 * i);
            }

            sigma.m00 += blade.pressure[i];
            sigma.m11 += blade.pressure[i];
            sigma.m22 += blade.pressure[i];

            float v0 = m * VOLUME_PER_MASS;

            // Quadratic B-splines are centred on the nearest grid node.
            float gxf = gridCoordinate(x, NX);
            float gyf = gridCoordinate(y, NY);
            float gzf = gridCoordinate(z, NZ);

            int cx = (int) gxf;
            int cy = (int) gyf;
            int cz = (int) gzf;

            float fx = gxf - cx;
            float fy = gyf - cy;
            float fz = gzf - cz;

            weights(fx, wx);
            weights(fy, wy);
            weights(fz, wz);

            for (int ox = -1; ox <= 1; ox++) {
                int gx = cx + ox;
                float xWeight = wx[ox + 1];
                float xDerivative = (NX / GRID_SIZE) * dw(fx, ox);

                for (int oy = -1; oy <= 1; oy++) {
                    int gy = cy + oy;
                    float xyWeight = xWeight * wy[oy + 1];
                    float dxWeight = xDerivative * wy[oy + 1];
                    float dyWeight = xWeight * (NY / GRID_SIZE) * dw(fy, oy);
                    float dzWeight = xyWeight * (NZ / GRID_SIZE);

                    for (int oz = -1; oz <= 1; oz++) {
                        int gz = cz + oz;

                        float w = xyWeight * wz[oz + 1];
                        int idx = block.index(gx, gy, gz);

                        if (w <= 0) continue;
                        if (gMass[idx] == 0) block.nodes[block.count++] = idx;
                        gMass[idx] += w * m;
                        gvx[idx] += w * m * blade.vx(i);
                        gvy[idx] += w * m * blade.vy(i);
                        gvz[idx] += w * m * blade.vz(i);

                        float gwx = dxWeight * wz[oz + 1];
                        float gwy = dyWeight * wz[oz + 1];
                        float gwz = dzWeight * dw(fz, oz);

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

    private void updateElasticStress(MpmBlade blade, ParticleBlock block, int i) {
        Mat3 f = block.f, fp = block.fp, fe = block.fe, sigma = block.sigma;
        // 弹性形变 Fe = F · Fp⁻¹。应力只由弹性部分产生，
        // 塑性部分永久保留、不回弹——这是"锤完定型"的关键。
        blade.readDeform(i, f);
        blade.readPlasticDeform(i, fp);
        fp.invert();          // fp 现在是 Fp⁻¹
        fe.set(f).mul(fp);            // Fe = F · Fp⁻¹

        MpmMath.stress(fe, sigma, MU, LAMBDA);

        // 冷作硬化
        float yield = blade.yieldStrength(i);
        float mean = (sigma.m00 + sigma.m11 + sigma.m22) / 3;
        sigma.m00 -= mean;
        sigma.m11 -= mean;
        sigma.m22 -= mean;
        if (sigma.frobenius() > yield) {
            belowYield[i] = false;
            returnMapping(blade, i, yield, block);
            blade.readPlasticDeform(i, fp);
            fe.set(f).mul(fp.invert());
            MpmMath.stress(fe, sigma, MU, LAMBDA);
        } else {
            belowYield[i] = true;
            sigma.m00 += mean;
            sigma.m11 += mean;
            sigma.m22 += mean;
        }
    }

    /** 网格更新：动量折成速度，再叠加上应力产生的加速度。 */
    private void gridUpdate(float dt) {
        for (int j = 0; j < activeCount; j++) {
            int i = active[j];
            float mass = gMass[i];
            if (mass <= 1.0E-9F) {
                continue;
            }
            float inv = 1.0F / mass;
            gvx[i] = gvx[i] * inv + dt * gfx[i] * inv;
            gvy[i] = gvy[i] * inv + dt * gfy[i] * inv;
            gvz[i] = gvz[i] * inv + dt * (gfz[i] * inv - GRAVITY);
            int ix = i % NX, iy = (i / NX) % NY, iz = i / (NX * NY) + gridZ0;
            if (onAnvil(GRID_MIN + ix * (GRID_SIZE / NX), GRID_MIN + iy * (GRID_SIZE / NY))
                    && GRID_MIN + iz * (GRID_SIZE / NZ) <= FLOOR_Z) {
                gvz[i] = Math.max(0, gvz[i]);
            }
        }
    }

    /** 网格 → 粒子：更新速度与形变矩阵。 */
    private void gridToParticle(MpmBlade blade, float dt, int begin, int end) {
        final float[] wx = new float[3];
        final float[] wy = new float[3];
        final float[] wz = new float[3];

        for (int i = begin; i < end; i++) {
            float x = blade.x(i), y = blade.y(i), z = blade.z(i);

            // 钳子：靠手柄那一端被夹死，不能动。
            // 没有夹持，锤击会把整块材料推着走，形状守不住。
            if (x < CLAMP_X) {
                blade.setVelocity(i, 0.0F, 0.0F, 0.0F);
                continue;
            }

            float gxf = gridCoordinate(x, NX);
            float gyf = gridCoordinate(y, NY);
            float gzf = gridCoordinate(z, NZ);

            int cx = (int) gxf;
            int cy = (int) gyf;
            int cz = (int) gzf;

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
                float xWeight = wx[ox + 1];
                float xDerivative = (NX / GRID_SIZE) * dw(fx, ox);

                for (int oy = -1; oy <= 1; oy++) {
                    int gy = cy + oy;
                    float xyWeight = xWeight * wy[oy + 1];
                    float dxWeight = xDerivative * wy[oy + 1];
                    float dyWeight = xWeight * (NY / GRID_SIZE) * dw(fy, oy);
                    float dzWeight = xyWeight * (NZ / GRID_SIZE);

                    for (int oz = -1; oz <= 1; oz++) {
                        int gz = cz + oz;

                        int idx = index(gx, gy, gz);
                        if (gMass[idx] <= 1.0E-9F) continue;

                        float w = xyWeight * wz[oz + 1];
                        nvx += w * gvx[idx];
                        nvy += w * gvy[idx];
                        nvz += w * gvz[idx];

                        float dx = dxWeight * wz[oz + 1];
                        float dy = dyWeight * wz[oz + 1];
                        float dz = dzWeight * dw(fz, oz);

                        g00 += dx * gvx[idx]; g01 += dy * gvx[idx]; g02 += dz * gvx[idx];
                        g10 += dx * gvy[idx]; g11 += dy * gvy[idx]; g12 += dz * gvy[idx];
                        g20 += dx * gvz[idx]; g21 += dy * gvz[idx]; g22 += dz * gvz[idx];
                    }
                }
            }

            nvx = clampSpeed(nvx * DAMPING);
            nvy = clampSpeed(nvy * DAMPING);
            nvz = clampSpeed(nvz * DAMPING);




            blade.setVelocity(i, nvx, nvy, nvz);

            // 不限制水平方向：材料被锤扁时会向四周延展，撞到"空气墙"很假。
            // 铁砧只在它自己的范围里提供支撑；伸出去的部分会悬空下垂。
            float nx = x + nvx * dt;
            float ny = y + nvy * dt;
            float nz = z + nvz * dt;
            if (onAnvil(nx, ny) && nz < FLOOR_Z) {
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

    /** Accumulate the volumetric stress before projecting F to unit determinant.
     * This retains the contact pressure that supports the weight of upper layers. */
    private void projectDeform(MpmBlade blade, int begin, int end, Mat3 f) {
        for (int i = begin; i < end; i++) {
            blade.readDeform(i, f);
            float jacobian = f.det();
            if (!(jacobian > 0) || !Float.isFinite(jacobian)) {
                throw new ArithmeticException("Invalid material Jacobian " + jacobian + " at particle " + i);
            }
            blade.pressure[i] += (LAMBDA + blade.pressure[i]) * (jacobian - 1);
            f.scale((float)(1 / Math.cbrt(jacobian)));
            blade.writeDeform(i, f);
        }
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

    private int index(int x, int y, int z) {
        return x + y * NX + (z - gridZ0) * NX * NY;
    }

    private static float gridCoordinate(float position, int nodes) {
        return Math.clamp((position - GRID_MIN) * (nodes / GRID_SIZE) + 0.5F,
                1, Math.nextDown((float)nodes - 1));
    }

    private static float clampSpeed(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return 0.0F;
        }
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }
}
