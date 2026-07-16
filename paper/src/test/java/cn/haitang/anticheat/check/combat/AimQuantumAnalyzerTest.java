package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AimQuantumAnalyzerTest {

    private static final AimQuantumAnalyzer.Params PARAMS =
            new AimQuantumAnalyzer.Params(40, 6, 16, 0.5);

    /** 模拟原版客户端：float 视角按"整数计数 × 量子"累加，含真实浮点存储噪声。 */
    private static List<AimQuantumAnalyzer.Window> feedQuantized(
            AimQuantumAnalyzer analyzer, double quantum, float startYaw,
            int steps, int maxCounts, long seed) {
        Random random = new Random(seed);
        float yaw = startYaw;
        List<AimQuantumAnalyzer.Window> windows = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            int counts = 1 + random.nextInt(maxCounts);
            if (random.nextBoolean()) counts = -counts;
            yaw = (float) (yaw + counts * quantum);
            windows.addAll(analyzer.accept(yaw, 12.0f, 0, PARAMS));
        }
        return windows;
    }

    @Test
    void quantizedMidSensitivityStaysClean() {
        List<AimQuantumAnalyzer.Window> windows = feedQuantized(
                new AimQuantumAnalyzer(), 0.15, 40.0f, 200, 20, 1L);
        assertTrue(windows.size() >= 2);
        for (AimQuantumAnalyzer.Window window : windows) {
            assertEquals(AimQuantumAnalyzer.Verdict.CLEAN, window.verdict(),
                    () -> "中灵敏度合法输入被误判: " + window);
        }
    }

    @Test
    void quantizedLowestSensitivityStaysClean() {
        // 灵敏度 0 的最小量子，且 yaw 基值较大以引入 float 存储噪声
        List<AimQuantumAnalyzer.Window> windows = feedQuantized(
                new AimQuantumAnalyzer(), AimQuantumAnalyzer.MIN_LEGIT_QUANTUM,
                1500.0f, 240, 60, 2L);
        assertTrue(windows.size() >= 2);
        for (AimQuantumAnalyzer.Window window : windows) {
            assertEquals(AimQuantumAnalyzer.Verdict.CLEAN, window.verdict(),
                    () -> "低灵敏度合法输入被误判: " + window);
        }
    }

    @Test
    void quantizedHighSensitivityStaysClean() {
        // 灵敏度 100%：量子 0.6144°，梳状谱带内只剩单计数增量
        List<AimQuantumAnalyzer.Window> windows = feedQuantized(
                new AimQuantumAnalyzer(), 0.6144, -80.0f, 200, 8, 3L);
        assertTrue(windows.size() >= 2);
        for (AimQuantumAnalyzer.Window window : windows) {
            assertEquals(AimQuantumAnalyzer.Verdict.CLEAN, window.verdict(),
                    () -> "高灵敏度合法输入被误判: " + window);
        }
    }

    @Test
    void easedSmoothAimProducesMicroStepVerdict() {
        AimQuantumAnalyzer analyzer = new AimQuantumAnalyzer();
        float yaw = 10.0f;
        List<AimQuantumAnalyzer.Window> windows = new ArrayList<>();
        // 反复"缓动收敛到目标"：每刻转剩余角度的 35%，尾部扫过微步区间
        for (int acquisition = 0; acquisition < 12; acquisition++) {
            double remaining = 25.0;
            while (remaining > 0.0005) {
                double step = remaining * 0.35;
                yaw = (float) (yaw + step);
                remaining -= step;
                windows.addAll(analyzer.accept(yaw, 12.0f, 0, PARAMS));
            }
        }
        assertTrue(windows.stream().anyMatch(window ->
                        window.verdict() == AimQuantumAnalyzer.Verdict.SUSPICIOUS_MICRO),
                "缓动平滑瞄准未触发微步证据");
    }

    @Test
    void gridlessSyntheticRotationsFailCombScan() {
        AimQuantumAnalyzer analyzer = new AimQuantumAnalyzer();
        Random random = new Random(42L);
        float yaw = 30.0f;
        List<AimQuantumAnalyzer.Window> windows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            double step = 0.05 + random.nextDouble() * 0.6;
            yaw = (float) (yaw + (random.nextBoolean() ? step : -step));
            windows.addAll(analyzer.accept(yaw, 12.0f, 0, PARAMS));
        }
        assertTrue(windows.size() >= 2);
        assertTrue(windows.stream().allMatch(window ->
                        window.verdict() == AimQuantumAnalyzer.Verdict.SUSPICIOUS_GRID),
                () -> "无网格合成视角未被识别: " + windows);
    }

    @Test
    void epochChangeDiscardsCrossTeleportDelta() {
        AimQuantumAnalyzer.Params params = new AimQuantumAnalyzer.Params(8, 2, 16, 0.5);
        AimQuantumAnalyzer analyzer = new AimQuantumAnalyzer();
        float yaw = 0.0f;
        List<AimQuantumAnalyzer.Window> windows = new ArrayList<>();
        windows.addAll(analyzer.accept(yaw, 12.0f, 0, params));
        // 传送后的第一次增量落在微步区间，若未按代数丢弃将污染窗口
        windows.addAll(analyzer.accept(yaw + 0.003f, 12.0f, 1, params));
        for (int i = 0; i < 16 && windows.isEmpty(); i++) {
            yaw = (float) (yaw + 0.15);
            windows.addAll(analyzer.accept(yaw, 12.0f, 1, params));
        }
        assertEquals(1, windows.size());
        assertEquals(0, windows.get(0).microSteps(), "跨代增量未被丢弃");
        assertEquals(AimQuantumAnalyzer.Verdict.CLEAN, windows.get(0).verdict());
    }

    @Test
    void pitchNearClampBoundaryIsNotSampled() {
        AimQuantumAnalyzer.Params params = new AimQuantumAnalyzer.Params(8, 2, 16, 0.5);
        AimQuantumAnalyzer analyzer = new AimQuantumAnalyzer();
        List<AimQuantumAnalyzer.Window> windows = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            // 俯仰角贴近 ±90° 截断区，截断余量不是量子倍数，必须整体跳过
            windows.addAll(analyzer.accept(50.0f, 89.6f + (i % 2) * 0.003f, 0, params));
        }
        assertTrue(windows.isEmpty());
    }

    @Test
    void hugeYawMagnitudeIsNotSampled() {
        AimQuantumAnalyzer.Params params = new AimQuantumAnalyzer.Params(8, 2, 16, 0.5);
        AimQuantumAnalyzer analyzer = new AimQuantumAnalyzer();
        float yaw = 5000.0f;
        List<AimQuantumAnalyzer.Window> windows = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            yaw = (float) (yaw + 0.15);
            windows.addAll(analyzer.accept(yaw, 12.0f, 0, params));
        }
        assertTrue(windows.isEmpty(), "float 精度不可控的高幅值 yaw 不应参与采样");
    }

    @Test
    void combScorePerfectGridIsOne() {
        double[] deltas = {0.1, 0.2, 0.3, 0.5, 0.1, 0.4, 0.2, 0.1,
                0.3, 0.2, 0.1, 0.6, 0.2, 0.1, 0.3, 0.2};
        double score = AimQuantumAnalyzer.combScore(deltas, deltas.length, 0.1, 16);
        assertEquals(1.0, score, 1.0e-9);
    }

    @Test
    void combScoreNeedsEnoughUsableSamples() {
        double[] deltas = {0.1, 0.2, 0.3};
        assertTrue(Double.isNaN(AimQuantumAnalyzer.combScore(deltas, deltas.length, 0.1, 16)));
    }

    @Test
    void evaluatePrefersMicroVerdictOverGrid() {
        double[] deltas = new double[40];
        for (int i = 0; i < 6; i++) deltas[i] = 0.003;
        for (int i = 6; i < 40; i++) deltas[i] = 0.15 * (1 + i % 4);
        AimQuantumAnalyzer.Window window =
                AimQuantumAnalyzer.evaluate("yaw", deltas, deltas.length, PARAMS);
        assertEquals(AimQuantumAnalyzer.Verdict.SUSPICIOUS_MICRO, window.verdict());
        assertEquals(6, window.microSteps());
    }
}
