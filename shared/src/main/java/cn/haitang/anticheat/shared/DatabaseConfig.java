package cn.haitang.anticheat.shared;

import java.util.Objects;

public record DatabaseConfig(String jdbcUrl, String username, String password) {
    public DatabaseConfig {
        jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl").trim();
        username = Objects.requireNonNullElse(username, "");
        password = Objects.requireNonNullElse(password, "");
        if (jdbcUrl.isEmpty()) throw new IllegalArgumentException("jdbcUrl must not be empty");
    }
}
