package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatGeometryTest {

    @Test
    void findsFirstRayIntersectionWithTargetBox() {
        double distance = CombatGeometry.rayBoxIntersectionDistance(
                0.0, 1.0, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, -0.5,
                4.0, 2.0, 0.5,
                6.0);

        assertEquals(3.0, distance, 1.0E-9);
    }

    @Test
    void rejectsRayMissAndIntersectionBeyondMaximumDistance() {
        double miss = CombatGeometry.rayBoxIntersectionDistance(
                0.0, 3.0, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, -0.5,
                4.0, 2.0, 0.5,
                6.0);
        double tooFar = CombatGeometry.rayBoxIntersectionDistance(
                0.0, 1.0, 0.0,
                1.0, 0.0, 0.0,
                3.0, 0.0, -0.5,
                4.0, 2.0, 0.5,
                2.9);

        assertTrue(Double.isNaN(miss));
        assertTrue(Double.isNaN(tooFar));
    }

    @Test
    void measuresOnlyMovementAlongKnockbackDirection() {
        assertEquals(0.4, CombatGeometry.horizontalProjection(0.4, 0.0, 1.0, 0.0), 1.0E-9);
        assertEquals(0.0, CombatGeometry.horizontalProjection(0.0, 0.4, 1.0, 0.0), 1.0E-9);
        assertEquals(-0.4, CombatGeometry.horizontalProjection(-0.4, 0.0, 1.0, 0.0), 1.0E-9);
        assertTrue(Double.isNaN(CombatGeometry.horizontalProjection(1.0, 1.0, 0.0, 0.0)));
    }
}
