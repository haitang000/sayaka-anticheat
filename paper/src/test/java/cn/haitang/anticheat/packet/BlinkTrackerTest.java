package cn.haitang.anticheat.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BlinkTrackerTest {

    private static final long MS = 1_000_000L;

    private static BlinkTracker tracker() {
        return new BlinkTracker(500, 5000, 150, 6, 2, 4, 30_000);
    }

    /** 跑完一个"暂停 + 爆发"循环，返回循环完成时（第 6 个爆发包）的证据。 */
    private static BlinkTracker.Evidence runCycle(BlinkTracker tracker, long start,
                                                  long pauseMs, boolean pongsDuringPause) {
        long now = start;
        // 正常节奏铺垫
        for (int i = 0; i < 5; i++) {
            assertNull(tracker.movement(now));
            now += 50 * MS;
        }
        long pauseStart = now - 50 * MS;
        long pauseEnd = pauseStart + pauseMs * MS;
        if (pongsDuringPause) {
            tracker.pong(pauseStart + pauseMs * MS / 3);
            tracker.pong(pauseStart + pauseMs * MS * 2 / 3);
        }
        // 爆发：6 个包挤在 50ms 内
        BlinkTracker.Evidence evidence = null;
        now = pauseEnd;
        for (int i = 0; i < 6; i++) {
            BlinkTracker.Evidence result = tracker.movement(now);
            if (result != null) evidence = result;
            now += 10 * MS;
        }
        return evidence;
    }

    @Test
    void aliveBlinkCyclesProduceEvidence() {
        BlinkTracker tracker = tracker();
        long start = 1_000_000_000L;
        assertNull(runCycle(tracker, start, 800, true));
        assertNull(runCycle(tracker, start + 2_000 * MS, 800, true));
        assertNull(runCycle(tracker, start + 4_000 * MS, 800, true));
        BlinkTracker.Evidence evidence = runCycle(tracker, start + 6_000 * MS, 800, true);
        assertNotNull(evidence);
        assertEquals(4, evidence.cycles());
        assertEquals(800, evidence.pauseMillis());
        assertEquals(6, evidence.burstPackets());
    }

    @Test
    void pauseWithoutLivePongsIsIgnored() {
        BlinkTracker tracker = tracker();
        long start = 1_000_000_000L;
        for (int i = 0; i < 6; i++) {
            assertNull(runCycle(tracker, start + i * 2_000L * MS, 800, false));
        }
    }

    @Test
    void pongsArrivingWithBurstDoNotCountAsAlive() {
        BlinkTracker tracker = tracker();
        long start = 1_000_000_000L;
        long now = start;
        for (int cycle = 0; cycle < 6; cycle++) {
            for (int i = 0; i < 5; i++) {
                assertNull(tracker.movement(now));
                now += 50 * MS;
            }
            now += 750 * MS;
            // 上行抖动：Pong 和移动包一起在爆发时刻到达，而不是暂停期间
            tracker.pong(now);
            tracker.pong(now + MS);
            for (int i = 0; i < 6; i++) {
                assertNull(tracker.movement(now));
                now += 10 * MS;
            }
            now += 1_000 * MS;
        }
    }

    @Test
    void longAfkPauseDoesNotStartCycle() {
        BlinkTracker tracker = tracker();
        long start = 1_000_000_000L;
        for (int i = 0; i < 6; i++) {
            assertNull(runCycle(tracker, start + i * 20_000L * MS, 8_000, true));
        }
    }

    @Test
    void smallBurstAfterPauseIsIgnored() {
        BlinkTracker tracker = tracker();
        long now = 1_000_000_000L;
        for (int cycle = 0; cycle < 6; cycle++) {
            for (int i = 0; i < 5; i++) {
                assertNull(tracker.movement(now));
                now += 50 * MS;
            }
            long pauseStart = now - 50 * MS;
            tracker.pong(pauseStart + 200 * MS);
            tracker.pong(pauseStart + 400 * MS);
            now = pauseStart + 700 * MS;
            // 暂停后只有 3 个包（正常卡顿恢复），凑不满爆发
            for (int i = 0; i < 3; i++) {
                assertNull(tracker.movement(now));
                now += 10 * MS;
            }
            now += 1_000 * MS;
        }
    }

    @Test
    void resetClearsAccumulatedCycles() {
        BlinkTracker tracker = tracker();
        long start = 1_000_000_000L;
        assertNull(runCycle(tracker, start, 800, true));
        assertNull(runCycle(tracker, start + 2_000 * MS, 800, true));
        assertNull(runCycle(tracker, start + 4_000 * MS, 800, true));
        tracker.reset();
        // 重置后重新累积，第 4 个循环不会立即出证据
        assertNull(runCycle(tracker, start + 6_000 * MS, 800, true));
    }
}
