package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.JdbcNetworkStore;
import cn.haitang.anticheat.shared.Json;
import cn.haitang.anticheat.shared.NetworkModels.Appeal;
import cn.haitang.anticheat.shared.NetworkModels.AppealStatus;
import cn.haitang.anticheat.shared.NetworkModels.AppealSubmitResult;
import cn.haitang.anticheat.shared.NetworkModels.Punishment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

final class DashboardServer {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_REASON_LENGTH = 2000;
    private static final int MAX_CONTACT_LENGTH = 200;

    private final JdbcNetworkStore store;
    private final IntSupplier onlinePlayers;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final String adminToken;
    private final String indexHtml;
    private final RateLimiter appealLimiter = new RateLimiter(20, 60_000L);

    private DashboardServer(JdbcNetworkStore store, IntSupplier onlinePlayers, HttpServer server,
                            ThreadPoolExecutor executor, String adminToken, String indexHtml) {
        this.store = store;
        this.onlinePlayers = onlinePlayers;
        this.server = server;
        this.executor = executor;
        this.adminToken = adminToken;
        this.indexHtml = indexHtml;
    }

    static DashboardServer start(JdbcNetworkStore store, IntSupplier onlinePlayers,
                                 VelocitySettings settings, Logger logger) throws IOException {
        String html;
        try (InputStream input = DashboardServer.class.getResourceAsStream("/web/index.html")) {
            if (input == null) throw new IOException("bundled web/index.html is missing");
            html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        HttpServer server = HttpServer.create(
                new InetSocketAddress(settings.webBind(), settings.webPort()), 0);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                settings.webThreads(), settings.webThreads(), 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256), new DashboardThreadFactory());
        executor.allowCoreThreadTimeOut(true);
        server.setExecutor(executor);
        boolean generated = settings.adminToken() == null || settings.adminToken().isBlank();
        String token = generated ? UUID.randomUUID().toString().replace("-", "") : settings.adminToken();
        DashboardServer dashboard = new DashboardServer(store, onlinePlayers, server, executor, token, html);
        dashboard.register();
        server.start();
        logger.info("Sayaka 统一面板已启动: http://{}:{}/", settings.webBind(), settings.webPort());
        if (generated) logger.warn("未设置 SAYAKA_ADMIN_TOKEN，本次临时管理令牌: {}", token);
        return dashboard;
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void register() {
        server.createContext("/api/appeal/lookup", wrap(this::appealLookup));
        server.createContext("/api/appeal/submit", wrap(this::appealSubmit));
        server.createContext("/api/admin/overview", wrap(admin(this::overview)));
        server.createContext("/api/admin/punishments", wrap(admin(this::punishments)));
        server.createContext("/api/admin/appeals", wrap(admin(this::appeals)));
        server.createContext("/api/admin/appeals/resolve", wrap(admin(this::resolve)));
        server.createContext("/", wrap(this::staticFile));
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        byte[] body = indexHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void appealLookup(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        String id = queryParam(exchange, "id");
        if (id == null || id.isBlank()) throw new HttpError(400, "缺少处罚 ID");
        Punishment punishment = store.getPunishment(id).orElseThrow(
                () -> new HttpError(404, "未找到该处罚 ID，请核对封禁界面上的编号"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("punishment", publicPunishment(punishment));
        body.put("appeal", store.getAppeal(id).map(value -> appealView(value, false)).orElse(null));
        sendJson(exchange, 200, body);
    }

    private void appealSubmit(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        if (!appealLimiter.allow(clientIp(exchange))) throw new HttpError(429, "提交过于频繁，请稍后再试");
        Map<String, Object> json = readJson(exchange);
        String id = string(json.get("id")).trim();
        String reason = string(json.get("reason")).trim();
        String contact = string(json.get("contact")).trim();
        if (id.isEmpty()) throw new HttpError(400, "缺少处罚 ID");
        if (reason.length() < 5) throw new HttpError(400, "请填写至少 5 个字的申诉理由");
        reason = truncate(reason, MAX_REASON_LENGTH);
        contact = truncate(contact, MAX_CONTACT_LENGTH);
        AppealSubmitResult result = store.submitAppeal(id, reason, contact, System.currentTimeMillis());
        if (result == AppealSubmitResult.PUNISHMENT_NOT_FOUND) throw new HttpError(404, "未找到该处罚 ID");
        if (result == AppealSubmitResult.ALREADY_RESOLVED) {
            throw new HttpError(409, "该处罚的申诉已被管理员处理，无法再次提交");
        }
        sendJson(exchange, 200, Map.of("ok", true, "message", "申诉已提交，请等待管理员处理"));
    }

    private void overview(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        List<Punishment> punishments = store.listPunishments();
        List<Appeal> appeals = store.listAppeals();
        long pending = appeals.stream().filter(value -> value.status() == AppealStatus.PENDING).count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("onlinePlayers", onlinePlayers.getAsInt());
        body.put("enabledChecks", 27);
        body.put("totalChecks", 27);
        body.put("totalPunishments", punishments.size());
        body.put("activeBans", store.activeBanCount(System.currentTimeMillis()));
        body.put("totalAppeals", appeals.size());
        body.put("pendingAppeals", pending);
        sendJson(exchange, 200, body);
    }

    private void punishments(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        List<Object> values = new ArrayList<>();
        for (Punishment punishment : store.listPunishments()) {
            Map<String, Object> item = publicPunishment(punishment);
            item.put("playerUuid", punishment.playerId().toString());
            item.put("appealStatus", store.getAppeal(punishment.id())
                    .map(value -> value.status().name()).orElse(null));
            values.add(item);
        }
        sendJson(exchange, 200, Map.of("punishments", values));
    }

    private void appeals(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        List<Object> values = new ArrayList<>();
        for (Appeal appeal : store.listAppeals()) {
            Map<String, Object> item = appealView(appeal, true);
            item.put("punishment", store.getPunishment(appeal.punishmentId())
                    .map(this::publicPunishment).orElse(null));
            values.add(item);
        }
        sendJson(exchange, 200, Map.of("appeals", values));
    }

    private void resolve(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, Object> json = readJson(exchange);
        String id = string(json.get("id")).trim();
        boolean approved = Boolean.TRUE.equals(json.get("approved"));
        String note = truncate(string(json.get("note")).trim(), MAX_REASON_LENGTH);
        Punishment punishment = store.getPunishment(id)
                .orElseThrow(() -> new HttpError(404, "未找到该处罚 ID"));
        if (!store.resolveAppeal(id, approved, note, System.currentTimeMillis())) {
            throw new HttpError(404, "该处罚没有待处理的申诉");
        }
        store.addHistory(punishment.playerId(), (approved ? "[申诉通过] Web 管理员解封" : "[申诉驳回] Web 管理员")
                + (note.isEmpty() ? "" : "（" + note + "）"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("appeal", store.getAppeal(id).map(value -> appealView(value, true)).orElse(null));
        sendJson(exchange, 200, body);
    }

    private Map<String, Object> publicPunishment(Punishment value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", value.id());
        map.put("playerName", value.playerName());
        map.put("serverId", value.serverId());
        map.put("check", value.check());
        map.put("checkDisplay", value.check());
        map.put("vl", round1(value.vl()));
        map.put("hours", value.hours());
        map.put("banNumber", value.banNumber());
        map.put("bannedAt", value.bannedAt());
        map.put("expiresAt", value.expiresAt());
        boolean active;
        try {
            active = store.isPunishmentActive(value.id(), System.currentTimeMillis());
        } catch (SQLException error) {
            active = value.expiresAt() > System.currentTimeMillis();
        }
        map.put("active", active);
        map.put("warnings", value.warnings().stream().map(item -> Map.of(
                "at", item.at(), "check", item.check(), "stage", item.stage(), "vl", round1(item.vl()))).toList());
        map.put("detections", value.detections().stream().map(item -> Map.of(
                "at", item.at(), "check", item.check(), "vl", round1(item.vl()), "detail", item.detail())).toList());
        return map;
    }

    private static Map<String, Object> appealView(Appeal value, boolean admin) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("punishmentId", value.punishmentId());
        map.put("playerName", value.playerName());
        map.put("reason", value.reason());
        map.put("status", value.status().name());
        map.put("submittedAt", value.submittedAt());
        map.put("resolvedAt", value.resolvedAt());
        map.put("note", value.note());
        if (admin) map.put("contact", value.contact());
        return map;
    }

    private ThrowingHandler admin(ThrowingHandler handler) {
        return exchange -> {
            String supplied = exchange.getRequestHeaders().getFirst("X-Admin-Token");
            if (supplied == null || !MessageDigest.isEqual(
                    supplied.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8))) {
                throw new HttpError(401, "管理令牌无效或缺失");
            }
            handler.handle(exchange);
        };
    }

    private com.sun.net.httpserver.HttpHandler wrap(ThrowingHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (HttpError error) {
                sendJson(exchange, error.status(), error(error.getMessage()));
            } catch (SQLException error) {
                sendJson(exchange, 503, error("群组数据库暂时不可用，请稍后重试"));
            } catch (IllegalArgumentException error) {
                sendJson(exchange, 400, error("请求内容格式错误"));
            } catch (Exception error) {
                sendJson(exchange, 500, error("服务器内部错误"));
            } finally {
                exchange.close();
            }
        };
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream input = exchange.getRequestBody()) {
            bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new HttpError(413, "请求体过大");
        return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void requireMethod(HttpExchange exchange, String method) {
        if (!method.equals(exchange.getRequestMethod())) throw new HttpError(405, "仅支持 " + method);
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private static String clientIp(HttpExchange exchange) {
        var remote = exchange.getRemoteAddress().getAddress();
        if (remote.isLoopbackAddress()) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",", 2)[0].trim();
            }
        }
        return remote.getHostAddress();
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> value) throws IOException {
        byte[] body = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String value) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class HttpError extends RuntimeException {
        private final int status;

        private HttpError(int status, String message) {
            super(message);
            this.status = status;
        }

        private int status() { return status; }
    }

    private static final class RateLimiter {
        private final int limit;
        private final long windowMillis;
        private final Map<String, Window> windows = new java.util.concurrent.ConcurrentHashMap<>();

        private RateLimiter(int limit, long windowMillis) {
            this.limit = limit;
            this.windowMillis = windowMillis;
        }

        private boolean allow(String key) {
            long now = System.currentTimeMillis();
            Window window = windows.compute(key, (ignored, old) ->
                    old == null || now - old.startedAt >= windowMillis
                            ? new Window(now, 1) : new Window(old.startedAt, old.count + 1));
            return window.count <= limit;
        }

        private record Window(long startedAt, int count) {}
    }

    private static final class DashboardThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "sayaka-web-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
