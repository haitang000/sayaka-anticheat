package cn.haitang.anticheat.config;

import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.EnforcementMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void rejectsReversedMovementPredictionWindowsAndCaps() {
        config.set("checks.speed.burst-window-ms", 1200);
        config.set("checks.speed.sustained-window-ms", 350);
        config.set("checks.speed.burst-max-bps", 8.0);
        config.set("checks.speed.sustained-max-bps", 12.0);

        assertTrue(ConfigSnapshot.validate(config).size() >= 2);
    }

    @Test
    void rejectsFlightGravityCheckBeforeAUsableBaselineExists() {
        config.set("checks.flight.gravity-min-air-ticks", 2);

        assertFalse(ConfigSnapshot.validate(config).isEmpty());
    }

    @Test
    void validatesNetworkIdentityAndMariaDbUrlWhenEnabled() {
        config.set("network.enabled", true);
        config.set("network.server-id", "");
        config.set("network.storage", "yaml");
        config.set("network.database.jdbc-url", "jdbc:h2:mem:test");

        assertTrue(ConfigSnapshot.validate(config).size() >= 3);
    }

    /** 摊平后的读取语义须与 YamlConfiguration 逐路径解析一致 */
    @Test
    void flattenedGettersMatchYamlSemantics() {
        ConfigSnapshot snapshot = new ConfigSnapshot(config);

        assertEquals(config.getDouble("checks.speed.max-bps"),
                snapshot.getDouble("checks.speed.max-bps"), 1.0e-9);
        assertEquals(config.getInt("punishment.kick-vl"), snapshot.getInt("punishment.kick-vl"));
        assertEquals(config.getBoolean("checks.speed.enabled"),
                snapshot.getBoolean("checks.speed.enabled", false));
        // 整数键按 Bukkit 语义可读为 double / long / string
        assertEquals(config.getDouble("punishment.kick-vl"),
                snapshot.getDouble("punishment.kick-vl"), 1.0e-9);
        assertEquals(config.getLong("punishment.kick-vl", -1L),
                snapshot.getLong("punishment.kick-vl", -1L));
        assertEquals(config.getString("config-version"), snapshot.getString("config-version"));

        // 缺失键回退默认值
        assertEquals(7.5, snapshot.getDouble("checks.speed.not-a-key", 7.5), 1.0e-9);
        assertEquals(42, snapshot.getInt("no.such.int", 42));
        assertTrue(snapshot.getBoolean("no.such.flag", true));
        assertNull(snapshot.getString("no.such.string"));
        // 类型不匹配同样回退（Bukkit 对字符串值的 getDouble 不做解析）
        config.set("weird.text", "fast");
        assertEquals(1.5, new ConfigSnapshot(config).getDouble("weird.text", 1.5), 1.0e-9);
    }

    @Test
    void listsAreCachedAndContainsSeesSectionsAndLeaves() {
        config.set("settings.disabled-worlds", List.of("w1", "w2"));
        ConfigSnapshot snapshot = new ConfigSnapshot(config);

        assertEquals(List.of("w1", "w2"), snapshot.getStringList("settings.disabled-worlds"));
        // 每次返回同一实例：热路径不再逐次复制
        assertSame(snapshot.getStringList("settings.disabled-worlds"),
                snapshot.getStringList("settings.disabled-worlds"));
        assertTrue(snapshot.getStringList("settings.no-such-list").isEmpty());
        assertEquals(config.getIntegerList("punishment.tempban-hours"),
                snapshot.getIntegerList("punishment.tempban-hours"));
        assertTrue(snapshot.isList("punishment.tempban-hours"));
        assertFalse(snapshot.isList("punishment.kick-vl"));

        assertTrue(snapshot.contains("checks.speed"));
        assertTrue(snapshot.contains("checks.speed.enabled"));
        assertFalse(snapshot.contains("checks.no-such-check"));
    }

    @Test
    void enforcementAndBedrockProfileArePrecomputed() {
        config.set("checks.reach.enforcement", "mitigate");
        config.set("checks.timer.enforcement", null);
        ConfigSnapshot snapshot = new ConfigSnapshot(config);

        assertEquals(EnforcementMode.MITIGATE, snapshot.enforcement(CheckType.REACH));
        // 未配置时落到检测项的默认执行等级
        assertEquals(CheckType.TIMER.defaultEnforcement(), snapshot.enforcement(CheckType.TIMER));
        assertEquals(ConfigSnapshot.BedrockProfile.CONSERVATIVE, snapshot.bedrockProfile());
    }
}
