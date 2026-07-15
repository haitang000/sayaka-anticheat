package cn.haitang.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSnapshotTest {

    private YamlConfiguration config;

    @BeforeEach
    void loadConfig() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml");
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(reader);
        }
        assertTrue(ConfigSnapshot.validate(config).isEmpty());
    }

    @Test
    void rejectsZeroDetectionThreshold() {
        config.set("checks.speed.max-bps", 0);
        assertFalse(ConfigSnapshot.validate(config).isEmpty());
    }

    @Test
    void rejectsNegativeNumericCheckSettings() {
        config.set("checks.reach.cancel-margin", -0.1);
        config.set("checks.anti-spam.flag-cooldown-ms", -1);
        assertTrue(ConfigSnapshot.validate(config).size() >= 2);
    }

    @Test
    void rejectsZeroThresholdsAcrossCheckFamilies() {
        config.set("checks.velocity.min-response-ratio", 0);
        config.set("checks.chest-stealer.max-actions-per-window", 0);
        assertTrue(ConfigSnapshot.validate(config).size() >= 2);
    }

    @Test
    void rejectsUnsafePunishmentConfiguration() {
        config.set("punishment.strikes.to-tempban", 0);
        config.set("punishment.tempban-hours", java.util.List.of(6, -1));
        assertTrue(ConfigSnapshot.validate(config).size() >= 2);
    }

    @Test
    void rejectsUnknownEnforcementAndBedrockModes() {
        config.set("checks.reach.enforcement", "ban-now");
        config.set("settings.bedrock-profile", "guess");
        assertTrue(ConfigSnapshot.validate(config).size() >= 2);
    }

    @Test
    void rejectsInvalidRuntimeAndDecaySettings() {
        config.set("settings.parallel-analysis.threads", -1);
        config.set("decay.per-check.reach", -0.1);
        assertTrue(ConfigSnapshot.validate(config).size() >= 2);
    }

    @Test
    void rejectsInvalidAutoClickerThresholds() {
        config.set("checks.auto-clicker.hard-max-cps", 20);
        config.set("checks.auto-clicker.cycle-min-similarity", 1.1);
        config.set("checks.auto-clicker.cycle-max-length", 1);
        assertTrue(ConfigSnapshot.validate(config).size() >= 3);
    }
}
