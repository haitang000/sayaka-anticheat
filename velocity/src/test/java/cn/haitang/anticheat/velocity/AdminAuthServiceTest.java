package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthServiceTest {
    private final AtomicLong now = new AtomicLong(1_000L);

    @Test
    void requiresSingleUseIpBoundCaptchaAfterFailureThreshold() throws Exception {
        AdminAuthService auth = service(3, 10, 600_000L, 43_200_000L);

        for (int attempt = 1; attempt <= 3; attempt++) {
            AdminAuthService.AuthFailure failure = assertThrows(AdminAuthService.AuthFailure.class,
                    () -> auth.login("192.0.2.1", "wrong", null, null));
            assertEquals("INVALID_TOKEN", failure.code());
            assertEquals(attempt >= 3, failure.captchaRequired());
        }
        AdminAuthService.AuthFailure required = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.login("192.0.2.1", "secret", null, null));
        assertEquals("CAPTCHA_REQUIRED", required.code());

        failThreeTimes(auth, "192.0.2.2");
        AdminAuthService.CaptchaChallenge challenge = auth.createCaptcha("192.0.2.1");
        AdminAuthService.AuthFailure wrongIp = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.login("192.0.2.2", "secret", challenge.challengeId(), "ABCDE"));
        assertEquals("CAPTCHA_INVALID", wrongIp.code());

        AdminAuthService.AuthFailure wrongAnswer = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.login("192.0.2.1", "secret", challenge.challengeId(), "XXXXX"));
        assertEquals("CAPTCHA_INVALID", wrongAnswer.code());
        AdminAuthService.AuthFailure consumed = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.login("192.0.2.1", "secret", challenge.challengeId(), "ABCDE"));
        assertEquals("CAPTCHA_INVALID", consumed.code());

        AdminAuthService.CaptchaChallenge fresh = auth.createCaptcha("192.0.2.1");
        AdminAuthService.LoginResult login = auth.login(
                "192.0.2.1", "secret", fresh.challengeId(), "abcde");
        assertTrue(auth.validateSession(login.sessionToken()));
        AdminAuthService.AuthFailure noLongerRequired = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.createCaptcha("192.0.2.1"));
        assertEquals("CAPTCHA_NOT_REQUIRED", noLongerRequired.code());

        failThreeTimes(auth, "192.0.2.3");
        AdminAuthService.CaptchaChallenge expiring = auth.createCaptcha("192.0.2.3");
        now.addAndGet(120_000L);
        AdminAuthService.AuthFailure expired = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.login("192.0.2.3", "secret", expiring.challengeId(), "ABCDE"));
        assertEquals("CAPTCHA_INVALID", expired.code());
    }

    @Test
    void locksAtFailureLimitAndResetsAfterWindow() throws Exception {
        AdminAuthService auth = service(2, 4, 1_000L, 43_200_000L);
        fail(auth, "198.51.100.8", null);
        fail(auth, "198.51.100.8", null);
        fail(auth, "198.51.100.8", auth.createCaptcha("198.51.100.8"));

        AdminAuthService.CaptchaChallenge finalChallenge = auth.createCaptcha("198.51.100.8");
        AdminAuthService.AuthFailure limited = assertThrows(AdminAuthService.AuthFailure.class,
                () -> auth.login("198.51.100.8", "wrong", finalChallenge.challengeId(), "ABCDE"));
        assertEquals(429, limited.status());
        assertEquals("RATE_LIMITED", limited.code());
        assertEquals(1L, limited.retryAfterSeconds());

        now.addAndGet(1_000L);
        AdminAuthService.LoginResult result = auth.login("198.51.100.8", "secret", null, null);
        assertTrue(auth.validateSession(result.sessionToken()));
    }

    @Test
    void refreshesIdleSessionAndSupportsRevocation() {
        AdminAuthService auth = service(3, 10, 600_000L, 500L);
        AdminAuthService.LoginResult first = auth.issueSession();
        AdminAuthService.LoginResult second = auth.issueSession();

        assertNotEquals(first.sessionToken(), second.sessionToken());
        assertTrue(first.sessionToken().matches("[A-Za-z0-9_-]{43}"));
        now.addAndGet(400L);
        assertTrue(auth.validateSession(first.sessionToken()));
        now.addAndGet(400L);
        assertTrue(auth.validateSession(first.sessionToken()));
        auth.revokeSession(first.sessionToken());
        assertFalse(auth.validateSession(first.sessionToken()));

        now.addAndGet(500L);
        assertFalse(auth.validateSession(second.sessionToken()));
    }

    @Test
    void rendersCaptchaAsPng() throws Exception {
        byte[] image = CaptchaImageRenderer.render("ABCDE", new SecureRandom());

        assertTrue(image.length > 100);
        assertEquals((byte) 0x89, image[0]);
        assertEquals('P', image[1]);
        assertEquals('N', image[2]);
        assertEquals('G', image[3]);
    }

    private AdminAuthService service(int captchaAfter, int limit,
                                     long windowMillis, long sessionIdleMillis) {
        return new AdminAuthService("secret", captchaAfter, limit, windowMillis, sessionIdleMillis,
                now::get, new SecureRandom(), () -> "ABCDE", (answer, random) -> new byte[] {1, 2, 3});
    }

    private static void failThreeTimes(AdminAuthService auth, String ip) {
        for (int i = 0; i < 3; i++) fail(auth, ip, null);
    }

    private static void fail(AdminAuthService auth, String ip,
                             AdminAuthService.CaptchaChallenge challenge) {
        assertThrows(AdminAuthService.AuthFailure.class, () -> auth.login(ip, "wrong",
                challenge == null ? null : challenge.challengeId(), challenge == null ? null : "ABCDE"));
    }
}
