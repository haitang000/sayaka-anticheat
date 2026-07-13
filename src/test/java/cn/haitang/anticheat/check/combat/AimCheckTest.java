package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimCheckTest {

    @Test
    void weightsLargerAngleViolationsMoreHeavily() {
        assertEquals(1.0, AimCheck.angleBufferIncrement(60.0, 50.0));
        assertEquals(1.5, AimCheck.angleBufferIncrement(80.0, 50.0));
        assertEquals(2.0, AimCheck.angleBufferIncrement(110.0, 50.0));
    }

    @Test
    void detectsDifferentTargetsWithinConfiguredTickGap() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(AimCheck.isRapidTargetSwitch(0, 2, second, first));
        assertTrue(AimCheck.isRapidTargetSwitch(2, 2, second, first));
        assertFalse(AimCheck.isRapidTargetSwitch(3, 2, second, first));
        assertFalse(AimCheck.isRapidTargetSwitch(1, 2, first, first));
        assertFalse(AimCheck.isRapidTargetSwitch(1, 2, first, null));
    }

}
