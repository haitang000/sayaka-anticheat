package cn.haitang.anticheat.check.world;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionMathTest {

    private static final BoundingBox TARGET = new BoundingBox(2, 0, -0.5, 3, 1, 0.5);

    @Test
    void eyeAboveBoxMeasuresVerticalDistance() {
        assertEquals(1.5, InteractionMath.eyeToBoxDistance(
                new Location(null, 2.5, 2.5, 0), TARGET), 1.0e-9);
    }

    @Test
    void originInsideBoxHasZeroDistance() {
        assertEquals(0.0, InteractionMath.eyeToBoxDistance(
                new Location(null, 2.5, 0.5, 0), TARGET), 1.0e-9);
    }

    @Test
    void measuresDiagonalEdgeHit() {
        assertEquals(Math.sqrt(5.0), InteractionMath.eyeToBoxDistance(
                new Location(null, 0, 2.0, 0), TARGET), 1.0e-9);
    }

    @Test
    void degenerateBoxIsExpandedAroundItsCenter() {
        BoundingBox zero = new BoundingBox(10, 64, 10, 10, 64, 10);
        assertEquals(0.75, InteractionMath.eyeToBoxDistance(
                new Location(null, 10, 65, 10), zero), 1.0e-9);
    }

    @Test
    void lookingAtNearestBoxPointHasZeroAngle() {
        Location eye = new Location(null, 0, 1.0, 0, -90.0f, 0.0f);
        assertEquals(0.0, InteractionMath.eyeToBoxAngleDegrees(eye, TARGET), 1.0e-9);
    }

    @Test
    void lookingAwayFromBoxIsOneHundredEightyDegrees() {
        Location eye = new Location(null, 0, 1.0, 0, 90.0f, 0.0f);
        assertEquals(180.0, InteractionMath.eyeToBoxAngleDegrees(eye, TARGET), 1.0e-3);
    }

    @Test
    void diagonalLookReportsDiagonalAngle() {
        // 眼睛看向 -X 与 +Z 的角平分线（东南方向），目标位于正东
        Location eye = new Location(null, 0, 1.0, 0, -45.0f, 0.0f);
        assertEquals(45.0, InteractionMath.eyeToBoxAngleDegrees(eye, TARGET), 1.0e-3);
    }

    @Test
    void eyeInsideBoxHasNoAngle() {
        Location eye = new Location(null, 2.5, 0.5, 0);
        assertTrue(Double.isNaN(InteractionMath.eyeToBoxAngleDegrees(eye, TARGET)));
    }

    @Test
    void emptyShapeFallsBackToFullCell() {
        BoundingBox box = InteractionMath.clickableShape(10, 64, 10, List.of());
        assertEquals(10.0, box.getMinX(), 1.0e-9);
        assertEquals(11.0, box.getMaxX(), 1.0e-9);
        assertEquals(64.0, box.getMinY(), 1.0e-9);
        assertEquals(65.0, box.getMaxY(), 1.0e-9);
    }

    @Test
    void multipleShapesAreUnioned() {
        BoundingBox half = new BoundingBox(10, 64, 10, 11, 64.5, 11);
        BoundingBox corner = new BoundingBox(10, 64.5, 10, 10.5, 65, 11);
        BoundingBox box = InteractionMath.clickableShape(10, 64, 10, List.of(half, corner));
        assertEquals(10.0, box.getMinX(), 1.0e-9);
        assertEquals(65.0, box.getMaxY(), 1.0e-9);
        assertEquals(11.0, box.getMaxX(), 1.0e-9);
        assertTrue(box.getVolume() > 0);
    }

    @Test
    void zeroVolumeShapeFallsBackToFullCell() {
        BoundingBox zero = new BoundingBox(10.4, 64.4, 10.4, 10.4, 64.4, 10.4);
        BoundingBox box = InteractionMath.clickableShape(10, 64, 10, List.of(zero));
        assertEquals(10.0, box.getMinX(), 1.0e-9);
        assertFalse(box.getVolume() <= InteractionMath.EPSILON);
    }
}
