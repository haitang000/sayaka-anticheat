package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickPatternAnalyzerTest {

    @Test
    void detectsPerfectlyRegularClickIntervals() {
        List<Long> clicks = clicksWithIntervals(50, 30);

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(clicks, 5.0);

        assertNotNull(stats);
        assertEquals(50.0, stats.mean(), 0.0001);
        assertEquals(0.0, stats.stddev(), 0.0001);
        assertEquals(1.0, stats.dominantBucketRatio(), 0.0001);
    }

    @Test
    void reportsVariationForHumanLikeIntervals() {
        List<Long> clicks = new ArrayList<>();
        long at = 0;
        clicks.add(at);
        int[] gaps = {43, 71, 58, 96, 52, 83, 64, 47};
        for (int i = 0; i < 32; i++) {
            at += gaps[i % gaps.length];
            clicks.add(at);
        }

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(clicks, 5.0);

        assertNotNull(stats);
        assertTrue(stats.stddev() > 10.0);
        assertTrue(stats.dominantBucketRatio() < 0.5);
    }

    @Test
    void ignoresShortOrInterruptedSamples() {
        assertNull(ClickPatternAnalyzer.analyze(clicksWithIntervals(50, 20), 5.0));

        List<Long> interrupted = clicksWithIntervals(50, 30);
        interrupted.set(interrupted.size() - 1,
                interrupted.get(interrupted.size() - 2) + 300);
        assertNull(ClickPatternAnalyzer.analyze(interrupted, 5.0));
    }

    private static List<Long> clicksWithIntervals(long gap, int intervalCount) {
        List<Long> clicks = new ArrayList<>();
        long at = 0;
        clicks.add(at);
        for (int i = 0; i < intervalCount; i++) {
            at += gap;
            clicks.add(at);
        }
        return clicks;
    }
}
