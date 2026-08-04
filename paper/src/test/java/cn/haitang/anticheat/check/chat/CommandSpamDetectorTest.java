package cn.haitang.anticheat.check.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSpamDetectorTest {

    private static final CommandSpamDetector.Settings SETTINGS =
            new CommandSpamDetector.Settings(2_000, 6, 15_000, 3, 5, 1_000);

    @Test
    void detectsFloodAfterConfiguredCommandLimit() {
        CommandSpamDetector detector = new CommandSpamDetector();
        for (int i = 0; i < 6; i++) {
            assertNull(detector.inspect("/say command " + i, i * 250L, SETTINGS));
        }

        CommandSpamDetector.Result result = detector.inspect("/say command 6", 1_750, SETTINGS);

        assertEquals(CommandSpamDetector.Reason.FLOOD, result.reason());
        assertTrue(result.shouldFlag());
    }

    @Test
    void detectsRepeatedCommandDespiteCaseWhitespaceAndPunctuation() {
        CommandSpamDetector detector = new CommandSpamDetector();
        assertNull(detector.inspect("/tp some-player", 0, SETTINGS));
        assertNull(detector.inspect("/tp  some-player ", 500, SETTINGS));

        CommandSpamDetector.Result result = detector.inspect("/TP SOME-PLAYER", 1_000, SETTINGS);

        assertEquals(CommandSpamDetector.Reason.DUPLICATE, result.reason());
    }

    @Test
    void shortCommandsSkipDuplicateButStillCountForFlood() {
        CommandSpamDetector.Settings shortSettings =
                new CommandSpamDetector.Settings(2_000, 5, 15_000, 3, 5, 1_000);
        CommandSpamDetector detector = new CommandSpamDetector();
        // /ok 归一化后长度低于 min-duplicate-length：重复不触发，但频率仍累积
        for (int i = 0; i < 5; i++) {
            assertNull(detector.inspect("/ok", i * 250L, shortSettings));
        }

        CommandSpamDetector.Result result = detector.inspect("/ok", 1_250, shortSettings);
        assertEquals(CommandSpamDetector.Reason.FLOOD, result.reason());
    }

    @Test
    void rateLimitsFlagsButContinuesBlockingSpam() {
        CommandSpamDetector detector = new CommandSpamDetector();
        detector.inspect("/toggle-help", 0, SETTINGS);
        detector.inspect("/toggle-help", 100, SETTINGS);
        assertTrue(detector.inspect("/toggle-help", 200, SETTINGS).shouldFlag());
        assertFalse(detector.inspect("/toggle-help", 300, SETTINGS).shouldFlag());
        assertTrue(detector.inspect("/toggle-help", 1_300, SETTINGS).shouldFlag());
    }
}
