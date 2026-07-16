package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.DatabaseConfig;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

record VelocitySettings(
        String serverId,
        DatabaseConfig database,
        boolean webEnabled,
        String webBind,
        int webPort,
        String webPublicUrl,
        String adminToken,
        int webThreads,
        long banCacheMillis
) {
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
        if (port < 1 || port > 65535) throw new IOException("web.port must be between 1 and 65535");
        if (threads < 1) throw new IOException("web.threads must be positive");
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
                Math.max(1L, cacheSeconds) * 1000L);
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
