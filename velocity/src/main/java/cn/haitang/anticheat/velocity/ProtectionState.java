package cn.haitang.anticheat.velocity;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, runtime-mutable view of which backend servers enforce shared bans. The configured
 * default and {@code config.toml} overrides form the immutable base; the dashboard layers persisted
 * overrides on top so administrators can toggle protection without editing files or restarting.
 */
final class ProtectionState {

    private final boolean defaultEnabled;
    private final Map<String, Boolean> configOverrides;
    private final ConcurrentHashMap<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();

    ProtectionState(boolean defaultEnabled, Map<String, Boolean> configOverrides) {
        this.defaultEnabled = defaultEnabled;
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        configOverrides.forEach((name, enabled) -> normalized.put(normalize(name), enabled));
        this.configOverrides = Map.copyOf(normalized);
    }

    static ProtectionState fromSettings(VelocitySettings settings) {
        return new ProtectionState(settings.protectionDefaultEnabled(), settings.serverProtection());
    }

    boolean enabledFor(String serverName) {
        if (serverName == null) return defaultEnabled;
        String key = normalize(serverName);
        Boolean runtime = runtimeOverrides.get(key);
        if (runtime != null) return runtime;
        Boolean config = configOverrides.get(key);
        return config != null ? config : defaultEnabled;
    }

    boolean defaultEnabled() {
        return defaultEnabled;
    }

    boolean hasRuntimeOverride(String serverName) {
        return runtimeOverrides.containsKey(normalize(serverName));
    }

    boolean hasConfigOverride(String serverName) {
        return configOverrides.containsKey(normalize(serverName));
    }

    /**
     * Replaces every persisted runtime override, used when (re)loading from the database. Applied as
     * a prune-then-merge rather than a clear so {@link #enabledFor(String)} on the connection path
     * never briefly observes an empty override set for keys that are unchanged.
     */
    void loadRuntimeOverrides(Map<String, Boolean> overrides) {
        Map<String, Boolean> normalized = new LinkedHashMap<>();
        overrides.forEach((name, enabled) -> normalized.put(normalize(name), enabled));
        runtimeOverrides.keySet().retainAll(normalized.keySet());
        runtimeOverrides.putAll(normalized);
    }

    void setRuntimeOverride(String serverName, boolean enabled) {
        runtimeOverrides.put(normalize(serverName), enabled);
    }

    void clearRuntimeOverride(String serverName) {
        runtimeOverrides.remove(normalize(serverName));
    }

    /** Server names that carry either a config or runtime override, lower-cased. */
    Map<String, Boolean> knownOverrides() {
        Map<String, Boolean> merged = new LinkedHashMap<>(configOverrides);
        merged.putAll(runtimeOverrides);
        return merged;
    }

    private static String normalize(String serverName) {
        return serverName.trim().toLowerCase(Locale.ROOT);
    }
}
