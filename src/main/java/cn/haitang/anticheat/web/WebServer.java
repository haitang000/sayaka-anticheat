package cn.haitang.anticheat.web;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PersistentStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.ban.BanListType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置 Web 服务（JDK {@code com.sun.net.httpserver}，零额外依赖）。
 *
 * <ul>
 *   <li>玩家：凭处罚 ID 查询封禁详情并提交申诉（{@code /api/appeal/*}）。</li>
 *   <li>管理员：凭令牌查看仪表盘、处罚列表与申诉，并可通过/驳回申诉
 *       （{@code /api/admin/*}，需 {@code X-Admin-Token} 请求头）。</li>
 * </ul>
 *
 * <p>处理线程为 HttpServer 自带线程池；所有 Bukkit API（在线玩家、封禁列表）
 * 通过 {@link #syncCall} 切回主线程执行，{@link PersistentStore} 自身线程安全。
 */
public final class WebServer {

    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_REASON_LENGTH = 2000;
    private static final int MAX_CONTACT_LENGTH = 200;

    private final AntiCheatPlugin plugin;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final String adminToken;
    private final boolean tokenGenerated;
    private final String indexHtml;
    private final String bind;
    private final RateLimiter appealLimiter = new RateLimiter(20, 60_000L);

    private WebServer(AntiCheatPlugin plugin, HttpServer server, ThreadPoolExecutor executor,
                      String adminToken, boolean tokenGenerated, String indexHtml, String bind) {
        this.plugin = plugin;
        this.server = server;
        this.executor = executor;
        this.adminToken = adminToken;
        this.tokenGenerated = tokenGenerated;
        this.indexHtml = indexHtml;
        this.bind = bind;
    }

    /**
     * 同步创建并启动 Web 服务。绑定失败或被禁用时返回 {@code null}，插件其余功能不受影响。
     */
    public static WebServer start(AntiCheatPlugin plugin) {
        if (!plugin.config().getBoolean("web.enabled", true)) {
            return null;
        }
        String bind = plugin.config().getString("web.bind", "0.0.0.0");
        int port = plugin.config().getInt("web.port", 8080);
        int threads = Math.max(1, plugin.config().getInt("web.threads", 2));

        String configured = plugin.config().getString("web.admin-token", "");
        boolean generated = configured == null || configured.isBlank();
        String token = generated ? UUID.randomUUID().toString().replace("-", "") : configured.trim();

        String html = loadIndexHtml(plugin);
        if (html == null) {
            plugin.getLogger().warning("未找到打包的 web/index.html，管理面板不可用；Web 服务未启动。");
            return null;
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException | IllegalArgumentException error) {
            plugin.getLogger().warning("Web 服务启动失败（" + bind + ":" + port + "）："
                    + error.getMessage() + " —— 反作弊其余功能正常运行。");
            return null;
        }

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(256),
                new WebThreadFactory());
        executor.allowCoreThreadTimeOut(true);
        server.setExecutor(executor);

        WebServer web = new WebServer(plugin, server, executor, token, generated, html, bind);
        web.registerRoutes();
        server.start();

        plugin.getLogger().info("反作弊 Web 面板已启动: http://" + displayHost(bind) + ":" + port + "/");
        if (generated) {
            plugin.getLogger().info("管理后台访问令牌（本次随机生成，可在 config.yml 的 web.admin-token 固定）: " + token);
        }
        return web;
    }

    private void registerRoutes() {
        server.createContext("/api/appeal/lookup", wrap(this::handleAppealLookup));
        server.createContext("/api/appeal/submit", wrap(this::handleAppealSubmit));
        server.createContext("/api/admin/overview", wrap(requireAdmin(this::handleOverview)));
        server.createContext("/api/admin/punishments", wrap(requireAdmin(this::handlePunishments)));
        server.createContext("/api/admin/appeals", wrap(requireAdmin(this::handleAppeals)));
        server.createContext("/api/admin/appeals/resolve", wrap(requireAdmin(this::handleResolve)));
        server.createContext("/", wrap(this::handleStatic));
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // ---- 处理器 ----

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        // 单页应用：所有非 API 路径都返回同一份 index.html
        byte[] body = indexHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void handleAppealLookup(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("仅支持 GET"));
            return;
        }
        String id = queryParam(exchange, "id");
        if (id == null || id.isBlank()) {
            sendJson(exchange, 400, error("缺少处罚 ID"));
            return;
        }
        PersistentStore store = plugin.getStore();
        PersistentStore.PunishmentRecord record = store.getPunishment(id.trim());
        if (record == null) {
            sendJson(exchange, 404, error("未找到该处罚 ID，请核对封禁界面上的编号"));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("punishment", publicPunishment(record));
        PersistentStore.AppealRecord appeal = store.getAppeal(id.trim());
        body.put("appeal", appeal == null ? null : appealView(appeal, false));
        sendJson(exchange, 200, body);
    }

    private void handleAppealSubmit(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("仅支持 POST"));
            return;
        }
        if (!appealLimiter.allow(clientIp(exchange))) {
            sendJson(exchange, 429, error("提交过于频繁，请稍后再试"));
            return;
        }
        Map<String, Object> json = readJsonBody(exchange);
        if (json == null) {
            sendJson(exchange, 400, error("请求体不是合法 JSON"));
            return;
        }
        String id = asString(json.get("id")).trim();
        String reason = asString(json.get("reason")).trim();
        String contact = asString(json.get("contact")).trim();
        if (id.isEmpty()) {
            sendJson(exchange, 400, error("缺少处罚 ID"));
            return;
        }
        if (reason.length() < 5) {
            sendJson(exchange, 400, error("请填写至少 5 个字的申诉理由"));
            return;
        }
        if (reason.length() > MAX_REASON_LENGTH) reason = reason.substring(0, MAX_REASON_LENGTH);
        if (contact.length() > MAX_CONTACT_LENGTH) contact = contact.substring(0, MAX_CONTACT_LENGTH);

        PersistentStore.AppealSubmitResult result =
                plugin.getStore().submitAppeal(id, reason, contact);
        switch (result) {
            case PUNISHMENT_NOT_FOUND -> sendJson(exchange, 404, error("未找到该处罚 ID"));
            case ALREADY_RESOLVED -> sendJson(exchange, 409,
                    error("该处罚的申诉已被管理员处理，无法再次提交"));
            case OK -> {
                plugin.getStore().saveAsync();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ok", true);
                body.put("message", "申诉已提交，请等待管理员处理");
                sendJson(exchange, 200, body);
            }
        }
    }

    private void handleOverview(HttpExchange exchange) throws IOException {
        List<PersistentStore.PunishmentRecord> punishments = plugin.getStore().listPunishments();
        List<PersistentStore.AppealRecord> appeals = plugin.getStore().listAppeals();
        long pending = appeals.stream()
                .filter(a -> a.status() == PersistentStore.AppealStatus.PENDING).count();
        long now = System.currentTimeMillis();
        long activeBans = punishments.stream().filter(p -> p.expiresAt() > now).count();

        int online;
        try {
            online = syncCall(() -> Bukkit.getOnlinePlayers().size());
        } catch (Exception ignored) {
            online = -1;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("onlinePlayers", online);
        body.put("enabledChecks", countEnabledChecks());
        body.put("totalChecks", CheckType.values().length);
        body.put("totalPunishments", punishments.size());
        body.put("activeBans", activeBans);
        body.put("totalAppeals", appeals.size());
        body.put("pendingAppeals", pending);
        sendJson(exchange, 200, body);
    }

    private void handlePunishments(HttpExchange exchange) throws IOException {
        List<Object> list = new ArrayList<>();
        for (PersistentStore.PunishmentRecord record : plugin.getStore().listPunishments()) {
            Map<String, Object> item = publicPunishment(record);
            item.put("playerUuid", record.playerId().toString());
            PersistentStore.AppealRecord appeal = plugin.getStore().getAppeal(record.id());
            item.put("appealStatus", appeal == null ? null : appeal.status().name());
            list.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("punishments", list);
        sendJson(exchange, 200, body);
    }

    private void handleAppeals(HttpExchange exchange) throws IOException {
        List<Object> list = new ArrayList<>();
        for (PersistentStore.AppealRecord appeal : plugin.getStore().listAppeals()) {
            Map<String, Object> item = appealView(appeal, true);
            PersistentStore.PunishmentRecord record = plugin.getStore().getPunishment(appeal.punishmentId());
            item.put("punishment", record == null ? null : publicPunishment(record));
            list.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appeals", list);
        sendJson(exchange, 200, body);
    }

    private void handleResolve(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, error("仅支持 POST"));
            return;
        }
        Map<String, Object> json = readJsonBody(exchange);
        if (json == null) {
            sendJson(exchange, 400, error("请求体不是合法 JSON"));
            return;
        }
        String id = asString(json.get("id")).trim();
        boolean approved = Boolean.TRUE.equals(json.get("approved"));
        String note = asString(json.get("note")).trim();

        PersistentStore.PunishmentRecord record = plugin.getStore().getPunishment(id);
        if (record == null) {
            sendJson(exchange, 404, error("未找到该处罚 ID"));
            return;
        }
        if (!plugin.getStore().resolveAppeal(id, approved, note)) {
            sendJson(exchange, 404, error("该处罚没有待处理的申诉"));
            return;
        }
        if (approved) {
            try {
                syncCall(() -> {
                    unban(record.playerId(), record.playerName());
                    return null;
                });
            } catch (Exception error) {
                plugin.getLogger().warning("网页申诉通过后解封失败: " + error.getMessage());
            }
        }
        plugin.getStore().addHistory(record.playerId(),
                (approved ? "[申诉通过] 网页管理员解封" : "[申诉驳回] 网页管理员")
                        + (note.isEmpty() ? "" : "（" + note + "）"));
        plugin.getStore().saveAsync();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        PersistentStore.AppealRecord updated = plugin.getStore().getAppeal(id);
        body.put("appeal", updated == null ? null : appealView(updated, true));
        sendJson(exchange, 200, body);
    }

    /** 主线程执行：解除封禁并清理在线玩家状态，逻辑与 /sac unban 保持一致。 */
    private void unban(UUID playerId, String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerId);
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        if (banList.isBanned(target.getPlayerProfile())) {
            banList.pardon(target.getPlayerProfile());
        }
        plugin.getStore().clearStrikes(playerId);
        Player online = target.getPlayer();
        if (online != null) {
            var data = plugin.getDataManager().get(online);
            data.resetAllVl();
            data.setPunishing(false);
        }
        plugin.getLogger().info("网页申诉通过，已解封 " + playerName);
    }

    // ---- 视图（脱敏后的 JSON 结构） ----

    private Map<String, Object> publicPunishment(PersistentStore.PunishmentRecord record) {
        CheckType type = CheckType.byId(record.check());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.id());
        map.put("playerName", record.playerName());
        map.put("check", record.check());
        map.put("checkDisplay", type != null ? type.display() : record.check());
        map.put("vl", round1(record.vl()));
        map.put("hours", record.hours());
        map.put("banNumber", record.banNumber());
        map.put("bannedAt", record.bannedAt());
        map.put("expiresAt", record.expiresAt());
        map.put("active", record.expiresAt() > System.currentTimeMillis());

        List<Object> detections = new ArrayList<>();
        for (PersistentStore.DetectionEvidence evidence : record.detections()) {
            CheckType dt = CheckType.byId(evidence.check());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", evidence.at());
            item.put("check", dt != null ? dt.display() : evidence.check());
            item.put("vl", round1(evidence.vl()));
            item.put("detail", evidence.detail());
            detections.add(item);
        }
        map.put("detections", detections);

        List<Object> warnings = new ArrayList<>();
        for (PersistentStore.WarningEvidence evidence : record.warnings()) {
            CheckType wt = CheckType.byId(evidence.check());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", evidence.at());
            item.put("check", wt != null ? wt.display() : evidence.check());
            item.put("stage", evidence.stage());
            item.put("vl", round1(evidence.vl()));
            warnings.add(item);
        }
        map.put("warnings", warnings);
        return map;
    }

    private Map<String, Object> appealView(PersistentStore.AppealRecord appeal, boolean admin) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("punishmentId", appeal.punishmentId());
        map.put("playerName", appeal.playerName());
        map.put("reason", appeal.reason());
        map.put("status", appeal.status().name());
        map.put("submittedAt", appeal.submittedAt());
        map.put("resolvedAt", appeal.resolvedAt());
        map.put("note", appeal.note());
        // 联系方式可能含隐私，仅管理员可见
        if (admin) map.put("contact", appeal.contact());
        return map;
    }

    private int countEnabledChecks() {
        int count = 0;
        for (CheckType type : CheckType.values()) {
            if (plugin.config().getBoolean("checks." + type.configKey() + ".enabled", false)) {
                count++;
            }
        }
        return count;
    }

    // ---- 鉴权 / 包装 ----

    private ThrowingHandler requireAdmin(ThrowingHandler handler) {
        return exchange -> {
            if (!isAuthorized(exchange)) {
                sendJson(exchange, 401, error("管理令牌无效或缺失"));
                return;
            }
            handler.handle(exchange);
        };
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String provided = firstHeader(exchange, "X-Admin-Token");
        if (provided == null) {
            String auth = firstHeader(exchange, "Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                provided = auth.substring(7).trim();
            }
        }
        return provided != null && constantTimeEquals(provided, adminToken);
    }

    private HttpHandler wrap(ThrowingHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception error) {
                plugin.getLogger().warning("Web 请求处理异常 " + exchange.getRequestURI() + ": " + error);
                try {
                    sendJson(exchange, 500, error("服务器内部错误"));
                } catch (IOException ignored) {
                    // 连接可能已关闭
                }
            } finally {
                exchange.close();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    // ---- 工具 ----

    private <T> T syncCall(Callable<T> callable) throws Exception {
        if (Bukkit.isPrimaryThread()) return callable.call();
        return Bukkit.getScheduler().callSyncMethod(plugin, callable).get(5, TimeUnit.SECONDS);
    }

    private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] bytes = readBody(exchange);
        if (bytes.length == 0) return null;
        try {
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int read;
            int total = 0;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new IOException("请求体过大");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return map;
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            if (name.equals(key)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String clientIp(HttpExchange exchange) {
        String forwarded = firstHeader(exchange, "X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRemoteAddress() == null ? "unknown"
                : exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int result = x.length ^ y.length;
        for (int i = 0; i < x.length; i++) {
            result |= x[i] ^ y[i < y.length ? i : 0];
        }
        return result == 0;
    }

    private static String displayHost(String bind) {
        return bind == null || bind.isBlank() || bind.equals("0.0.0.0") ? "<服务器IP>" : bind;
    }

    private static String loadIndexHtml(AntiCheatPlugin plugin) {
        try (InputStream stream = plugin.getResource("web/index.html")) {
            if (stream == null) return null;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            plugin.getLogger().warning("读取 web/index.html 失败: " + error.getMessage());
            return null;
        }
    }

    public String adminToken() {
        return adminToken;
    }

    public boolean isTokenGenerated() {
        return tokenGenerated;
    }

    /** 供管理员查看的面板地址，例如 {@code http://<服务器IP>:8080/}。 */
    public String displayUrl() {
        return "http://" + displayHost(bind) + ":" + port() + "/";
    }

    /** 固定窗口限流：每个来源在窗口内最多 {@code limit} 次。 */
    private static final class RateLimiter {
        private final int limit;
        private final long windowMs;
        private final Map<String, long[]> buckets = new ConcurrentHashMap<>();

        RateLimiter(int limit, long windowMs) {
            this.limit = limit;
            this.windowMs = windowMs;
        }

        boolean allow(String key) {
            long now = System.currentTimeMillis();
            long[] bucket = buckets.compute(key, (k, existing) -> {
                if (existing == null || now - existing[0] >= windowMs) {
                    return new long[] {now, 1};
                }
                existing[1]++;
                return existing;
            });
            if (buckets.size() > 4096) buckets.clear(); // 兜底防止无限增长
            return bucket[1] <= limit;
        }
    }

    private static final class WebThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "sayaka-web-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
