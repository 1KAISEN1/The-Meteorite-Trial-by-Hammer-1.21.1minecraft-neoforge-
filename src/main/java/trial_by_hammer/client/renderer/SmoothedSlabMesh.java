package trial_by_hammer.client.renderer;

import java.util.ArrayList;
import java.util.List;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;

/**
 * 把离散守恒网格转换为连续可见表面；物理数据保持确定性，玩家不会直接看到柱状求解单元。
 * Converts the conservative discrete grid into a continuous visible surface. The physics data
 * remains deterministic while the player no longer sees individual solver columns.
 */
final class SmoothedSlabMesh {
    static final int FLOATS_PER_VERTEX = 8;
    static final int VERTICES_PER_FACE = 4;
    static final int FLOATS_PER_FACE = FLOATS_PER_VERTEX * VERTICES_PER_FACE;

    private static final float ISO_LEVEL = 0.5F;

    private SmoothedSlabMesh() {
    }

    static Mesh bake(LayeredSlabWorkpiece geometry) {
        FloatBuilder vertices = new FloatBuilder(32_768);
        int gridSize = LayeredSlabWorkpiece.GRID_SIZE;

        // 双网格外扩一圈，0.5 等值线恰好落在最外层材料柱的真实边界上。
        // One padded dual-grid ring makes the 0.5 isocontour land on the true outer cell edge.
        for (int x = -1; x < gridSize; x++) {
            for (int z = -1; z < gridSize; z++) {
                SurfaceVertex a = sample(geometry, x, z);
                SurfaceVertex b = sample(geometry, x, z + 1);
                SurfaceVertex c = sample(geometry, x + 1, z + 1);
                SurfaceVertex d = sample(geometry, x + 1, z);
                int occupiedCorners = occupied(a) + occupied(b) + occupied(c) + occupied(d);
                if (occupiedCorners == 0) {
                    continue;
                }

                if (occupiedCorners == 4) {
                    addTopFace(vertices, a, b, c, d);
                    addBottomFace(vertices, a, b, c, d);
                    continue;
                }

                addClippedTriangle(vertices, a, b, c);
                addClippedTriangle(vertices, a, c, d);
            }
        }

        float[] bakedVertices = vertices.toArray();
        float maxY = 0.0F;
        float minZ = Float.POSITIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < bakedVertices.length; offset += FLOATS_PER_VERTEX) {
            maxY = Math.max(maxY, bakedVertices[offset + 1]);
            minZ = Math.min(minZ, bakedVertices[offset + 2]);
            maxZ = Math.max(maxZ, bakedVertices[offset + 2]);
        }
        if (minZ == Float.POSITIVE_INFINITY) {
            minZ = 0.0F;
            maxZ = 0.0F;
        }
        return new Mesh(bakedVertices, maxY, minZ, maxZ);
    }

    private static int occupied(SurfaceVertex vertex) {
        return vertex.density() >= ISO_LEVEL ? 1 : 0;
    }

    private static SurfaceVertex sample(LayeredSlabWorkpiece geometry, int x, int z) {
        float worldX = (x + 0.5F - LayeredSlabWorkpiece.GRID_SIZE / 2.0F)
                * LayeredSlabWorkpiece.CELL_SIZE;
        float worldZ = (z + 0.5F - LayeredSlabWorkpiece.GRID_SIZE / 2.0F)
                * LayeredSlabWorkpiece.CELL_SIZE;
        int thickness = geometry.thicknessUnitsAt(x, z);
        if (thickness <= 0) {
            return new SurfaceVertex(worldX, 0.0F, worldZ, 0.0F, 0.0F, 1.0F, 0.0F, false);
        }

        float worldY = height(thickness);
        float left = neighborHeightOrSelf(geometry, x - 1, z, worldY);
        float right = neighborHeightOrSelf(geometry, x + 1, z, worldY);
        float near = neighborHeightOrSelf(geometry, x, z - 1, worldY);
        float far = neighborHeightOrSelf(geometry, x, z + 1, worldY);
        float gradientX = (right - left) / (2.0F * LayeredSlabWorkpiece.CELL_SIZE);
        float gradientZ = (far - near) / (2.0F * LayeredSlabWorkpiece.CELL_SIZE);
        float normalX = -gradientX;
        float normalY = 1.0F;
        float normalZ = -gradientZ;
        float inverseLength = inverseLength(normalX, normalY, normalZ);
        return new SurfaceVertex(
                worldX,
                worldY,
                worldZ,
                normalX * inverseLength,
                normalY * inverseLength,
                normalZ * inverseLength,
                1.0F,
                false);
    }

    private static float neighborHeightOrSelf(
            LayeredSlabWorkpiece geometry, int x, int z, float fallback) {
        int thickness = geometry.thicknessUnitsAt(x, z);
        return thickness > 0 ? height(thickness) : fallback;
    }

    private static float height(int thicknessUnits) {
        return thicknessUnits
                / (float)LayeredSlabWorkpiece.THICKNESS_UNITS_PER_CELL
                * LayeredSlabWorkpiece.VERTICAL_CELL_SIZE;
    }

    private static float inverseLength(float x, float y, float z) {
        float squared = x * x + y * y + z * z;
        return squared > 1.0E-12F ? (float)(1.0D / Math.sqrt(squared)) : 1.0F;
    }

    private static void addClippedTriangle(
            FloatBuilder vertices, SurfaceVertex first, SurfaceVertex second, SurfaceVertex third) {
        List<SurfaceVertex> input = List.of(first, second, third);
        List<SurfaceVertex> polygon = new ArrayList<>(4);
        SurfaceVertex previous = input.getLast();
        boolean previousInside = occupied(previous) == 1;
        for (SurfaceVertex current : input) {
            boolean currentInside = occupied(current) == 1;
            if (currentInside != previousInside) {
                polygon.add(intersection(previous, current));
            }
            if (currentInside) {
                polygon.add(current);
            }
            previous = current;
            previousInside = currentInside;
        }
        if (polygon.size() < 3) {
            return;
        }

        SurfaceVertex origin = polygon.getFirst();
        for (int vertex = 1; vertex < polygon.size() - 1; vertex++) {
            SurfaceVertex middle = polygon.get(vertex);
            SurfaceVertex end = polygon.get(vertex + 1);
            addTopTriangle(vertices, origin, middle, end);
            addBottomTriangle(vertices, origin, middle, end);
        }
        for (int vertex = 0; vertex < polygon.size(); vertex++) {
            SurfaceVertex start = polygon.get(vertex);
            SurfaceVertex end = polygon.get((vertex + 1) % polygon.size());
            if (start.boundary() && end.boundary() && !samePosition(start, end)) {
                addSideFace(vertices, start, end);
            }
        }
    }

    private static SurfaceVertex intersection(SurfaceVertex first, SurfaceVertex second) {
        float denominator = second.density() - first.density();
        float ratio = Math.abs(denominator) < 1.0E-6F
                ? 0.5F
                : (ISO_LEVEL - first.density()) / denominator;
        SurfaceVertex inside = occupied(first) == 1 ? first : second;
        return new SurfaceVertex(
                lerp(first.x(), second.x(), ratio),
                inside.y(),
                lerp(first.z(), second.z(), ratio),
                inside.normalX(),
                inside.normalY(),
                inside.normalZ(),
                ISO_LEVEL,
                true);
    }

    private static float lerp(float first, float second, float ratio) {
        return first + (second - first) * ratio;
    }

    private static boolean samePosition(SurfaceVertex first, SurfaceVertex second) {
        return Math.abs(first.x() - second.x()) < 1.0E-6F
                && Math.abs(first.z() - second.z()) < 1.0E-6F;
    }

    private static void addTopFace(
            FloatBuilder vertices,
            SurfaceVertex first,
            SurfaceVertex second,
            SurfaceVertex third,
            SurfaceVertex fourth) {
        addVertex(vertices, first, first.normalX(), first.normalY(), first.normalZ(), first.x(), first.z());
        addVertex(vertices, second, second.normalX(), second.normalY(), second.normalZ(), second.x(), second.z());
        addVertex(vertices, third, third.normalX(), third.normalY(), third.normalZ(), third.x(), third.z());
        addVertex(vertices, fourth, fourth.normalX(), fourth.normalY(), fourth.normalZ(), fourth.x(), fourth.z());
    }

    private static void addBottomFace(
            FloatBuilder vertices,
            SurfaceVertex first,
            SurfaceVertex second,
            SurfaceVertex third,
            SurfaceVertex fourth) {
        addVertex(vertices, fourth, 0.0F, -1.0F, 0.0F, fourth.x(), fourth.z(), 0.0F);
        addVertex(vertices, third, 0.0F, -1.0F, 0.0F, third.x(), third.z(), 0.0F);
        addVertex(vertices, second, 0.0F, -1.0F, 0.0F, second.x(), second.z(), 0.0F);
        addVertex(vertices, first, 0.0F, -1.0F, 0.0F, first.x(), first.z(), 0.0F);
    }

    private static void addTopTriangle(
            FloatBuilder vertices, SurfaceVertex first, SurfaceVertex second, SurfaceVertex third) {
        addVertex(vertices, first, first.normalX(), first.normalY(), first.normalZ(), first.x(), first.z());
        addVertex(vertices, second, second.normalX(), second.normalY(), second.normalZ(), second.x(), second.z());
        addVertex(vertices, third, third.normalX(), third.normalY(), third.normalZ(), third.x(), third.z());
        addVertex(vertices, third, third.normalX(), third.normalY(), third.normalZ(), third.x(), third.z());
    }

    private static void addBottomTriangle(
            FloatBuilder vertices, SurfaceVertex first, SurfaceVertex second, SurfaceVertex third) {
        addVertex(vertices, third, 0.0F, -1.0F, 0.0F, third.x(), third.z(), 0.0F);
        addVertex(vertices, second, 0.0F, -1.0F, 0.0F, second.x(), second.z(), 0.0F);
        addVertex(vertices, first, 0.0F, -1.0F, 0.0F, first.x(), first.z(), 0.0F);
        addVertex(vertices, first, 0.0F, -1.0F, 0.0F, first.x(), first.z(), 0.0F);
    }

    private static void addSideFace(FloatBuilder vertices, SurfaceVertex start, SurfaceVertex end) {
        float directionX = end.x() - start.x();
        float directionZ = end.z() - start.z();
        float inverseLength = inverseLength(directionZ, 0.0F, directionX);
        float normalX = -directionZ * inverseLength;
        float normalZ = directionX * inverseLength;
        float startU = start.x() + start.z();
        float endU = end.x() + end.z();
        addVertex(vertices, start, normalX, 0.0F, normalZ, startU, start.y());
        addVertex(vertices, start, normalX, 0.0F, normalZ, startU, 0.0F, 0.0F);
        addVertex(vertices, end, normalX, 0.0F, normalZ, endU, 0.0F, 0.0F);
        addVertex(vertices, end, normalX, 0.0F, normalZ, endU, end.y());
    }

    private static void addVertex(
            FloatBuilder vertices,
            SurfaceVertex position,
            float normalX,
            float normalY,
            float normalZ,
            float u,
            float v) {
        addVertex(vertices, position, normalX, normalY, normalZ, u, v, position.y());
    }

    private static void addVertex(
            FloatBuilder vertices,
            SurfaceVertex position,
            float normalX,
            float normalY,
            float normalZ,
            float u,
            float v,
            float y) {
        vertices.add(position.x());
        vertices.add(y);
        vertices.add(position.z());
        vertices.add(normalX);
        vertices.add(normalY);
        vertices.add(normalZ);
        vertices.add(u);
        vertices.add(v);
    }

    record Mesh(float[] vertices, float maxY, float minZ, float maxZ) {
        Mesh {
            if (vertices.length % FLOATS_PER_FACE != 0) {
                throw new IllegalArgumentException("Slab mesh must contain complete quad faces");
            }
        }

        int faceCount() {
            return this.vertices.length / FLOATS_PER_FACE;
        }
    }

    private record SurfaceVertex(
            float x,
            float y,
            float z,
            float normalX,
            float normalY,
            float normalZ,
            float density,
            boolean boundary) {
    }

    private static final class FloatBuilder {
        private float[] values;
        private int size;

        private FloatBuilder(int initialCapacity) {
            this.values = new float[initialCapacity];
        }

        private void add(float value) {
            if (this.size == this.values.length) {
                float[] expanded = new float[this.values.length * 2];
                System.arraycopy(this.values, 0, expanded, 0, this.values.length);
                this.values = expanded;
            }
            this.values[this.size++] = value;
        }

        private float[] toArray() {
            float[] result = new float[this.size];
            System.arraycopy(this.values, 0, result, 0, this.size);
            return result;
        }
    }
}
