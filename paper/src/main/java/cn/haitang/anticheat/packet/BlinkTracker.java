package cn.haitang.anticheat.packet;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Blink/掉包器识别模型（纯逻辑，Netty 线程在 Timeline 锁内调用）。
 *
 * Blink 客户端按住键时把移动包憋在本地，松开后一次性放出：移动流出现
 * "长暂停 → 毫秒级爆发"循环，但平均包速率不变，Timer 的余额时钟与
 * Speed 的窗口测速都看不到它。判别真卡顿与假憋包的关键是暂停期间的
 * 事务 Pong：上行抖动会把移动包和 Pong 一起拖进爆发；客户端冻结
 * （区块加载/GC）时主循环停摆，Pong 同样停发；只有故意憋移动包的
 * 客户端会在暂停期间照常返回 Pong。多个循环才产生证据，单次不上报。
 */
final class BlinkTracker {

    record Evidence(int cycles, long pauseMillis, int burstPackets) { }

    /** 暂停达到该时长才进入爆发观察（毫秒） */
    private final long minPauseNanos;
    /** 暂停超过该时长视为 AFK/切界面，不参与判定 */
    private final long maxPauseNanos;
    /** 暂停结束后多长时间内的包算同一次爆发 */
    private final long burstWindowNanos;
    /** 爆发内至少多少个移动包才算一次憋停-爆发循环 */
    private final int minBurstPackets;
    /** 暂停期间至少收到多少个 Pong 才认定连接存活 */
    private final int minLivePongs;
    /** 滚动窗口内多少次循环产生一次证据 */
    private final int cyclesToSignal;
    /** 循环统计的滚动窗口 */
    private final long cycleWindowNanos;

    private final Deque<Long> pongArrivals = new ArrayDeque<>();
    private final Deque<Long> cycleTimes = new ArrayDeque<>();

    private long lastMoveNanos;
    /** 当前正在观察的爆发：0 = 无 */
    private long burstStartNanos;
    private long burstPauseNanos;
    private boolean burstAlive;
    private int burstCount;

    BlinkTracker(long minPauseMs, long maxPauseMs, long burstWindowMs,
                 int minBurstPackets, int minLivePongs, int cyclesToSignal, long cycleWindowMs) {
        this.minPauseNanos = minPauseMs * 1_000_000L;
        this.maxPauseNanos = maxPauseMs * 1_000_000L;
        this.burstWindowNanos = burstWindowMs * 1_000_000L;
        this.minBurstPackets = minBurstPackets;
        this.minLivePongs = minLivePongs;
        this.cyclesToSignal = cyclesToSignal;
        this.cycleWindowNanos = cycleWindowMs * 1_000_000L;
    }

    /** 事务 Pong 到达（连接存活的直接证据） */
    void pong(long now) {
        pongArrivals.addLast(now);
        while (pongArrivals.size() > 64) pongArrivals.removeFirst();
    }

    /** 移动包到达；凑满循环阈值时返回证据，其余返回 null */
    Evidence movement(long now) {
        long previous = lastMoveNanos;
        lastMoveNanos = now;
        if (previous == 0L) return null;

        long gap = now - previous;
        if (gap >= minPauseNanos && gap <= maxPauseNanos) {
            // 新暂停开始一轮爆发观察；上一轮未凑满爆发数则直接作废
            burstStartNanos = now;
            burstPauseNanos = gap;
            burstAlive = pongsBetween(previous, now) >= minLivePongs;
            burstCount = 1;
            return null;
        }

        if (burstStartNanos == 0L) return null;
        if (now - burstStartNanos > burstWindowNanos) {
            burstStartNanos = 0L;
            return null;
        }
        burstCount++;
        if (burstCount < minBurstPackets || !burstAlive) return null;

        // 一次完整的"存活暂停 + 爆发"循环成立
        long pauseMillis = burstPauseNanos / 1_000_000L;
        burstStartNanos = 0L;
        cycleTimes.addLast(now);
        while (!cycleTimes.isEmpty() && now - cycleTimes.peekFirst() > cycleWindowNanos) {
            cycleTimes.removeFirst();
        }
        if (cycleTimes.size() < cyclesToSignal) return null;

        int cycles = cycleTimes.size();
        cycleTimes.clear();
        return new Evidence(cycles, pauseMillis, burstCount);
    }

    /** 传送 / 上下载具 / 服务端改写位置后，暂停-爆发序列不再可比 */
    void reset() {
        lastMoveNanos = 0L;
        burstStartNanos = 0L;
        burstCount = 0;
        burstAlive = false;
        cycleTimes.clear();
    }

    private int pongsBetween(long fromExclusive, long toExclusive) {
        int count = 0;
        for (Long arrival : pongArrivals) {
            if (arrival > fromExclusive && arrival < toExclusive) count++;
        }
        return count;
    }
}
