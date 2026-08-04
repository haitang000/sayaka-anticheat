package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Checks GitHub releases for a newer Velocity artifact and, on request, downloads and stages the
 * verified jar into a staging directory. The proxy is not hot-reloaded: applying an update means an
 * administrator replaces the running jar with the staged file and restarts Velocity.
 */
final class VelocityUpdateManager {

    private static final String REPOSITORY = "haitang000/sayaka-anticheat";
    private static final String DEFAULT_BASE = "https://github.com";
    private static final String ARTIFACT_PREFIX = "Sayaka-AntiCheat-Velocity-";
    private static final String PLUGIN_ID = "sayaka-anticheat";
    private static final String PLUGIN_MAIN = "cn.haitang.anticheat.velocity.SayakaVelocityPlugin";
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_DESCRIPTOR_BYTES = 128L * 1024L;
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;

    record Release(SemanticVersion version, String tag, URI page, Optional<URI> download) {
    }

    record Available(String version, String tag, String pageUrl) {
    }

    record Staged(String version, String fileName, String path, long sizeBytes, long stagedAt) {
    }

    record Snapshot(String currentVersion, Long lastCheckedAt, String lastError,
                    Available available, Staged staged) {
    }

    private final String currentVersion;
    private final Path stagingDirectory;
    private final String mirrorBase;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean();
    private final AtomicBoolean downloading = new AtomicBoolean();

    private volatile Release availableRelease;
    private volatile Long lastCheckedAt;
    private volatile String lastError;
    private volatile Staged staged;

    VelocityUpdateManager(String currentVersion, Path stagingDirectory) {
        this(currentVersion, stagingDirectory, normalizeMirrorBase(""), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    VelocityUpdateManager(String currentVersion, Path stagingDirectory, String mirrorBase) {
        this(currentVersion, stagingDirectory, mirrorBase, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    VelocityUpdateManager(String currentVersion, Path stagingDirectory,
                          String mirrorBase, HttpClient httpClient) {
        this.currentVersion = currentVersion;
        this.stagingDirectory = stagingDirectory;
        this.mirrorBase = normalizeMirrorBase(mirrorBase);
        this.httpClient = httpClient;
    }

    Snapshot snapshot() {
        Release release = availableRelease;
        Available available = release == null ? null
                : new Available(release.version().toString(), release.tag(), release.page().toString());
        return new Snapshot(currentVersion, lastCheckedAt, lastError, available, staged);
    }

    /** Performs a network check for a newer release and refreshes the cached snapshot. */
    Snapshot check() throws IOException, InterruptedException {
        if (!checking.compareAndSet(false, true)) {
            throw new IllegalStateException("已有一次版本检查正在进行");
        }
        try {
            Optional<Release> found = findAvailableRelease();
            availableRelease = found.orElse(null);
            if (found.isEmpty()) staged = null;
            lastError = null;
            lastCheckedAt = System.currentTimeMillis();
            return snapshot();
        } catch (IOException | InterruptedException | RuntimeException error) {
            lastError = safeMessage(error);
            lastCheckedAt = System.currentTimeMillis();
            throw error;
        } finally {
            checking.set(false);
        }
    }

    /** Downloads, verifies, and stages the currently-available release for a manual restart. */
    Staged download() throws IOException, InterruptedException {
        Release release = availableRelease;
        if (release == null) {
            release = findAvailableRelease().orElse(null);
            availableRelease = release;
            lastCheckedAt = System.currentTimeMillis();
        }
        if (release == null) throw new IOException("当前已是最新版本，没有可下载的更新");
        if (!downloading.compareAndSet(false, true)) {
            throw new IllegalStateException("已有一次下载正在进行");
        }
        try {
            Staged result = stage(release);
            staged = result;
            lastError = null;
            return result;
        } catch (IOException | InterruptedException | RuntimeException error) {
            lastError = safeMessage(error);
            throw error;
        } finally {
            downloading.set(false);
        }
    }

    private Optional<Release> findAvailableRelease() throws IOException, InterruptedException {
        SemanticVersion current = SemanticVersion.parse(currentVersion)
                .orElseThrow(() -> new IOException("无法解析当前版本号 " + currentVersion));
        HttpRequest request = HttpRequest.newBuilder(feedUri())
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sayaka-AntiCheat/" + currentVersion)
                .header("Cache-Control", "no-cache")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub 返回 HTTP " + response.statusCode()
                    + errorDetail(response));
        }
        Optional<Release> latest;
        try (InputStream body = response.body()) {
            byte[] feed = body.readNBytes(MAX_FEED_BYTES + 1);
            if (feed.length > MAX_FEED_BYTES) throw new IOException("GitHub 发布订阅源过大");
            latest = latestReleaseFromFeed(new ByteArrayInputStream(feed));
        }
        if (latest.isEmpty()) throw new IOException("GitHub 发布订阅源没有有效的发布记录");
        if (latest.get().version().compareTo(current) <= 0) return Optional.empty();
        return Optional.of(resolveReleaseAsset(latest.get()));
    }

    static Optional<Release> latestReleaseFromFeed(InputStream feed) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            NodeList entries = factory.newDocumentBuilder().parse(feed)
                    .getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
            Release latest = null;
            Instant latestPublishedAt = null;
            for (int entryIndex = 0; entryIndex < entries.getLength(); entryIndex++) {
                Element entry = (Element) entries.item(entryIndex);
                Optional<Instant> publishedAt = releasePublishedAt(entry);
                if (publishedAt.isEmpty()) continue;
                NodeList links = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link");
                for (int linkIndex = 0; linkIndex < links.getLength(); linkIndex++) {
                    Element link = (Element) links.item(linkIndex);
                    if (!"alternate".equals(link.getAttribute("rel"))) continue;
                    Optional<Release> candidate = releaseFromLatestUri(URI.create(link.getAttribute("href")));
                    if (candidate.isPresent()
                            && (latestPublishedAt == null || publishedAt.get().isAfter(latestPublishedAt))) {
                        latest = candidate.get();
                        latestPublishedAt = publishedAt.get();
                    }
                }
            }
            return Optional.ofNullable(latest);
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException error) {
            throw new IOException("GitHub 发布订阅源无效", error);
        }
    }

    private static Optional<Instant> releasePublishedAt(Element entry) {
        NodeList timestamps = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "updated");
        if (timestamps.getLength() == 0) return Optional.empty();
        try {
            return Optional.of(OffsetDateTime.parse(timestamps.item(0).getTextContent().trim()).toInstant());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
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
        return Optional.of(new Release(parsed.get(), tag, page, Optional.empty()));
    }

    /**
     * 由 Atom 源解析出的 tag 直接构造下载地址，不再请求 api.github.com：
     * 发布产物下载 URL 是确定格式，而 api.github.com 未认证接口有 60 次/小时/IP
     * 限额（共享出口 IP 的机房极易 403），绕过它即可消除该限额。
     * 完整性仍由下载后的 {@link #validateArtifact}（jar 身份 + 版本匹配 tag）保证。
     */
    static Release resolveReleaseAsset(Release release, String base) {
        String artifactName = ARTIFACT_PREFIX + release.version() + ".jar";
        String baseUrl = normalizeMirrorBase(base);
        URI download = URI.create(baseUrl + "/" + REPOSITORY + "/releases/download/"
                + encodePathSegment(release.tag()) + "/" + encodePathSegment(artifactName));
        if (!validAssetUri(download, release, artifactName, baseUrl)) {
            throw new IllegalArgumentException("构造的发布资源地址无效");
        }
        return new Release(release.version(), release.tag(), release.page(), Optional.of(download));
    }

    private static boolean validAssetUri(URI download, Release release, String assetName, String baseUrl) {
        if (!"https".equalsIgnoreCase(download.getScheme())) return false;
        URI base = URI.create(baseUrl);
        if (!base.getScheme().equalsIgnoreCase(download.getScheme())
                || !base.getAuthority().equalsIgnoreCase(download.getAuthority())) {
            return false;
        }
        String expectedPath = base.getRawPath() + "/" + REPOSITORY + "/releases/download/"
                + encodePathSegment(release.tag()) + "/" + encodePathSegment(assetName);
        return expectedPath.equals(download.getRawPath());
    }

    private URI feedUri() {
        return URI.create(mirrorBase + "/" + REPOSITORY + "/releases.atom");
    }

    private Release resolveReleaseAsset(Release release) {
        return resolveReleaseAsset(release, mirrorBase);
    }

    /** 空/空白视为 GitHub 默认源；去尾斜杠，只允许 https 绝对地址（无 query/fragment）。 */
    static String normalizeMirrorBase(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) return DEFAULT_BASE;
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("更新镜像地址无效: " + value, invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("更新镜像地址必须是 https URL 且不能带 query 或 fragment");
        }
        return value;
    }

    private Staged stage(Release release) throws IOException, InterruptedException {
        Files.createDirectories(stagingDirectory);
        String artifactFileName = ARTIFACT_PREFIX + release.version() + ".jar";
        Path target = stagingDirectory.resolve(artifactFileName);
        Path temporary = Files.createTempFile(stagingDirectory, artifactFileName + ".", ".part");
        try {
            long size = download(release, temporary);
            validateArtifact(temporary, release);
            moveReplacing(temporary, target);
            return new Staged(release.version().toString(), artifactFileName,
                    target.toAbsolutePath().toString(), size, System.currentTimeMillis());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private long download(Release release, Path destination) throws IOException, InterruptedException {
        URI download = release.download()
                .orElseThrow(() -> new IOException("发布资源地址尚未解析"));
        HttpRequest request = HttpRequest.newBuilder(download)
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Sayaka-AntiCheat/" + currentVersion)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("下载发布产物返回 HTTP " + response.statusCode()
                    + errorDetail(response));
        }
        long declaredSize = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredSize > MAX_ARTIFACT_BYTES) {
            response.body().close();
            throw new IOException("发布产物超过 64 MiB");
        }
        try (InputStream input = response.body(); var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long copied = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_ARTIFACT_BYTES) throw new IOException("发布产物超过 64 MiB");
                output.write(buffer, 0, read);
            }
            if (copied == 0L) throw new IOException("发布产物为空");
            return copied;
        }
    }

    static void validateArtifact(Path artifact, Release release) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            JarEntry descriptor = jar.getJarEntry("velocity-plugin.json");
            if (descriptor == null || descriptor.getSize() > MAX_DESCRIPTOR_BYTES) {
                throw new IOException("发布产物缺少有效的 velocity-plugin.json");
            }
            Map<String, Object> document;
            try (InputStream input = jar.getInputStream(descriptor)) {
                document = Json.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException error) {
                throw new IOException("发布产物的 velocity-plugin.json 无效", error);
            }
            String main = String.valueOf(document.get("main"));
            if (!PLUGIN_ID.equals(document.get("id"))
                    || !PLUGIN_MAIN.equals(main)
                    || SemanticVersion.parse(String.valueOf(document.get("version")))
                    .map(release.version()::compareTo).orElse(1) != 0) {
                throw new IOException("发布产物的身份或版本与 " + release.tag() + " 不一致");
            }
            String mainEntry = main.replace('.', '/') + ".class";
            if (jar.getJarEntry(mainEntry) == null) throw new IOException("发布产物缺少插件主类");
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** 非 200 响应时尽量读出服务端的真实原因（如 API 限频消息），并把 body 关闭。 */
    private static String errorDetail(HttpResponse<InputStream> response) {
        String detail = "";
        try (InputStream body = response.body()) {
            byte[] snippet = body.readNBytes(512);
            String text = new String(snippet, StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                detail = ": " + text.replaceAll("\\s+", " ").substring(0, Math.min(200, text.length()));
            }
        } catch (Exception ignored) {
            // 错误详情读不到也不影响主错误
        }
        return detail;
    }
}
