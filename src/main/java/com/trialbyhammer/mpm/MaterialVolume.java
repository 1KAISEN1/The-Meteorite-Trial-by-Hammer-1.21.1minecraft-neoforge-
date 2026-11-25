package com.trialbyhammer.mpm;

/** Closed surface of the original material samples. Connectivity follows particle
 * identity through a stroke or a rigid rotation. It is only used for quadrature. */
final class MaterialVolume {
    private static final int[] SURFACE = surfaceTriangles();

    private MaterialVolume() {}

    private static int[] surfaceTriangles() {
        int[] sizes = {MpmBlade.SAMPLES_X, MpmBlade.SAMPLES_Y, MpmBlade.SAMPLES_Z};
        int[] strides = {sizes[1] * sizes[2], sizes[2], 1};
        int[] faces = new int[12 * ((sizes[0]-1)*(sizes[1]-1)
                + (sizes[1]-1)*(sizes[2]-1) + (sizes[2]-1)*(sizes[0]-1))];
        int n = 0;
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis+1)%3, v = (axis+2)%3;
            for (int side = 0; side <= 1; side++) {
                int start = side * (sizes[axis]-1) * strides[axis];
                for (int i = 0; i < sizes[u]-1; i++) {
                    for (int j = 0; j < sizes[v]-1; j++) {
                        int a = start + i * strides[u] + j * strides[v];
                        int b = a + strides[u], c = a + strides[v], d = b + strides[v];
                        faces[n++] = a; faces[n++] = side == 1 ? b : c; faces[n++] = d;
                        faces[n++] = a; faces[n++] = d; faces[n++] = side == 1 ? c : b;
                    }
                }
            }
        }
        return faces;
    }

    static double measure(MpmBlade blade) {
        return integrate(blade, null);
    }

    // Divergence theorem: integral(y * n_y dA). With x,z fixed it is
    // exactly linear in y, even for a bent or tapered workpiece.
    private static double integrate(MpmBlade blade, float[] displacement) {
        double sum = 0;
        for (int t = 0; t < SURFACE.length; t += 3) {
            int a = SURFACE[t], b = SURFACE[t+1], c = SURFACE[t+2];
            double nxz = (double)(blade.z(b)-blade.z(a)) * (blade.x(c)-blade.x(a))
                    - (double)(blade.x(b)-blade.x(a)) * (blade.z(c)-blade.z(a));
            double y = displacement == null ? (double)blade.y(a) + blade.y(b) + blade.y(c)
                    : (double)displacement[a] + displacement[b] + displacement[c];
            sum += nxz * y;
        }
        return sum / 6;
    }

    static void project(MpmBlade blade, double rest) {
        double error = rest - measure(blade);
        if (Math.abs(error) < rest * 1e-7) return;

        // Correct only transport/quadrature drift. A lateral displacement leaves
        // anvil contact and draw length intact and fades to zero inside the grip.
        float[] dy = new float[MpmBlade.COUNT];
        double sumY = 0;
        for (int i = 0; i < dy.length; i++) sumY += blade.y(i);
        float cy = (float)(sumY / dy.length);
        float transition = MpmSolver.GRID_SIZE / MpmSolver.NX;
        for (int i = 0; i < dy.length; i++) {
            float w = Math.clamp((blade.x(i)-MpmSolver.CLAMP_X) / transition, 0, 1);
            dy[i] = w * (blade.y(i)-cy);
        }
        double response = integrate(blade, dy);
        if (response <= rest * 1e-8) return;
        float scale = (float)(error / response);
        for (int i = 0; i < dy.length; i++) {
            float u = (blade.x(i) - MpmSolver.CLAMP_X) / transition;
            float stretch = 1 + scale * Math.clamp(u, 0, 1);
            float shear = u > 0 && u < 1 ? scale * (blade.y(i)-cy) / transition : 0;
            blade.correctWidth(i, scale * dy[i], shear, stretch);
        }
    }
}
