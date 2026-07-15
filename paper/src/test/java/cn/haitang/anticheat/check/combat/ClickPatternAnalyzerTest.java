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

    @Test
    void constantIntervalsHaveZeroSkewAndAutocorrelation() {
        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithIntervals(50, 30), 5.0);

        assertNotNull(stats);
        assertEquals(0.0, stats.skewness(), 1.0E-9);
        assertEquals(0.0, stats.lag1Autocorrelation(), 1.0E-9);
    }

    @Test
    void gaussianRandomizedMacroLooksSymmetricAndIndependent() {
        // 种子 19 产生 skew≈0.08、r1≈0.01 的典型 IID 样本，距判定线余量充足；
        // 单个窗口的抽样波动本就允许漏判，检测依靠连续多窗口 buffer 积累
        java.util.Random random = new java.util.Random(19);
        long[] nanos = new long[41];
        long at = 0;
        for (int i = 1; i < nanos.length; i++) {
            at += Math.round((80.0 + random.nextGaussian() * 8.0) * 1_000_000.0);
            nanos[i] = at;
        }

        ClickPatternAnalyzer.TimingStats stats =
                ClickPatternAnalyzer.analyzeNanos(nanos, 5.0, 2.0, 6);

        assertNotNull(stats);
        assertTrue(stats.coefficientOfVariation() > 0.06,
                "randomized macro must not fall into the low-dispersion band");
        assertTrue(Math.abs(stats.skewness()) <= 0.35,
                "IID gaussian sampling is symmetric, got " + stats.skewness());
        assertTrue(Math.abs(stats.lag1Autocorrelation()) <= 0.15,
                "IID sampling has no serial correlation, got " + stats.lag1Autocorrelation());
    }

    @Test
    void humanTempoDriftShowsStrongPositiveAutocorrelation() {
        // 疲劳漂移：点击间隔从 60ms 缓慢爬升到 100ms，带小幅抖动
        int[] gaps = new int[40];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = 60 + i + ((i % 3) - 1) * 2;
        }

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithGaps(gaps), 5.0);

        assertNotNull(stats);
        assertTrue(stats.lag1Autocorrelation() > 0.15,
                "drifting tempo must fail the independence gate, got "
                        + stats.lag1Autocorrelation());
    }

    @Test
    void jitterClickAlternationShowsStrongNegativeAutocorrelation() {
        // 抖动/蝶点：短长间隔交替
        int[] gaps = new int[40];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = (i % 2 == 0 ? 55 : 90) + (i % 5);
        }

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithGaps(gaps), 5.0);

        assertNotNull(stats);
        assertTrue(stats.lag1Autocorrelation() < -0.15,
                "alternating tempo must fail the independence gate, got "
                        + stats.lag1Autocorrelation());
    }

    @Test
    void occasionalSlowClicksSkewTheDistributionRight() {
        // 人手连点：多数间隔 65-75ms，偶发 150-180ms 长间隔
        int[] gaps = new int[40];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = 65 + (i % 4) * 3;
        }
        gaps[9] = 150;
        gaps[23] = 180;
        gaps[36] = 160;

        ClickPatternAnalyzer.TimingStats stats = ClickPatternAnalyzer.analyze(
                clicksWithGaps(gaps), 5.0);

        assertNotNull(stats);
        assertTrue(stats.skewness() > 0.35,
                "occasional lapses must fail the symmetry gate, got " + stats.skewness());
    }

    @Test
    void nanosEntryKeepsSubMillisecondPrecision() {
        // 79.5ms 与 80.5ms 交替：毫秒时间戳无法表达，纳秒入口应测得 σ=0.5ms
        long[] nanos = new long[31];
        long at = 0;
        for (int i = 1; i < nanos.length; i++) {
            at += (i % 2 == 0) ? 79_500_000L : 80_500_000L;
            nanos[i] = at;
        }

        ClickPatternAnalyzer.TimingStats stats =
                ClickPatternAnalyzer.analyzeNanos(nanos, 5.0, 2.0, 6);

        assertNotNull(stats);
        assertEquals(80.0, stats.mean(), 0.01);
        assertEquals(0.5, stats.stddev(), 0.01);
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
