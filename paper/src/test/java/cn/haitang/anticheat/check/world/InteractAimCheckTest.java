package cn.haitang.anticheat.check.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InteractAimCheckTest {

    @Test
    void slightExcessBuffersOnePoint() {
        assertEquals(1.0, InteractAimCheck.angleBufferIncrement(70.0, 60.0), 1.0e-9);
        assertEquals(1.0, InteractAimCheck.angleBufferIncrement(89.9, 60.0), 1.0e-9);
    }

    @Test
    void largeExcessBuffersFaster() {
        assertEquals(1.5, InteractAimCheck.angleBufferIncrement(90.0, 60.0), 1.0e-9);
        assertEquals(2.0, InteractAimCheck.angleBufferIncrement(120.0, 60.0), 1.0e-9);
    }

    @Test
    void behindBackInteractIsHighestIncrement() {
        assertEquals(2.0, InteractAimCheck.angleBufferIncrement(150.0, 60.0), 1.0e-9);
    }
}
