package cn.haitang.anticheat.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquidWalkCheckTest {

    private static final double MIN_HORIZONTAL = 0.025;
    private static final double MAX_VERTICAL = 0.025;

    @Test
    void detectsFlatHorizontalMovementAcrossACompleteLiquidSurface() {
        assertTrue(sample(true, true, false, false, 0.08, 0.001));
        assertTrue(sample(true, true, false, false, MIN_HORIZONTAL, MAX_VERTICAL));
    }

    @Test
    void ignoresStationaryAndVerticallyMovingSamples() {
        assertFalse(sample(true, true, false, false, 0.0, 0.0));
        assertFalse(sample(true, true, false, false, 0.08, 0.026));
        assertFalse(sample(true, true, false, false, 0.08, -0.026));
    }

    @Test
    void ignoresSwimmingAndSubmergedPlayers() {
        assertFalse(sample(true, true, false, true, 0.08, 0.0));
        assertFalse(sample(false, true, false, false, 0.08, 0.0));
    }

    @Test
    void ignoresShorelinesAndRealCollisionSupport() {
        assertFalse(sample(true, false, false, false, 0.08, 0.0));
        assertFalse(sample(true, true, true, false, 0.08, 0.0));
    }

    @Test
    void rejectsNonFiniteMovementData() {
        assertFalse(sample(true, true, false, false, Double.NaN, 0.0));
        assertFalse(sample(true, true, false, false, 0.08, Double.POSITIVE_INFINITY));
    }

    private static boolean sample(boolean feetClear, boolean fullLiquidFootprint,
                                  boolean collisionBelow, boolean swimming,
                                  double horizontal, double vertical) {
        return LiquidWalkCheck.isSuspiciousSample(feetClear, fullLiquidFootprint,
                collisionBelow, swimming, horizontal, vertical,
                MIN_HORIZONTAL, MAX_VERTICAL);
    }
}
