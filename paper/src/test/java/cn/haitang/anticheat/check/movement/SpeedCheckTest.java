package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedCheckTest {

    @Test
    void refusesToJudgeWithoutThreeMeasuredIntervals() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        window.add(new PlayerData.MoveSample(0, 5.0));
        window.add(new PlayerData.MoveSample(150, 5.0));
        window.add(new PlayerData.MoveSample(300, 5.0));

        assertEquals(-1, SpeedCheck.windowBps(window, 350), 0.0001);
    }

    @Test
    void refusesToJudgeBeforeMostOfTheWindowHasElapsed() {
        Deque<PlayerData.MoveSample> window = samples(0, 250, 50, 0.6);

        assertEquals(-1, SpeedCheck.windowBps(window, 350), 0.0001);
    }

    @Test
    void treatsTheFirstSampleAsAnAnchorInsteadOfDistance() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        window.add(new PlayerData.MoveSample(0, 99.0));
        for (long at = 50; at <= 350; at += 50) {
            window.add(new PlayerData.MoveSample(at, 0.4));
        }

        assertEquals(8.0, SpeedCheck.windowBps(window, 350), 0.0001);
    }

    @Test
    void normalizesBackloggedSamplesToOneClientTickEach() {
        Deque<PlayerData.MoveSample> window = new ArrayDeque<>();
        window.add(new PlayerData.MoveSample(0, 0.4));
        window.add(new PlayerData.MoveSample(50, 0.4));
        window.add(new PlayerData.MoveSample(100, 0.4));
        for (int i = 0; i < 6; i++) {
            window.add(new PlayerData.MoveSample(300, 0.4));
        }

        assertEquals(8.0, SpeedCheck.windowBps(window, 350), 0.0001);
    }

    @Test
    void distinguishesNormalSprintFromSustainedLowSpeedCheat() {
        double normal = SpeedCheck.windowBps(samples(0, 1200, 50, 0.355), 1200);
        double cheated = SpeedCheck.windowBps(samples(0, 1200, 50, 0.43), 1200);

        assertEquals(7.1, normal, 0.0001);
        assertEquals(8.6, cheated, 0.0001);
        assertTrue(normal < 8.2);
        assertTrue(cheated > 8.2);
    }

    @Test
    void detectsShortBurstAndHonorsTrailingWindowBoundary() {
        Deque<PlayerData.MoveSample> window = samples(-50, 350, 50, 0.65);
        window.addFirst(new PlayerData.MoveSample(-51, 50.0));

        assertEquals(13.0, SpeedCheck.windowBps(window, 350), 0.0001);
    }

    @Test
    void rotationOnlyPacketsAreNotPositionChanges() {
        assertFalse(MovementTracker.isPositionChange(0.0, 0.0, 0.0));
        assertTrue(MovementTracker.isPositionChange(0.0, 0.0001, 0.0));
    }

    private static Deque<PlayerData.MoveSample> samples(long start, long end,
                                                         long step, double distance) {
        Deque<PlayerData.MoveSample> samples = new ArrayDeque<>();
        for (long at = start; at <= end; at += step) {
            samples.add(new PlayerData.MoveSample(at, distance));
        }
        return samples;
    }
}
