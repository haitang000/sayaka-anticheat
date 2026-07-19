package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.data.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraCheckTest {

    /** 以给定的每 tick 位移序列构造 50ms 间隔的采样轨迹 */
    private static List<PlayerData.GlideSample> trajectory(double[][] deltas) {
        List<PlayerData.GlideSample> samples = new ArrayList<>();
        long at = 1_000;
        double x = 0;
        double y = 100;
        double z = 0;
        samples.add(new PlayerData.GlideSample(at, x, y, z));
        for (double[] delta : deltas) {
            at += 50;
            x += delta[0];
            y += delta[1];
            z += delta[2];
            samples.add(new PlayerData.GlideSample(at, x, y, z));
        }
        return samples;
    }

    private static double[][] repeat(double dx, double dy, double dz, int ticks) {
        double[][] deltas = new double[ticks][];
        for (int i = 0; i < ticks; i++) deltas[i] = new double[] {dx, dy, dz};
        return deltas;
    }

    /** 正常滑翔：缓慢下降的恒速平飞，能量单调下降 */
    @Test
    void steadyGlideLosesEnergy() {
        Double gain = ElytraCheck.energyGain(trajectory(repeat(1.0, -0.05, 0.0, 30)));
        assertTrue(gain != null && gain < 0,
                "下滑轨迹能量应为负增益，实际 " + gain);
    }

    /** 俯冲：高度换速度，动能增加但总能量不增 */
    @Test
    void diveConvertsHeightToSpeed() {
        double[][] deltas = new double[30][];
        for (int i = 0; i < 30; i++) {
            double speedUp = i / 30.0;
            deltas[i] = new double[] {0.5 + speedUp, -(0.4 + speedUp * 0.8), 0.0};
        }
        Double gain = ElytraCheck.energyGain(trajectory(deltas));
        assertTrue(gain != null && gain < 0.5,
                "俯冲不应产生明显能量净增，实际 " + gain);
    }

    /** ElytraFly：恒速爬升，能量凭空增加 */
    @Test
    void poweredClimbGainsEnergy() {
        Double gain = ElytraCheck.energyGain(trajectory(repeat(1.0, 0.2, 0.0, 30)));
        assertTrue(gain != null && gain > 2.5,
                "恒速爬升 30 刻应产生 ~6 格能量净增，实际 " + gain);
    }

    /** ElytraFly：平飞加速（无助推凭空提速） */
    @Test
    void levelAccelerationGainsEnergy() {
        double[][] deltas = new double[30][];
        for (int i = 0; i < 30; i++) {
            deltas[i] = new double[] {0.8 + i * 0.06, 0.0, 0.0};
        }
        Double gain = ElytraCheck.energyGain(trajectory(deltas));
        assertTrue(gain != null && gain > 2.5,
                "平飞从 16 提速到 51 m/s 应产生显著能量净增，实际 " + gain);
    }

    /** 样本不足不判 */
    @Test
    void tooFewSamplesReturnsNull() {
        assertNull(ElytraCheck.energyGain(trajectory(repeat(1.0, 0.0, 0.0, 4))));
    }

    /** 时间戳挤在一起（服务端卡顿批处理）不判 */
    @Test
    void bunchedTimestampsReturnNull() {
        List<PlayerData.GlideSample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(new PlayerData.GlideSample(1_000 + i, i * 1.0, 100, 0));
        }
        assertNull(ElytraCheck.energyGain(samples));
    }
}
