package trial_by_hammer.forging.geometry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import trial_by_hammer.forging.geometry.SectionedWorkpiece.DeformationParameters;
import trial_by_hammer.forging.geometry.SectionedWorkpiece.ImpactAxis;
import trial_by_hammer.forging.material.ForgingMaterialProperties;

class SectionedWorkpieceTest {
    @Test
    void ironBilletHasExpectedVolume() {
        SectionedWorkpiece workpiece = SectionedWorkpiece.createIronBillet();
        assertEquals(40 * 8 * 4, workpiece.occupiedCellCount());
        assertEquals(
                40L * 8L * 4L * SectionedWorkpiece.VOLUME_UNITS_PER_CELL,
                workpiece.totalVolumeUnits());
    }

    @Test
    void repeatedHammeringConservesVolume() {
        SectionedWorkpiece workpiece = SectionedWorkpiece.createIronBillet();
        long initialVolume = workpiece.totalVolumeUnits();
        for (int strike = 0; strike < 1000; strike++) {
            workpiece.hammer(ImpactAxis.THICKNESS, true, 20 + strike % 24, 10 + strike % 4, 1 + strike % 10, true);
        }
        assertEquals(initialVolume, workpiece.totalVolumeUnits());
    }

    @Test
    void sameStrikeHistoryIsBitForBitDeterministic() {
        SectionedWorkpiece first = SectionedWorkpiece.createIronBillet();
        SectionedWorkpiece second = SectionedWorkpiece.createIronBillet();
        for (int strike = 0; strike < 120; strike++) {
            int force = 1 + strike % 10;
            first.hammer(ImpactAxis.THICKNESS, true, 18 + strike % 28, 9 + strike % 6, force, true);
            second.hammer(ImpactAxis.THICKNESS, true, 18 + strike % 28, 9 + strike % 6, force, true);
        }
        assertArrayEquals(first.encode(), second.encode());
    }

    @Test
    void geometryRoundTripsThroughCompactEncoding() throws IOException {
        SectionedWorkpiece original = SectionedWorkpiece.createIronBillet();
        original.hammer(ImpactAxis.WIDTH, false, 30, 6, 8, false);
        SectionedWorkpiece restored = SectionedWorkpiece.decode(original.encode());
        assertArrayEquals(original.encode(), restored.encode());
    }

    @Test
    void legacyBinaryCellsMigrateToFullContinuousCells() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x54424847);
            output.writeByte(1);
            for (int length = 0; length < SectionedWorkpiece.LENGTH_CELLS; length++) {
                output.writeShort(0);
                output.writeShort(0);
            }
            output.writeByte(SectionedWorkpiece.IRON);
            output.writeShort(
                    SectionedWorkpiece.LENGTH_CELLS
                            * SectionedWorkpiece.WIDTH_CELLS
                            * SectionedWorkpiece.THICKNESS_CELLS);
        }

        SectionedWorkpiece migrated = SectionedWorkpiece.decode(bytes.toByteArray());
        assertEquals(
                (long)SectionedWorkpiece.LENGTH_CELLS
                        * SectionedWorkpiece.WIDTH_CELLS
                        * SectionedWorkpiece.THICKNESS_CELLS
                        * SectionedWorkpiece.VOLUME_UNITS_PER_CELL,
                migrated.totalVolumeUnits());
    }

    @Test
    void unsupportedStrikeRecordsBendingWithoutChangingVolume() {
        SectionedWorkpiece workpiece = SectionedWorkpiece.createIronBillet();
        long initialVolume = workpiece.totalVolumeUnits();
        SectionedWorkpiece.HammerResult result = workpiece.hammer(
                ImpactAxis.THICKNESS, true, 20, 10, 7, false);
        assertTrue(result.changed());
        assertTrue(result.bent());
        assertTrue(workpiece.thicknessOffsetSubcells(20) < 0);
        assertEquals(initialVolume, workpiece.totalVolumeUnits());
    }

    @Test
    void oneStrikeChangesThicknessGraduallyWithoutDeletingAWholeCell() {
        SectionedWorkpiece workpiece = SectionedWorkpiece.createIronBillet();
        int before = workpiece.thicknessVolumeUnits(ImpactAxis.THICKNESS, 30, 12);
        SectionedWorkpiece.HammerResult result = workpiece.hammer(
                ImpactAxis.THICKNESS,
                true,
                30,
                12,
                10,
                true,
                new DeformationParameters(1.0D, 0.68D));
        int after = workpiece.thicknessVolumeUnits(ImpactAxis.THICKNESS, 30, 12);

        assertTrue(result.changed());
        assertTrue(after > 0);
        assertTrue(after < before);
        assertTrue(before - after < SectionedWorkpiece.VOLUME_UNITS_PER_CELL);
    }

    @Test
    void hotterIronMovesMoreMaterialThanColdIron() {
        SectionedWorkpiece cold = SectionedWorkpiece.createIronBillet();
        SectionedWorkpiece hot = SectionedWorkpiece.createIronBillet();
        SectionedWorkpiece.HammerResult coldResult = cold.hammer(
                ImpactAxis.THICKNESS,
                true,
                30,
                12,
                8,
                true,
                new DeformationParameters(0.05D, 0.68D));
        SectionedWorkpiece.HammerResult hotResult = hot.hammer(
                ImpactAxis.THICKNESS,
                true,
                30,
                12,
                8,
                true,
                new DeformationParameters(1.0D, 0.68D));

        assertTrue(hotResult.movedVolumeUnits() > coldResult.movedVolumeUnits());
    }

    @Test
    void ironPlasticityCurveInterpolatesBetweenTemperaturePoints() {
        double at900 = ForgingMaterialProperties.TEST_IRON.plasticityAt(900);
        double at1000 = ForgingMaterialProperties.TEST_IRON.plasticityAt(1000);
        double at1100 = ForgingMaterialProperties.TEST_IRON.plasticityAt(1100);
        assertTrue(at1000 > at900);
        assertTrue(at1000 < at1100);
    }
}
