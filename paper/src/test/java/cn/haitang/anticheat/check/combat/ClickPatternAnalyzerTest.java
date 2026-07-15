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
        int[] gaps = {
                43, 71, 58, 96, 52, 83, 64, 47, 89, 55,
                102, 68, 45, 77, 61, 94, 50, 86, 73, 41,
                99, 57, 80, 66, 48, 91, 62, 75, 53, 87,
                69, 44, 82, 59, 97, 46, 74, 65, 88, 51
        };

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithGaps(gaps), 5.0);

        assertNotNull(stats);
        assertTrue(stats.stddev() > 10.0);
        assertTrue(stats.dominantBucketRatio() < 0.5);
        assertTrue(stats.cycleSimilarity() < 0.9);
    }

    @Test
    void detectsShortRepeatingCycleThatDistributionCheckWouldMiss() {
        int[] cycle = {46, 63, 51, 72};
        int[] gaps = new int[40];
        for (int i = 0; i < gaps.length; i++) gaps[i] = cycle[i % cycle.length];

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithGaps(gaps), 5.0, 2.0, 6);

        assertNotNull(stats);
        assertTrue(stats.stddev() > 4.5);
        assertTrue(stats.dominantBucketRatio() < 0.78);
        assertEquals(4, stats.cycleLength());
        assertEquals(1.0, stats.cycleSimilarity(), 0.0001);
    }

    @Test
    void detectsRepeatingCycleWithSmallPerCycleJitter() {
        int[] cycle = {46, 63, 51, 72};
        int[] jitter = {-1, 0, 1, 0};
        int[] gaps = new int[40];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = cycle[i % cycle.length] + jitter[(i / cycle.length) % jitter.length];
        }

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithGaps(gaps), 5.0, 2.0, 6);

        assertNotNull(stats);
        assertEquals(4, stats.cycleLength());
        assertTrue(stats.cycleSimilarity() >= 0.9);
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

    private static List<Long> clicksWithGaps(int[] gaps) {
        List<Long> clicks = new ArrayList<>();
        long at = 0;
        clicks.add(at);
        for (int gap : gaps) {
            at += gap;
            clicks.add(at);
        }
        return clicks;
    }
}
