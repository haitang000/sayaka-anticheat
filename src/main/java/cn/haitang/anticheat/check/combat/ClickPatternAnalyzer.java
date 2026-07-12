package cn.haitang.anticheat.check.combat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure click-timing analysis. This class must not access Bukkit state. */
public final class ClickPatternAnalyzer {

    private ClickPatternAnalyzer() { }

    public static TimingStats analyze(List<Long> times, double bucketMs) {
        double[] intervals = new double[Math.min(30, Math.max(0, times.size() - 1))];
        int count = 0;
        for (int i = times.size() - 1; i > 0 && count < intervals.length; i--) {
            double gap = times.get(i) - times.get(i - 1);
            if (gap > 250) break;
            intervals[count++] = gap;
        }
        if (count < 25) return null;

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
        return new TimingStats(mean, stddev, cv, dominant / (double) count);
    }

    public record TimingStats(double mean, double stddev,
                              double coefficientOfVariation,
                              double dominantBucketRatio) { }
}
