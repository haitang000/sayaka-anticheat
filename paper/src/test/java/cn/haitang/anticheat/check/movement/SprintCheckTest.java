package cn.haitang.anticheat.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SprintCheckTest {

    /** yaw 0 = 朝 +Z：正前方移动夹角为 0 */
    @Test
    void forwardMovementIsZeroAngle() {
        assertEquals(0.0, SprintCheck.moveViewAngle(0.0, 1.0, 0f), 1.0e-9);
    }

    /** 前进 + 平移的原版合成最多 45° */
    @Test
    void strafeForwardIsFortyFive() {
        assertEquals(45.0, SprintCheck.moveViewAngle(-1.0, 1.0, 0f), 1.0e-9);
    }

    /** 正后方移动为 180° */
    @Test
    void backwardsMovementIsOppositeAngle() {
        assertEquals(180.0, SprintCheck.moveViewAngle(0.0, -1.0, 0f), 1.0e-9);
    }

    /** yaw 90 = 朝 -X：向 -X 移动夹角为 0，向 +X 为 180 */
    @Test
    void westFacingConvention() {
        assertEquals(0.0, SprintCheck.moveViewAngle(-1.0, 0.0, 90f), 1.0e-9);
        assertEquals(180.0, SprintCheck.moveViewAngle(1.0, 0.0, 90f), 1.0e-9);
    }

    /** yaw 跨越 ±180 边界时按最短角差 */
    @Test
    void wrapsAcrossBoundary() {
        assertTrue(SprintCheck.moveViewAngle(0.0, 1.0, 350f) <= 10.0 + 1.0e-9);
        assertEquals(10.0, SprintCheck.moveViewAngle(0.0, 1.0, -350f), 1.0e-9);
    }
}
