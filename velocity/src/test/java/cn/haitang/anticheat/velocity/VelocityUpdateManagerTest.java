package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
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

class VelocityUpdateManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesReleaseFromFeedPageWithoutGuessingArtifactUrl() {
        VelocityUpdateManager.Release release = VelocityUpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/haitang000/sayaka-anticheat/releases/tag/v2.1.0"))
                .orElseThrow();

        assertEquals("2.1.0", release.version().toString());
        assertEquals("v2.1.0", release.tag());
        assertTrue(release.download().isEmpty());
    }

    @Test
    void rejectsRedirectsOutsideTheExpectedRepository() {
        assertTrue(VelocityUpdateManager.releaseFromLatestUri(
                URI.create("https://example.com/haitang000/sayaka-anticheat/releases/tag/v9.0.0")).isEmpty());
        assertTrue(VelocityUpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/other/project/releases/tag/v9.0.0")).isEmpty());
    }

    @Test
    void findsMostRecentlyPublishedPrerelease() throws Exception {
        String feed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <updated>2026-07-15T08:19:34Z</updated>
                    <link rel="alternate" href="https://github.com/haitang000/sayaka-anticheat/releases/tag/v2.1.0.2"/>
                  </entry>
                  <entry>
                    <updated>2026-07-15T09:19:40Z</updated>
                    <link rel="alternate" href="https://github.com/haitang000/sayaka-anticheat/releases/tag/v2.1.0.2-beta.1"/>
                  </entry>
                </feed>
                """;

        VelocityUpdateManager.Release release = VelocityUpdateManager.latestReleaseFromFeed(
                new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8))).orElseThrow();

        assertEquals("2.1.0.2-beta.1", release.version().toString());
    }

    @Test
    void selectsVelocityArtifactAndRejectsUntrustedUrl() throws Exception {
        VelocityUpdateManager.Release candidate = release("v2.1.0.3-beta.3");
        String json = "{\"tag_name\":\"v2.1.0.3-beta.3\",\"assets\":["
                + asset("Sayaka-AntiCheat-Paper-2.1.0.3-beta.3.jar", true) + ","
                + asset("Sayaka-AntiCheat-Velocity-2.1.0.3-beta.3.jar", true) + "]}";

        VelocityUpdateManager.Release velocity = VelocityUpdateManager.releaseWithVelocityAsset(
                candidate, input(json));
        assertEquals("https://github.com/haitang000/sayaka-anticheat/releases/download/"
                + "v2.1.0.3-beta.3/Sayaka-AntiCheat-Velocity-2.1.0.3-beta.3.jar",
                velocity.download().orElseThrow().toString());

        String untrusted = "{\"tag_name\":\"v2.1.0.3-beta.3\",\"assets\":["
                + asset("Sayaka-AntiCheat-Velocity-2.1.0.3-beta.3.jar", false) + "]}";
        assertThrows(IOException.class, () -> VelocityUpdateManager.releaseWithVelocityAsset(
                candidate, input(untrusted)));

        String onlyPaper = "{\"tag_name\":\"v2.1.0.3-beta.3\",\"assets\":["
                + asset("Sayaka-AntiCheat-Paper-2.1.0.3-beta.3.jar", true) + "]}";
        assertThrows(IOException.class, () -> VelocityUpdateManager.releaseWithVelocityAsset(
                candidate, input(onlyPaper)));
    }

    @Test
    void validatesVelocityArtifactIdentityAndVersion() throws Exception {
        VelocityUpdateManager.Release release = release("v2.1.0");
        Path matching = createArtifact("2.1.0", "sayaka-anticheat",
                "cn.haitang.anticheat.velocity.SayakaVelocityPlugin");
        Path wrongVersion = createArtifact("2.0.9", "sayaka-anticheat",
                "cn.haitang.anticheat.velocity.SayakaVelocityPlugin");
        Path wrongId = createArtifact("2.1.0", "impostor",
                "cn.haitang.anticheat.velocity.SayakaVelocityPlugin");

        VelocityUpdateManager.validateArtifact(matching, release);
        assertThrows(IOException.class, () -> VelocityUpdateManager.validateArtifact(wrongVersion, release));
        assertThrows(IOException.class, () -> VelocityUpdateManager.validateArtifact(wrongId, release));
    }

    private VelocityUpdateManager.Release release(String tag) {
        return VelocityUpdateManager.releaseFromLatestUri(URI.create(
                "https://github.com/haitang000/sayaka-anticheat/releases/tag/" + tag)).orElseThrow();
    }

    private ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private String asset(String name, boolean trusted) {
        String url = trusted
                ? "https://github.com/haitang000/sayaka-anticheat/releases/download/v2.1.0.3-beta.3/" + name
                : "https://example.com/" + name;
        return "{\"name\":\"" + name + "\",\"browser_download_url\":\"" + url + "\"}";
    }

    private Path createArtifact(String version, String id, String main) throws IOException {
        Path artifact = Files.createTempFile(temporaryDirectory, "release-", ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("velocity-plugin.json"));
            jar.write(("{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"main\":\"" + main + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(main.replace('.', '/') + ".class"));
            jar.write(new byte[]{0});
            jar.closeEntry();
        }
        return artifact;
    }
}
