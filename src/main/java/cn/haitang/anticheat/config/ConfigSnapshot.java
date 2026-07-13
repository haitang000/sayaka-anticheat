package cn.haitang.anticheat.config;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.EnforcementMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validated configuration copied away from Bukkit's mutable live config. */
public final class ConfigSnapshot {

    public enum BedrockProfile {
        NORMAL,
        CONSERVATIVE,
        EXEMPT
    }

    public record LoadResult(ConfigSnapshot snapshot, List<String> errors, boolean migrated) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private final YamlConfiguration values;

    private ConfigSnapshot(YamlConfiguration values) {
        this.values = values;
    }

    public static LoadResult load(AntiCheatPlugin plugin) {
        YamlConfiguration merged = loadBundled(plugin);
        for (Map.Entry<String, Object> entry : plugin.getConfig().getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                merged.set(entry.getKey(), entry.getValue());
            }
        }

        boolean hasVersion = plugin.getConfig().contains("config-version");
        boolean migrated = !hasVersion || (plugin.getConfig().isInt("config-version")
                && plugin.getConfig().getInt("config-version") == 1);
        if (migrated) merged.set("config-version", 2);

        List<String> errors = validate(merged);
        return new LoadResult(errors.isEmpty() ? new ConfigSnapshot(merged) : null,
                List.copyOf(errors), migrated);
    }

    private static YamlConfiguration loadBundled(AntiCheatPlugin plugin) {
        YamlConfiguration bundled = new YamlConfiguration();
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) return bundled;
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Unable to read bundled config: " + error.getMessage());
            return bundled;
        }
    }

    static List<String> validate(YamlConfiguration config) {
        List<String> errors = new ArrayList<>();
        int version = config.getInt("config-version", 0);
        if (version != 2) errors.add("config-version must be 2");

        positive(config, errors, "decay.vl-per-second");
        nonNegative(config, errors, "decay.hold-seconds");
        positive(config, errors, "punishment.warn-1-vl");
        positive(config, errors, "punishment.mitigate-vl");
        positive(config, errors, "punishment.warn-2-vl");
        positive(config, errors, "punishment.kick-vl");

        double warn1 = config.getDouble("punishment.warn-1-vl");
        double mitigate = config.getDouble("punishment.mitigate-vl");
        double warn2 = config.getDouble("punishment.warn-2-vl");
        double kick = config.getDouble("punishment.kick-vl");
        if (!(warn1 < mitigate && mitigate < warn2 && warn2 < kick)) {
            errors.add("punishment thresholds must satisfy warn-1 < mitigate < warn-2 < kick");
        }

        boundedExclusiveZero(config, errors, "punishment.warned-kick-multipliers.warn-1", 1.0);
        boundedExclusiveZero(config, errors, "punishment.warned-kick-multipliers.warn-2", 1.0);
        positiveInt(config, errors, "punishment.strikes.window-hours");
        positiveInt(config, errors, "punishment.strikes.to-tempban");

        List<Integer> ladder = config.getIntegerList("punishment.tempban-hours");
        if (ladder.isEmpty()) {
            errors.add("punishment.tempban-hours must not be empty");
        } else {
            int previous = 0;
            for (int hours : ladder) {
                if (hours <= previous) {
                    errors.add("punishment.tempban-hours must be positive and strictly increasing");
                    break;
                }
                previous = hours;
            }
        }

        String bedrock = config.getString("settings.bedrock-profile", "conservative");
        try {
            BedrockProfile.valueOf(bedrock.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            errors.add("settings.bedrock-profile must be normal, conservative, or exempt");
        }

        positiveInt(config, errors, "settings.packet-analysis.queue-capacity");
        positiveInt(config, errors, "settings.packet-analysis.completions-per-tick");
        positiveInt(config, errors, "settings.packet-analysis.history-per-player");
        nonNegativeInt(config, errors, "settings.join-grace-seconds");
        nonNegativeInt(config, errors, "settings.parallel-analysis.threads");
        positiveInt(config, errors, "settings.parallel-analysis.queue-capacity");
        positiveInt(config, errors, "settings.parallel-analysis.completions-per-tick");

        ConfigurationSection decayOverrides = config.getConfigurationSection("decay.per-check");
        if (decayOverrides != null) {
            for (Map.Entry<String, Object> entry : decayOverrides.getValues(false).entrySet()) {
                if (!(entry.getValue() instanceof Number number)
                        || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) {
                    errors.add("decay.per-check." + entry.getKey()
                            + " must be finite and non-negative");
                }
            }
        }

        for (CheckType type : CheckType.values()) {
            String base = "checks." + type.configKey();
            if (!config.isConfigurationSection(base)) {
                errors.add(base + " must be a configuration section");
                continue;
            }
            String configured = config.getString(base + ".enforcement");
            if (configured != null && !configured.equalsIgnoreCase("alert")
                    && !configured.equalsIgnoreCase("mitigate")
                    && !configured.equalsIgnoreCase("punish")) {
                errors.add(base + ".enforcement must be alert, mitigate, or punish");
            }
        }

        ConfigurationSection checks = config.getConfigurationSection("checks");
        if (checks != null) {
            for (Map.Entry<String, Object> entry : checks.getValues(true).entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    double value = number.doubleValue();
                    if (!Double.isFinite(value) || value < 0) {
                        errors.add("checks." + entry.getKey() + " must be finite and non-negative");
                    }
                }
            }
        }

        for (String path : List.of(
                "checks.speed.max-bps", "checks.speed.buffer-to-flag",
                "checks.flight.max-jump", "checks.flight.buffer-to-flag",
                "checks.ground-spoof.buffer-to-flag",
                "checks.timer.max-packets-per-second", "checks.timer.buffer-to-flag",
                "checks.fast-ladder.max-climb-per-move", "checks.fast-ladder.buffer-to-flag",
                "checks.step.max-height-per-move", "checks.step.min-horizontal-per-move",
                "checks.step.buffer-to-flag",
                "checks.liquid-walk.min-horizontal-per-move",
                "checks.liquid-walk.max-vertical-per-move",
                "checks.liquid-walk.buffer-to-flag",
                "checks.reach.base-reach", "checks.reach.buffer-to-flag",
                "checks.kill-aura.buffer-to-flag",
                "checks.auto-clicker.max-cps", "checks.auto-clicker.buffer-to-flag",
                "checks.no-swing.buffer-to-flag", "checks.criticals.buffer-to-flag",
                "checks.velocity.min-kb-velocity", "checks.velocity.min-displacement",
                "checks.velocity.min-response-ratio", "checks.velocity.buffer-to-flag",
                "checks.auto-totem.buffer-to-flag",
                "checks.inventory-move.max-distance", "checks.inventory-move.buffer-to-flag",
                "checks.no-slow.max-using-bps", "checks.no-slow.buffer-to-flag",
                "checks.fast-use.default-min-ms", "checks.fast-use.fast-food-min-ms",
                "checks.fast-use.buffer-to-flag",
                "checks.fast-bow.minimum-force", "checks.fast-bow.full-charge-min-ms",
                "checks.fast-bow.buffer-to-flag",
                "checks.chest-stealer.window-ms", "checks.chest-stealer.max-actions-per-window",
                "checks.chest-stealer.buffer-to-flag",
                "checks.fast-break.lenience",
                "checks.scaffold.max-place-per-second", "checks.scaffold.tower-max-per-second",
                "checks.scaffold.buffer-to-flag",
                "checks.anti-spam.flood-window-ms", "checks.anti-spam.max-messages",
                "checks.anti-spam.duplicate-window-ms", "checks.anti-spam.max-duplicates",
                "checks.anti-ads.flag-weight")) {
            positive(config, errors, path);
        }
        return errors;
    }

    private static void positive(YamlConfiguration config, List<String> errors, String path) {
        if (!config.isDouble(path) && !config.isInt(path)) errors.add(path + " must be numeric");
        else if (!Double.isFinite(config.getDouble(path)) || config.getDouble(path) <= 0) {
            errors.add(path + " must be greater than 0");
        }
    }

    private static void nonNegative(YamlConfiguration config, List<String> errors, String path) {
        if (!Double.isFinite(config.getDouble(path)) || config.getDouble(path) < 0) {
            errors.add(path + " must be at least 0");
        }
    }

    private static void positiveInt(YamlConfiguration config, List<String> errors, String path) {
        if (!config.isInt(path) || config.getInt(path) <= 0) errors.add(path + " must be a positive integer");
    }

    private static void nonNegativeInt(YamlConfiguration config, List<String> errors, String path) {
        if (!config.isInt(path) || config.getInt(path) < 0) {
            errors.add(path + " must be a non-negative integer");
        }
    }

    private static void boundedExclusiveZero(YamlConfiguration config, List<String> errors,
                                             String path, double max) {
        double value = config.getDouble(path);
        if (!Double.isFinite(value) || value <= 0 || value > max) {
            errors.add(path + " must be in (0, " + max + "]");
        }
    }

    public boolean getBoolean(String path) { return values.getBoolean(path); }
    public boolean getBoolean(String path, boolean fallback) { return values.getBoolean(path, fallback); }
    public int getInt(String path) { return values.getInt(path); }
    public int getInt(String path, int fallback) { return values.getInt(path, fallback); }
    public long getLong(String path, long fallback) { return values.getLong(path, fallback); }
    public double getDouble(String path) { return values.getDouble(path); }
    public double getDouble(String path, double fallback) { return values.getDouble(path, fallback); }
    public String getString(String path) { return values.getString(path); }
    public String getString(String path, String fallback) { return values.getString(path, fallback); }
    public List<String> getStringList(String path) { return List.copyOf(values.getStringList(path)); }
    public List<Integer> getIntegerList(String path) { return List.copyOf(values.getIntegerList(path)); }
    public boolean isList(String path) { return values.isList(path); }
    public boolean contains(String path) { return values.contains(path); }

    public EnforcementMode enforcement(CheckType type) {
        String path = "checks." + type.configKey() + ".enforcement";
        return EnforcementMode.parse(values.getString(path), type.defaultEnforcement());
    }

    public BedrockProfile bedrockProfile() {
        return BedrockProfile.valueOf(values.getString("settings.bedrock-profile", "conservative")
                .toUpperCase(Locale.ROOT));
    }
}
