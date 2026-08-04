package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesPerServerProtectionOverridesCaseInsensitively() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                [database]
                jdbc-url = "jdbc:h2:mem:protection_overrides"

                [protection]
                default-enabled = false

                [protection.servers]
                lobby = true
                "Survival-2" = false
                """);

        VelocitySettings settings = VelocitySettings.load(tempDir);

        assertTrue(settings.protectionEnabledFor("LOBBY"));
        assertFalse(settings.protectionEnabledFor("survival-2"));
        assertFalse(settings.protectionEnabledFor("minigames"));
    }

    @Test
    void keepsProtectionEnabledForExistingConfigurations() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                server-id = "velocity"

                [database]
                jdbc-url = "jdbc:h2:mem:protection_defaults"
                """);

        VelocitySettings settings = VelocitySettings.load(tempDir);

        assertTrue(settings.protectionEnabledFor("lobby"));
    }

    @Test
    void loadsUpdateMirrorBase() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                [database]
                jdbc-url = "jdbc:h2:mem:mirror"

                [updates]
                mirror-base = "https://updates.example.com/"
                """);

        VelocitySettings settings = VelocitySettings.load(tempDir);

        assertEquals("https://updates.example.com/", settings.updateMirrorBase());
    }

    @Test
    void defaultsUpdateMirrorBaseToEmpty() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                [database]
                jdbc-url = "jdbc:h2:mem:mirror_default"
                """);

        VelocitySettings settings = VelocitySettings.load(tempDir);

        assertEquals("", settings.updateMirrorBase());
    }

    @Test
    void rejectsUnsafeUpdateMirrorBase() throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), """
                [database]
                jdbc-url = "jdbc:h2:mem:mirror_bad"

                [updates]
                mirror-base = "http://updates.example.com"
                """);

        assertThrows(java.io.IOException.class, () -> VelocitySettings.load(tempDir));
    }
}
