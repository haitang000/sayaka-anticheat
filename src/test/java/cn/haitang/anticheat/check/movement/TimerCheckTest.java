package cn.haitang.anticheat.check.movement;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimerCheckTest {

    private static final long MIN_GAP_MS = 25;

    @Test
    void countsEvenlySpacedMovePackets() {
        Deque<Long> times = new ArrayDeque<>();

        assertEquals(1, TimerCheck.recordMove(times, 1_000, MIN_GAP_MS));
        assertEquals(2, TimerCheck.recordMove(times, 1_050, MIN_GAP_MS));
        assertEquals(3, TimerCheck.recordMove(times, 1_100, MIN_GAP_MS));
    }

    @Test
    void dropsBackloggedPacketsArrivingWithinTheMinimumGap() {
        Deque<Long> times = new ArrayDeque<>();
        TimerCheck.recordMove(times, 1_000, MIN_GAP_MS);

        // 卡顿恢复时集中处理的积压包：到达时间几乎相同，不计入窗口
        assertEquals(-1, TimerCheck.recordMove(times, 1_000, MIN_GAP_MS));
        assertEquals(-1, TimerCheck.recordMove(times, 1_024, MIN_GAP_MS));
        assertEquals(1, times.size());

        // 恰好达到最小间隔的包正常计数
        assertEquals(2, TimerCheck.recordMove(times, 1_025, MIN_GAP_MS));
    }

    @Test
    void trimsPacketsThatFellOutOfTheRollingWindow() {
        Deque<Long> times = new ArrayDeque<>();
        TimerCheck.recordMove(times, 1_000, MIN_GAP_MS);
        TimerCheck.recordMove(times, 2_000, MIN_GAP_MS);
        TimerCheck.recordMove(times, 3_000, MIN_GAP_MS);

        // 窗口 3 秒：4000ms 时首包(1000)恰好在边界上，保留
        assertEquals(4, TimerCheck.recordMove(times, 4_000, MIN_GAP_MS));
        // 4030ms 再来一包，1000ms 的首包滑出窗口
        assertEquals(4, TimerCheck.recordMove(times, 4_030, MIN_GAP_MS));
        assertEquals(Long.valueOf(2_000), times.peekFirst());
    }

    @Test
    void packetPathCountsBurstArrivalsWithoutDeduplication() {
        Deque<Long> times = new ArrayDeque<>();

        // 包级路径 minGap=0：网络突发簇内的包到达时间几乎相同，
        // 但都是客户端真实发出的包，必须全部计入，否则高倍速 Timer 会被去重掩盖
        assertEquals(1, TimerCheck.recordMove(times, 1_000, 0));
        assertEquals(2, TimerCheck.recordMove(times, 1_000, 0));
        assertEquals(3, TimerCheck.recordMove(times, 1_005, 0));
    }

    @Test
    void timerSpeedShowsUpAsInflatedWindowCount() {
        Deque<Long> times = new ArrayDeque<>();
        // 2 倍速 Timer：3 秒窗口内以 25ms 间隔发出 40/秒 的移动包
        int last = 0;
        for (int i = 0; i < 120; i++) {
            last = TimerCheck.recordMove(times, 10_000 + i * 25L, MIN_GAP_MS);
        }
        // 默认上限 24/秒 × 3 秒 = 72，2 倍速的窗口计数明显超标
        assertEquals(120, last);
    }
}
