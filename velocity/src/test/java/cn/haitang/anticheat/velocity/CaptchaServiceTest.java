package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {
    private final AtomicLong now = new AtomicLong(1_000L);

    @Test
    void generatesFiveUnambiguousCharactersAndPngDataUrl() throws Exception {
        AtomicReference<String> renderedAnswer = new AtomicReference<>();
        CaptchaService service = new CaptchaService(now::get, new SecureRandom(), null,
                (answer, random) -> {
                    renderedAnswer.set(answer);
                    return new byte[] {1, 2, 3};
                });

        CaptchaService.CaptchaChallenge challenge = service.create("192.0.2.1");

        assertTrue(renderedAnswer.get().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}"));
        assertTrue(challenge.challengeId().matches("[A-Za-z0-9_-]{24}"));
        assertEquals("data:image/png;base64,AQID", challenge.imageDataUrl());
        assertEquals(120L, challenge.expiresInSeconds());
    }

    @Test
    void defaultRendererProducesPng() throws Exception {
        CaptchaService.CaptchaChallenge challenge = new CaptchaService().create("192.0.2.2");
        byte[] image = Base64.getDecoder().decode(
                challenge.imageDataUrl().substring("data:image/png;base64,".length()));

        assertTrue(image.length > 100);
        assertEquals((byte) 0x89, image[0]);
        assertEquals('P', image[1]);
        assertEquals('N', image[2]);
        assertEquals('G', image[3]);
    }

    @Test
    void acceptsCaseInsensitiveAnswerAndConsumesSuccessfulChallenge() throws Exception {
        CaptchaService service = service();
        CaptchaService.CaptchaChallenge challenge = service.create("192.0.2.3");

        service.verify("192.0.2.3", challenge.challengeId(), "  abcde  ");

        CaptchaService.CaptchaFailure replay = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.3", challenge.challengeId(), "ABCDE"));
        assertFailure(replay, 403, "CAPTCHA_INVALID", 0L);
    }

    @Test
    void missingCredentialsAreRequiredWithoutConsumingChallenge() throws Exception {
        CaptchaService service = service();
        CaptchaService.CaptchaChallenge challenge = service.create("192.0.2.4");

        CaptchaService.CaptchaFailure missingId = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.4", " ", "ABCDE"));
        assertFailure(missingId, 403, "CAPTCHA_REQUIRED", 0L);
        CaptchaService.CaptchaFailure missingAnswer = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.4", challenge.challengeId(), null));
        assertFailure(missingAnswer, 403, "CAPTCHA_REQUIRED", 0L);

        service.verify("192.0.2.4", challenge.challengeId(), "ABCDE");
    }

    @Test
    void wrongAnswerConsumesChallenge() throws Exception {
        CaptchaService service = service();
        CaptchaService.CaptchaChallenge challenge = service.create("192.0.2.5");

        CaptchaService.CaptchaFailure wrong = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.5", challenge.challengeId(), "XXXXX"));
        assertFailure(wrong, 403, "CAPTCHA_INVALID", 0L);
        CaptchaService.CaptchaFailure consumed = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.5", challenge.challengeId(), "ABCDE"));
        assertFailure(consumed, 403, "CAPTCHA_INVALID", 0L);
    }

    @Test
    void wrongIpDoesNotConsumeChallenge() throws Exception {
        CaptchaService service = service();
        CaptchaService.CaptchaChallenge challenge = service.create("192.0.2.6");

        CaptchaService.CaptchaFailure wrongIp = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.7", challenge.challengeId(), "ABCDE"));
        assertFailure(wrongIp, 403, "CAPTCHA_INVALID", 0L);

        service.verify("192.0.2.6", challenge.challengeId(), "ABCDE");
    }

    @Test
    void expiresAtTtlBoundary() throws Exception {
        CaptchaService service = service();
        CaptchaService.CaptchaChallenge beforeBoundary = service.create("192.0.2.8");
        now.addAndGet(119_999L);
        service.verify("192.0.2.8", beforeBoundary.challengeId(), "ABCDE");

        CaptchaService.CaptchaChallenge atBoundary = service.create("192.0.2.8");
        now.addAndGet(120_000L);
        CaptchaService.CaptchaFailure expired = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("192.0.2.8", atBoundary.challengeId(), "ABCDE"));
        assertFailure(expired, 403, "CAPTCHA_INVALID", 0L);
    }

    @Test
    void limitsEachIpToTenCreationsPerMinute() throws Exception {
        CaptchaService service = service();
        for (int i = 0; i < 10; i++) service.create("198.51.100.1");

        CaptchaService.CaptchaFailure limited = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.create("198.51.100.1"));
        assertFailure(limited, 429, "RATE_LIMITED", 60L);

        now.addAndGet(60_000L);
        service.create("198.51.100.1");
    }

    @Test
    void boundsGlobalChallengeAndRequestWindowState() throws Exception {
        CaptchaService service = service();
        for (int i = 0; i < 4096; i++) service.create("challenge-client-" + i);

        CaptchaService.CaptchaFailure challengeCapacity = assertThrows(
                CaptchaService.CaptchaFailure.class,
                () -> service.create("challenge-over-capacity"));
        assertFailure(challengeCapacity, 429, "RATE_LIMITED", 120L);

        now.addAndGet(120_000L);
        for (int i = 0; i < 4096; i++) {
            String clientIp = "window-client-" + i;
            CaptchaService.CaptchaChallenge challenge = service.create(clientIp);
            service.verify(clientIp, challenge.challengeId(), "ABCDE");
        }
        CaptchaService.CaptchaFailure windowCapacity = assertThrows(
                CaptchaService.CaptchaFailure.class,
                () -> service.create("window-over-capacity"));
        assertFailure(windowCapacity, 429, "RATE_LIMITED", 60L);
    }

    @Test
    void rendererFailureDoesNotLeaveChallenge() {
        FixedBytesSecureRandom random = new FixedBytesSecureRandom((byte) 7);
        CaptchaService service = new CaptchaService(now::get, random, () -> "ABCDE",
                (answer, ignored) -> { throw new IOException("render failed"); });
        String expectedId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(repeated((byte) 7, 18));

        assertThrows(IOException.class, () -> service.create("203.0.113.1"));
        CaptchaService.CaptchaFailure absent = assertThrows(CaptchaService.CaptchaFailure.class,
                () -> service.verify("203.0.113.1", expectedId, "ABCDE"));
        assertFailure(absent, 403, "CAPTCHA_INVALID", 0L);
    }

    @Test
    void concurrentVerificationSucceedsExactlyOnce() throws Exception {
        CaptchaService service = service();
        CaptchaService.CaptchaChallenge challenge = service.create("203.0.113.2");
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < workers; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        service.verify("203.0.113.2", challenge.challengeId(), "ABCDE");
                        succeeded.incrementAndGet();
                    } catch (CaptchaService.CaptchaFailure expected) {
                        assertEquals("CAPTCHA_INVALID", expected.code());
                        rejected.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, succeeded.get());
        assertEquals(workers - 1, rejected.get());
    }

    private CaptchaService service() {
        return new CaptchaService(now::get, new SecureRandom(), () -> "ABCDE",
                (answer, random) -> new byte[] {1, 2, 3});
    }

    private static void assertFailure(CaptchaService.CaptchaFailure failure, int status,
                                      String code, long retryAfterSeconds) {
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        assertEquals(retryAfterSeconds, failure.retryAfterSeconds());
    }

    private static byte[] repeated(byte value, int length) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static final class FixedBytesSecureRandom extends SecureRandom {
        private final byte value;

        private FixedBytesSecureRandom(byte value) {
            this.value = value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            java.util.Arrays.fill(bytes, value);
        }
    }
}
