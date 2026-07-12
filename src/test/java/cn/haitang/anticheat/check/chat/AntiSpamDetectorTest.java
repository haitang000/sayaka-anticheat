package cn.haitang.anticheat.check.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiSpamDetectorTest {

    private static final AntiSpamDetector.Settings SETTINGS =
            new AntiSpamDetector.Settings(4_000, 5, 15_000, 3, 4, 1_000);

    @Test
    void detectsFloodAfterConfiguredMessageLimit() {
        AntiSpamDetector detector = new AntiSpamDetector();
        for (int i = 0; i < 5; i++) {
            assertNull(detector.inspect("message " + i, i * 500L, SETTINGS));
        }

        AntiSpamDetector.Result result = detector.inspect("message 6", 2_500, SETTINGS);

        assertEquals(AntiSpamDetector.Reason.FLOOD, result.reason());
        assertTrue(result.shouldFlag());
    }

    @Test
    void detectsRepeatedTextDespiteCaseWhitespaceAndPunctuation() {
        AntiSpamDetector detector = new AntiSpamDetector();
        assertNull(detector.inspect("Join My Server!", 0, SETTINGS));
        assertNull(detector.inspect(" join-my-server ", 500, SETTINGS));

        AntiSpamDetector.Result result = detector.inspect("JOIN MY SERVER!!!", 1_000, SETTINGS);

        assertEquals(AntiSpamDetector.Reason.DUPLICATE, result.reason());
    }

    @Test
    void rateLimitsFlagsButContinuesBlockingSpam() {
        AntiSpamDetector detector = new AntiSpamDetector();
        detector.inspect("repeat me", 0, SETTINGS);
        detector.inspect("repeat me", 100, SETTINGS);
        assertTrue(detector.inspect("repeat me", 200, SETTINGS).shouldFlag());
        assertFalse(detector.inspect("repeat me", 300, SETTINGS).shouldFlag());
        assertTrue(detector.inspect("repeat me", 1_300, SETTINGS).shouldFlag());
    }
}
