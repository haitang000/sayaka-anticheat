package cn.haitang.anticheat.velocity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class CaptchaService {
    private static final char[] CAPTCHA_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long CAPTCHA_TTL_MILLIS = 120_000L;
    private static final int CAPTCHA_GENERATION_LIMIT = 10;
    private static final long CAPTCHA_GENERATION_WINDOW_MILLIS = 60_000L;
    private static final int MAX_CHALLENGES = 4096;
    private static final int MAX_REQUEST_WINDOWS = 4096;
    private static final int TOKEN_GENERATION_ATTEMPTS = 32;

    private final LongSupplier clock;
    private final SecureRandom random;
    private final Supplier<String> answerSupplier;
    private final Renderer renderer;
    private final Map<String, ChallengeState> challenges = new HashMap<>();
    private final Map<String, RequestWindow> requestWindows = new HashMap<>();

    CaptchaService() {
        this(System::currentTimeMillis, new SecureRandom(), null, CaptchaImageRenderer::render);
    }

    CaptchaService(LongSupplier clock, SecureRandom random, Supplier<String> answerSupplier,
                   Renderer renderer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.answerSupplier = answerSupplier;
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    synchronized CaptchaChallenge create(String clientIp) throws IOException {
        Objects.requireNonNull(clientIp, "clientIp");
        long now = clock.getAsLong();
        cleanup(now);
        ensureChallengeCapacity(now);
        recordRequest(clientIp, now);

        String answer = normalizedAnswer(answerSupplier == null ? randomAnswer() : answerSupplier.get());
        String challengeId = uniqueToken();
        byte[] image = renderer.render(answer, random);

        challenges.put(challengeId,
                new ChallengeState(clientIp, answer, now + CAPTCHA_TTL_MILLIS));
        return new CaptchaChallenge(challengeId,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(image),
                CAPTCHA_TTL_MILLIS / 1000L);
    }

    synchronized void verify(String clientIp, String challengeId, String answer) {
        Objects.requireNonNull(clientIp, "clientIp");
        long now = clock.getAsLong();
        cleanup(now);
        if (challengeId == null || challengeId.isBlank() || answer == null || answer.isBlank()) {
            throw new CaptchaFailure(403, "CAPTCHA_REQUIRED", "请输入验证码", 0L);
        }

        ChallengeState challenge = challenges.get(challengeId);
        if (challenge == null || challenge.expiresAt() <= now
                || !challenge.clientIp().equals(clientIp)) {
            throw invalidCaptcha("验证码无效或已过期");
        }

        challenges.remove(challengeId);
        String supplied = answer.trim().toUpperCase(Locale.ROOT);
        if (!constantEquals(supplied, challenge.answer())) {
            throw invalidCaptcha("验证码错误，请重新输入");
        }
    }

    private void ensureChallengeCapacity(long now) {
        if (challenges.size() < MAX_CHALLENGES) return;
        long earliestExpiry = challenges.values().stream()
                .mapToLong(ChallengeState::expiresAt)
                .min()
                .orElse(now + CAPTCHA_TTL_MILLIS);
        throw rateLimited("验证码请求过于频繁，请稍后重试",
                secondsUntil(earliestExpiry, now));
    }

    private void recordRequest(String clientIp, long now) {
        RequestWindow window = requestWindows.get(clientIp);
        if (window == null) {
            if (requestWindows.size() >= MAX_REQUEST_WINDOWS) {
                long earliestExpiry = requestWindows.values().stream()
                        .mapToLong(value -> value.startedAt() + CAPTCHA_GENERATION_WINDOW_MILLIS)
                        .min()
                        .orElse(now + CAPTCHA_GENERATION_WINDOW_MILLIS);
                throw rateLimited("验证码请求过于频繁，请稍后重试",
                        secondsUntil(earliestExpiry, now));
            }
            requestWindows.put(clientIp, new RequestWindow(now, 1));
            return;
        }

        if (window.count() >= CAPTCHA_GENERATION_LIMIT) {
            throw rateLimited("验证码请求过于频繁，请稍后重试",
                    secondsUntil(window.startedAt() + CAPTCHA_GENERATION_WINDOW_MILLIS, now));
        }
        requestWindows.put(clientIp, new RequestWindow(window.startedAt(), window.count() + 1));
    }

    private String randomAnswer() {
        StringBuilder answer = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            answer.append(CAPTCHA_ALPHABET[random.nextInt(CAPTCHA_ALPHABET.length)]);
        }
        return answer.toString();
    }

    private static String normalizedAnswer(String answer) {
        if (answer == null) throw new IllegalStateException("Captcha answer supplier returned null");
        String normalized = answer.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 5) {
            throw new IllegalStateException("Captcha answer must contain exactly five characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            boolean allowed = false;
            for (char candidate : CAPTCHA_ALPHABET) {
                if (normalized.charAt(i) == candidate) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) throw new IllegalStateException("Captcha answer contains an ambiguous character");
        }
        return normalized;
    }

    private String uniqueToken() {
        for (int attempt = 0; attempt < TOKEN_GENERATION_ATTEMPTS; attempt++) {
            byte[] value = new byte[18];
            random.nextBytes(value);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
            if (!challenges.containsKey(token)) return token;
        }
        throw new IllegalStateException("Unable to generate a unique captcha challenge ID");
    }

    private void cleanup(long now) {
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        requestWindows.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt() >= CAPTCHA_GENERATION_WINDOW_MILLIS);
    }

    private static long secondsUntil(long deadline, long now) {
        return Math.max(1L, (deadline - now + 999L) / 1000L);
    }

    private static boolean constantEquals(String supplied, String expected) {
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static CaptchaFailure invalidCaptcha(String message) {
        return new CaptchaFailure(403, "CAPTCHA_INVALID", message, 0L);
    }

    private static CaptchaFailure rateLimited(String message, long retryAfterSeconds) {
        return new CaptchaFailure(429, "RATE_LIMITED", message, retryAfterSeconds);
    }

    @FunctionalInterface
    interface Renderer {
        byte[] render(String answer, SecureRandom random) throws IOException;
    }

    record CaptchaChallenge(String challengeId, String imageDataUrl, long expiresInSeconds) {}

    static final class CaptchaFailure extends RuntimeException {
        private final int status;
        private final String code;
        private final long retryAfterSeconds;

        private CaptchaFailure(int status, String code, String message, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        int status() { return status; }
        String code() { return code; }
        long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private record ChallengeState(String clientIp, String answer, long expiresAt) {}
    private record RequestWindow(long startedAt, int count) {}
}
