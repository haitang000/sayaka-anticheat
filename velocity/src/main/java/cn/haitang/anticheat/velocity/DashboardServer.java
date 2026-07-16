package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.JdbcNetworkStore;
import cn.haitang.anticheat.shared.Json;
import cn.haitang.anticheat.shared.NetworkModels.Appeal;
import cn.haitang.anticheat.shared.NetworkModels.AppealFilter;
import cn.haitang.anticheat.shared.NetworkModels.AppealStatus;
import cn.haitang.anticheat.shared.NetworkModels.AppealSubmitResult;
import cn.haitang.anticheat.shared.NetworkModels.DashboardOverview;
import cn.haitang.anticheat.shared.NetworkModels.FilterOptions;
import cn.haitang.anticheat.shared.NetworkModels.Page;
import cn.haitang.anticheat.shared.NetworkModels.PardonResult;
import cn.haitang.anticheat.shared.NetworkModels.PlayerProfile;
import cn.haitang.anticheat.shared.NetworkModels.Punishment;
import cn.haitang.anticheat.shared.NetworkModels.PunishmentFilter;
import cn.haitang.anticheat.shared.NetworkModels.PunishmentView;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

final class DashboardServer {
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_REASON_LENGTH = 2000;
    private static final int MAX_CONTACT_LENGTH = 200;
    private static final int MAX_QUERY_LENGTH = 64;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final long DAY_MILLIS = 86_400_000L;

    private final JdbcNetworkStore store;
    private final NetworkControl control;
    private final VelocityUpdateManager updateManager;
    private final ProtectionState protection;
    private final Consumer<UUID> invalidateBanCache;
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final AdminAuthService adminAuth;
    private final String indexHtml;
    private final String publicUrl;
    private final String serverId;
    private final RateLimiter appealLimiter = new RateLimiter(20, 60_000L);
    private final OneTimeLoginTokens loginTokens = new OneTimeLoginTokens();

    private DashboardServer(JdbcNetworkStore store, NetworkControl control,
                            VelocityUpdateManager updateManager, ProtectionState protection,
                            Consumer<UUID> invalidateBanCache, HttpServer server,
                            ThreadPoolExecutor executor, AdminAuthService adminAuth, String indexHtml,
                            String publicUrl, String serverId) {
        this.store = store;
        this.control = control;
        this.updateManager = updateManager;
        this.protection = protection;
        this.invalidateBanCache = invalidateBanCache;
        this.server = server;
        this.executor = executor;
        this.adminAuth = adminAuth;
        this.indexHtml = indexHtml;
        this.publicUrl = publicUrl;
        this.serverId = serverId;
    }

    static DashboardServer start(JdbcNetworkStore store, IntSupplier onlinePlayers,
                                 VelocitySettings settings, Logger logger) throws IOException {
        return start(store, onlinePlayers, ignored -> {}, settings, logger);
    }

    static DashboardServer start(JdbcNetworkStore store, IntSupplier onlinePlayers,
                                 Consumer<UUID> invalidateBanCache,
                                 VelocitySettings settings, Logger logger) throws IOException {
        return start(store, onlinePlayers, invalidateBanCache, settings, logger, null);
    }

    static DashboardServer start(JdbcNetworkStore store, IntSupplier onlinePlayers,
                                 Consumer<UUID> invalidateBanCache,
                                 VelocitySettings settings, Logger logger,
                                 AdminAuthService injectedAuth) throws IOException {
        return start(store, controlFrom(onlinePlayers), null, ProtectionState.fromSettings(settings),
                invalidateBanCache, settings, logger, injectedAuth);
    }

    static DashboardServer start(JdbcNetworkStore store, NetworkControl control,
                                 VelocityUpdateManager updateManager, ProtectionState protection,
                                 Consumer<UUID> invalidateBanCache, VelocitySettings settings,
                                 Logger logger) throws IOException {
        return start(store, control, updateManager, protection, invalidateBanCache, settings, logger, null);
    }

    static DashboardServer start(JdbcNetworkStore store, NetworkControl control,
                                 VelocityUpdateManager updateManager, ProtectionState protection,
                                 Consumer<UUID> invalidateBanCache, VelocitySettings settings,
                                 Logger logger, AdminAuthService injectedAuth) throws IOException {
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
        boolean generated = settings.adminToken() == null || settings.adminToken().isBlank();
        String token = generated ? UUID.randomUUID().toString().replace("-", "") : settings.adminToken();
        AdminAuthService adminAuth = injectedAuth == null
                ? new AdminAuthService(token, settings) : injectedAuth;
        DashboardServer dashboard = new DashboardServer(
                store, control, updateManager, protection, invalidateBanCache, server, executor,
                adminAuth, html, settings.webPublicUrl(), settings.serverId());
        dashboard.register();
        server.start();
        logger.info("Sayaka 统一面板已启动: {}", dashboard.displayUrl());
        if (generated) logger.warn("未设置 SAYAKA_ADMIN_TOKEN，本次临时管理令牌: {}", token);
        return dashboard;
    }

    private static NetworkControl controlFrom(IntSupplier onlinePlayers) {
        return new NetworkControl() {
            @Override public int onlineCount() { return onlinePlayers.getAsInt(); }
            @Override public List<OnlinePlayer> onlinePlayers() { return List.of(); }
            @Override public boolean kick(UUID playerId, String reason) { return false; }
            @Override public List<ServerNode> servers() { return List.of(); }
        };
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String createOneTimeLoginUrl() {
        return displayUrl() + "#admin-login=" + loginTokens.issue();
    }

    private String displayUrl() {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl.endsWith("/") ? publicUrl : publicUrl + "/";
        }
        String host = server.getAddress().getHostString();
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("0:0:0:0:0:0:0:0")) {
            host = "127.0.0.1";
        } else if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + port() + "/";
    }

    private void register() {
        server.createContext("/api/appeal/lookup", wrap(this::appealLookup));
        server.createContext("/api/appeal/submit", wrap(this::appealSubmit));
        server.createContext("/api/admin/login/captcha", wrap(this::loginCaptcha));
        server.createContext("/api/admin/login/exchange", wrap(this::loginExchange));
        server.createContext("/api/admin/login", wrap(this::login));
        server.createContext("/api/admin/logout", wrap(admin(this::logout)));
        server.createContext("/api/admin/overview", wrap(admin(this::overview)));
        server.createContext("/api/admin/filters", wrap(admin(this::filters)));
        server.createContext("/api/admin/punishments/export", wrap(admin(this::exportPunishments)));
        server.createContext("/api/admin/punishments/pardon", wrap(admin(this::pardon)));
        server.createContext("/api/admin/punishments", wrap(admin(this::punishments)));
        server.createContext("/api/admin/appeals/resolve", wrap(admin(this::resolve)));
        server.createContext("/api/admin/appeals", wrap(admin(this::appeals)));
        server.createContext("/api/admin/players/search", wrap(admin(this::searchPlayers)));
        server.createContext("/api/admin/players/detail", wrap(admin(this::playerDetail)));
        server.createContext("/api/admin/whitelist/add", wrap(admin(this::addWhitelist)));
        server.createContext("/api/admin/whitelist/remove", wrap(admin(this::removeWhitelist)));
        server.createContext("/api/admin/whitelist", wrap(admin(this::whitelist)));
        server.createContext("/api/admin/system/update/check", wrap(admin(this::updateCheck)));
        server.createContext("/api/admin/system/update/download", wrap(admin(this::updateDownload)));
        server.createContext("/api/admin/system", wrap(admin(this::systemInfo)));
        server.createContext("/api/admin/network/players/kick", wrap(admin(this::kickPlayer)));
        server.createContext("/api/admin/network/players", wrap(admin(this::networkPlayers)));
        server.createContext("/api/admin/network/servers", wrap(admin(this::networkServers)));
        server.createContext("/api/admin/protection/set", wrap(admin(this::setProtection)));
        server.createContext("/", wrap(this::staticFile));
    }

    private void loginExchange(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        String ticket = string(readJson(exchange).get("ticket")).trim();
        if (!loginTokens.redeem(ticket)) {
            throw new HttpError(401, "一次性登录链接无效、已使用或已过期");
        }
        sendJson(exchange, 200, sessionResponse(adminAuth.issueSession()));
    }

    private void login(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        Map<String, Object> json = readJson(exchange);
        AdminAuthService.LoginResult result = adminAuth.login(clientIp(exchange),
                string(json.get("token")), string(json.get("captchaId")),
                string(json.get("captchaAnswer")));
        sendJson(exchange, 200, sessionResponse(result));
    }

    private void loginCaptcha(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        AdminAuthService.CaptchaChallenge challenge = adminAuth.createCaptcha(clientIp(exchange));
        sendJson(exchange, 200, Map.of(
                "challengeId", challenge.challengeId(),
                "imageDataUrl", challenge.imageDataUrl(),
                "expiresInSeconds", challenge.expiresInSeconds()));
    }

    private void logout(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "POST");
        adminAuth.revokeSession(exchange.getRequestHeaders().getFirst("X-Admin-Session"));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static Map<String, Object> sessionResponse(AdminAuthService.LoginResult result) {
        return Map.of("sessionToken", result.sessionToken(),
                "expiresInSeconds", result.expiresInSeconds());
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }
        sendText(exchange, 200, indexHtml, "text/html; charset=utf-8");
    }

    private void appealLookup(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        String query = queryParam(exchange, "query");
        if (query == null || query.isBlank()) query = queryParam(exchange, "id");
        if (query == null || query.isBlank()) throw new HttpError(400, "缺少处罚 ID 或玩家名称");
        Punishment punishment = store.getPunishment(query).orElse(null);
        if (punishment == null) {
            punishment = store.findLatestPunishmentByPlayerName(query)
                    .orElseThrow(() -> new HttpError(404, "未找到该处罚 ID 或玩家名称"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("punishment", punishmentMap(punishment,
                store.isPunishmentActive(punishment.id(), System.currentTimeMillis()), false));
        body.put("appeal", store.getAppeal(punishment.id()).map(value -> appealMap(value, false)).orElse(null));
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
        long now = System.currentTimeMillis();
        long to = longParam(exchange, "to", now);
        long from = longParam(exchange, "from", to - 30L * DAY_MILLIS);
        validateRange(from, to);
        DashboardOverview value = store.dashboardOverview(from, to, DAY_MILLIS, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("onlinePlayers", control.onlineCount());
        body.put("from", from);
        body.put("to", to);
        body.put("totalPunishments", value.totalPunishments());
        body.put("periodPunishments", value.periodPunishments());
        body.put("activeBans", value.activeBans());
        body.put("totalAppeals", value.totalAppeals());
        body.put("pendingAppeals", value.pendingAppeals());
        body.put("trend", value.trend().stream()
                .map(item -> Map.of("start", item.start(), "count", item.count())).toList());
        body.put("checks", value.checks().stream()
                .map(item -> Map.of("name", item.name(), "count", item.count())).toList());
        body.put("servers", value.servers().stream()
                .map(item -> Map.of("name", item.name(), "count", item.count())).toList());
        sendJson(exchange, 200, body);
    }

    private void filters(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        FilterOptions options = store.filterOptions();
        sendJson(exchange, 200, Map.of("servers", options.servers(), "checks", options.checks()));
    }

    private void punishments(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        int page = intParam(exchange, "page", 1, 1, Integer.MAX_VALUE);
        int pageSize = intParam(exchange, "pageSize", 20, 1, MAX_PAGE_SIZE);
        Page<PunishmentView> result = store.queryPunishments(
                punishmentFilter(exchange), page, pageSize, System.currentTimeMillis());
        List<Object> items = result.items().stream().map(value -> {
            Map<String, Object> item = punishmentMap(value.punishment(), value.active(), true);
            item.put("appealStatus", value.appealStatus() == null ? null : value.appealStatus().name());
            return (Object) item;
        }).toList();
        sendJson(exchange, 200, pageMap(result, items));
    }

    private void appeals(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        int page = intParam(exchange, "page", 1, 1, Integer.MAX_VALUE);
        int pageSize = intParam(exchange, "pageSize", 20, 1, MAX_PAGE_SIZE);
        AppealFilter filter = new AppealFilter(query(exchange), appealStatus(exchange, "status"),
                longParam(exchange, "from", 0L), longParam(exchange, "to", 0L));
        validateOptionalRange(filter.from(), filter.to());
        var result = store.queryAppeals(filter, page, pageSize, System.currentTimeMillis());
        List<Object> items = result.items().stream().map(value -> {
            Map<String, Object> item = appealMap(value.appeal(), true);
            item.put("punishment", punishmentMap(value.punishment(), value.active(), true));
            return (Object) item;
        }).toList();
        sendJson(exchange, 200, pageMap(result, items));
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
            throw new HttpError(409, "该处罚没有待处理的申诉");
        }
        store.addHistory(punishment.playerId(),
                (approved ? "[申诉通过] Web 管理员解封" : "[申诉驳回] Web 管理员")
                        + (note.isEmpty() ? "" : "（" + note + "）"));
        if (approved) invalidateBanCache.accept(punishment.playerId());
        sendJson(exchange, 200, Map.of("ok", true,
                "appeal", store.getAppeal(id).map(value -> appealMap(value, true)).orElse(null)));
    }

    private void pardon(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, Object> json = readJson(exchange);
        String id = string(json.get("id")).trim();
        String note = truncate(string(json.get("note")).trim(), MAX_REASON_LENGTH);
        boolean resetBanCount = Boolean.TRUE.equals(json.get("resetBanCount"));
        Punishment punishment = store.getPunishment(id)
                .orElseThrow(() -> new HttpError(404, "未找到该处罚 ID"));
        PardonResult result = store.pardonPunishment(id, resetBanCount, note, System.currentTimeMillis());
        if (result == PardonResult.PUNISHMENT_NOT_FOUND) throw new HttpError(404, "未找到该处罚 ID");
        if (result == PardonResult.NOT_ACTIVE) throw new HttpError(409, "该处罚不是玩家当前生效的封禁");
        invalidateBanCache.accept(punishment.playerId());
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void searchPlayers(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        String query = query(exchange);
        if (query == null || query.isBlank()) {
            sendJson(exchange, 200, Map.of("players", List.of()));
            return;
        }
        List<Map<String, String>> players = store.searchPlayers(query, 20).stream().map(value -> Map.of(
                "playerUuid", value.playerId().toString(), "playerName", value.playerName())).toList();
        sendJson(exchange, 200, Map.of("players", players));
    }

    private void playerDetail(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        UUID playerId = uuidParam(exchange, "uuid");
        PlayerProfile profile = store.playerProfile(playerId, System.currentTimeMillis())
                .orElseThrow(() -> new HttpError(404, "未找到该玩家"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerUuid", profile.playerId().toString());
        body.put("playerName", profile.playerName());
        body.put("banCount", profile.banCount());
        body.put("whitelisted", profile.whitelisted());
        body.put("activePunishmentId", profile.activeBan() == null ? null : profile.activeBan().punishmentId());
        body.put("history", profile.history().stream()
                .map(item -> Map.of("at", item.at(), "text", item.text())).toList());
        body.put("punishments", profile.punishments().stream().map(item -> punishmentMap(item,
                profile.activeBan() != null && profile.activeBan().punishmentId().equals(item.id()), true)).toList());
        sendJson(exchange, 200, body);
    }

    private void whitelist(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        List<Map<String, String>> items = store.listWhitelist().stream().map(entry -> Map.of(
                "playerUuid", entry.getKey().toString(), "playerName", entry.getValue())).toList();
        sendJson(exchange, 200, Map.of("items", items));
    }

    private void addWhitelist(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        UUID playerId = uuidValue(readJson(exchange).get("uuid"));
        PlayerProfile profile = store.playerProfile(playerId, System.currentTimeMillis())
                .orElseThrow(() -> new HttpError(404, "只能添加数据库中已知的玩家"));
        store.addWhitelistAudited(playerId, profile.playerName(), System.currentTimeMillis());
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void removeWhitelist(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        UUID playerId = uuidValue(readJson(exchange).get("uuid"));
        if (!store.removeWhitelistAudited(playerId, System.currentTimeMillis())) {
            throw new HttpError(404, "该玩家不在白名单中");
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void exportPunishments(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        long now = System.currentTimeMillis();
        Page<PunishmentView> result = store.queryPunishments(
                punishmentFilter(exchange), 1, MAX_EXPORT_ROWS + 1, now);
        if (result.total() > MAX_EXPORT_ROWS) {
            throw new HttpError(422, "匹配记录超过 10000 条，请缩小筛选范围后再导出");
        }
        StringBuilder csv = new StringBuilder("\ufeff处罚 ID,玩家,玩家 UUID,来源服,检测,VL,封禁时长(小时),封禁时间,到期时间,状态,申诉状态\r\n");
        for (PunishmentView value : result.items()) {
            Punishment item = value.punishment();
            csv.append(csvCell(item.id())).append(',')
                    .append(csvCell(item.playerName())).append(',')
                    .append(csvCell(item.playerId().toString())).append(',')
                    .append(csvCell(item.serverId())).append(',')
                    .append(csvCell(item.check())).append(',')
                    .append(round1(item.vl())).append(',').append(item.hours()).append(',')
                    .append(item.bannedAt()).append(',').append(item.expiresAt()).append(',')
                    .append(csvCell(value.active() ? "封禁中" : "已到期")).append(',')
                    .append(csvCell(value.appealStatus() == null ? "" : value.appealStatus().name()))
                    .append("\r\n");
        }
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"sayaka-punishments.csv\"");
        sendText(exchange, 200, csv.toString(), "text/csv; charset=utf-8");
    }

    private void systemInfo(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serverId", serverId);
        body.put("onlinePlayers", control.onlineCount());
        body.put("databaseOnline", store.healthCheck());
        body.put("update", updateMap());
        sendJson(exchange, 200, body);
    }

    private void updateCheck(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        requireUpdateManager();
        runUpdate(updateManager::check);
        sendJson(exchange, 200, Map.of("ok", true, "update", updateMap()));
    }

    private void updateDownload(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        requireUpdateManager();
        runUpdate(updateManager::download);
        sendJson(exchange, 200, Map.of("ok", true, "update", updateMap()));
    }

    private void requireUpdateManager() {
        if (updateManager == null) throw new HttpError(503, "更新功能未启用");
    }

    private void runUpdate(UpdateAction action) {
        try {
            action.run();
        } catch (IllegalStateException busy) {
            throw new HttpError(409, busy.getMessage());
        } catch (IOException error) {
            throw new HttpError(502, "更新操作失败：" + safeMessage(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HttpError(502, "更新操作被中断");
        }
    }

    private Map<String, Object> updateMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (updateManager == null) {
            map.put("enabled", false);
            return map;
        }
        VelocityUpdateManager.Snapshot snapshot = updateManager.snapshot();
        map.put("enabled", true);
        map.put("currentVersion", snapshot.currentVersion());
        map.put("lastCheckedAt", snapshot.lastCheckedAt());
        map.put("lastError", snapshot.lastError());
        map.put("available", availableMap(snapshot.available()));
        map.put("staged", stagedMap(snapshot.staged()));
        return map;
    }

    private static Object availableMap(VelocityUpdateManager.Available available) {
        if (available == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", available.version());
        map.put("tag", available.tag());
        map.put("pageUrl", available.pageUrl());
        return map;
    }

    private static Object stagedMap(VelocityUpdateManager.Staged staged) {
        if (staged == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", staged.version());
        map.put("fileName", staged.fileName());
        map.put("path", staged.path());
        map.put("sizeBytes", staged.sizeBytes());
        map.put("stagedAt", staged.stagedAt());
        return map;
    }

    private void networkPlayers(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        List<Object> items = control.onlinePlayers().stream().map(player -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("playerUuid", player.id().toString());
            map.put("playerName", player.name());
            map.put("server", player.server());
            map.put("ping", player.pingMillis());
            return (Object) map;
        }).toList();
        sendJson(exchange, 200, Map.of("players", items, "total", items.size()));
    }

    private void kickPlayer(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, Object> json = readJson(exchange);
        UUID id = uuidValue(json.get("uuid"));
        String reason = truncate(string(json.get("reason")).trim(), MAX_REASON_LENGTH);
        if (reason.isEmpty()) reason = "你已被管理员从服务器踢出";
        if (!control.kick(id, reason)) throw new HttpError(404, "该玩家当前不在线");
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void networkServers(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "GET");
        var seen = new java.util.LinkedHashSet<String>();
        List<Object> items = new java.util.ArrayList<>();
        for (NetworkControl.ServerNode node : control.servers()) {
            seen.add(node.name().toLowerCase(java.util.Locale.ROOT));
            items.add(serverMap(node.name(), node.playerCount(), node.reachable(), node.pingMillis()));
        }
        for (String name : protection.knownOverrides().keySet()) {
            if (seen.add(name)) items.add(serverMap(name, 0, false, -1L));
        }
        sendJson(exchange, 200, Map.of("defaultEnabled", protection.defaultEnabled(), "servers", items));
    }

    private Map<String, Object> serverMap(String name, int playerCount, boolean reachable, long ping) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("playerCount", playerCount);
        map.put("reachable", reachable);
        map.put("ping", ping);
        map.put("protectionEnabled", protection.enabledFor(name));
        map.put("source", protection.hasRuntimeOverride(name) ? "override"
                : protection.hasConfigOverride(name) ? "config" : "default");
        return map;
    }

    private void setProtection(HttpExchange exchange) throws Exception {
        requireMethod(exchange, "POST");
        Map<String, Object> json = readJson(exchange);
        String server = string(json.get("server")).trim();
        if (server.isEmpty()) throw new HttpError(400, "缺少服务器名称");
        if (server.length() > 64) throw new HttpError(400, "服务器名称过长");
        if (Boolean.TRUE.equals(json.get("reset"))) {
            store.clearProtectionOverride(server);
            protection.clearRuntimeOverride(server);
        } else if (json.get("enabled") instanceof Boolean enabled) {
            store.setProtectionOverride(server, enabled, System.currentTimeMillis());
            protection.setRuntimeOverride(server, enabled);
        } else {
            throw new HttpError(400, "缺少 enabled 布尔值");
        }
        sendJson(exchange, 200, Map.of("ok", true, "protectionEnabled", protection.enabledFor(server)));
    }

    private PunishmentFilter punishmentFilter(HttpExchange exchange) {
        String status = normalizedParam(exchange, "status");
        Boolean active = switch (status == null ? "all" : status) {
            case "all" -> null;
            case "active" -> true;
            case "expired" -> false;
            default -> throw new HttpError(400, "无效的处罚状态");
        };
        long from = longParam(exchange, "from", 0L);
        long to = longParam(exchange, "to", 0L);
        validateOptionalRange(from, to);
        return new PunishmentFilter(query(exchange), active, appealStatus(exchange, "appealStatus"),
                normalizedParam(exchange, "server"), normalizedParam(exchange, "check"), from, to);
    }

    private static Map<String, Object> pageMap(Page<?> page, List<Object> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page.page());
        body.put("pageSize", page.pageSize());
        body.put("total", page.total());
        return body;
    }

    private static Map<String, Object> punishmentMap(Punishment value, boolean active, boolean admin) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", value.id());
        map.put("playerName", value.playerName());
        if (admin) map.put("playerUuid", value.playerId().toString());
        map.put("serverId", value.serverId());
        map.put("check", value.check());
        map.put("checkDisplay", value.check());
        map.put("vl", round1(value.vl()));
        map.put("hours", value.hours());
        map.put("banNumber", value.banNumber());
        map.put("bannedAt", value.bannedAt());
        map.put("expiresAt", value.expiresAt());
        map.put("active", active);
        map.put("warnings", value.warnings().stream().map(item -> Map.of(
                "at", item.at(), "check", item.check(), "stage", item.stage(), "vl", round1(item.vl()))).toList());
        map.put("detections", value.detections().stream().map(item -> Map.of(
                "at", item.at(), "check", item.check(), "vl", round1(item.vl()), "detail", item.detail())).toList());
        return map;
    }

    private static Map<String, Object> appealMap(Appeal value, boolean admin) {
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
            String supplied = exchange.getRequestHeaders().getFirst("X-Admin-Session");
            if (!adminAuth.validateSession(supplied)) {
                throw new HttpError(401, "管理会话无效或已过期", "SESSION_EXPIRED", false, 0L);
            }
            handler.handle(exchange);
        };
    }

    private com.sun.net.httpserver.HttpHandler wrap(ThrowingHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (AdminAuthService.AuthFailure error) {
                if (error.retryAfterSeconds() > 0) {
                    exchange.getResponseHeaders().set("Retry-After",
                            String.valueOf(error.retryAfterSeconds()));
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", error.getMessage());
                body.put("code", error.code());
                body.put("captchaRequired", error.captchaRequired());
                if (error.retryAfterSeconds() > 0) {
                    body.put("retryAfterSeconds", error.retryAfterSeconds());
                }
                sendJson(exchange, error.status(), body);
            } catch (HttpError error) {
                sendJson(exchange, error.status(), error(error));
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

    private static String normalizedParam(HttpExchange exchange, String name) {
        String value = queryParam(exchange, name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String query(HttpExchange exchange) {
        String value = normalizedParam(exchange, "q");
        if (value != null && value.length() > MAX_QUERY_LENGTH) throw new HttpError(400, "搜索内容过长");
        return value;
    }

    private static int intParam(HttpExchange exchange, String name, int fallback, int min, int max) {
        String value = queryParam(exchange, name);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new HttpError(400, "无效的参数 " + name);
        }
    }

    private static long longParam(HttpExchange exchange, String name, long fallback) {
        String value = queryParam(exchange, name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new HttpError(400, "无效的参数 " + name);
        }
    }

    private static AppealStatus appealStatus(HttpExchange exchange, String name) {
        String value = normalizedParam(exchange, name);
        if (value == null || value.equalsIgnoreCase("all") || value.equalsIgnoreCase("none")) return null;
        try {
            return AppealStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new HttpError(400, "无效的申诉状态");
        }
    }

    private static UUID uuidParam(HttpExchange exchange, String name) {
        return uuidValue(queryParam(exchange, name));
    }

    private static UUID uuidValue(Object value) {
        try {
            return UUID.fromString(string(value).trim());
        } catch (IllegalArgumentException invalid) {
            throw new HttpError(400, "无效的玩家 UUID");
        }
    }

    private static void validateRange(long from, long to) {
        if (from <= 0 || to <= from || to - from > 366L * DAY_MILLIS) {
            throw new HttpError(400, "无效的时间范围");
        }
    }

    private static void validateOptionalRange(long from, long to) {
        if ((from < 0 || to < 0) || (from > 0 && to > 0 && to <= from)) {
            throw new HttpError(400, "无效的时间范围");
        }
    }

    private static String clientIp(HttpExchange exchange) {
        var remote = exchange.getRemoteAddress().getAddress();
        if (remote.isLoopbackAddress()) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        }
        return remote.getHostAddress();
    }

    private static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(exchange, status, body);
    }

    private static void sendText(HttpExchange exchange, int status, String value, String contentType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        }
        sendBytes(exchange, status, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static Map<String, Object> error(HttpError error) {
        if (error.code() == null) return error(error.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.getMessage());
        body.put("code", error.code());
        body.put("captchaRequired", error.captchaRequired());
        if (error.retryAfterSeconds() > 0) body.put("retryAfterSeconds", error.retryAfterSeconds());
        return body;
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String csvCell(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    @FunctionalInterface
    private interface UpdateAction {
        void run() throws IOException, InterruptedException;
    }

    private static final class HttpError extends RuntimeException {
        private final int status;
        private final String code;
        private final boolean captchaRequired;
        private final long retryAfterSeconds;

        private HttpError(int status, String message) {
            this(status, message, null, false, 0L);
        }

        private HttpError(int status, String message, String code,
                          boolean captchaRequired, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.code = code;
            this.captchaRequired = captchaRequired;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private int status() { return status; }
        private String code() { return code; }
        private boolean captchaRequired() { return captchaRequired; }
        private long retryAfterSeconds() { return retryAfterSeconds; }
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
