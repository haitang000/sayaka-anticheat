package cn.haitang.anticheat.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlideCheckTest {

    @Test
    void detectsSustainedSlowDescentAfterAirborneGrace() {
        assertTrue(GlideCheck.isSuspiciousDescent(12, -0.08, 10, 0.02, 0.18));
        assertTrue(GlideCheck.isSuspiciousDescent(10, -0.18, 10, 0.02, 0.18));
    }

    @Test
    void ignoresJumpApexAndNormalFastFalling() {
        assertFalse(GlideCheck.isSuspiciousDescent(6, -0.08, 10, 0.02, 0.18));
        assertFalse(GlideCheck.isSuspiciousDescent(12, -0.01, 10, 0.02, 0.18));
        assertFalse(GlideCheck.isSuspiciousDescent(12, -0.31, 10, 0.02, 0.18));
        assertFalse(GlideCheck.isSuspiciousDescent(12, 0.10, 10, 0.02, 0.18));
    }

    @Test
    void rejectsInvalidThresholdConfiguration() {
        assertFalse(GlideCheck.isSuspiciousDescent(12, -0.08, 10, -0.01, 0.18));
        assertFalse(GlideCheck.isSuspiciousDescent(12, -0.08, 10, 0.20, 0.18));
    }
}
