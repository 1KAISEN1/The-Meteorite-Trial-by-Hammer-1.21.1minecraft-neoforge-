package trial_by_hammer.forging.geometry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece.DeformationParameters;

class LayeredSlabWorkpieceTest {
    private static final int SCALE = LayeredSlabWorkpiece.RESOLUTION_SCALE;
    private static final int CENTER_X = 32 * SCALE;
    private static final int CENTER_Z = 32 * SCALE;
    private static final int BILLET_MIN_X = 12 * SCALE;
    private static final int BILLET_MAX_X_EXCLUSIVE = 52 * SCALE;
    private static final int BILLET_MIN_Z = 28 * SCALE;
    private static final int BILLET_MAX_Z_EXCLUSIVE = 36 * SCALE;

    @Test
    void solveGridUsesTwelveSubcellsPerLegacyCell() {
        assertEquals(768, LayeredSlabWorkpiece.GRID_SIZE);
        assertEquals(12, LayeredSlabWorkpiece.RESOLUTION_SCALE);
    }

    @Test
    void hammerRestoresOriginalFiveByThreeRectangularFace() {
        assertEquals(
                5 * SCALE,
                ContinuumPlasticFlowSolver.HAMMER_HALF_LENGTH_CELLS * 2);
        assertEquals(
                3 * SCALE,
                ContinuumPlasticFlowSolver.HAMMER_HALF_WIDTH_CELLS * 2);
        assertTrue(ContinuumPlasticFlowSolver.HAMMER_HALF_LENGTH_CELLS
                > ContinuumPlasticFlowSolver.HAMMER_HALF_WIDTH_CELLS);
    }

    @Test
    void oneHotBlowThinsTheContactRegionAndConservesVolume() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        long volumeBefore = workpiece.totalVolumeUnits();
        int centerBefore = workpiece.thicknessUnitsAt(CENTER_X, CENTER_Z);

        LayeredSlabWorkpiece.HammerResult result = workpiece.hammerFace(
                true, CENTER_X, CENTER_Z, 10, new DeformationParameters(1.0D, 0.68D));

        assertTrue(result.changed());
        assertTrue(result.movedVolumeUnits() > 0);
        assertTrue(workpiece.thicknessUnitsAt(CENTER_X, CENTER_Z) < centerBefore);
        assertEquals(volumeBefore, workpiece.totalVolumeUnits());
    }

    @Test
    void influenceIsBroaderThanThePhysicalHammerFace() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        workpiece.hammerFace(
                true, CENTER_X, CENTER_Z, 10, new DeformationParameters(1.0D, 0.68D));

        int firstExpandedX = Integer.MAX_VALUE;
        int lastExpandedX = Integer.MIN_VALUE;
        int expandedColumns = 0;
        for (int x = BILLET_MIN_X; x < BILLET_MAX_X_EXCLUSIVE; x++) {
            if (outerMinimumZ(workpiece, x) < BILLET_MIN_Z) {
                firstExpandedX = Math.min(firstExpandedX, x);
                lastExpandedX = Math.max(lastExpandedX, x);
                expandedColumns++;
            }
        }

        assertTrue(expandedColumns > 2 * ContinuumPlasticFlowSolver.HAMMER_HALF_LENGTH_CELLS);
        assertTrue(firstExpandedX < CENTER_X - ContinuumPlasticFlowSolver.HAMMER_HALF_LENGTH_CELLS);
        assertTrue(lastExpandedX > CENTER_X + ContinuumPlasticFlowSolver.HAMMER_HALF_LENGTH_CELLS);
    }

    @Test
    void broadInfluenceHasNoReverseCompressionShockRing() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        workpiece.hammerFace(
                true, CENTER_X, CENTER_Z, 10, new DeformationParameters(1.0D, 0.68D));

        int maximumThickness = 0;
        for (int x = 0; x < LayeredSlabWorkpiece.GRID_SIZE; x++) {
            for (int z = 0; z < LayeredSlabWorkpiece.GRID_SIZE; z++) {
                maximumThickness = Math.max(maximumThickness, workpiece.thicknessUnitsAt(x, z));
            }
        }
        assertTrue(maximumThickness <= LayeredSlabWorkpiece.INITIAL_THICKNESS_UNITS + 2);
    }

    @Test
    void oneBlowCreatesOneSmoothConnectedBulgeInsteadOfTeeth() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        workpiece.hammerFace(
                true, CENTER_X, CENTER_Z, 10, new DeformationParameters(1.0D, 0.68D));

        int previousBoundary = outerMinimumZ(workpiece, BILLET_MIN_X);
        int expandedRuns = 0;
        boolean insideRun = false;
        for (int x = BILLET_MIN_X + 1; x < BILLET_MAX_X_EXCLUSIVE; x++) {
            int boundary = outerMinimumZ(workpiece, x);
            assertTrue(Math.abs(boundary - previousBoundary) <= 1);
            previousBoundary = boundary;

            boolean expanded = boundary < BILLET_MIN_Z;
            if (expanded && !insideRun) {
                expandedRuns++;
            }
            insideRun = expanded;
        }
        assertEquals(1, expandedRuns);
        assertSingleConnectedFootprint(workpiece);
    }

    @Test
    void repeatedMediumBlowsRemainSmoothAndVolumeConserving() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        long volumeBefore = workpiece.totalVolumeUnits();
        for (int strike = 0; strike < 6; strike++) {
            workpiece.hammerFace(
                    true, CENTER_X, CENTER_Z, 5, new DeformationParameters(1.0D, 0.68D));
        }

        int previousBoundary = outerMinimumZ(workpiece, BILLET_MIN_X);
        int maximumStep = 0;
        for (int x = BILLET_MIN_X + 1; x < BILLET_MAX_X_EXCLUSIVE; x++) {
            int boundary = outerMinimumZ(workpiece, x);
            maximumStep = Math.max(maximumStep, Math.abs(boundary - previousBoundary));
            previousBoundary = boundary;
        }
        assertTrue(maximumStep <= 1);
        assertTrue(outerMinimumZ(workpiece, CENTER_X) <= BILLET_MIN_Z - 3);
        assertEquals(volumeBefore, workpiece.totalVolumeUnits());
        assertSingleConnectedFootprint(workpiece);
    }

    @Test
    void strongerStrikeMovesMoreMass() {
        LayeredSlabWorkpiece medium = LayeredSlabWorkpiece.createIronBillet();
        LayeredSlabWorkpiece strong = LayeredSlabWorkpiece.createIronBillet();

        int mediumMoved = medium.hammerFace(
                        true, CENTER_X, CENTER_Z, 5, new DeformationParameters(1.0D, 0.68D))
                .movedVolumeUnits();
        int strongMoved = strong.hammerFace(
                        true, CENTER_X, CENTER_Z, 10, new DeformationParameters(1.0D, 0.68D))
                .movedVolumeUnits();

        assertTrue(strongMoved > mediumMoved);
    }

    @Test
    void higherFrictionReducesLateralFlow() {
        LayeredSlabWorkpiece lowFriction = LayeredSlabWorkpiece.createIronBillet();
        LayeredSlabWorkpiece highFriction = LayeredSlabWorkpiece.createIronBillet();

        int lowMoved = lowFriction.hammerFace(
                        true, CENTER_X, CENTER_Z, 8, new DeformationParameters(1.0D, 0.68D, 0.0D))
                .movedVolumeUnits();
        int highMoved = highFriction.hammerFace(
                        true, CENTER_X, CENTER_Z, 8, new DeformationParameters(1.0D, 0.68D, 1.0D))
                .movedVolumeUnits();

        assertTrue(lowMoved > highMoved);
    }

    @Test
    void coldLowEnergyBlowStaysBelowYieldThreshold() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        byte[] before = workpiece.encode();

        LayeredSlabWorkpiece.HammerResult result = workpiece.hammerFace(
                true, CENTER_X, CENTER_Z, 1, new DeformationParameters(0.015D, 0.68D));

        assertFalse(result.changed());
        assertArrayEquals(before, workpiece.encode());
    }

    @Test
    void identicalStrikeHistoryIsBitForBitDeterministic() {
        LayeredSlabWorkpiece first = LayeredSlabWorkpiece.createIronBillet();
        LayeredSlabWorkpiece second = LayeredSlabWorkpiece.createIronBillet();
        DeformationParameters parameters = new DeformationParameters(0.87D, 0.68D);
        for (int strike = 0; strike < 40; strike++) {
            int x = CENTER_X - 3 * SCALE + strike % (6 * SCALE);
            int z = CENTER_Z - SCALE + strike % (2 * SCALE);
            int force = 3 + strike % 8;
            first.hammerFace(true, x, z, force, parameters);
            second.hammerFace(true, x, z, force, parameters);
        }
        assertArrayEquals(first.encode(), second.encode());
    }

    @Test
    void compressedGeometryRoundTrips() throws IOException {
        LayeredSlabWorkpiece original = LayeredSlabWorkpiece.createIronBillet();
        original.hammerFace(
                true, CENTER_X, CENTER_Z, 8, new DeformationParameters(0.9D, 0.68D));

        LayeredSlabWorkpiece restored = LayeredSlabWorkpiece.decode(original.encode());

        assertArrayEquals(original.encode(), restored.encode());
    }

    @Test
    void legacyThreeDimensionalGeometryMigratesWithoutLosingVolume() throws IOException {
        SectionedWorkpiece legacy = SectionedWorkpiece.createIronBillet();
        LayeredSlabWorkpiece migrated = LayeredSlabWorkpiece.decode(legacy.encode());
        assertEquals(legacy.totalVolumeUnits() * SCALE * SCALE, migrated.totalVolumeUnits());
    }

    @Test
    void versionOneGridMigratesToTwelveByTwelveSubcolumns() throws IOException {
        assertGridMigration(1, 1, SCALE);
    }

    @Test
    void versionTwoGridMigratesToFourByFourSubcolumns() throws IOException {
        assertGridMigration(2, 3, 4);
    }

    @Test
    void versionThreeGridMigratesToTwoByTwoSubcolumns() throws IOException {
        assertGridMigration(3, 6, 2);
    }

    private static void assertGridMigration(int version, int encodedScale, int upscale) throws IOException {
        LayeredSlabWorkpiece migrated = LayeredSlabWorkpiece.decode(encodedGrid(version, encodedScale));
        long encodedVolume = 40L
                * 8L
                * encodedScale
                * encodedScale
                * LayeredSlabWorkpiece.INITIAL_THICKNESS_UNITS;
        assertEquals(encodedVolume * upscale * upscale, migrated.totalVolumeUnits());
        for (int x = 30 * SCALE; x < 31 * SCALE; x++) {
            for (int z = 32 * SCALE; z < 33 * SCALE; z++) {
                assertEquals(
                        LayeredSlabWorkpiece.INITIAL_THICKNESS_UNITS,
                        migrated.thicknessUnitsAt(x, z));
            }
        }
    }

    private static byte[] encodedGrid(int version, int encodedScale) throws IOException {
        int encodedGridSize = LayeredSlabWorkpiece.LEGACY_GRID_SIZE * encodedScale;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(bytes);
        header.writeInt(0x54424853);
        header.writeByte(version);
        header.flush();
        try (DataOutputStream body = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            for (int x = 0; x < encodedGridSize; x++) {
                for (int z = 0; z < encodedGridSize; z++) {
                    boolean occupied = x >= 12 * encodedScale
                            && x < 52 * encodedScale
                            && z >= 28 * encodedScale
                            && z < 36 * encodedScale;
                    body.writeByte(occupied ? LayeredSlabWorkpiece.IRON : LayeredSlabWorkpiece.EMPTY);
                    body.writeShort(occupied ? LayeredSlabWorkpiece.INITIAL_THICKNESS_UNITS : 0);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static int outerMinimumZ(LayeredSlabWorkpiece workpiece, int x) {
        for (int z = 0; z < LayeredSlabWorkpiece.GRID_SIZE; z++) {
            if (workpiece.isOccupied(x, z)) {
                return z;
            }
        }
        return LayeredSlabWorkpiece.GRID_SIZE;
    }

    private static void assertSingleConnectedFootprint(LayeredSlabWorkpiece workpiece) {
        int occupied = 0;
        int startX = -1;
        int startZ = -1;
        for (int x = 0; x < LayeredSlabWorkpiece.GRID_SIZE; x++) {
            for (int z = 0; z < LayeredSlabWorkpiece.GRID_SIZE; z++) {
                if (workpiece.isOccupied(x, z)) {
                    occupied++;
                    if (startX < 0) {
                        startX = x;
                        startZ = z;
                    }
                }
            }
        }

        boolean[] visited = new boolean[LayeredSlabWorkpiece.GRID_SIZE * LayeredSlabWorkpiece.GRID_SIZE];
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.add(new int[] {startX, startZ});
        int connected = 0;
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        while (!open.isEmpty()) {
            int[] cell = open.removeFirst();
            int flat = cell[0] * LayeredSlabWorkpiece.GRID_SIZE + cell[1];
            if (visited[flat]) {
                continue;
            }
            visited[flat] = true;
            connected++;
            for (int[] direction : directions) {
                int x = cell[0] + direction[0];
                int z = cell[1] + direction[1];
                if (x >= 0
                        && x < LayeredSlabWorkpiece.GRID_SIZE
                        && z >= 0
                        && z < LayeredSlabWorkpiece.GRID_SIZE
                        && workpiece.isOccupied(x, z)) {
                    open.addLast(new int[] {x, z});
                }
            }
        }
        assertEquals(occupied, connected);
    }
}
