package trial_by_hammer.forging.geometry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 分段截面材料格：长度方向由连续截面组成，每个截面保存宽度×厚度材料格。
 * Sectioned material lattice: consecutive lengthwise sections each store a width-by-thickness material grid.
 */
public final class SectionedWorkpiece {
    public static final int LENGTH_CELLS = 64;
    public static final int WIDTH_CELLS = 24;
    public static final int THICKNESS_CELLS = 12;
    public static final float CELL_SIZE = 1.0F / 48.0F;
    public static final byte EMPTY = 0;
    public static final byte IRON = 1;
    public static final int VOLUME_UNITS_PER_CELL = 256;
    public static final byte PACK_THICKNESS_LOW = 0;
    public static final byte PACK_THICKNESS_HIGH = 1;
    public static final byte PACK_WIDTH_LOW = 2;
    public static final byte PACK_WIDTH_HIGH = 3;

    private static final int FORMAT_MAGIC = 0x54424847;
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_BEND_SUBCELLS = 4 * 256;
    private static final int HAMMER_HALF_LENGTH = 2;
    private static final int HAMMER_HALF_CROSS = 1;

    private final byte[] materials;
    private final short[] volumeUnits;
    private final byte[] packingDirections;
    private final short[] widthOffsets;
    private final short[] thicknessOffsets;

    private SectionedWorkpiece(
            byte[] materials,
            short[] volumeUnits,
            byte[] packingDirections,
            short[] widthOffsets,
            short[] thicknessOffsets) {
        int expectedCells = LENGTH_CELLS * WIDTH_CELLS * THICKNESS_CELLS;
        if (materials.length != expectedCells
                || volumeUnits.length != expectedCells
                || packingDirections.length != expectedCells
                || widthOffsets.length != LENGTH_CELLS
                || thicknessOffsets.length != LENGTH_CELLS) {
            throw new IllegalArgumentException("Invalid sectioned workpiece dimensions");
        }
        this.materials = materials;
        this.volumeUnits = volumeUnits;
        this.packingDirections = packingDirections;
        this.widthOffsets = widthOffsets;
        this.thicknessOffsets = thicknessOffsets;
    }

    /** 创建一根居中的基础铁胚，并在四周保留材料延展空间。/ Creates a centered iron billet with room to spread. */
    public static SectionedWorkpiece createIronBillet() {
        byte[] materials = new byte[LENGTH_CELLS * WIDTH_CELLS * THICKNESS_CELLS];
        short[] volumeUnits = new short[materials.length];
        SectionedWorkpiece workpiece = new SectionedWorkpiece(
                materials,
                volumeUnits,
                new byte[materials.length],
                new short[LENGTH_CELLS],
                new short[LENGTH_CELLS]);
        for (int length = 12; length < 52; length++) {
            for (int width = 8; width < 16; width++) {
                for (int thickness = 4; thickness < 8; thickness++) {
                    workpiece.setCell(length, width, thickness, IRON, VOLUME_UNITS_PER_CELL, PACK_THICKNESS_LOW);
                }
            }
        }
        return workpiece;
    }

    public SectionedWorkpiece copy() {
        return new SectionedWorkpiece(
                this.materials.clone(),
                this.volumeUnits.clone(),
                this.packingDirections.clone(),
                this.widthOffsets.clone(),
                this.thicknessOffsets.clone());
    }

    public byte materialAt(int length, int width, int thickness) {
        if (!inside(length, width, thickness)) {
            return EMPTY;
        }
        return this.materials[index(length, width, thickness)];
    }

    public boolean isOccupied(int length, int width, int thickness) {
        return this.volumeUnitsAt(length, width, thickness) > 0;
    }

    public boolean isFull(int length, int width, int thickness) {
        return this.volumeUnitsAt(length, width, thickness) == VOLUME_UNITS_PER_CELL;
    }

    public int volumeUnitsAt(int length, int width, int thickness) {
        if (!inside(length, width, thickness)) {
            return 0;
        }
        return Short.toUnsignedInt(this.volumeUnits[index(length, width, thickness)]);
    }

    public byte packingDirectionAt(int length, int width, int thickness) {
        if (!inside(length, width, thickness)) {
            return PACK_THICKNESS_LOW;
        }
        return this.packingDirections[index(length, width, thickness)];
    }

    public int occupiedCellCount() {
        int count = 0;
        for (byte material : this.materials) {
            if (material != EMPTY) {
                count++;
            }
        }
        return count;
    }

    public long totalVolumeUnits() {
        long total = 0L;
        for (short volume : this.volumeUnits) {
            total += Short.toUnsignedInt(volume);
        }
        return total;
    }

    public int widthOffsetSubcells(int length) {
        return this.widthOffsets[length];
    }

    public int thicknessOffsetSubcells(int length) {
        return this.thicknessOffsets[length];
    }

    /**
     * 在给定局部列上执行一次确定性锤击；材料只能从受击表面搬运到邻近列。
     * Applies one deterministic strike at a local column; material moves only from the struck surface to nearby columns.
     */
    public HammerResult hammer(
            ImpactAxis axis,
            boolean fromHighSide,
            int centerLength,
            int centerCross,
            int force,
            boolean supported) {
        return this.hammer(
                axis,
                fromHighSide,
                centerLength,
                centerCross,
                force,
                supported,
                new DeformationParameters(1.0D, 0.68D));
    }

    public HammerResult hammer(
            ImpactAxis axis,
            boolean fromHighSide,
            int centerLength,
            int centerCross,
            int force,
            boolean supported,
            DeformationParameters deformation) {
        int clampedForce = Math.max(1, Math.min(10, force));
        int crossSize = axis == ImpactAxis.THICKNESS ? WIDTH_CELLS : THICKNESS_CELLS;
        if (centerLength < 0 || centerLength >= LENGTH_CELLS || centerCross < 0 || centerCross >= crossSize) {
            return HammerResult.NO_CHANGE;
        }

        double plasticity = Math.max(0.0D, Math.min(1.0D, deformation.plasticity()));
        double ductility = Math.max(0.0D, Math.min(1.0D, deformation.ductility()));
        if (plasticity <= 0.0D) {
            return HammerResult.NO_CHANGE;
        }

        byte[] workingMaterials = this.materials.clone();
        short[] workingVolumes = this.volumeUnits.clone();
        byte[] workingPacking = this.packingDirections.clone();
        List<Column> footprint = footprintColumns(centerLength, centerCross, crossSize);
        if (footprint.isEmpty()) {
            return HammerResult.NO_CHANGE;
        }

        List<Transfer> displaced = new ArrayList<>(footprint.size());
        int movedVolumeUnits = 0;
        for (Column column : footprint) {
            int surface = surfaceIndex(workingVolumes, axis, fromHighSide, column.length(), column.cross());
            if (surface < 0) {
                continue;
            }
            int materialIndex = axisIndex(axis, column.length(), column.cross(), surface);
            int distance = Math.abs(column.length() - centerLength) + Math.abs(column.cross() - centerCross);
            double faceWeight = Math.max(0.4D, 1.0D - distance * 0.15D);
            int requested = Math.max(1, (int)Math.round(clampedForce * 4.0D * plasticity * faceWeight));
            int available = Short.toUnsignedInt(workingVolumes[materialIndex]);
            int removed = Math.min(requested, available);
            if (removed <= 0) {
                continue;
            }
            displaced.add(new Transfer(workingMaterials[materialIndex], removed));
            setVolume(workingMaterials, workingVolumes, workingPacking, materialIndex, available - removed,
                    packingFor(axis, fromHighSide));
            movedVolumeUnits += removed;
        }

        if (displaced.isEmpty()) {
            return HammerResult.NO_CHANGE;
        }

        int spreadRadius = 2 + (int)Math.round(ductility * 8.0D);
        for (Transfer transfer : displaced) {
            int remaining = transfer.volumeUnits();
            while (remaining > 0) {
                Recipient recipient = findRecipient(
                        workingVolumes,
                        axis,
                        fromHighSide,
                        centerLength,
                        centerCross,
                        crossSize,
                        footprint,
                        spreadRadius);
                if (recipient == null) {
                    return HammerResult.NO_CHANGE;
                }
                int recipientIndex = axisIndex(axis, recipient.length(), recipient.cross(), recipient.axisCell());
                int current = Short.toUnsignedInt(workingVolumes[recipientIndex]);
                int accepted = Math.min(remaining, VOLUME_UNITS_PER_CELL - current);
                if (accepted <= 0) {
                    return HammerResult.NO_CHANGE;
                }
                workingMaterials[recipientIndex] = transfer.material();
                setVolume(
                        workingMaterials,
                        workingVolumes,
                        workingPacking,
                        recipientIndex,
                        current + accepted,
                        packingFor(axis, fromHighSide));
                remaining -= accepted;
            }
        }

        System.arraycopy(workingMaterials, 0, this.materials, 0, this.materials.length);
        System.arraycopy(workingVolumes, 0, this.volumeUnits, 0, this.volumeUnits.length);
        System.arraycopy(workingPacking, 0, this.packingDirections, 0, this.packingDirections.length);
        if (!supported) {
            int bendForce = Math.max(1, (int)Math.round(clampedForce * plasticity));
            this.applyBend(axis, fromHighSide, centerLength, bendForce);
        }
        return new HammerResult(true, movedVolumeUnits, !supported);
    }

    public int thicknessCells(ImpactAxis axis, int length, int cross) {
        return (this.thicknessVolumeUnits(axis, length, cross) + VOLUME_UNITS_PER_CELL - 1)
                / VOLUME_UNITS_PER_CELL;
    }

    public int thicknessVolumeUnits(ImpactAxis axis, int length, int cross) {
        int total = 0;
        for (int axisCell = 0; axisCell < axisSize(axis); axisCell++) {
            total += Short.toUnsignedInt(this.volumeUnits[axisIndex(axis, length, cross, axisCell)]);
        }
        return total;
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream header = new DataOutputStream(bytes);
            header.writeInt(FORMAT_MAGIC);
            header.writeByte(FORMAT_VERSION);
            header.flush();
            try (DataOutputStream output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                for (int length = 0; length < LENGTH_CELLS; length++) {
                    output.writeShort(this.widthOffsets[length]);
                    output.writeShort(this.thicknessOffsets[length]);
                }
                for (int cell = 0; cell < this.materials.length; cell++) {
                    output.writeByte(this.materials[cell]);
                    output.writeShort(Short.toUnsignedInt(this.volumeUnits[cell]));
                    output.writeByte(this.packingDirections[cell]);
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode workpiece geometry", exception);
        }
    }

    public static SectionedWorkpiece decode(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != FORMAT_MAGIC) {
                throw new IOException("Invalid workpiece geometry magic");
            }
            int version = input.readUnsignedByte();
            if (version == 1) {
                return decodeLegacyV1(input);
            }
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported workpiece geometry version " + version);
            }

            DataInputStream body = new DataInputStream(new InflaterInputStream(input));
            short[] widthOffsets = new short[LENGTH_CELLS];
            short[] thicknessOffsets = new short[LENGTH_CELLS];
            for (int length = 0; length < LENGTH_CELLS; length++) {
                widthOffsets[length] = body.readShort();
                thicknessOffsets[length] = body.readShort();
            }

            byte[] materials = new byte[LENGTH_CELLS * WIDTH_CELLS * THICKNESS_CELLS];
            short[] volumeUnits = new short[materials.length];
            byte[] packingDirections = new byte[materials.length];
            for (int cell = 0; cell < materials.length; cell++) {
                byte material = body.readByte();
                int volume = body.readUnsignedShort();
                byte packing = body.readByte();
                if (volume > VOLUME_UNITS_PER_CELL || (volume == 0) != (material == EMPTY)) {
                    throw new IOException("Invalid workpiece cell state");
                }
                materials[cell] = material;
                volumeUnits[cell] = (short)volume;
                packingDirections[cell] = packing;
            }
            if (body.read() != -1) {
                throw new IOException("Trailing workpiece geometry data");
            }
            return new SectionedWorkpiece(materials, volumeUnits, packingDirections, widthOffsets, thicknessOffsets);
        }
    }

    private static SectionedWorkpiece decodeLegacyV1(DataInputStream input) throws IOException {
        short[] widthOffsets = new short[LENGTH_CELLS];
        short[] thicknessOffsets = new short[LENGTH_CELLS];
        for (int length = 0; length < LENGTH_CELLS; length++) {
            widthOffsets[length] = input.readShort();
            thicknessOffsets[length] = input.readShort();
        }

        byte[] materials = new byte[LENGTH_CELLS * WIDTH_CELLS * THICKNESS_CELLS];
        int cursor = 0;
        while (cursor < materials.length) {
            byte value = input.readByte();
            int runLength = input.readUnsignedShort();
            if (runLength == 0 || cursor + runLength > materials.length) {
                throw new IOException("Invalid legacy workpiece material run");
            }
            Arrays.fill(materials, cursor, cursor + runLength, value);
            cursor += runLength;
        }

        short[] volumeUnits = new short[materials.length];
        byte[] packingDirections = new byte[materials.length];
        for (int cell = 0; cell < materials.length; cell++) {
            if (materials[cell] != EMPTY) {
                volumeUnits[cell] = (short)VOLUME_UNITS_PER_CELL;
            }
        }
        return new SectionedWorkpiece(materials, volumeUnits, packingDirections, widthOffsets, thicknessOffsets);
    }

    private List<Column> footprintColumns(int centerLength, int centerCross, int crossSize) {
        List<Column> footprint = new ArrayList<>(15);
        for (int length = centerLength - HAMMER_HALF_LENGTH; length <= centerLength + HAMMER_HALF_LENGTH; length++) {
            for (int cross = centerCross - HAMMER_HALF_CROSS; cross <= centerCross + HAMMER_HALF_CROSS; cross++) {
                if (length >= 0 && length < LENGTH_CELLS && cross >= 0 && cross < crossSize) {
                    footprint.add(new Column(length, cross));
                }
            }
        }
        footprint.sort(Comparator
                .comparingInt((Column column) -> Math.abs(column.length() - centerLength)
                        + Math.abs(column.cross() - centerCross))
                .thenComparingInt(Column::length)
                .thenComparingInt(Column::cross));
        return footprint;
    }

    private Recipient findRecipient(
            short[] workingVolumes,
            ImpactAxis axis,
            boolean fromHighSide,
            int centerLength,
            int centerCross,
            int crossSize,
            List<Column> footprint,
            int spreadRadius) {
        Recipient best = null;
        for (int length = Math.max(0, centerLength - spreadRadius);
                length <= Math.min(LENGTH_CELLS - 1, centerLength + spreadRadius);
                length++) {
            for (int cross = Math.max(0, centerCross - spreadRadius);
                    cross <= Math.min(crossSize - 1, centerCross + spreadRadius);
                    cross++) {
                Column column = new Column(length, cross);
                if (footprint.contains(column)
                        || !hasAdjacentOccupiedColumn(workingVolumes, axis, length, cross, crossSize)) {
                    continue;
                }
                int axisCell = supportSideWritableIndex(workingVolumes, axis, fromHighSide, length, cross);
                if (axisCell < 0) {
                    continue;
                }
                int distance = Math.abs(length - centerLength) + Math.abs(cross - centerCross);
                int height = thicknessVolumeUnits(workingVolumes, axis, length, cross);
                Recipient candidate = new Recipient(length, cross, axisCell, distance, height);
                if (best == null || candidate.compareTo(best) < 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean hasAdjacentOccupiedColumn(
            short[] workingVolumes, ImpactAxis axis, int length, int cross, int crossSize) {
        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] offset : offsets) {
            int neighborLength = length + offset[0];
            int neighborCross = cross + offset[1];
            if (neighborLength < 0
                    || neighborLength >= LENGTH_CELLS
                    || neighborCross < 0
                    || neighborCross >= crossSize) {
                continue;
            }
            if (thicknessVolumeUnits(workingVolumes, axis, neighborLength, neighborCross) > 0) {
                return true;
            }
        }
        return false;
    }

    private int surfaceIndex(short[] workingVolumes, ImpactAxis axis, boolean fromHighSide, int length, int cross) {
        int axisSize = axisSize(axis);
        if (fromHighSide) {
            for (int axisCell = axisSize - 1; axisCell >= 0; axisCell--) {
                if (Short.toUnsignedInt(workingVolumes[axisIndex(axis, length, cross, axisCell)]) > 0) {
                    return axisCell;
                }
            }
        } else {
            for (int axisCell = 0; axisCell < axisSize; axisCell++) {
                if (Short.toUnsignedInt(workingVolumes[axisIndex(axis, length, cross, axisCell)]) > 0) {
                    return axisCell;
                }
            }
        }
        return -1;
    }

    private int supportSideWritableIndex(
            short[] workingVolumes, ImpactAxis axis, boolean fromHighSide, int length, int cross) {
        int axisSize = axisSize(axis);
        if (fromHighSide) {
            for (int axisCell = 0; axisCell < axisSize; axisCell++) {
                if (Short.toUnsignedInt(workingVolumes[axisIndex(axis, length, cross, axisCell)])
                        < VOLUME_UNITS_PER_CELL) {
                    return axisCell;
                }
            }
        } else {
            for (int axisCell = axisSize - 1; axisCell >= 0; axisCell--) {
                if (Short.toUnsignedInt(workingVolumes[axisIndex(axis, length, cross, axisCell)])
                        < VOLUME_UNITS_PER_CELL) {
                    return axisCell;
                }
            }
        }
        return -1;
    }

    private void applyBend(ImpactAxis axis, boolean fromHighSide, int centerLength, int force) {
        int direction = fromHighSide ? -1 : 1;
        for (int delta = -HAMMER_HALF_LENGTH; delta <= HAMMER_HALF_LENGTH; delta++) {
            int length = centerLength + delta;
            if (length < 0 || length >= LENGTH_CELLS) {
                continue;
            }
            int weight = HAMMER_HALF_LENGTH + 1 - Math.abs(delta);
            int amount = direction * force * weight * 4;
            if (axis == ImpactAxis.THICKNESS) {
                this.thicknessOffsets[length] = clampBend(this.thicknessOffsets[length] + amount);
            } else {
                this.widthOffsets[length] = clampBend(this.widthOffsets[length] + amount);
            }
        }
    }

    private static short clampBend(int value) {
        return (short)Math.max(-MAX_BEND_SUBCELLS, Math.min(MAX_BEND_SUBCELLS, value));
    }

    private int thicknessVolumeUnits(short[] workingVolumes, ImpactAxis axis, int length, int cross) {
        int total = 0;
        for (int axisCell = 0; axisCell < axisSize(axis); axisCell++) {
            total += Short.toUnsignedInt(workingVolumes[axisIndex(axis, length, cross, axisCell)]);
        }
        return total;
    }

    private void setCell(int length, int width, int thickness, byte material, int volume, byte packing) {
        int cell = index(length, width, thickness);
        this.materials[cell] = material;
        this.volumeUnits[cell] = (short)volume;
        this.packingDirections[cell] = packing;
    }

    private static void setVolume(
            byte[] materials, short[] volumes, byte[] packing, int cell, int volume, byte packingDirection) {
        if (volume <= 0) {
            materials[cell] = EMPTY;
            volumes[cell] = 0;
            packing[cell] = PACK_THICKNESS_LOW;
            return;
        }
        volumes[cell] = (short)Math.min(VOLUME_UNITS_PER_CELL, volume);
        packing[cell] = packingDirection;
    }

    private static byte packingFor(ImpactAxis axis, boolean fromHighSide) {
        if (axis == ImpactAxis.THICKNESS) {
            return fromHighSide ? PACK_THICKNESS_LOW : PACK_THICKNESS_HIGH;
        }
        return fromHighSide ? PACK_WIDTH_LOW : PACK_WIDTH_HIGH;
    }

    private static boolean inside(int length, int width, int thickness) {
        return length >= 0
                && length < LENGTH_CELLS
                && width >= 0
                && width < WIDTH_CELLS
                && thickness >= 0
                && thickness < THICKNESS_CELLS;
    }

    private static int index(int length, int width, int thickness) {
        return (length * WIDTH_CELLS + width) * THICKNESS_CELLS + thickness;
    }

    private static int axisIndex(ImpactAxis axis, int length, int cross, int axisCell) {
        return axis == ImpactAxis.THICKNESS
                ? index(length, cross, axisCell)
                : index(length, axisCell, cross);
    }

    private static int axisSize(ImpactAxis axis) {
        return axis == ImpactAxis.THICKNESS ? THICKNESS_CELLS : WIDTH_CELLS;
    }

    public enum ImpactAxis {
        WIDTH,
        THICKNESS
    }

    public record HammerResult(boolean changed, int movedVolumeUnits, boolean bent) {
        private static final HammerResult NO_CHANGE = new HammerResult(false, 0, false);
    }

    public record DeformationParameters(double plasticity, double ductility) {
    }

    private record Column(int length, int cross) {
    }

    private record Transfer(byte material, int volumeUnits) {
    }

    private record Recipient(int length, int cross, int axisCell, int distance, int height)
            implements Comparable<Recipient> {
        @Override
        public int compareTo(Recipient other) {
            int byDistance = Integer.compare(this.distance, other.distance);
            if (byDistance != 0) {
                return byDistance;
            }
            int byHeight = Integer.compare(this.height, other.height);
            if (byHeight != 0) {
                return byHeight;
            }
            int byLength = Integer.compare(this.length, other.length);
            return byLength != 0 ? byLength : Integer.compare(this.cross, other.cross);
        }
    }
}
