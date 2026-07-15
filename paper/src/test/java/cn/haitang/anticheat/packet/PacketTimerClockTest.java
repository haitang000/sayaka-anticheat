package cn.haitang.anticheat.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketTimerClockTest {

    @Test
    void vanillaTwentyPacketsPerSecondNeverBuildsBalance() {
        PacketTimerClock clock = new PacketTimerClock();
        long now = 1_000_000_000L;
        for (int i = 0; i < 200; i++) {
            assertNull(clock.accept(now));
            now += 50_000_000L;
        }
    }

    @Test
    void sustainedTenPercentTimerEventuallySignals() {
        PacketTimerClock clock = new PacketTimerClock();
        long now = 1_000_000_000L;
        PacketTimerClock.Evidence evidence = null;
        for (int i = 0; i < 240 && evidence == null; i++) {
            evidence = clock.accept(now);
            now += 45_000_000L;
        }
        assertNotNull(evidence);
        assertTrue(evidence.ratePerSecond() > 21.0);
    }

    @Test
    void longGapResetsAccumulatedBalance() {
        PacketTimerClock clock = new PacketTimerClock();
        long now = 1_000_000_000L;
        for (int i = 0; i < 20; i++) {
            assertNull(clock.accept(now));
            now += 40_000_000L;
        }
        now += 2_000_000_000L;
        assertNull(clock.accept(now));
        for (int i = 0; i < 60; i++) {
            now += 50_000_000L;
            assertNull(clock.accept(now));
        }
    }
}
