package cn.haitang.anticheat.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketTimelineTest {

    @Test
    void acceptsOnlyTheAttackAdjacentSwingSequence() {
        long attackNanos = 1_000_000_000L;
        assertTrue(PacketTimeline.swingMatches(10, attackNanos, 9,
                9, attackNanos - 20_000_000L));
        assertTrue(PacketTimeline.swingMatches(10, attackNanos, 2,
                11, attackNanos + 20_000_000L));
        assertFalse(PacketTimeline.swingMatches(10, attackNanos, 2,
                9, attackNanos - 20_000_000L));
        assertFalse(PacketTimeline.swingMatches(10, attackNanos, 9,
                14, attackNanos + 20_000_000L));
    }

    @Test
    void rejectsStaleSwingTimesEvenWhenSequenceIsAdjacent() {
        assertFalse(PacketTimeline.swingMatches(10, 1_000_000_000L, 9,
                9, 800_000_000L));
    }
}
