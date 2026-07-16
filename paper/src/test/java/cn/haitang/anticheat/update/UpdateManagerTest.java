package cn.haitang.anticheat.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesReleaseFromFeedPageWithoutGuessingArtifactUrl() {
        UpdateManager.Release release = UpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/haitang000/sayaka-anticheat/releases/tag/v2.1.0"))
                .orElseThrow();

        assertEquals("2.1.0", release.version().toString());
        assertEquals("v2.1.0", release.tag());
        assertTrue(release.download().isEmpty());
    }

    @Test
    void rejectsRedirectsOutsideTheExpectedRepository() {
        assertTrue(UpdateManager.releaseFromLatestUri(
                URI.create("https://example.com/haitang000/sayaka-anticheat/releases/tag/v9.0.0")).isEmpty());
        assertTrue(UpdateManager.releaseFromLatestUri(
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

        UpdateManager.Release release = UpdateManager.latestReleaseFromFeed(
                new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8))).orElseThrow();

        assertEquals("2.1.0.2-beta.1", release.version().toString());
        assertTrue(release.download().isEmpty());
    }

    @Test
    void selectsArtifactMatchingTheCurrentServerPlatform() throws Exception {
        UpdateManager.Release candidate = release("v2.1.0.3-beta.3");
        String json = releaseJson(
                "Sayaka-AntiCheat-Velocity-2.1.0.3-beta.3.jar",
                "Sayaka-AntiCheat-Paper-2.1.0.3-beta.3.jar");

        UpdateManager.Release paper = UpdateManager.releaseWithPlatformAsset(candidate,
                input(json), UpdateManager.ServerPlatform.PAPER);
        UpdateManager.Release velocity = UpdateManager.releaseWithPlatformAsset(candidate,
                input(json), UpdateManager.ServerPlatform.VELOCITY);

        assertEquals("https://github.com/haitang000/sayaka-anticheat/releases/download/"
                + "v2.1.0.3-beta.3/Sayaka-AntiCheat-Paper-2.1.0.3-beta.3.jar",
                paper.download().orElseThrow().toString());
        assertEquals("https://github.com/haitang000/sayaka-anticheat/releases/download/"
                + "v2.1.0.3-beta.3/Sayaka-AntiCheat-Velocity-2.1.0.3-beta.3.jar",
                velocity.download().orElseThrow().toString());
    }

    @Test
    void rejectsMissingOrUntrustedPlatformArtifact() {
        UpdateManager.Release candidate = release("v2.1.0.3-beta.3");
        String missingPaper = releaseJson("Sayaka-AntiCheat-Velocity-2.1.0.3-beta.3.jar");
        String untrustedPaper = """
                {"tag_name":"v2.1.0.3-beta.3","assets":[{
                  "name":"Sayaka-AntiCheat-Paper-2.1.0.3-beta.3.jar",
                  "browser_download_url":"https://example.com/Sayaka-AntiCheat-Paper-2.1.0.3-beta.3.jar"
                }]}
                """;

        assertThrows(IOException.class, () -> UpdateManager.releaseWithPlatformAsset(candidate,
                input(missingPaper), UpdateManager.ServerPlatform.PAPER));
        assertThrows(IOException.class, () -> UpdateManager.releaseWithPlatformAsset(candidate,
                input(untrustedPaper), UpdateManager.ServerPlatform.PAPER));
    }

    @Test
    void validatesArtifactIdentityAndVersion() throws Exception {
        UpdateManager.Release release = release("v2.1.0");
        Path matching = createArtifact("2.1.0");
        Path wrongVersion = createArtifact("2.0.9");

        UpdateManager.validateArtifact(matching, release);
        assertThrows(IOException.class, () -> UpdateManager.validateArtifact(wrongVersion, release));
    }

    @Test
    void swapsAndRestoresPluginJarForIsolatedHotReload() throws Exception {
        Path current = temporaryDirectory.resolve("Sayaka-AntiCheat-Paper-old.jar");
        Path staged = temporaryDirectory.resolve("update/Sayaka-AntiCheat-Paper-new.jar");
        Path target = temporaryDirectory.resolve("Sayaka-AntiCheat-Paper-new.jar");
        Path backup = temporaryDirectory.resolve("update/Sayaka-AntiCheat-Paper-old.jar.rollback");
        Files.createDirectories(staged.getParent());
        Files.writeString(current, "old");
        Files.writeString(staged, "new");

        IsolatedReload.replaceJar(current, staged, target, backup);

        assertFalse(Files.exists(current));
        assertEquals("new", Files.readString(target));
        assertEquals("old", Files.readString(backup));

        IsolatedReload.restoreJar(current, staged, target, backup);

        assertEquals("old", Files.readString(current));
        assertEquals("new", Files.readString(staged));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(backup));
    }

    private UpdateManager.Release release(String tag) {
        return UpdateManager.releaseFromLatestUri(URI.create(
                "https://github.com/haitang000/sayaka-anticheat/releases/tag/" + tag)).orElseThrow();
    }

    private ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private String releaseJson(String... artifactNames) {
        StringBuilder assets = new StringBuilder();
        for (String artifactName : artifactNames) {
            if (!assets.isEmpty()) assets.append(',');
            assets.append("{\"name\":\"").append(artifactName)
                    .append("\",\"browser_download_url\":\"https://github.com/haitang000/")
                    .append("sayaka-anticheat/releases/download/v2.1.0.3-beta.3/")
                    .append(artifactName).append("\"}");
        }
        return "{\"tag_name\":\"v2.1.0.3-beta.3\",\"assets\":[" + assets + "]}";
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
