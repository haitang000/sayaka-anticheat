package cn.haitang.anticheat.check.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * 旋转量化分析（纯数学，不访问 Bukkit 状态；实例方法在 Netty 线程调用，内部同步）。
 *
 * 原版客户端把鼠标整数计数换算成视角增量：
 * 量子 q = (灵敏度 × 0.6 + 0.2)^3 × 8 × 0.15，灵敏度 ∈ [0,1] → q ∈ [0.0096°, 0.6144°]，
 * 一刻内的视角变化 = 计数和 × q，必为同一量子的整数倍。作弊客户端直接写入
 * 计算出的实数视角（平滑瞄准、加噪声的"人性化"瞄准都一样），留下两类破绽：
 *
 * 1. 微步：小于最小量子（0.0096° 减浮点噪声）的增量。任何灵敏度都造不出来，
 *    而平滑瞄准的缓动尾部每次收敛都会扫过这个区间。
 * 2. 无网格：中等增量不落在任何 q ≥ 0.009° 的等距网格上。用梳状谱扫描候选
 *    量子并给相位余弦打分——合法输入在真实量子处得分≈1，合成视角在所有
 *    候选处都接近 0。谱峰阈值远低于合法得分，浮点存储噪声不会误伤。
 *
 * 已知的罕见误报源：电影视角平滑、手柄/触控板模组、观察镜缩放灵敏度——
 * 都几乎不可能出现在近战命中期间，由战斗窗口门控 + 连续窗口缓冲吸收，
 * 且子检测可整体关闭。
 */
final class AimQuantumAnalyzer {

    /** 灵敏度 0 时的最小合法量子（度） */
    static final double MIN_LEGIT_QUANTUM = 0.0096;
    /** 微步证据带（度）：下界高于浮点噪声，上界与最小量子留出安全边距 */
    static final double MICRO_MIN = 0.001;
    static final double MICRO_MAX = 0.0055;
    /** 梳状谱测试的增量带（度）：上界限制倍数范围，保证候选扫描分辨率充足 */
    static final double COMB_MIN = 0.008;
    static final double COMB_MAX = 0.7;
    /** 候选量子扫描范围与几何步长；下界略低于最小合法量子 */
    static final double CANDIDATE_MIN = 0.009;
    static final double CANDIDATE_MAX = 0.72;
    static final double COARSE_RATIO = 1.005;
    static final double REFINE_RATIO = 1.0005;
    /** 单个增量允许的最大量子倍数；配合 COMB_MAX 限定相位漂移 */
    static final int MAX_MULTIPLE = 80;
    /** 超过该幅度的增量来自整臂甩枪，对两类测试都无信息量 */
    static final double MAX_DELTA = 20.0;
    /** yaw 幅值门限：float 在 2048 处 ULP ≈ 2.4e-4°，再大则存储噪声不可控 */
    static final double MAX_YAW_MAGNITUDE = 2048.0;
    /** pitch 在 ±90° 被客户端截断，截断余量不是量子倍数，靠近边界不采样 */
    static final double MAX_PITCH_MAGNITUDE = 89.5;

    enum Verdict { CLEAN, SUSPICIOUS_MICRO, SUSPICIOUS_GRID }

    /** 可调参数由 AimCheck 从配置缓存后传入，其余阈值为推导常量。 */
    record Params(int windowSize, int microCountToSuspect, int combMinSamples,
                  double suspectScore) {}

    record Window(String stream, int samples, int microSteps, int combSamples,
                  double bestScore, double bestQuantum, Verdict verdict) {}

    private static final class Stream {
        private final String name;
        private double last = Double.NaN;
        private double[] deltas = new double[0];
        private int count;

        private Stream(String name) {
            this.name = name;
        }
    }

    private final Stream yaw = new Stream("yaw");
    private final Stream pitch = new Stream("pitch");
    private int epoch = Integer.MIN_VALUE;

    /**
     * 接收一次旋转包的视角。返回本次采样凑满的窗口结论（0-2 个）。
     * 客户端不回绕 yaw，相邻包的差值即客户端内部累加的原始增量。
     */
    synchronized List<Window> accept(float yawValue, float pitchValue,
                                     int rotationEpoch, Params params) {
        if (rotationEpoch != epoch) {
            // 服务端改写过视角：跨代差值不是鼠标输入，丢弃一次基准
            epoch = rotationEpoch;
            yaw.last = Double.NaN;
            pitch.last = Double.NaN;
        }
        List<Window> results = null;
        results = push(yaw, yawValue, MAX_YAW_MAGNITUDE, params, results);
        results = push(pitch, pitchValue, MAX_PITCH_MAGNITUDE, params, results);
        return results == null ? List.of() : results;
    }

    private static List<Window> push(Stream stream, double value, double magnitudeLimit,
                                     Params params, List<Window> results) {
        if (!Double.isFinite(value)) {
            stream.last = Double.NaN;
            return results;
        }
        double previous = stream.last;
        stream.last = value;
        if (Double.isNaN(previous)) return results;
        if (Math.abs(previous) > magnitudeLimit || Math.abs(value) > magnitudeLimit) return results;

        double delta = Math.abs(value - previous);
        if (delta < MICRO_MIN || delta > MAX_DELTA) return results;

        int windowSize = Math.max(8, params.windowSize());
        if (stream.deltas.length != windowSize) {
            stream.deltas = new double[windowSize];
            stream.count = 0;
        }
        stream.deltas[stream.count++] = delta;
        if (stream.count < windowSize) return results;

        Window window = evaluate(stream.name, stream.deltas, stream.count, params);
        stream.count = 0;
        if (results == null) results = new ArrayList<>(2);
        results.add(window);
        return results;
    }

    /** 对一个采样窗口做微步计数与梳状谱扫描；纯函数，供单元测试直接调用。 */
    static Window evaluate(String stream, double[] deltas, int count, Params params) {
        int micro = 0;
        double[] comb = new double[count];
        int combCount = 0;
        for (int i = 0; i < count; i++) {
            if (deltas[i] <= MICRO_MAX) micro++;
            if (deltas[i] >= COMB_MIN && deltas[i] <= COMB_MAX) comb[combCount++] = deltas[i];
        }

        double bestScore = Double.NaN;
        double bestQuantum = Double.NaN;
        int combMinSamples = Math.max(8, params.combMinSamples());
        if (combCount >= combMinSamples) {
            double coarseBest = Double.NEGATIVE_INFINITY;
            double coarseBestQ = Double.NaN;
            for (double q = CANDIDATE_MIN; q <= CANDIDATE_MAX; q *= COARSE_RATIO) {
                double score = combScore(comb, combCount, q, combMinSamples);
                if (!Double.isNaN(score) && score > coarseBest) {
                    coarseBest = score;
                    coarseBestQ = q;
                }
            }
            if (!Double.isNaN(coarseBestQ)) {
                bestScore = coarseBest;
                bestQuantum = coarseBestQ;
                double high = coarseBestQ * COARSE_RATIO;
                for (double q = coarseBestQ / COARSE_RATIO; q <= high; q *= REFINE_RATIO) {
                    double score = combScore(comb, combCount, q, combMinSamples);
                    if (!Double.isNaN(score) && score > bestScore) {
                        bestScore = score;
                        bestQuantum = q;
                    }
                }
            }
        }

        Verdict verdict = Verdict.CLEAN;
        if (micro >= Math.max(2, params.microCountToSuspect())) {
            verdict = Verdict.SUSPICIOUS_MICRO;
        } else if (!Double.isNaN(bestScore) && bestScore < params.suspectScore()) {
            verdict = Verdict.SUSPICIOUS_GRID;
        }
        return new Window(stream, count, micro, combCount, bestScore, bestQuantum, verdict);
    }

    /**
     * 候选量子 q 的梳状谱得分：各增量归一化到量子倍数后的相位余弦均值。
     * 完美网格 → 1；无网格 → 期望 0、方差 1/(2n)。可用样本不足返回 NaN。
     */
    static double combScore(double[] deltas, int count, double q, int minSamples) {
        double sum = 0.0;
        int used = 0;
        for (int i = 0; i < count; i++) {
            double multiple = deltas[i] / q;
            long nearest = Math.round(multiple);
            if (nearest < 1 || nearest > MAX_MULTIPLE) continue;
            sum += Math.cos(2.0 * Math.PI * (multiple - nearest));
            used++;
        }
        return used < minSamples ? Double.NaN : sum / used;
    }
}
