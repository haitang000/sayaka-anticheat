package cn.haitang.anticheat.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsArtifactUrlFromLatestReleaseRedirect() {
        UpdateManager.Release release = UpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/haitang000/sayaka-anticheat/releases/tag/v2.1.0.1"))
                .orElseThrow();

        assertEquals("2.1.0.1", release.version().toString());
        assertEquals("v2.1.0.1", release.tag());
        assertEquals("https://github.com/haitang000/sayaka-anticheat/releases/download/"
                + "v2.1.0.1/Sayaka-AntiCheat-2.1.0.1.jar", release.download().toString());
    }

    @Test
    void rejectsRedirectsOutsideTheExpectedRepository() {
        assertTrue(UpdateManager.releaseFromLatestUri(
                URI.create("https://example.com/haitang000/sayaka-anticheat/releases/tag/v9.0.0")).isEmpty());
        assertTrue(UpdateManager.releaseFromLatestUri(
                URI.create("https://github.com/other/project/releases/tag/v9.0.0")).isEmpty());
    }

    @Test
    void findsMostRecentlyPublishedReleaseRegardlessOfVersionOrder() throws Exception {
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
                  <entry>
                    <updated>2026-07-15T07:51:58Z</updated>
                    <link rel="alternate" href="https://github.com/haitang000/sayaka-anticheat/releases/tag/v9.0.0"/>
                  </entry>
                </feed>
                """;

        UpdateManager.Release release = UpdateManager.latestReleaseFromFeed(
                new ByteArrayInputStream(feed.getBytes(StandardCharsets.UTF_8))).orElseThrow();

        assertEquals("2.1.0.2-beta.1", release.version().toString());
        assertEquals("v2.1.0.2-beta.1", release.tag());
        assertEquals("https://github.com/haitang000/sayaka-anticheat/releases/download/"
                + "v2.1.0.2-beta.1/Sayaka-AntiCheat-2.1.0.2-beta.1.jar", release.download().toString());
    }

    @Test
    void validatesArtifactIdentityAndVersion() throws Exception {
        UpdateManager.Release release = release("v2.1.0.1");
        Path matching = createArtifact("2.1.0.1");
        Path wrongVersion = createArtifact("2.1.0");

        UpdateManager.validateArtifact(matching, release);
        assertThrows(IOException.class, () -> UpdateManager.validateArtifact(wrongVersion, release));
    }

    @Test
    void neverReloadsEntireBukkitServerAfterStagingUpdate() throws Exception {
        AtomicBoolean invokesBukkitReload = new AtomicBoolean();
        try (InputStream bytecode = UpdateManager.class.getResourceAsStream("UpdateManager.class")) {
            assertNotNull(bytecode);
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            if ("org/bukkit/Bukkit".equals(owner) && "reload".equals(name)) {
                                invokesBukkitReload.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        assertFalse(invokesBukkitReload.get(),
                "staged plugin updates must wait for a full restart instead of reloading packet plugins");
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
