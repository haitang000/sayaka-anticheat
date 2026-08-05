package cn.haitang.anticheat.util;

/**
 * 短时停顿检测器。
 *
 * <p>Paper 的 {@code getTPS()[0]} 是一分钟滑动平均：一次 1~2 秒的 GC 或同步区块
 * 加载几乎不会让它跌破阈值，但恰恰是这类停顿会让积压的客户端数据包在同一
 * tick/毫秒内被排空，从而拖垮所有依赖墙钟时间差的"排空型"检测。本检测器通过
 * 一个高频重复任务采样相邻执行间隔，识别最近窗口内的短时停顿。
 *
 * <p>调度任务本身会被停顿阻塞，因此停顿结束后下一次执行会记录到很大的间隔。
 * 间隔样本写入环形窗口（默认保留约 2 秒 @20tps），使得停顿信号在停顿结束后
 * 持续一段时间，覆盖积压包排空所需的窗口。
 */
public final class LagDetector implements Runnable {

    /** 单次 tick 间隔超过该值（毫秒）即视为一次停顿 */
    public static final long STALL_THRESHOLD_MILLIS = 500L;
    private static final long STALL_THRESHOLD_NANOS = STALL_THRESHOLD_MILLIS * 1_000_000L;
    /** 环形窗口容量；按 20tps 约保留 2 秒 */
    private static final int WINDOW_SIZE = 40;

    private final long[] recentIntervals = new long[WINDOW_SIZE];
    private long lastTickNanos;
    private int writeIndex;
    private int sampleCount;
    private volatile boolean lagging;

    public LagDetector() {
        this(System.nanoTime());
    }

    /** 测试用：以指定时间点为起点创建检测器 */
    public LagDetector(long initialTickNanos) {
        this.lastTickNanos = initialTickNanos;
    }

    @Override
    public void run() {
        long now = System.nanoTime();
        long interval = now - lastTickNanos;
        lastTickNanos = now;
        recordInterval(interval);
    }

    /**
     * 记录一次相邻调度间隔。正常运行时约 50ms；停顿后下一次调用会传入大间隔。
     */
    public void recordInterval(long intervalNanos) {
        synchronized (this) {
            recentIntervals[writeIndex] = intervalNanos;
            writeIndex = (writeIndex + 1) % WINDOW_SIZE;
            if (sampleCount < WINDOW_SIZE) sampleCount++;
        }
        lagging = intervalNanos > STALL_THRESHOLD_NANOS;
    }

    /**
     * 最近窗口内是否存在一次停顿。快速路径命中后保持为 true，直到连续
     * WINDOW_SIZE 次正常间隔把它刷掉。
     */
    public boolean isLagging() {
        if (lagging) return true;
        synchronized (this) {
            for (int i = 0; i < sampleCount; i++) {
                if (recentIntervals[i] > STALL_THRESHOLD_NANOS) return true;
            }
        }
        return false;
    }
}