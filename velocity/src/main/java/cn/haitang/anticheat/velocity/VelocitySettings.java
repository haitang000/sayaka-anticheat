package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.DatabaseConfig;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

record VelocitySettings(
        String serverId,
        DatabaseConfig database,
        boolean webEnabled,
        String webBind,
        int webPort,
        String webPublicUrl,
        String adminToken,
        int webThreads,
        long banCacheMillis,
        int captchaAfterFailures,
        int loginFailureLimit,
        long loginWindowMillis,
        long sessionIdleMillis,
        boolean protectionDefaultEnabled,
        Map<String, Boolean> serverProtection
) {
    VelocitySettings {
        serverProtection = Map.copyOf(serverProtection);
    }

    VelocitySettings(String serverId, DatabaseConfig database, boolean webEnabled, String webBind,
                     int webPort, String webPublicUrl, String adminToken, int webThreads,
                     long banCacheMillis) {
        this(serverId, database, webEnabled, webBind, webPort, webPublicUrl, adminToken, webThreads,
                banCacheMillis, 3, 10, 600_000L, 43_200_000L, true, Map.of());
    }

    VelocitySettings(String serverId, DatabaseConfig database, boolean webEnabled, String webBind,
                     int webPort, String webPublicUrl, String adminToken, int webThreads,
                     long banCacheMillis, int captchaAfterFailures, int loginFailureLimit,
                     long loginWindowMillis, long sessionIdleMillis) {
        this(serverId, database, webEnabled, webBind, webPort, webPublicUrl, adminToken, webThreads,
                banCacheMillis, captchaAfterFailures, loginFailureLimit, loginWindowMillis,
                sessionIdleMillis, true, Map.of());
    }

    static VelocitySettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.toml");
        if (Files.notExists(file)) {
            try (InputStream defaults = VelocitySettings.class.getResourceAsStream("/config.toml")) {
                if (defaults == null) throw new IOException("bundled config.toml is missing");
                Files.copy(defaults, file);
            }
        }
        TomlParseResult toml = Toml.parse(file);
        if (toml.hasErrors()) throw new IOException("invalid config.toml: " + toml.errors());
        int port = Math.toIntExact(toml.getLong("web.port", () -> 8080L));
        int threads = Math.toIntExact(toml.getLong("web.threads", () -> 4L));
        long cacheSeconds = toml.getLong("cache.ban-seconds", () -> 5L);
        int captchaAfterFailures = positiveInt(toml, "web.security.captcha-after-failures", 3);
        int loginFailureLimit = positiveInt(toml, "web.security.login-failure-limit", 10);
        long loginWindowMillis = secondsToMillis(toml, "web.security.login-window-seconds", 600L);
        long sessionIdleMillis = secondsToMillis(toml, "web.security.session-idle-seconds", 43_200L);
        boolean protectionDefaultEnabled = toml.getBoolean("protection.default-enabled", () -> true);
        Map<String, Boolean> serverProtection = loadServerProtection(toml);
        if (port < 1 || port > 65535) throw new IOException("web.port must be between 1 and 65535");
        if (threads < 1) throw new IOException("web.threads must be positive");
        if (loginFailureLimit <= captchaAfterFailures) {
            throw new IOException("web.security.login-failure-limit must be greater than captcha-after-failures");
        }
        String publicUrl = toml.getString("web.public-url", () -> "").trim();
        if (!publicUrl.isEmpty()) validatePublicUrl(publicUrl);
        return new VelocitySettings(
                toml.getString("server-id", () -> "velocity"),
                new DatabaseConfig(
                        toml.getString("database.jdbc-url", () -> ""),
                        toml.getString("database.username", () -> ""),
                        secret(toml.getString("database.password", () -> ""))),
                toml.getBoolean("web.enabled", () -> true),
                toml.getString("web.bind", () -> "127.0.0.1"),
                port,
                publicUrl,
                secret(toml.getString("web.admin-token", () -> "")),
                threads,
                Math.max(1L, cacheSeconds) * 1000L,
                captchaAfterFailures,
                loginFailureLimit,
                loginWindowMillis,
                sessionIdleMillis,
                protectionDefaultEnabled,
                serverProtection);
    }

    boolean protectionEnabledFor(String serverName) {
        if (serverName == null) return protectionDefaultEnabled;
        return serverProtection.getOrDefault(serverName.toLowerCase(Locale.ROOT),
                protectionDefaultEnabled);
    }

    private static Map<String, Boolean> loadServerProtection(TomlParseResult toml) throws IOException {
        TomlTable servers = toml.getTable("protection.servers");
        if (servers == null) return Map.of();
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : servers.entrySet()) {
            String serverName = entry.getKey().trim();
            if (serverName.isEmpty()) {
                throw new IOException("protection.servers keys must not be blank");
            }
            if (!(entry.getValue() instanceof Boolean enabled)) {
                throw new IOException("protection.servers." + entry.getKey() + " must be boolean");
            }
            String normalized = serverName.toLowerCase(Locale.ROOT);
            if (result.putIfAbsent(normalized, enabled) != null) {
                throw new IOException("protection.servers contains duplicate server name: " + serverName);
            }
        }
        return result;
    }

    private static int positiveInt(TomlParseResult toml, String key, int fallback) throws IOException {
        long value = toml.getLong(key, () -> (long) fallback);
        if (value <= 0 || value > Integer.MAX_VALUE) throw new IOException(key + " must be positive");
        return (int) value;
    }

    private static long secondsToMillis(TomlParseResult toml, String key, long fallback)
            throws IOException {
        long value = toml.getLong(key, () -> fallback);
        if (value <= 0) throw new IOException(key + " must be positive");
        try {
            return Math.multiplyExact(value, 1000L);
        } catch (ArithmeticException overflow) {
            throw new IOException(key + " is too large", overflow);
        }
    }

    private static void validatePublicUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String path = uri.getRawPath();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !path.equals("/"))) {
                throw new IOException("web.public-url must be an HTTP(S) origin URL");
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("web.public-url must be a valid URL", error);
        }
    }

    private static String secret(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            return System.getenv().getOrDefault(value.substring(2, value.length() - 1), "");
        }
        return value == null ? "" : value;
    }
}
