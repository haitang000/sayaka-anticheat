package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.data.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeedCheckTest {

    @Test
    void refusesToJudgeWithFewerThanFourSamples() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        window.add(new PlayerData.MoveSample(0, 5.0));
        window.add(new PlayerData.MoveSample(500, 5.0));
        window.add(new PlayerData.MoveSample(1_000, 5.0));

        assertEquals(-1, SpeedCheck.windowBps(window), 0.0001);
    }

    @Test
    void refusesToJudgeWhenTheWindowSpansLessThan700Ms() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        for (int i = 0; i < 6; i++) {
            window.add(new PlayerData.MoveSample(i * 100L, 9.9));
        }

        // 6 个样本但总时长仅 500ms：偶发尖峰不足以定罪
        assertEquals(-1, SpeedCheck.windowBps(window), 0.0001);
    }

    @Test
    void averagesDistanceOverTheWindowDuration() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        window.add(new PlayerData.MoveSample(0, 3.0));
        window.add(new PlayerData.MoveSample(500, 3.0));
        window.add(new PlayerData.MoveSample(1_000, 3.0));
        window.add(new PlayerData.MoveSample(1_500, 3.0));

        // 1.5 秒内累计移动 12 格 → 8 m/s（低于默认上限 9.2，正常疾跑跳跃）
        assertEquals(8.0, SpeedCheck.windowBps(window), 0.0001);
    }

    @Test
    void sustainedCheatSpeedClearlyExceedsTheDefaultCap() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        for (int i = 0; i <= 15; i++) {
            // 加速客户端：每 100ms 移动 1.3 格 ≈ 13 m/s
            window.add(new PlayerData.MoveSample(i * 100L, 1.3));
        }

        double bps = SpeedCheck.windowBps(window);
        // 16 个样本合计 20.8 格 / 1.5 秒 ≈ 13.87 m/s，远超默认 9.2 上限
        assertEquals(20.8 / 1.5, bps, 0.01);
    }
}
