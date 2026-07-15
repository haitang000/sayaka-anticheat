package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimPatternAnalyzerTest {

    @Test
    void measuresYawAcrossTheWrapBoundary() {
        assertEquals(2.0, AimPatternAnalyzer.rotationDistance(179.0, 0.0, -179.0, 0.0), 1.0E-9);
        assertEquals(-2.0, AimPatternAnalyzer.wrappedAngleDelta(-179.0, 179.0), 1.0E-9);
    }

    @Test
    void detectsLargeAimSnapThatReturnsToOriginalView() {
        assertTrue(AimPatternAnalyzer.isSnapBack(
                10.0, 5.0, 48.0, 8.0, 11.0, 5.5,
                25.0, 4.0, 0.70));
    }

    @Test
    void rejectsNormalTrackingAndFlicksThatDoNotReturn() {
        assertFalse(AimPatternAnalyzer.isSnapBack(
                10.0, 5.0, 22.0, 7.0, 11.0, 5.0,
                25.0, 4.0, 0.70));
        assertFalse(AimPatternAnalyzer.isSnapBack(
                10.0, 5.0, 48.0, 8.0, 40.0, 7.0,
                25.0, 4.0, 0.70));
    }

    @Test
    void mapsRotationsOntoMinecraftDirectionVectors() {
        double[] south = AimPatternAnalyzer.directionFromRotation(0.0, 0.0);
        assertEquals(0.0, south[0], 1.0E-9);
        assertEquals(0.0, south[1], 1.0E-9);
        assertEquals(1.0, south[2], 1.0E-9);

        double[] west = AimPatternAnalyzer.directionFromRotation(90.0, 0.0);
        assertEquals(-1.0, west[0], 1.0E-9);

        double[] up = AimPatternAnalyzer.directionFromRotation(0.0, -90.0);
        assertEquals(1.0, up[1], 1.0E-9);
    }

    @Test
    void measuresViewErrorAgainstTheTargetDirection() {
        assertEquals(0.0, AimPatternAnalyzer.viewAngleToTarget(0.0, 0.0, 0.0, 0.0, 5.0), 1.0E-6);
        assertEquals(90.0, AimPatternAnalyzer.viewAngleToTarget(0.0, 0.0, -5.0, 0.0, 0.0), 1.0E-6);
        assertEquals(180.0, AimPatternAnalyzer.viewAngleToTarget(0.0, 0.0, 0.0, 0.0, -5.0), 1.0E-6);
        assertTrue(Double.isNaN(AimPatternAnalyzer.viewAngleToTarget(0.0, 0.0, 0.0, 0.0, 0.0)));
    }

    @Test
    void horizontalBearingFollowsYawConvention() {
        assertEquals(0.0, AimPatternAnalyzer.horizontalBearing(0.0, 1.0), 1.0E-9);
        assertEquals(90.0, AimPatternAnalyzer.horizontalBearing(-1.0, 0.0), 1.0E-9);
        assertEquals(-90.0, AimPatternAnalyzer.horizontalBearing(1.0, 0.0), 1.0E-9);
    }
}
