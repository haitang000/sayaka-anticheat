package cn.haitang.anticheat.velocity;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Issues short-lived tokens that can each be redeemed exactly once. */
final class OneTimeLoginTokens {
    static final long DEFAULT_TTL_MILLIS = 2 * 60_000L;

    private final long ttlMillis;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> expiresAtByToken = new ConcurrentHashMap<>();

    OneTimeLoginTokens() {
        this(DEFAULT_TTL_MILLIS, System::currentTimeMillis);
    }

    OneTimeLoginTokens(long ttlMillis, LongSupplier clock) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttlMillis must be positive");
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    String issue() {
        long now = clock.getAsLong();
        expiresAtByToken.entrySet().removeIf(entry -> entry.getValue() <= now);
        byte[] bytes = new byte[32];
        String token;
        do {
            random.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (expiresAtByToken.putIfAbsent(token, now + ttlMillis) != null);
        return token;
    }

    boolean redeem(String token) {
        if (token == null || token.isBlank()) return false;
        Long expiresAt = expiresAtByToken.remove(token);
        return expiresAt != null && clock.getAsLong() < expiresAt;
    }
}
