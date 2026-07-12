package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimTrackingAnalyzerTest {

    @Test
    void reportsNearPerfectTrackingAsLowVariation() {
        AimTrackingAnalyzer.TrackingStats stats = AimTrackingAnalyzer.analyze(
                List.of(0.31, 0.28, 0.34, 0.30, 0.29, 0.33, 0.31, 0.30));

        assertEquals(0.3075, stats.mean(), 1.0E-9);
        assertTrue(stats.stddev() < 0.02);
        assertEquals(0.06, stats.range(), 1.0E-9);
    }

    @Test
    void humanLikeAimHasMeaningfulErrorSpread() {
        AimTrackingAnalyzer.TrackingStats stats = AimTrackingAnalyzer.analyze(
                List.of(0.4, 1.8, 0.9, 2.4, 0.7, 1.3, 2.0, 0.5));

        assertTrue(stats.stddev() > 0.6);
        assertTrue(stats.range() > 1.5);
    }
}
