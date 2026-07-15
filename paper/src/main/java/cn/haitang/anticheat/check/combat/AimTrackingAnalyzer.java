package cn.haitang.anticheat.check.combat;

import java.util.List;

/** Pure statistical analysis for repeated target-center aim errors. */
final class AimTrackingAnalyzer {

    private AimTrackingAnalyzer() { }

    static TrackingStats analyze(List<Double> errors) {
        if (errors.isEmpty()) return null;
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double error : errors) {
            sum += error;
            min = Math.min(min, error);
            max = Math.max(max, error);
        }
        double mean = sum / errors.size();
        double variance = 0.0;
        for (double error : errors) {
            double delta = error - mean;
            variance += delta * delta;
        }
        return new TrackingStats(mean, Math.sqrt(variance / errors.size()), max - min);
    }

    record TrackingStats(double mean, double stddev, double range) { }
}
