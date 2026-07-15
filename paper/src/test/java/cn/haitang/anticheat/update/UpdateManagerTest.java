package cn.haitang.anticheat.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsArtifactUrlFromLatestReleaseRedirect() {
        UpdateManager.Release release = UpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/haitang000/sayaka-anticheat/releases/tag/v2.1.0"))
                .orElseThrow();

        assertEquals("2.1.0", release.version().toString());
        assertEquals("v2.1.0", release.tag());
        assertEquals("https://github.com/haitang000/sayaka-anticheat/releases/download/"
                + "v2.1.0/Sayaka-AntiCheat-Paper-2.1.0.jar", release.download().toString());
    }

    @Test
    void rejectsRedirectsOutsideTheExpectedRepository() {
        assertTrue(UpdateManager.releaseFromLatestUri(
                URI.create("https://example.com/haitang000/sayaka-anticheat/releases/tag/v9.0.0")).isEmpty());
        assertTrue(UpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/other/project/releases/tag/v9.0.0")).isEmpty());
    }

    @Test
    void validatesArtifactIdentityAndVersion() throws Exception {
        UpdateManager.Release release = release("v2.1.0");
        Path matching = createArtifact("2.1.0");
        Path wrongVersion = createArtifact("2.0.9");

        UpdateManager.validateArtifact(matching, release);
        assertThrows(IOException.class, () -> UpdateManager.validateArtifact(wrongVersion, release));
    }

    private UpdateManager.Release release(String tag) {
        return UpdateManager.releaseFromLatestUri(URI.create(
                "https://github.com/haitang000/sayaka-anticheat/releases/tag/" + tag)).orElseThrow();
    }

    private Path createArtifact(String version) throws IOException {
        Path artifact = Files.createTempFile(temporaryDirectory, "release-", ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(("name: SayakaAntiCheat\n"
                    + "version: '" + version + "'\n"
                    + "main: cn.haitang.anticheat.AntiCheatPlugin\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("cn/haitang/anticheat/AntiCheatPlugin.class"));
            jar.write(new byte[]{0});
            jar.closeEntry();
        }
        return artifact;
    }
}
