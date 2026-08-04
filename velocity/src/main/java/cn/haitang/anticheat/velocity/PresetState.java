package cn.haitang.anticheat.velocity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, runtime-mutable view of which preset tier (strict/balanced/lenient) each backend
 * server uses. The configured default and {@code config.toml} overrides form the immutable base;
 * the dashboard layers persisted overrides on top so administrators can set presets per server
 * without editing files or restarting.
 */
final class PresetState {

    private final String defaultPreset;
    private final Map<String, String> configOverrides;
    private final ConcurrentHashMap<String, String> runtimeOverrides = new ConcurrentHashMap<>();

    PresetState(String defaultPreset, Map<String, String> configOverrides) {
        this.defaultPreset = defaultPreset;
        Map<String, String> normalized = new LinkedHashMap<>();
        configOverrides.forEach((name, preset) -> normalized.put(normalize(name), preset));
        this.configOverrides = Map.copyOf(normalized);
    }

    static PresetState fromSettings(VelocitySettings settings) {
        return new PresetState(settings.presetDefault(), settings.serverPresets());
    }

    String presetFor(String serverName) {
        if (serverName == null) return defaultPreset;
        String key = normalize(serverName);
        String runtime = runtimeOverrides.get(key);
        if (runtime != null) return runtime;
        String config = configOverrides.get(key);
        return config != null ? config : defaultPreset;
    }

    String defaultPreset() {
        return defaultPreset;
    }

    boolean hasRuntimeOverride(String serverName) {
        return runtimeOverrides.containsKey(normalize(serverName));
    }

    boolean hasConfigOverride(String serverName) {
        return configOverrides.containsKey(normalize(serverName));
    }

    void loadRuntimeOverrides(Map<String, String> overrides) {
        Map<String, String> normalized = new LinkedHashMap<>();
        overrides.forEach((name, preset) -> normalized.put(normalize(name), preset));
        runtimeOverrides.keySet().retainAll(normalized.keySet());
        runtimeOverrides.putAll(normalized);
    }

    void setRuntimeOverride(String serverName, String preset) {
        runtimeOverrides.put(normalize(serverName), preset);
    }

    void clearRuntimeOverride(String serverName) {
        runtimeOverrides.remove(normalize(serverName));
    }

    /** Server names that carry either a config or runtime preset override, lower-cased. */
    Map<String, String> knownOverrides() {
        Map<String, String> merged = new LinkedHashMap<>(configOverrides);
        merged.putAll(runtimeOverrides);
        return merged;
    }

    private static String normalize(String serverName) {
        return serverName.trim().toLowerCase(Locale.ROOT);
    }
}
