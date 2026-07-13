package cn.haitang.anticheat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiCheatPluginVersionTest {

    @Test
    void acceptsSupportedPacketEventsTwoXVersions() {
        assertTrue(AntiCheatPlugin.supportedPacketEventsVersion("2.13.0"));
        assertTrue(AntiCheatPlugin.supportedPacketEventsVersion("2.14.1-SNAPSHOT"));
    }

    @Test
    void rejectsOldMalformedAndFutureBreakingMajorVersions() {
        assertFalse(AntiCheatPlugin.supportedPacketEventsVersion("2.12.2"));
        assertFalse(AntiCheatPlugin.supportedPacketEventsVersion("3.0.0"));
        assertFalse(AntiCheatPlugin.supportedPacketEventsVersion("unknown"));
    }
}
