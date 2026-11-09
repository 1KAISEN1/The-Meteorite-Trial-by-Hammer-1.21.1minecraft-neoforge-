package com.trialbyhammer.mpm;

/**
 * SPH（光滑粒子流体动力学）求解器。
 *
 * <p>与 MPM 的根本区别：<b>没有网格</b>。粒子之间直接通过核函数相互作用，
 * 所以不存在"网格边界"——材料可以无限延展。</p>
 *
 * <p>代价：</p>
 * <ul>
 *   <li>邻居搜索需要空间哈希，比 MPM 的网格插值慢</li>
 *   <li>固体材料在拉伸时容易出现"颗粒化"（粒子散开），需要额外处理</li>
 * </ul>
 *
 * <p>这里实现的是最经典的压力驱动 SPH，足够做对比测试。</p>
 */
public final class SphSolver implements SoftBodySolver {

    /** 核半径。比粒子间距大 2–3 倍。 */
    private static final float H = 0.035F;

    /** 核半径的平方，避免反复开方。 */
    private static final float H2 = H * H;

    /** 刚度：压力对密度的响应强度。 */
    private static final float STIFFNESS = 0.8F;

    /** 参考密度，第一次计算时固定。 */
    private float restDensity = 0.0F;

    /** 速度阻尼。 */
    private static final float DAMPING = 0.985F;

    /** 速度上限。 */
    private static final float MAX_SPEED = 1.2F;

    /** 铁砧底面。 */
    public static final float FLOOR_Z = 0.30F;

    /** 哈希网格分辨率。格子尺寸 ≈ H。 */
    private static final int HNX = 64;
    private static final int HNY = 32;
    private static final int HNZ = 24;
    private static final float HCELL = 1.0F / HNX;

    private final int[] cellCount = new int[HNX * HNY * HNZ];
    private final int[] cellStart = new int[HNX * HNY * HNZ + 1];
    private final int[] sortedIds = new int[MpmBlade.COUNT];

    private final float[] density = new float[MpmBlade.COUNT];
    private final float[] pressure = new float[MpmBlade.COUNT];

    @Override
    public String name() {
        return "sph";
    }

    @Override
    public void step(MpmBlade blade, float dt, int substeps) {
        for (int s = 0; s < substeps; s++) {
            buildHash(blade);
            computeDensity(blade);
            computePressure();
            integrate(blade, dt);
        }
    }

    /** 用规则网格做空间哈希，把粒子按格子排序，便于找邻居。 */
    private void buildHash(MpmBlade blade) {
        java.util.Arrays.fill(cellCount, 0);

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            int c = cellIndex(blade.x(i), blade.y(i), blade.z(i));
            cellCount[c]++;
        }

        int sum = 0;
        for (int c = 0; c < cellCount.length; c++) {
            cellStart[c] = sum;
            sum += cellCount[c];
        }
        cellStart[cellCount.length] = sum;

        int[] cursor = new int[cellCount.length];
        System.arraycopy(cellStart, 0, cursor, 0, cellCount.length);

        for (int i = 0; i < MpmBlade.COUNT; i++) {
            int c = cellIndex(blade.x(i), blade.y(i), blade.z(i));
            sortedIds[cursor[c]++] = i;
        }
    }

    private void computeDensity(MpmBlade blade) {
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float xi = blade.x(i), yi = blade.y(i), zi = blade.z(i);
            float rho = 0.0F;

            int cx = hashCell(xi);
            int cy = hashCell(yi);
            int cz = hashCell(zi);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int c = hashIndex(cx + dx, cy + dy, cz + dz);
                        if (c < 0) {
                            continue;
                        }
                        for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                            int j = sortedIds[k];
                            float rx = xi - blade.x(j);
                            float ry = yi - blade.y(j);
                            float rz = zi - blade.z(j);
                            float r2 = rx * rx + ry * ry + rz * rz;
                            if (r2 < H2) {
                                rho += blade.mass(j) * kernel(r2);
                            }
                        }
                    }
                }
            }
            density[i] = rho;
        }

        if (restDensity <= 0.0F) {
            double total = 0.0;
            for (int i = 0; i < MpmBlade.COUNT; i++) {
                total += density[i];
            }
            restDensity = (float) (total / MpmBlade.COUNT);
        }
    }

    private void computePressure() {
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float rho = Math.max(1.0E-9F, density[i]);
            pressure[i] = STIFFNESS * Math.max(0.0F, rho - restDensity);
        }
    }

    private void integrate(MpmBlade blade, float dt) {
        for (int i = 0; i < MpmBlade.COUNT; i++) {
            float xi = blade.x(i), yi = blade.y(i), zi = blade.z(i);
            float fx = 0.0F, fy = 0.0F, fz = 0.0F;

            int cx = hashCell(xi);
            int cy = hashCell(yi);
            int cz = hashCell(zi);

            float pi = pressure[i] / (density[i] * density[i]);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int c = hashIndex(cx + dx, cy + dy, cz + dz);
                        if (c < 0) {
                            continue;
                        }
                        for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                            int j = sortedIds[k];
                            if (j == i) {
                                continue;
                            }
                            float rx = xi - blade.x(j);
                            float ry = yi - blade.y(j);
                            float rz = zi - blade.z(j);
                            float r2 = rx * rx + ry * ry + rz * rz;
                            if (r2 >= H2 || r2 < 1.0E-12F) {
                                continue;
                            }
                            float r = (float) Math.sqrt(r2);
                            float pj = pressure[j] / (density[j] * density[j]);
                            // ∇W = -2r/h² · W
                            float grad = -2.0F * kernel(r2) / H2;
                            float scale = blade.mass(j) * (pi + pj) * grad / r;
                            fx += rx * scale;
                            fy += ry * scale;
                            fz += rz * scale;
                        }
                    }
                }
            }

            float invMass = 1.0F / blade.mass(i);
            float vx = blade.vx(i) + dt * fx * invMass;
            float vy = blade.vy(i) + dt * fy * invMass;
            float vz = blade.vz(i) + dt * fz * invMass;

            vx = clampSpeed(vx * DAMPING);
            vy = clampSpeed(vy * DAMPING);
            vz = clampSpeed(vz * DAMPING);

            float nz = zi + vz * dt;
            if (nz < FLOOR_Z) {
                nz = FLOOR_Z;
                vz = 0.0F;
            }

            blade.setVelocity(i, vx, vy, vz);
            blade.setPosition(i, xi + vx * dt, yi + vy * dt, nz);
        }
    }

    /** 高斯核。 */
    private static float kernel(float r2) {
        return (float) (Math.exp(-r2 / H2) / (Math.PI * H2 * H));
    }

    private static int hashCell(float v) {
        int c = (int) (v / HCELL);
        return Math.max(0, Math.min(HNX - 1, c));
    }

    private static int hashIndex(int x, int y, int z) {
        if (x < 0 || x >= HNX || y < 0 || y >= HNY || z < 0 || z >= HNZ) {
            return -1;
        }
        return x + y * HNX + z * HNX * HNY;
    }

    private static int cellIndex(float x, float y, float z) {
        int cx = Math.max(0, Math.min(HNX - 1, (int) (x / HCELL)));
        int cy = Math.max(0, Math.min(HNY - 1, (int) (y / HCELL)));
        int cz = Math.max(0, Math.min(HNZ - 1, (int) (z / HCELL)));
        return cx + cy * HNX + cz * HNX * HNY;
    }

    private static float clampSpeed(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return 0.0F;
        }
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }
}
