package cn.haitang.anticheat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LagDetectorTest {

    private static final long MS = 1_000_000L;
    private static final long NORMAL = 50 * MS;
    private static final long STALL = 2_000 * MS;

    @Test
    void steadyNormalIntervalsAreNotLagging() {
        LagDetector detector = new LagDetector(0);
        for (int i = 0; i < 60; i++) {
            detector.recordInterval(NORMAL);
        }
        assertFalse(detector.isLagging());
    }

    @Test
    void singleStallIsDetectedImmediately() {
        LagDetector detector = new LagDetector(0);
        for (int i = 0; i < 5; i++) {
            detector.recordInterval(NORMAL);
        }
        detector.recordInterval(STALL);
        assertTrue(detector.isLagging());
    }

    @Test
    void lagSignalPersistsAcrossFollowingNormalTicks() {
        LagDetector detector = new LagDetector(0);
        detector.recordInterval(STALL);
        // 停顿后积压包在随后几个 tick 内排空，保护窗口应继续保持
        for (int i = 0; i < 10; i++) {
            detector.recordInterval(NORMAL);
        }
        assertTrue(detector.isLagging());
    }

    @Test
    void lagSignalExpiresAfterFullWindowOfNormalIntervals() {
        LagDetector detector = new LagDetector(0);
        detector.recordInterval(STALL);
        // WINDOW_SIZE(40) 次正常间隔后旧停顿样本被刷出环形窗口
        for (int i = 0; i < 41; i++) {
            detector.recordInterval(NORMAL);
        }
        assertFalse(detector.isLagging());
    }

    @Test
    void boundaryStallIsNotLagging() {
        LagDetector detector = new LagDetector(0);
        // 恰好等于阈值（500ms）不算停顿，避免把常规低 TPS 误判
        detector.recordInterval(LagDetector.STALL_THRESHOLD_MILLIS * MS);
        assertFalse(detector.isLagging());
    }
}