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

    @Test
    void acquisitionDecisionNeedsSamplesThenJudgesByRatio() {
        // 样本不足：继续累积
        assertEquals(0, AimCheck.acquisitionDecision(5, 8, 10, 0.75, 0.4));
        // 12 次再瞄准中 9 次同刻转正 → 可疑
        assertEquals(1, AimCheck.acquisitionDecision(9, 12, 10, 0.75, 0.4));
        // 占比正常 → 洗白
        assertEquals(-1, AimCheck.acquisitionDecision(4, 12, 10, 0.75, 0.4));
        // 灰色区间：不清空继续滑动
        assertEquals(0, AimCheck.acquisitionDecision(7, 12, 10, 0.75, 0.4));
    }

    @Test
    void accuracyReportRequiresEnoughSwings() {
        assertFalse(AimCheck.accuracyExceeds(39, 39, 40, 0.95));
        assertTrue(AimCheck.accuracyExceeds(39, 40, 40, 0.95));
        assertFalse(AimCheck.accuracyExceeds(37, 40, 40, 0.95));
        // 攻击数偶发超过挥臂数（NoSwing 另行处理）按 100% 截断
        assertTrue(AimCheck.accuracyExceeds(45, 40, 40, 0.95));
    }

}
