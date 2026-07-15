package cn.haitang.anticheat.check.combat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure click-timing analysis. This class must not access Bukkit state. */
public final class ClickPatternAnalyzer {

    private static final int MAX_INTERVALS = 40;
    private static final int MIN_INTERVALS = 25;

    private ClickPatternAnalyzer() { }

    public static TimingStats analyze(List<Long> times, double bucketMs) {
        return analyze(times, bucketMs, 2.0, 6);
    }

    public static TimingStats analyze(List<Long> times, double bucketMs,
                                      double cycleToleranceMs, int maxCycleLength) {
        double[] intervals = new double[Math.min(MAX_INTERVALS, Math.max(0, times.size() - 1))];
        int count = 0;
        for (int i = times.size() - 1; i > 0 && count < intervals.length; i--) {
            double gap = times.get(i) - times.get(i - 1);
            if (gap > 250) break;
            intervals[count++] = gap;
        }
        if (count < MIN_INTERVALS) return null;

        double mean = 0;
        for (int i = 0; i < count; i++) mean += intervals[i];
        mean /= count;

        double variance = 0;
        for (int i = 0; i < count; i++) {
            double delta = intervals[i] - mean;
            variance += delta * delta;
        }
        double stddev = Math.sqrt(variance / count);

        double normalizedBucketMs = Math.max(1.0, bucketMs);
        Map<Long, Integer> buckets = new HashMap<>();
        int dominant = 0;
        for (int i = 0; i < count; i++) {
            long bucket = Math.round(intervals[i] / normalizedBucketMs);
            int bucketCount = buckets.merge(bucket, 1, Integer::sum);
            dominant = Math.max(dominant, bucketCount);
        }

        double cv = mean <= 0 ? 0.0 : stddev / mean;
        CycleMatch cycle = findBestCycle(intervals, count, cycleToleranceMs, maxCycleLength);
        return new TimingStats(mean, stddev, cv, dominant / (double) count,
                cycle.similarity(), cycle.length());
    }

    private static CycleMatch findBestCycle(double[] intervals, int count,
                                            double toleranceMs, int maxCycleLength) {
        double normalizedTolerance = Math.max(0.1, toleranceMs);
        int normalizedMaxLength = Math.max(2, maxCycleLength);
        double bestSimilarity = 0.0;
        int bestLength = 0;
        for (int length = 2; length <= normalizedMaxLength && count >= length * 4; length++) {
            int matches = 0;
            int comparisons = count - length;
            for (int i = length; i < count; i++) {
                if (Math.abs(intervals[i] - intervals[i - length]) <= normalizedTolerance) {
                    matches++;
                }
            }
            double similarity = matches / (double) comparisons;
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestLength = length;
            }
        }
        return new CycleMatch(bestSimilarity, bestLength);
    }

    public record TimingStats(double mean, double stddev,
                              double coefficientOfVariation,
                              double dominantBucketRatio,
                              double cycleSimilarity,
                              int cycleLength) { }

    private record CycleMatch(double similarity, int length) { }
}
