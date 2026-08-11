package trial_by_hammer.forging.geometry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 二维轮廓与连续柱厚度组成的板坯；当前试验版只启用单一铁层和正反面锤击。
 * A slab defined by a 2D footprint and continuous column thickness; this prototype enables one iron layer and face strikes only.
 */
public final class LayeredSlabWorkpiece {
    public static final int RESOLUTION_SCALE = 12;
    public static final int LEGACY_GRID_SIZE = 64;
    private static final int VERSION_TWO_GRID_SIZE = LEGACY_GRID_SIZE * 3;
    private static final int VERSION_THREE_GRID_SIZE = LEGACY_GRID_SIZE * 6;
    public static final int GRID_SIZE = LEGACY_GRID_SIZE * RESOLUTION_SCALE;
    // 最初的锤面是 5×3 个旧网格单元；高分辨率求解器保持同一物理尺寸。
    // The original face measured 5×3 legacy cells; the high-resolution solver preserves that physical size.
    public static final int HAMMER_FACE_HALF_LENGTH_CELLS = 5 * RESOLUTION_SCALE / 2;
    public static final int HAMMER_FACE_HALF_WIDTH_CELLS = 3 * RESOLUTION_SCALE / 2;
    public static final int THICKNESS_UNITS_PER_CELL = 256;
    public static final int INITIAL_THICKNESS_UNITS = 4 * THICKNESS_UNITS_PER_CELL;
    public static final int MAX_THICKNESS_UNITS = 16 * THICKNESS_UNITS_PER_CELL;
    public static final int MIN_OCCUPIED_THICKNESS_UNITS = 1;
    public static final float CELL_SIZE = 1.0F / (48.0F * RESOLUTION_SCALE);
    public static final float VERTICAL_CELL_SIZE = 1.0F / 48.0F;
    public static final byte EMPTY = 0;
    public static final byte IRON = 1;

    private static final int FORMAT_MAGIC = 0x54424853;
    private static final int FORMAT_VERSION = 4;
    private static final int LEGACY_FORMAT_MAGIC = 0x54424847;

    private final byte[] materials;
    private final short[] thicknessUnits;

    private LayeredSlabWorkpiece(byte[] materials, short[] thicknessUnits) {
        int expected = GRID_SIZE * GRID_SIZE;
        if (materials.length != expected || thicknessUnits.length != expected) {
            throw new IllegalArgumentException("Invalid layered slab dimensions");
        }
        this.materials = materials;
        this.thicknessUnits = thicknessUnits;
    }

    /**
     * 创建与旧铁工件质量相同的狭长试验胚料。
     * Creates a narrow test billet with the same mass as the previous iron workpiece.
     */
    public static LayeredSlabWorkpiece createIronBillet() {
        LayeredSlabWorkpiece workpiece = new LayeredSlabWorkpiece(
                new byte[GRID_SIZE * GRID_SIZE], new short[GRID_SIZE * GRID_SIZE]);
        for (int x = 12 * RESOLUTION_SCALE; x < 52 * RESOLUTION_SCALE; x++) {
            for (int z = 28 * RESOLUTION_SCALE; z < 36 * RESOLUTION_SCALE; z++) {
                workpiece.setColumn(x, z, IRON, INITIAL_THICKNESS_UNITS);
            }
        }
        return workpiece;
    }

    public LayeredSlabWorkpiece copy() {
        return new LayeredSlabWorkpiece(this.materials.clone(), this.thicknessUnits.clone());
    }

    public boolean isOccupied(int x, int z) {
        return this.thicknessUnitsAt(x, z) > 0;
    }

    public byte materialAt(int x, int z) {
        return inside(x, z) ? this.materials[index(x, z)] : EMPTY;
    }

    public int thicknessUnitsAt(int x, int z) {
        return inside(x, z) ? Short.toUnsignedInt(this.thicknessUnits[index(x, z)]) : 0;
    }

    public long totalVolumeUnits() {
        long total = 0L;
        for (short thickness : this.thicknessUnits) {
            total += Short.toUnsignedInt(thickness);
        }
        return total;
    }

    /**
     * 用长方形锤面驱动大范围、连续且体积守恒的面内塑性流动；不包含重力或持续形变。
     * Uses a rectangular hammer face to drive broad, continuous, volume-conserving in-plane
     * plastic flow, with no gravity or continuous deformation.
     */
    public HammerResult hammerFace(
            boolean fromTop,
            int centerX,
            int centerZ,
            int force,
            DeformationParameters deformation) {
        if (!inside(centerX, centerZ)) {
            return HammerResult.NO_CHANGE;
        }
        ContinuumPlasticFlowSolver.SolveResult result = ContinuumPlasticFlowSolver.solve(
                this.materials,
                this.thicknessUnits,
                centerX,
                centerZ,
                force,
                deformation);
        if (!result.changed()) {
            return HammerResult.NO_CHANGE;
        }
        return new HammerResult(true, result.movedVolumeUnits(), fromTop);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream header = new DataOutputStream(bytes);
            header.writeInt(FORMAT_MAGIC);
            header.writeByte(FORMAT_VERSION);
            header.flush();
            try (DataOutputStream body = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                for (int cell = 0; cell < this.materials.length; cell++) {
                    body.writeByte(this.materials[cell]);
                    body.writeShort(Short.toUnsignedInt(this.thicknessUnits[cell]));
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode layered slab workpiece", exception);
        }
    }

    public static LayeredSlabWorkpiece decode(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int magic = input.readInt();
            if (magic == LEGACY_FORMAT_MAGIC) {
                return migrateLegacy(encoded);
            }
            if (magic != FORMAT_MAGIC) {
                throw new IOException("Invalid layered slab geometry magic");
            }
            int version = input.readUnsignedByte();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IOException("Unsupported layered slab geometry version " + version);
            }

            int encodedGridSize = switch (version) {
                case 1 -> LEGACY_GRID_SIZE;
                case 2 -> VERSION_TWO_GRID_SIZE;
                case 3 -> VERSION_THREE_GRID_SIZE;
                default -> GRID_SIZE;
            };
            byte[] materials = new byte[encodedGridSize * encodedGridSize];
            short[] thickness = new short[encodedGridSize * encodedGridSize];
            DataInputStream body = new DataInputStream(new InflaterInputStream(input));
            for (int cell = 0; cell < materials.length; cell++) {
                byte material = body.readByte();
                int units = body.readUnsignedShort();
                if (units > MAX_THICKNESS_UNITS || (units == 0) != (material == EMPTY)) {
                    throw new IOException("Invalid layered slab column");
                }
                materials[cell] = material;
                thickness[cell] = (short)units;
            }
            if (body.read() != -1) {
                throw new IOException("Trailing layered slab geometry data");
            }
            if (encodedGridSize != GRID_SIZE) {
                return upscaleEncodedGrid(materials, thickness, encodedGridSize);
            }
            return new LayeredSlabWorkpiece(materials, thickness);
        }
    }

    /**
     * 将旧网格的每个柱按整数比例复制到 768×768 网格；厚度不变，因此物理体积保持不变。
     * Replicates each old-grid column by an integer ratio into the 768×768 grid. Thickness is
     * unchanged, so physical volume remains unchanged while the numerical unit sum scales by area.
     */
    private static LayeredSlabWorkpiece upscaleEncodedGrid(
            byte[] oldMaterials, short[] oldThickness, int oldGridSize) {
        if (GRID_SIZE % oldGridSize != 0) {
            throw new IllegalArgumentException("Layered slab grids must have an integer scale ratio");
        }
        int scale = GRID_SIZE / oldGridSize;
        LayeredSlabWorkpiece migrated = new LayeredSlabWorkpiece(
                new byte[GRID_SIZE * GRID_SIZE], new short[GRID_SIZE * GRID_SIZE]);
        for (int oldX = 0; oldX < oldGridSize; oldX++) {
            for (int oldZ = 0; oldZ < oldGridSize; oldZ++) {
                int oldCell = oldX * oldGridSize + oldZ;
                int units = Short.toUnsignedInt(oldThickness[oldCell]);
                if (units > 0) {
                    migrated.setUpscaledColumn(oldX, oldZ, scale, oldMaterials[oldCell], units);
                }
            }
        }
        return migrated;
    }

    private static LayeredSlabWorkpiece migrateLegacy(byte[] encoded) throws IOException {
        SectionedWorkpiece legacy = SectionedWorkpiece.decode(encoded);
        LayeredSlabWorkpiece migrated = new LayeredSlabWorkpiece(
                new byte[GRID_SIZE * GRID_SIZE], new short[GRID_SIZE * GRID_SIZE]);
        int xOffset = (LEGACY_GRID_SIZE - SectionedWorkpiece.LENGTH_CELLS) / 2;
        int zOffset = (LEGACY_GRID_SIZE - SectionedWorkpiece.WIDTH_CELLS) / 2;
        for (int oldX = 0; oldX < SectionedWorkpiece.LENGTH_CELLS; oldX++) {
            for (int oldZ = 0; oldZ < SectionedWorkpiece.WIDTH_CELLS; oldZ++) {
                int units = 0;
                byte material = EMPTY;
                for (int oldY = 0; oldY < SectionedWorkpiece.THICKNESS_CELLS; oldY++) {
                    int cellUnits = legacy.volumeUnitsAt(oldX, oldZ, oldY);
                    if (cellUnits > 0) {
                        units += cellUnits;
                        material = legacy.materialAt(oldX, oldZ, oldY);
                    }
                }
                if (units > 0) {
                    migrated.setUpscaledColumn(
                            oldX + xOffset, oldZ + zOffset, RESOLUTION_SCALE, material, units);
                }
            }
        }
        return migrated;
    }

    private void setUpscaledColumn(int oldX, int oldZ, int scale, byte material, int thickness) {
        int startX = oldX * scale;
        int startZ = oldZ * scale;
        for (int x = startX; x < startX + scale; x++) {
            for (int z = startZ; z < startZ + scale; z++) {
                this.setColumn(x, z, material, thickness);
            }
        }
    }

    /**
     * 包内可见仅用于几何迁移与确定性测试，不属于对外模组 API。
     * Package-visible for geometry migration and deterministic tests only; not part of the public mod API.
     */
    void setColumn(int x, int z, byte material, int thickness) {
        int cell = index(x, z);
        this.materials[cell] = thickness > 0 ? material : EMPTY;
        this.thicknessUnits[cell] = (short)Math.max(0, Math.min(MAX_THICKNESS_UNITS, thickness));
    }

    private static boolean inside(int x, int z) {
        return x >= 0 && x < GRID_SIZE && z >= 0 && z < GRID_SIZE;
    }

    private static int index(int x, int z) {
        return x * GRID_SIZE + z;
    }

    public record DeformationParameters(double plasticity, double ductility, double interfaceFriction) {
        public DeformationParameters(double plasticity, double ductility) {
            this(plasticity, ductility, 0.55D);
        }

        public DeformationParameters {
            plasticity = clamp01(plasticity);
            ductility = clamp01(ductility);
            interfaceFriction = clamp01(interfaceFriction);
        }

        private static double clamp01(double value) {
            return Math.max(0.0D, Math.min(1.0D, value));
        }
    }

    public record HammerResult(boolean changed, int movedVolumeUnits, boolean hitFromTop) {
        private static final HammerResult NO_CHANGE = new HammerResult(false, 0, true);
    }

}
