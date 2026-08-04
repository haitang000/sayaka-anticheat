package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBlockCheckTest {

    @Test
    void evidenceBelowThresholdDoesNotReport() {
        assertFalse(AutoBlockCheck.evidenceThresholdReached(2.0, 2.5));
        assertFalse(AutoBlockCheck.evidenceThresholdReached(0.0, 2.5));
    }

    @Test
    void evidenceAtOrAboveThresholdReports() {
        assertTrue(AutoBlockCheck.evidenceThresholdReached(2.5, 2.5));
        assertTrue(AutoBlockCheck.evidenceThresholdReached(3.0, 2.5));
    }

    @Test
    void thresholdNeverDropsBelowOneEvidence() {
        // 配置写 0 时仍需要至少一次证据，防止阈值失效后单次抖动即上报
        assertTrue(AutoBlockCheck.evidenceThresholdReached(1.0, 0.0));
        assertFalse(AutoBlockCheck.evidenceThresholdReached(0.9, 0.0));
    }
}
