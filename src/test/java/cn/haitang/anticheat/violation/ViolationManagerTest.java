package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.EnforcementMode;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationManagerTest {

    @Test
    void usesDefaultThresholdsWhenMultiplierConfigurationIsMissing() {
        YamlConfiguration config = new YamlConfiguration();

        assertEquals(20.0, ViolationManager.effectiveKickVl(config, 0), 0.0001);
        assertEquals(18.0, ViolationManager.effectiveKickVl(config, 1), 0.0001);
        assertEquals(15.0, ViolationManager.effectiveKickVl(config, 2), 0.0001);
    }

    @Test
    void warningMultipliersScaleWithTheConfiguredBaseThreshold() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("punishment.kick-vl", 40.0);

        assertEquals(40.0, ViolationManager.effectiveKickVl(config, 0), 0.0001);
        assertEquals(36.0, ViolationManager.effectiveKickVl(config, 1), 0.0001);
        assertEquals(30.0, ViolationManager.effectiveKickVl(config, 2), 0.0001);
    }

    @Test
    void warningMultipliersAreClampedToTheSupportedRange() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("punishment.warned-kick-multipliers.warn-1", 1.5);
        config.set("punishment.warned-kick-multipliers.warn-2", -0.5);

        assertEquals(20.0, ViolationManager.effectiveKickVl(config, 1), 0.0001);
        assertEquals(0.0, ViolationManager.effectiveKickVl(config, 2), 0.0001);
    }

    @Test
    void enforcementModesSelectTheirOwnPunishmentThreshold() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("punishment.kick-vl", 20.0);
        config.set("punishment.mitigate-kick-vl", 100.0);

        assertEquals(15.0, ViolationManager.punishmentThreshold(
                config, EnforcementMode.PUNISH, 2), 0.0001);
        assertEquals(100.0, ViolationManager.punishmentThreshold(
                config, EnforcementMode.MITIGATE, 2), 0.0001);
        assertEquals(Double.POSITIVE_INFINITY, ViolationManager.punishmentThreshold(
                config, EnforcementMode.ALERT, 2));
    }

    @Test
    void clearingTheWarningStageRestoresTheBaseThreshold() {
        YamlConfiguration config = new YamlConfiguration();
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
        YamlConfiguration config = new YamlConfiguration();
        config.set("decay.per-check.kill-aura", 0.1);
        config.set("decay.per-check.reach", -1.0);

        Map<CheckType, Double> rates = ViolationManager.perCheckRates(config);

        assertEquals(0.1, rates.get(CheckType.KILL_AURA), 0.0001);
        assertEquals(0.0, rates.get(CheckType.REACH), 0.0001);
        assertTrue(rates.containsKey(CheckType.REACH));
        assertFalse(rates.containsKey(CheckType.SPEED));
    }
}
