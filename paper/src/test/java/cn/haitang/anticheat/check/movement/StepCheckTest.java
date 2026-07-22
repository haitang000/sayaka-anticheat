package cn.haitang.anticheat.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepCheckTest {

    private static final double MAX_HEIGHT = 0.70;
    private static final double MIN_HORIZONTAL = 0.03;

    @Test
    void flagsGroundedRiseAboveAutoStepLimit() {
        // 站定到高方块上、上升越过 0.70、带水平位移：典型 Step。
        assertTrue(StepCheck.isStepViolation(1.0, 0.25, true, MAX_HEIGHT, MIN_HORIZONTAL));
    }

    @Test
    void ignoresAirborneCoalescedJumpSpike() {
        // 网络合批把两个跳跃刻拼成一个 dy=0.75 的滞空包：脚下无碰撞体，不算跨步。
        assertFalse(StepCheck.isStepViolation(0.75, 0.28, false, MAX_HEIGHT, MIN_HORIZONTAL));
    }

    @Test
    void ignoresPurelyVerticalPush() {
        // 活塞等纯竖直推动：无水平位移。
        assertFalse(StepCheck.isStepViolation(1.2, 0.0, true, MAX_HEIGHT, MIN_HORIZONTAL));
    }

    @Test
    void ignoresNormalAutoStep() {
        // 半砖/台阶等 ≤0.6 的合法自动跨步。
        assertFalse(StepCheck.isStepViolation(0.5, 0.2, true, MAX_HEIGHT, MIN_HORIZONTAL));
    }
}
