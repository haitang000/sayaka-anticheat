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

    /** 毫秒时间戳入口（事件级回退路径）。 */
    public static TimingStats analyze(List<Long> times, double bucketMs,
                                      double cycleToleranceMs, int maxCycleLength) {
        double[] intervals = new double[Math.min(MAX_INTERVALS, Math.max(0, times.size() - 1))];
        int count = 0;
        for (int i = times.size() - 1; i > 0 && count < intervals.length; i--) {
            double gap = times.get(i) - times.get(i - 1);
            if (gap > 250) break;
            intervals[count++] = gap;
        }
        return analyzeIntervals(intervals, count, bucketMs, cycleToleranceMs, maxCycleLength);
    }

    /** 纳秒到达时间入口（协议层路径）：保留亚毫秒精度，免疫服务端 tick 批处理。 */
    public static TimingStats analyzeNanos(long[] nanos, double bucketMs,
                                           double cycleToleranceMs, int maxCycleLength) {
        double[] intervals = new double[Math.min(MAX_INTERVALS, Math.max(0, nanos.length - 1))];
        int count = 0;
        for (int i = nanos.length - 1; i > 0 && count < intervals.length; i--) {
            double gap = (nanos[i] - nanos[i - 1]) / 1.0e6;
            if (gap > 250) break;
            intervals[count++] = gap;
        }
        return analyzeIntervals(intervals, count, bucketMs, cycleToleranceMs, maxCycleLength);
    }

    private static TimingStats analyzeIntervals(double[] intervals, int count, double bucketMs,
                                                double cycleToleranceMs, int maxCycleLength) {
        if (count < MIN_INTERVALS) return null;

        double mean = 0;
        for (int i = 0; i < count; i++) mean += intervals[i];
        mean /= count;

        double squaredSum = 0;
        for (int i = 0; i < count; i++) {
            double delta = intervals[i] - mean;
            squaredSum += delta * delta;
        }
        double stddev = Math.sqrt(squaredSum / count);

        // 偏度与一阶自相关：人手点击右偏且相邻间隔相关（运动控制反馈校正），
        // 独立同分布的随机化宏两者都趋近于零。σ≈0 时两者无定义，记 0。
        double skewness = 0.0;
        double lag1 = 0.0;
        if (stddev > 1.0e-9) {
            double cubedSum = 0;
            double lagSum = 0;
            for (int i = 0; i < count; i++) {
                double delta = intervals[i] - mean;
                cubedSum += delta * delta * delta;
                if (i + 1 < count) lagSum += delta * (intervals[i + 1] - mean);
            }
            skewness = (cubedSum / count) / (stddev * stddev * stddev);
            lag1 = lagSum / squaredSum;
        }

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
                cycle.similarity(), cycle.length(), skewness, lag1);
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
                              int cycleLength,
                              double skewness,
                              double lag1Autocorrelation) { }

    private record CycleMatch(double similarity, int length) { }
}
