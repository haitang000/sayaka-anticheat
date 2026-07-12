package cn.haitang.anticheat;

import cn.haitang.anticheat.check.CheckType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验打包进插件的 config.yml 与代码保持一致：
 * 新增检测忘了加配置段、调整惩罚阶梯破坏了递进顺序、per-check 键拼错导致静默失效，
 * 这类错误在服务器上只会表现为"某项检测悄悄不工作"，靠测试兜底。
 */
class BundledConfigTest {

    private static YamlConfiguration config;

    @BeforeAll
    static void loadBundledConfig() throws Exception {
        try (InputStream stream = BundledConfigTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(stream, "插件资源中缺少 config.yml");
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                config = YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    @Test
    void everyCheckTypeHasAConfigSectionWithEnabledSwitch() {
        for (CheckType type : CheckType.values()) {
            String path = "checks." + type.configKey();
            assertTrue(config.isConfigurationSection(path),
                    type + " 缺少配置段 " + path);
            assertTrue(config.isBoolean(path + ".enabled"),
                    path + ".enabled 缺失或不是布尔值");
        }
    }

    @Test
    void perCheckDecayKeysAllMapToRealCheckTypes() {
        ConfigurationSection section = config.getConfigurationSection("decay.per-check");
        assertNotNull(section, "缺少 decay.per-check 配置段");

        Set<String> knownKeys = java.util.Arrays.stream(CheckType.values())
                .map(CheckType::configKey)
                .collect(Collectors.toSet());
        for (String key : section.getKeys(false)) {
            assertTrue(knownKeys.contains(key),
                    "decay.per-check." + key + " 不对应任何检测项，会静默失效");
        }
    }

    @Test
    void decayRatesArePositive() {
        assertTrue(config.getDouble("decay.vl-per-second") > 0);
        assertTrue(config.getDouble("decay.hold-seconds") >= 0);
        ConfigurationSection section = config.getConfigurationSection("decay.per-check");
        for (String key : section.getKeys(false)) {
            assertTrue(section.getDouble(key) >= 0, "decay.per-check." + key + " 为负");
        }
    }

    @Test
    void punishmentLadderEscalatesInOrder() {
        double warn1 = config.getDouble("punishment.warn-1-vl");
        double mitigate = config.getDouble("punishment.mitigate-vl");
        double warn2 = config.getDouble("punishment.warn-2-vl");
        double kick = config.getDouble("punishment.kick-vl");

        assertTrue(warn1 > 0, "warn-1-vl 必须为正");
        assertTrue(mitigate > warn1, "拦截阈值应高于首次警告");
        assertTrue(warn2 > mitigate, "最后通牒应高于拦截阈值");
        assertTrue(kick > warn2, "踢出阈值应高于最后通牒");
    }

    @Test
    void warnedKickMultipliersTightenTheThresholdWithoutZeroingIt() {
        double warn1 = config.getDouble("punishment.warned-kick-multipliers.warn-1");
        double warn2 = config.getDouble("punishment.warned-kick-multipliers.warn-2");

        assertTrue(warn1 > 0 && warn1 <= 1, "warn-1 乘数应在 (0,1]");
        assertTrue(warn2 > 0 && warn2 <= 1, "warn-2 乘数应在 (0,1]");
        assertTrue(warn2 <= warn1, "警告阶段越深阈值应越低");
    }

    @Test
    void tempbanLadderIsPositiveAndStrictlyIncreasing() {
        List<Integer> ladder = config.getIntegerList("punishment.tempban-hours");
        assertFalse(ladder.isEmpty(), "tempban-hours 不能为空");
        int previous = 0;
        for (int hours : ladder) {
            assertTrue(hours > previous, "封禁时长阶梯应严格递增: " + ladder);
            previous = hours;
        }
        assertTrue(config.getInt("punishment.strikes.to-tempban") >= 1);
        assertTrue(config.getInt("punishment.strikes.window-hours") >= 1);
    }

    @Test
    void messagesReferencedFromCodeAreAllPresent() {
        // PunishmentExecutor / AlertManager / ViolationManager 直接引用的文案
        List<String> singleLine = List.of(
                "prefix", "warn-1-title", "warn-1-subtitle",
                "warn-2-title", "warn-2-subtitle",
                "kick-screen", "ban-screen",
                "broadcast-kick", "broadcast-ban", "alert");
        for (String key : singleLine) {
            String value = config.getString("messages." + key);
            assertNotNull(value, "messages." + key + " 缺失");
            assertFalse(value.isBlank(), "messages." + key + " 为空");
        }
        for (String key : List.of("warn-1-chat", "warn-2-chat")) {
            assertFalse(config.getStringList("messages." + key).isEmpty(),
                    "messages." + key + " 应为非空列表");
        }
    }
}
