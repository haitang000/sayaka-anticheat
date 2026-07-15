package cn.haitang.anticheat.update;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Checks stable GitHub releases and stages verified updates for Bukkit hot reload. */
public final class UpdateManager {

    private static final String REPOSITORY = "haitang000/sayaka-anticheat";
    private static final URI LATEST_RELEASE = URI.create("https://github.com/" + REPOSITORY + "/releases/latest");
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DESCRIPTOR_BYTES = 128L * 1024L;
    private static final long MIN_CHECK_INTERVAL_TICKS = 5L * 60L * 20L;

    record Release(SemanticVersion version, String tag, URI page, URI download) {
    }

    private final AntiCheatPlugin plugin;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicBoolean installing = new AtomicBoolean();
    private volatile Release available;
    private volatile boolean shuttingDown;
    private BukkitTask checkTask;
    private String lastAnnouncedVersion;

    public UpdateManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void start() {
        stopCheckTask();
        shuttingDown = false;
        if (!plugin.config().getBoolean("updates.enabled", true)) {
            available = null;
            lastAnnouncedVersion = null;
            return;
        }
        long configured = plugin.config().getLong("updates.check-interval-minutes", 30L) * 60L * 20L;
        long interval = Math.max(MIN_CHECK_INTERVAL_TICKS, configured);
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> runCheck(null, false), 100L, interval);
    }

    public void reloadConfiguration() {
        start();
    }

    public void shutdown() {
        shuttingDown = true;
        stopCheckTask();
    }

    public void check(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefixed("update-checking", null));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runCheck(sender, true));
    }

    public void install(CommandSender sender) {
        if (!installing.compareAndSet(false, true)) {
            sender.sendMessage(plugin.getMessages().prefixed("update-busy", null));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefixed("update-download-start", null));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean staged = false;
            try {
                Release release = available;
                if (release == null) release = findAvailableRelease().orElse(null);
                if (release == null) {
                    sendSync(sender, "update-current", Map.of("version", currentVersion()));
                    return;
                }
                stage(release);
                Release installed = release;
                Bukkit.getScheduler().runTask(plugin, () -> beginHotReload(sender, installed));
                staged = true;
            } catch (Exception error) {
                plugin.getLogger().warning("Update failed: " + error.getMessage());
                sendSync(sender, "update-failed", Map.of("error", safeMessage(error)));
            } finally {
                if (!staged) installing.set(false);
            }
        });
    }

    public void notifyIfAvailable(Player player) {
        Release release = available;
        if (release == null || !player.hasPermission("anticheat.admin")) return;
        player.sendMessage(plugin.getMessages().prefixed("update-available", placeholders(release)));
    }

    private void runCheck(CommandSender sender, boolean reportCurrent) {
        if (!checking.compareAndSet(false, true)) {
            if (sender != null) sendSync(sender, "update-busy", null);
            return;
        }
        try {
            Optional<Release> found = findAvailableRelease();
            available = found.orElse(null);
            if (found.isPresent()) {
                scheduleAnnouncement(found.get());
                if (sender != null) sendSync(sender, "update-available", placeholders(found.get()));
            } else if (sender != null && reportCurrent) {
                sendSync(sender, "update-current", Map.of("version", currentVersion()));
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Unable to check GitHub releases: " + error.getMessage());
            if (sender != null) sendSync(sender, "update-check-failed", Map.of("error", safeMessage(error)));
        } finally {
            checking.set(false);
        }
    }

    private Optional<Release> findAvailableRelease() throws IOException, InterruptedException {
        SemanticVersion current = SemanticVersion.parse(currentVersion())
                .orElseThrow(() -> new IOException("invalid installed version " + currentVersion()));
        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sayaka-AntiCheat/" + currentVersion())
                .header("Cache-Control", "no-cache")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 400) {
            throw new IOException("GitHub returned HTTP " + response.statusCode());
        }
        Release latest = releaseFromLatestUri(response.uri())
                .orElseThrow(() -> new IOException("GitHub latest release redirect was invalid"));
        return latest.version().compareTo(current) > 0 ? Optional.of(latest) : Optional.empty();
    }

    static Optional<Release> releaseFromLatestUri(URI page) {
        if (!"https".equalsIgnoreCase(page.getScheme()) || !"github.com".equalsIgnoreCase(page.getHost())) {
            return Optional.empty();
        }
        String prefix = "/" + REPOSITORY + "/releases/tag/";
        String rawPath = page.getRawPath();
        if (rawPath == null || !rawPath.startsWith(prefix) || rawPath.length() <= prefix.length()) {
            return Optional.empty();
        }
        String rawTag = rawPath.substring(prefix.length());
        if (rawTag.contains("/")) return Optional.empty();
        String tag = URLDecoder.decode(rawTag.replace("+", "%2B"), StandardCharsets.UTF_8);
        Optional<SemanticVersion> parsed = SemanticVersion.parse(tag);
        if (parsed.isEmpty()) return Optional.empty();
        String artifact = "Sayaka-AntiCheat-Paper-" + parsed.get() + ".jar";
        URI download = URI.create("https://github.com/" + REPOSITORY + "/releases/download/"
                + encodePathSegment(tag) + "/" + encodePathSegment(artifact));
        return Optional.of(new Release(parsed.get(), tag, page, download));
    }

    private void stage(Release release) throws IOException, InterruptedException {
        Path updateDirectory = Bukkit.getUpdateFolderFile().toPath();
        Files.createDirectories(updateDirectory);
        String installedFileName = plugin.getPluginJarFile().getName();
        Path staged = updateDirectory.resolve(installedFileName);
        Path temporary = Files.createTempFile(updateDirectory, installedFileName + ".", ".part");
        try {
            download(release, temporary);
            validateArtifact(temporary, release);
            try {
                Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, staged, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void download(Release release, Path destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(release.download())
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Sayaka-AntiCheat/" + currentVersion())
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("release download returned HTTP " + response.statusCode());
        }
        long declaredSize = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredSize > MAX_ARTIFACT_BYTES) {
            response.body().close();
            throw new IOException("release artifact exceeds 64 MiB");
        }
        try (InputStream input = response.body(); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long copied = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_ARTIFACT_BYTES) throw new IOException("release artifact exceeds 64 MiB");
                output.write(buffer, 0, read);
            }
            if (copied == 0L) throw new IOException("release artifact was empty");
        }
    }

    static void validateArtifact(Path artifact, Release release) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            JarEntry descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null || descriptor.getSize() > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("release artifact has no valid plugin.yml");
            }
            YamlConfiguration yaml;
            try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(descriptor), StandardCharsets.UTF_8)) {
                yaml = YamlConfiguration.loadConfiguration(reader);
            }
            String main = yaml.getString("main");
            String version = yaml.getString("version");
            if (!"SayakaAntiCheat".equals(yaml.getString("name"))
                    || !"cn.haitang.anticheat.AntiCheatPlugin".equals(main)
                    || SemanticVersion.parse(version).map(release.version()::compareTo).orElse(1) != 0) {
                throw new IOException("release artifact identity or version does not match " + release.tag());
            }
            String mainEntry = main.replace('.', '/') + ".class";
            if (jar.getJarEntry(mainEntry) == null) throw new IOException("release artifact has no plugin main class");
        }
    }

    private void scheduleAnnouncement(Release release) {
        if (shuttingDown) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            String version = release.version().toString();
            if (version.equals(lastAnnouncedVersion)) return;
            lastAnnouncedVersion = version;
            plugin.getLogger().warning("A new release is available: " + currentVersion() + " -> " + version
                    + ". Run /sac update to install it with hot reload.");
            for (Player player : Bukkit.getOnlinePlayers()) notifyIfAvailable(player);
        });
    }

    private void beginHotReload(CommandSender sender, Release release) {
        if (shuttingDown) return;
        Map<String, String> values = placeholders(release);
        sender.sendMessage(plugin.getMessages().prefixed("update-ready", values));
        plugin.getLogger().warning("Release " + release.version()
                + " is verified and staged; starting Bukkit hot reload in 3 seconds.");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("anticheat.admin") && player != sender) {
                player.sendMessage(plugin.getMessages().prefixed("update-ready", values));
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                Bukkit.reload();
            } catch (Throwable error) {
                plugin.getLogger().severe("Hot reload failed: " + error.getMessage());
            } finally {
                installing.set(false);
            }
        }, 60L);
    }

    private void sendSync(CommandSender sender, String key, Map<String, String> placeholders) {
        if (shuttingDown) return;
        Bukkit.getScheduler().runTask(plugin,
                () -> sender.sendMessage(plugin.getMessages().prefixed(key, placeholders)));
    }

    private Map<String, String> placeholders(Release release) {
        return Map.of(
                "current", currentVersion(),
                "latest", release.version().toString(),
                "url", release.page().toString());
    }

    private String currentVersion() {
        return plugin.getDescription().getVersion();
    }

    private void stopCheckTask() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
