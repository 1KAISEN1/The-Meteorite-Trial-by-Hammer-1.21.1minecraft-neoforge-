package trial_by_hammer.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece;
import trial_by_hammer.forging.geometry.LayeredSlabWorkpiece.DeformationParameters;

class SmoothedSlabMeshTest {
    @Test
    void billetMeshPreservesPhysicalDimensionsAfterResolutionIncrease() {
        SmoothedSlabMesh.Mesh mesh = SmoothedSlabMesh.bake(LayeredSlabWorkpiece.createIronBillet());

        assertTrue(mesh.faceCount() > 0);
        assertEquals(4.0F / 48.0F, mesh.maxY(), 1.0E-5F);
        assertEquals(-4.0F / 48.0F, mesh.minZ(), 1.0E-5F);
        assertEquals(4.0F / 48.0F, mesh.maxZ(), 1.0E-5F);
    }

    @Test
    void deformedTopUsesSlopedNormalsAndIrregularBoundaryUsesDiagonalFaces() {
        LayeredSlabWorkpiece workpiece = LayeredSlabWorkpiece.createIronBillet();
        int x = oldCellCenter(30);
        int z = oldCellCenter(28);
        for (int strike = 0; strike < 6; strike++) {
            workpiece.hammerFace(true, x, z, 8, new DeformationParameters(0.90D, 0.68D));
        }

        SmoothedSlabMesh.Mesh mesh = SmoothedSlabMesh.bake(workpiece);
        boolean slopedTopNormal = false;
        boolean diagonalSideNormal = false;
        float[] vertices = mesh.vertices();
        for (int offset = 0; offset < vertices.length; offset += SmoothedSlabMesh.FLOATS_PER_VERTEX) {
            float normalX = vertices[offset + 3];
            float normalY = vertices[offset + 4];
            float normalZ = vertices[offset + 5];
            if (normalY > 0.0F && normalY < 0.999F) {
                slopedTopNormal = true;
            }
            if (Math.abs(normalY) < 1.0E-6F
                    && Math.abs(normalX) > 0.1F
                    && Math.abs(normalZ) > 0.1F) {
                diagonalSideNormal = true;
            }
        }

        assertTrue(slopedTopNormal);
        assertTrue(diagonalSideNormal);
    }

    private static int oldCellCenter(int coordinate) {
        return coordinate * LayeredSlabWorkpiece.RESOLUTION_SCALE
                + LayeredSlabWorkpiece.RESOLUTION_SCALE / 2;
    }
}
