package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.EnforcementMode;
import cn.haitang.anticheat.config.ConfigSnapshot;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationManagerTest {

    /** 用原始配置构造运行期快照，测试与生产走同一条读取/计算路径。 */
    private static ConfigSnapshot snapshot(YamlConfiguration values) {
        return new ConfigSnapshot(values);
    }

    @Test
    void usesDefaultThresholdsWhenMultiplierConfigurationIsMissing() {
        ConfigSnapshot config = snapshot(new YamlConfiguration());

        assertEquals(20.0, ViolationManager.effectiveKickVl(config, 0), 0.0001);
        assertEquals(18.0, ViolationManager.effectiveKickVl(config, 1), 0.0001);
    }

    @Test
    void warningMultipliersScaleWithTheConfiguredBaseThreshold() {
        YamlConfiguration values = new YamlConfiguration();
        values.set("punishment.kick-vl", 40.0);
        ConfigSnapshot config = snapshot(values);

        assertEquals(40.0, ViolationManager.effectiveKickVl(config, 0), 0.0001);
        assertEquals(36.0, ViolationManager.effectiveKickVl(config, 1), 0.0001);
    }

    @Test
    void warningMultiplierIsClampedToTheSupportedRange() {
        YamlConfiguration values = new YamlConfiguration();
        values.set("punishment.warned-kick-multipliers.warn-1", 1.5);
        ConfigSnapshot config = snapshot(values);

        assertEquals(20.0, ViolationManager.effectiveKickVl(config, 1), 0.0001);
    }

    @Test
    void nonPositiveMultiplierKeepsATinyPositiveThreshold() {
        // 校验层已把该配置限制在 (0, 1]，但计算仍对越界值兜底：
        // 夹到 0.01 下限而非 0，避免「已警告玩家任何一次违规即踢出」。
        YamlConfiguration values = new YamlConfiguration();
        values.set("punishment.kick-vl", 20.0);
        values.set("punishment.warned-kick-multipliers.warn-1", -0.5);
        ConfigSnapshot config = snapshot(values);

        assertEquals(0.2, ViolationManager.effectiveKickVl(config, 1), 1.0e-9);
    }

    @Test
    void enforcementModesSelectTheirOwnPunishmentThreshold() {
        YamlConfiguration values = new YamlConfiguration();
        values.set("punishment.kick-vl", 20.0);
        values.set("punishment.mitigate-kick-vl", 100.0);
        ConfigSnapshot config = snapshot(values);

        assertEquals(18.0, ViolationManager.punishmentThreshold(
                config, EnforcementMode.PUNISH, 1), 0.0001);
        assertEquals(100.0, ViolationManager.punishmentThreshold(
                config, EnforcementMode.MITIGATE, 2), 0.0001);
        assertEquals(Double.POSITIVE_INFINITY, ViolationManager.punishmentThreshold(
                config, EnforcementMode.ALERT, 2));
    }

    @Test
    void nextViolationAfterFinalWarningPunishesImmediately() {
        YamlConfiguration values = new YamlConfiguration();
        values.set("punishment.kick-vl", 20.0);
        values.set("punishment.mitigate-kick-vl", 100.0);
        ConfigSnapshot config = snapshot(values);

        assertFalse(ViolationManager.shouldPunish(
                config, EnforcementMode.PUNISH, 1, 12.0));
        assertFalse(ViolationManager.shouldPunish(
                config, EnforcementMode.MITIGATE, 1, 12.0));
        assertTrue(ViolationManager.shouldPunish(
                config, EnforcementMode.PUNISH, 2, 12.1));
        assertTrue(ViolationManager.shouldPunish(
                config, EnforcementMode.MITIGATE, 2, 12.1));
        assertFalse(ViolationManager.shouldPunish(
                config, EnforcementMode.ALERT, 2, 200.0));
    }

    @Test
    void clearingTheWarningStageRestoresTheBaseThreshold() {
        ConfigSnapshot config = snapshot(new YamlConfiguration());
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addVl(CheckType.SPEED, 2.0);
        data.setPunishmentWarnStage(CheckType.SPEED, 2);

        data.decay(0.0, Map.of(), 0L, 2.5);
        assertEquals(0, data.getPunishmentWarnStage(CheckType.SPEED));
        assertEquals(20.0,
                ViolationManager.effectiveKickVl(config,
                        data.getPunishmentWarnStage(CheckType.SPEED)), 0.0001);

        data.setPunishmentWarnStage(CheckType.SPEED, 2);
        data.resetAllVl();
        assertEquals(0, data.getPunishmentWarnStage(CheckType.SPEED));
        assertEquals(20.0,
                ViolationManager.effectiveKickVl(config,
                        data.getPunishmentWarnStage(CheckType.SPEED)), 0.0001);
    }

    @Test
    void perCheckRatesOnlyContainConfiguredOverridesAndClampNegatives() {
        YamlConfiguration values = new YamlConfiguration();
        values.set("decay.per-check.kill-aura", 0.1);
        values.set("decay.per-check.reach", -1.0);
        ConfigSnapshot config = snapshot(values);

        Map<CheckType, Double> rates = ViolationManager.perCheckRates(config);

        assertEquals(0.1, rates.get(CheckType.KILL_AURA), 0.0001);
        assertEquals(0.0, rates.get(CheckType.REACH), 0.0001);
        assertTrue(rates.containsKey(CheckType.REACH));
        assertFalse(rates.containsKey(CheckType.SPEED));
    }
}
