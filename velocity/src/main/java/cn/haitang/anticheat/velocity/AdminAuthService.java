package cn.haitang.anticheat.velocity;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class AdminAuthService {
    private static final char[] CAPTCHA_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long CAPTCHA_TTL_MILLIS = 120_000L;
    private static final int CAPTCHA_GENERATION_LIMIT = 10;
    private static final long CAPTCHA_GENERATION_WINDOW_MILLIS = 60_000L;
    private static final int MAX_CHALLENGES = 4096;
    private static final int MAX_SESSIONS = 4096;

    private final String adminToken;
    private final int captchaAfterFailures;
    private final int loginFailureLimit;
    private final long loginWindowMillis;
    private final long sessionIdleMillis;
    private final LongSupplier clock;
    private final SecureRandom random;
    private final Supplier<String> captchaAnswers;
    private final CaptchaRenderer captchaRenderer;
    private final Map<String, FailureWindow> failures = new HashMap<>();
    private final Map<String, ChallengeState> challenges = new HashMap<>();
    private final Map<String, SessionState> sessions = new HashMap<>();
    private final Map<String, RequestWindow> captchaRequests = new HashMap<>();

    AdminAuthService(String adminToken, VelocitySettings settings) {
        this(adminToken, settings.captchaAfterFailures(), settings.loginFailureLimit(),
                settings.loginWindowMillis(), settings.sessionIdleMillis(), System::currentTimeMillis,
                new SecureRandom(), null, CaptchaImageRenderer::render);
    }

    AdminAuthService(String adminToken, int captchaAfterFailures, int loginFailureLimit,
                     long loginWindowMillis, long sessionIdleMillis, LongSupplier clock,
                     SecureRandom random, Supplier<String> captchaAnswers,
                     CaptchaRenderer captchaRenderer) {
        this.adminToken = adminToken;
        this.captchaAfterFailures = captchaAfterFailures;
        this.loginFailureLimit = loginFailureLimit;
        this.loginWindowMillis = loginWindowMillis;
        this.sessionIdleMillis = sessionIdleMillis;
        this.clock = clock;
        this.random = random;
        this.captchaAnswers = captchaAnswers;
        this.captchaRenderer = captchaRenderer;
    }

    synchronized LoginResult login(String clientIp, String suppliedToken,
                                   String captchaId, String captchaAnswer) throws IOException {
        long now = clock.getAsLong();
        cleanup(now);
        FailureWindow failure = activeFailure(clientIp, now);
        if (failure != null && failure.count() >= loginFailureLimit) {
            throw rateLimited(failure, now);
        }
        if (failure != null && failure.count() >= captchaAfterFailures) {
            verifyCaptcha(clientIp, captchaId, captchaAnswer, now);
        }
        if (!constantEquals(suppliedToken, adminToken)) {
            FailureWindow updated = recordFailure(clientIp, failure, now);
            if (updated.count() >= loginFailureLimit) throw rateLimited(updated, now);
            throw new AuthFailure(401, "INVALID_TOKEN", "管理令牌无效",
                    updated.count() >= captchaAfterFailures, 0L);
        }
        failures.remove(clientIp);
        return new LoginResult(issueSessionLocked(now), sessionIdleMillis / 1000L);
    }

    synchronized CaptchaChallenge createCaptcha(String clientIp) throws IOException {
        long now = clock.getAsLong();
        cleanup(now);
        FailureWindow failure = activeFailure(clientIp, now);
        if (failure == null || failure.count() < captchaAfterFailures) {
            throw new AuthFailure(409, "CAPTCHA_NOT_REQUIRED", "当前登录无需验证码", false, 0L);
        }
        if (failure.count() >= loginFailureLimit) throw rateLimited(failure, now);
        checkCaptchaGenerationLimit(clientIp, now);
        if (challenges.size() >= MAX_CHALLENGES) {
            throw new AuthFailure(429, "RATE_LIMITED", "验证码请求过于频繁，请稍后重试",
                    true, 60L);
        }
        String answer = captchaAnswers == null ? randomAnswer() : captchaAnswers.get();
        String id = randomToken(18);
        challenges.put(id, new ChallengeState(clientIp, answer.toUpperCase(Locale.ROOT),
                now + CAPTCHA_TTL_MILLIS));
        String image = Base64.getEncoder().encodeToString(captchaRenderer.render(answer, random));
        return new CaptchaChallenge(id, "data:image/png;base64," + image,
                CAPTCHA_TTL_MILLIS / 1000L);
    }

    synchronized LoginResult issueSession() {
        long now = clock.getAsLong();
        cleanup(now);
        return new LoginResult(issueSessionLocked(now), sessionIdleMillis / 1000L);
    }

    synchronized boolean validateSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return false;
        long now = clock.getAsLong();
        cleanup(now);
        SessionState session = sessions.get(sessionToken);
        if (session == null) return false;
        sessions.put(sessionToken, new SessionState(now));
        return true;
    }

    synchronized void revokeSession(String sessionToken) {
        if (sessionToken != null) sessions.remove(sessionToken);
    }

    private void verifyCaptcha(String clientIp, String captchaId, String answer, long now) {
        if (captchaId == null || captchaId.isBlank() || answer == null || answer.isBlank()) {
            throw new AuthFailure(403, "CAPTCHA_REQUIRED", "请输入验证码", true, 0L);
        }
        ChallengeState challenge = challenges.get(captchaId);
        if (challenge == null || challenge.expiresAt() <= now || !challenge.clientIp().equals(clientIp)) {
            throw new AuthFailure(403, "CAPTCHA_INVALID", "验证码无效或已过期", true, 0L);
        }
        challenges.remove(captchaId);
        if (!constantEquals(answer.trim().toUpperCase(Locale.ROOT), challenge.answer())) {
            throw new AuthFailure(403, "CAPTCHA_INVALID", "验证码错误，请重新输入", true, 0L);
        }
    }

    private FailureWindow activeFailure(String clientIp, long now) {
        FailureWindow failure = failures.get(clientIp);
        if (failure != null && now - failure.startedAt() >= loginWindowMillis) {
            failures.remove(clientIp);
            return null;
        }
        return failure;
    }

    private FailureWindow recordFailure(String clientIp, FailureWindow current, long now) {
        FailureWindow updated = current == null
                ? new FailureWindow(now, 1)
                : new FailureWindow(current.startedAt(), current.count() + 1);
        failures.put(clientIp, updated);
        return updated;
    }

    private AuthFailure rateLimited(FailureWindow failure, long now) {
        long retryAfter = Math.max(1L,
                (failure.startedAt() + loginWindowMillis - now + 999L) / 1000L);
        return new AuthFailure(429, "RATE_LIMITED", "登录失败次数过多，请稍后重试",
                true, retryAfter);
    }

    private void checkCaptchaGenerationLimit(String clientIp, long now) {
        RequestWindow window = captchaRequests.get(clientIp);
        if (window == null || now - window.startedAt() >= CAPTCHA_GENERATION_WINDOW_MILLIS) {
            captchaRequests.put(clientIp, new RequestWindow(now, 1));
            return;
        }
        if (window.count() >= CAPTCHA_GENERATION_LIMIT) {
            long retryAfter = Math.max(1L,
                    (window.startedAt() + CAPTCHA_GENERATION_WINDOW_MILLIS - now + 999L) / 1000L);
            throw new AuthFailure(429, "RATE_LIMITED", "验证码请求过于频繁，请稍后重试",
                    true, retryAfter);
        }
        captchaRequests.put(clientIp, new RequestWindow(window.startedAt(), window.count() + 1));
    }

    private String issueSessionLocked(long now) {
        if (sessions.size() >= MAX_SESSIONS) {
            String oldest = sessions.entrySet().stream()
                    .min(Map.Entry.comparingByValue((left, right) ->
                            Long.compare(left.lastSeenAt(), right.lastSeenAt())))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) sessions.remove(oldest);
        }
        String token;
        do {
            token = randomToken(32);
        } while (sessions.containsKey(token));
        sessions.put(token, new SessionState(now));
        return token;
    }

    private String randomAnswer() {
        StringBuilder answer = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            answer.append(CAPTCHA_ALPHABET[random.nextInt(CAPTCHA_ALPHABET.length)]);
        }
        return answer.toString();
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void cleanup(long now) {
        failures.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= loginWindowMillis);
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        sessions.entrySet().removeIf(entry -> now - entry.getValue().lastSeenAt() >= sessionIdleMillis);
        captchaRequests.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt() >= CAPTCHA_GENERATION_WINDOW_MILLIS);
    }

    private static boolean constantEquals(String supplied, String expected) {
        if (supplied == null || expected == null) return false;
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    record LoginResult(String sessionToken, long expiresInSeconds) {}

    record CaptchaChallenge(String challengeId, String imageDataUrl, long expiresInSeconds) {}

    static final class AuthFailure extends RuntimeException {
        private final int status;
        private final String code;
        private final boolean captchaRequired;
        private final long retryAfterSeconds;

        private AuthFailure(int status, String code, String message,
                            boolean captchaRequired, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.code = code;
            this.captchaRequired = captchaRequired;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        int status() { return status; }
        String code() { return code; }
        boolean captchaRequired() { return captchaRequired; }
        long retryAfterSeconds() { return retryAfterSeconds; }
    }

    @FunctionalInterface
    interface CaptchaRenderer {
        byte[] render(String answer, SecureRandom random) throws IOException;
    }

    private record FailureWindow(long startedAt, int count) {}
    private record ChallengeState(String clientIp, String answer, long expiresAt) {}
    private record SessionState(long lastSeenAt) {}
    private record RequestWindow(long startedAt, int count) {}
}

final class CaptchaImageRenderer {
    private static final int WIDTH = 180;
    private static final int HEIGHT = 64;

    private CaptchaImageRenderer() {}

    static byte[] render(String answer, SecureRandom random) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(246, 248, 250));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 8; i++) {
                graphics.setColor(new Color(120 + random.nextInt(100), 120 + random.nextInt(100),
                        120 + random.nextInt(100)));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            for (int i = 0; i < answer.length(); i++) {
                int x = 17 + i * 31;
                int y = 43 + random.nextInt(7) - 3;
                AffineTransform original = graphics.getTransform();
                graphics.rotate((random.nextDouble() - 0.5D) * 0.35D, x + 10, HEIGHT / 2.0D);
                graphics.setColor(new Color(25 + random.nextInt(65), 35 + random.nextInt(70),
                        45 + random.nextInt(75)));
                graphics.drawString(String.valueOf(answer.charAt(i)), x, y);
                graphics.setTransform(original);
            }
            for (int i = 0; i < 90; i++) {
                graphics.setColor(new Color(80 + random.nextInt(150), 80 + random.nextInt(150),
                        80 + random.nextInt(150)));
                graphics.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder is unavailable");
        return output.toByteArray();
    }
}
