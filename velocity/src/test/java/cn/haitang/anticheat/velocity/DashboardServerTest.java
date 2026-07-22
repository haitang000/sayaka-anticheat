package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.DatabaseConfig;
import cn.haitang.anticheat.shared.JdbcNetworkStore;
import cn.haitang.anticheat.shared.Json;
import cn.haitang.anticheat.shared.NetworkModels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DashboardServerTest {
    @TempDir
    Path tempDir;

    @Test
    void writesAndLoadsTheBundledVelocityConfiguration() throws Exception {
        VelocitySettings settings = VelocitySettings.load(tempDir);

        assertTrue(java.nio.file.Files.isRegularFile(tempDir.resolve("config.toml")));
        assertEquals("127.0.0.1", settings.webBind());
        assertEquals(8080, settings.webPort());
        assertEquals(3, settings.captchaAfterFailures());
        assertEquals(10, settings.loginFailureLimit());
        assertEquals(600_000L, settings.loginWindowMillis());
        assertEquals(43_200_000L, settings.sessionIdleMillis());
    }

    @Test
    void rejectsInvalidWebSecurityConfiguration() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("config.toml"), """
                [web.security]
                captcha-after-failures = 3
                login-failure-limit = 3
                login-window-seconds = 600
                session-idle-seconds = 43200
                """);

        IOException error = assertThrows(IOException.class, () -> VelocitySettings.load(tempDir));
        assertTrue(error.getMessage().contains("login-failure-limit"));
    }

    @Test
    void loadsCustomWebSecurityConfiguration() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("config.toml"), """
                [database]
                jdbc-url = "jdbc:h2:mem:custom_security"

                [web.security]
                captcha-after-failures = 4
                login-failure-limit = 12
                login-window-seconds = 900
                session-idle-seconds = 7200
                """);

        VelocitySettings settings = VelocitySettings.load(tempDir);

        assertEquals(4, settings.captchaAfterFailures());
        assertEquals(12, settings.loginFailureLimit());
        assertEquals(900_000L, settings.loginWindowMillis());
        assertEquals(7_200_000L, settings.sessionIdleMillis());
    }

    @Test
    void servesBundledPanelAndRejectsASecondListenerOnTheSamePort() throws Exception {
        String database = "dashboard_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        VelocitySettings dynamic = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        AtomicReference<String> requestThread = new AtomicReference<>();
        DashboardServer first = DashboardServer.start(store, () -> {
            requestThread.set(Thread.currentThread().getName());
            return 0;
        }, dynamic,
                LoggerFactory.getLogger("dashboard-test"));
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + first.port() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Sayaka AntiCheat"));
            assertTrue(response.body().contains("/api/appeal/captcha"));
            assertTrue(response.body().contains("X-Captcha-Id"));

            String loginUrl = first.createOneTimeLoginUrl();
            assertTrue(loginUrl.startsWith("http://127.0.0.1:" + first.port() + "/#admin-login="));
            String ticket = new URI(loginUrl).getFragment().substring("admin-login=".length());
            URI exchange = URI.create("http://127.0.0.1:" + first.port()
                    + "/api/admin/login/exchange");
            HttpResponse<String> login = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(exchange).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("ticket", ticket))))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            String session = String.valueOf(Json.parseObject(login.body()).get("sessionToken"));
            assertTrue(session.matches("[A-Za-z0-9_-]{43}"));
            HttpResponse<String> overview = clientGet(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + first.port() + "/api/admin/overview"), session);
            assertEquals(200, overview.statusCode());
            assertTrue(requestThread.get().startsWith("sayaka-web-"));
            HttpResponse<String> reused = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(exchange).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("ticket", ticket))))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, reused.statusCode());

            VelocitySettings conflicting = new VelocitySettings("velocity-test", databaseConfig,
                    true, "127.0.0.1", first.port(), "", "test-token", 1, 1000L);
            assertThrows(IOException.class, () -> DashboardServer.start(store, () -> 0, conflicting,
                    LoggerFactory.getLogger("dashboard-test")));
        } finally {
            first.stop();
        }
    }

    @Test
    void servesPagedAdminOperationsAndCsvExport() throws Exception {
        String database = "dashboard_api_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        var punishment = store.prepareEnforcement(request(player, "=Formula", "lobby"), now).punishment();
        AtomicReference<UUID> invalidated = new AtomicReference<>();
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        DashboardServer dashboard = DashboardServer.start(store, () -> 12, invalidated::set, settings,
                LoggerFactory.getLogger("dashboard-api-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(root.resolve("/api/admin/punishments")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());
            HttpResponse<String> legacyToken = client.send(HttpRequest.newBuilder(
                            root.resolve("/api/admin/punishments"))
                    .header("X-Admin-Token", "test-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, legacyToken.statusCode());
            String session = login(client, root, "test-token");

            HttpResponse<String> page = get(client, root.resolve(
                    "/api/admin/punishments?q=Formula&page=1&pageSize=20"), session);
            assertEquals(200, page.statusCode());
            Map<String, Object> pageJson = Json.parseObject(page.body());
            assertEquals(1L, ((Number) pageJson.get("total")).longValue());
            assertEquals(1, ((List<?>) pageJson.get("items")).size());

            HttpResponse<String> invalidPage = get(client, root.resolve(
                    "/api/admin/punishments?pageSize=101"), session);
            assertEquals(400, invalidPage.statusCode());

            HttpResponse<String> addWhitelist = post(client, root.resolve("/api/admin/whitelist/add"),
                    Map.of("uuid", player.toString()), session);
            assertEquals(200, addWhitelist.statusCode());
            assertTrue(store.isWhitelisted(player));

            HttpResponse<String> detail = get(client, root.resolve(
                    "/api/admin/players/detail?uuid=" + player), session);
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains("Formula"));

            HttpResponse<String> csv = get(client, root.resolve("/api/admin/punishments/export"), session);
            assertEquals(200, csv.statusCode());
            assertTrue(csv.headers().firstValue("Content-Disposition").orElse("").contains(".csv"));
            assertTrue(csv.body().startsWith("\ufeff"));
            assertTrue(csv.body().contains("\"'=Formula\""));

            HttpResponse<String> pardon = post(client, root.resolve("/api/admin/punishments/pardon"),
                    Map.of("id", punishment.id(), "note", "Web review", "resetBanCount", true), session);
            assertEquals(200, pardon.statusCode());
            assertEquals(player, invalidated.get());
            assertFalse(store.findActiveBan(player, now + 1).isPresent());
            assertEquals(0, store.banCount(player));
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void approvingAnAppealInvalidatesTheVelocityBanCache() throws Exception {
        String database = "dashboard_appeal_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        String id = store.prepareEnforcement(request(player, "Appealer", "games"), now).punishment().id();
        store.submitAppeal(id, "这是一次误判，请重新检查", "discord", now + 1);
        AtomicReference<UUID> invalidated = new AtomicReference<>();
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, invalidated::set, settings,
                LoggerFactory.getLogger("dashboard-appeal-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();
            String session = login(client, root, "test-token");
            HttpResponse<String> response = post(client, root.resolve("/api/admin/appeals/resolve"),
                    Map.of("id", id, "approved", true, "note", "evidence reviewed"), session);
            assertEquals(200, response.statusCode());
            assertEquals(player, invalidated.get());
            assertFalse(store.findActiveBan(player, now + 2).isPresent());
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void looksUpAndSubmitsAppealByPlayerName() throws Exception {
        String database = "dashboard_player_appeal_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        String id = store.prepareEnforcement(
                request(UUID.randomUUID(), "Appealer", "games"), System.currentTimeMillis()).punishment().id();
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        AtomicLong captchaNow = new AtomicLong(1_000L);
        CaptchaService appealCaptchas = new CaptchaService(captchaNow::get, new SecureRandom(),
                () -> "ABCDE", (answer, random) -> new byte[] {1, 2, 3});
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, settings,
                LoggerFactory.getLogger("dashboard-player-appeal-test"), appealCaptchas);
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> legacyGet = client.send(HttpRequest.newBuilder(
                            root.resolve("/api/appeal/lookup?query=appealer")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, legacyGet.statusCode());
            assertEquals("CAPTCHA_REQUIRED", Json.parseObject(legacyGet.body()).get("code"));
            HttpResponse<String> missingCaptcha = lookup(client, root, "appealer", null, null);
            assertEquals(403, missingCaptcha.statusCode());
            assertEquals("CAPTCHA_REQUIRED", Json.parseObject(missingCaptcha.body()).get("code"));

            Map<String, Object> getChallenge = appealCaptcha(client, root);
            HttpResponse<String> getLookup = lookupGet(client, root, "appealer",
                    String.valueOf(getChallenge.get("challengeId")), "ABCDE");
            assertEquals(200, getLookup.statusCode());

            Map<String, Object> challenge = appealCaptcha(client, root);
            HttpResponse<String> lookup = lookup(client, root, "appealer",
                    String.valueOf(challenge.get("challengeId")), "abcde");
            assertEquals(200, lookup.statusCode());
            Map<?, ?> punishment = (Map<?, ?>) Json.parseObject(lookup.body()).get("punishment");
            assertEquals(id, punishment.get("id"));

            HttpResponse<String> replay = lookup(client, root, "appealer",
                    String.valueOf(challenge.get("challengeId")), "ABCDE");
            assertEquals(403, replay.statusCode());
            assertEquals("CAPTCHA_INVALID", Json.parseObject(replay.body()).get("code"));

            Map<String, Object> missingChallenge = appealCaptcha(client, root);
            HttpResponse<String> notFound = lookup(client, root, "missing-player",
                    String.valueOf(missingChallenge.get("challengeId")), "ABCDE");
            assertEquals(404, notFound.statusCode());
            assertEquals(403, lookup(client, root, "appealer",
                    String.valueOf(missingChallenge.get("challengeId")), "ABCDE").statusCode());

            HttpResponse<String> submit = client.send(HttpRequest.newBuilder(root.resolve("/api/appeal/submit"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of(
                                    "id", punishment.get("id"), "reason", "请重新检查这次处罚")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, submit.statusCode());
            Map<?, ?> submittedAppeal = (Map<?, ?>) Json.parseObject(submit.body()).get("appeal");
            assertEquals("PENDING", submittedAppeal.get("status"));
            assertEquals(NetworkModels.AppealStatus.PENDING, store.getAppeal(id).orElseThrow().status());
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void requiresCaptchaAndRateLimitsManualTokenLogin() throws Exception {
        String database = "dashboard_auth_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L,
                1, 3, 600_000L, 43_200_000L, true, Map.of());
        AtomicLong now = new AtomicLong(1_000L);
        AdminAuthService auth = new AdminAuthService("test-token", 1, 3,
                600_000L, 43_200_000L, now::get, new SecureRandom(), () -> "ABCDE",
                (answer, random) -> new byte[] {1, 2, 3});
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, ignored -> {}, settings,
                LoggerFactory.getLogger("dashboard-auth-test"), auth);
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> first = loginResponse(client, root, "wrong", null, null);
            assertEquals(401, first.statusCode());
            assertEquals(true, Json.parseObject(first.body()).get("captchaRequired"));

            Map<String, Object> firstCaptcha = captcha(client, root);
            HttpResponse<String> second = loginResponse(client, root, "wrong",
                    String.valueOf(firstCaptcha.get("challengeId")), "ABCDE");
            assertEquals(401, second.statusCode());
            Map<String, Object> secondCaptcha = captcha(client, root);
            HttpResponse<String> limited = loginResponse(client, root, "wrong",
                    String.valueOf(secondCaptcha.get("challengeId")), "ABCDE");
            assertEquals(429, limited.statusCode());
            assertTrue(limited.headers().firstValue("Retry-After").isPresent());
            assertEquals("RATE_LIMITED", Json.parseObject(limited.body()).get("code"));

            now.addAndGet(600_000L);
            String session = login(client, root, "test-token");
            assertEquals(200, clientGet(client, root.resolve("/api/admin/overview"), session).statusCode());
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void servesSystemNetworkStatusAndRuntimeProtectionToggles() throws Exception {
        String database = "dashboard_ops_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        UUID alice = UUID.randomUUID();
        AtomicReference<UUID> kicked = new AtomicReference<>();
        NetworkControl control = new NetworkControl() {
            @Override public int onlineCount() { return 3; }
            @Override public List<OnlinePlayer> onlinePlayers() {
                return List.of(new OnlinePlayer(alice, "Alice", "lobby", 42L));
            }
            @Override public boolean kick(UUID playerId, String reason) {
                if (!alice.equals(playerId)) return false;
                kicked.set(playerId);
                return true;
            }
            @Override public List<ServerNode> servers() {
                return List.of(new ServerNode("lobby", 1, true, 5L),
                        new ServerNode("survival", 0, false, -1L));
            }
        };
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        ProtectionState protection = ProtectionState.fromSettings(settings);
        VelocityUpdateManager updateManager = new VelocityUpdateManager(
                "2.1.0.5", tempDir.resolve("updates"));
        DashboardServer dashboard = DashboardServer.start(store, control, updateManager, protection,
                ignored -> {}, settings, LoggerFactory.getLogger("dashboard-ops-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(401, client.send(HttpRequest.newBuilder(root.resolve("/api/admin/system")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            String session = login(client, root, "test-token");

            Map<String, Object> system = Json.parseObject(get(client, root.resolve("/api/admin/system"), session).body());
            assertEquals(3L, ((Number) system.get("onlinePlayers")).longValue());
            assertEquals(Boolean.TRUE, system.get("databaseOnline"));
            Map<?, ?> update = (Map<?, ?>) system.get("update");
            assertEquals(Boolean.TRUE, update.get("enabled"));
            assertEquals("2.1.0.5", update.get("currentVersion"));

            Map<String, Object> players = Json.parseObject(
                    get(client, root.resolve("/api/admin/network/players"), session).body());
            assertEquals(1L, ((Number) players.get("total")).longValue());
            assertTrue(players.toString().contains("Alice"));

            assertEquals(200, post(client, root.resolve("/api/admin/network/players/kick"),
                    Map.of("uuid", alice.toString()), session).statusCode());
            assertEquals(alice, kicked.get());
            assertEquals(404, post(client, root.resolve("/api/admin/network/players/kick"),
                    Map.of("uuid", UUID.randomUUID().toString()), session).statusCode());

            Map<String, Object> servers = Json.parseObject(
                    get(client, root.resolve("/api/admin/network/servers"), session).body());
            assertEquals(Boolean.TRUE, servers.get("defaultEnabled"));
            assertEquals(2, ((List<?>) servers.get("servers")).size());

            HttpResponse<String> disable = post(client, root.resolve("/api/admin/protection/set"),
                    Map.of("server", "lobby", "enabled", false), session);
            assertEquals(200, disable.statusCode());
            assertEquals(Boolean.FALSE, Json.parseObject(disable.body()).get("protectionEnabled"));
            assertFalse(protection.enabledFor("lobby"));
            assertEquals(Boolean.FALSE, store.protectionOverrides().get("lobby"));

            HttpResponse<String> reset = post(client, root.resolve("/api/admin/protection/set"),
                    Map.of("server", "lobby", "reset", true), session);
            assertEquals(200, reset.statusCode());
            assertTrue(protection.enabledFor("lobby"));
            assertTrue(store.protectionOverrides().isEmpty());

            assertEquals(400, post(client, root.resolve("/api/admin/protection/set"),
                    Map.of("server", "lobby"), session).statusCode());
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void servesManualBansBatchPardonsActivityStatsAndBroadcast() throws Exception {
        String database = "dashboard_features_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        long now = System.currentTimeMillis();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        var first = store.prepareEnforcement(request(alice, "Alice", "lobby"), now).punishment();
        var second = store.prepareEnforcement(request(bob, "Bob", "games"), now + 1).punishment();
        store.addStrike(carol, "Carol", now);
        AtomicReference<String> broadcasted = new AtomicReference<>();
        NetworkControl control = new NetworkControl() {
            @Override public int onlineCount() { return 3; }
            @Override public List<OnlinePlayer> onlinePlayers() { return List.of(); }
            @Override public boolean kick(UUID playerId, String reason) { return false; }
            @Override public int broadcast(String message) {
                broadcasted.set(message);
                return 3;
            }
            @Override public List<ServerNode> servers() { return List.of(); }
        };
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        DashboardServer dashboard = DashboardServer.start(store, control, null,
                ProtectionState.fromSettings(settings), ignored -> {}, settings,
                LoggerFactory.getLogger("dashboard-features-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();
            String session = login(client, root, "test-token");

            HttpResponse<String> manual = post(client, root.resolve("/api/admin/punishments/manual"),
                    Map.of("uuid", carol.toString(), "hours", 48, "reason", "人工确认作弊"), session);
            assertEquals(200, manual.statusCode());
            assertTrue(store.findActiveBan(carol, now + 2).isPresent());
            assertEquals(409, post(client, root.resolve("/api/admin/punishments/manual"),
                    Map.of("uuid", carol.toString(), "hours", 24), session).statusCode());
            assertEquals(404, post(client, root.resolve("/api/admin/punishments/manual"),
                    Map.of("uuid", UUID.randomUUID().toString(), "hours", 24), session).statusCode());
            assertEquals(400, post(client, root.resolve("/api/admin/punishments/manual"),
                    Map.of("uuid", carol.toString(), "hours", 0), session).statusCode());

            long newExpiry = now + 96 * 3_600_000L;
            HttpResponse<String> adjust = post(client, root.resolve("/api/admin/punishments/adjust"),
                    Map.of("id", first.id(), "expiresAt", newExpiry, "note", "延长"), session);
            assertEquals(200, adjust.statusCode());
            assertEquals(newExpiry, store.findActiveBan(alice, now + 3).orElseThrow().expiresAt());
            assertEquals(400, post(client, root.resolve("/api/admin/punishments/adjust"),
                    Map.of("id", first.id(), "expiresAt", now - 1), session).statusCode());
            assertEquals(404, post(client, root.resolve("/api/admin/punishments/adjust"),
                    Map.of("id", UUID.randomUUID().toString(), "expiresAt", newExpiry), session).statusCode());

            HttpResponse<String> batch = post(client, root.resolve("/api/admin/punishments/pardon-batch"),
                    Map.of("ids", List.of(first.id(), second.id(), UUID.randomUUID().toString()),
                            "note", "批量复查", "resetBanCount", true), session);
            assertEquals(200, batch.statusCode());
            Map<String, Object> batchJson = Json.parseObject(batch.body());
            assertEquals(2L, ((Number) batchJson.get("succeeded")).longValue());
            assertEquals(1, ((List<?>) batchJson.get("failed")).size());
            assertFalse(store.findActiveBan(alice, now + 4).isPresent());
            assertFalse(store.findActiveBan(bob, now + 4).isPresent());
            assertEquals(400, post(client, root.resolve("/api/admin/punishments/pardon-batch"),
                    Map.of("ids", List.of()), session).statusCode());

            HttpResponse<String> activity = get(client, root.resolve("/api/admin/activity?limit=20"), session);
            assertEquals(200, activity.statusCode());
            List<?> items = (List<?>) Json.parseObject(activity.body()).get("items");
            assertEquals(3, items.size());

            HttpResponse<String> stats = get(client, root.resolve(
                    "/api/admin/stats?tzOffsetMinutes=-480"), session);
            assertEquals(200, stats.statusCode());
            Map<String, Object> statsJson = Json.parseObject(stats.body());
            assertFalse(((List<?>) statsJson.get("topPlayers")).isEmpty());
            assertEquals(5, ((List<?>) statsJson.get("durations")).size());
            assertFalse(((List<?>) statsJson.get("heatmap")).isEmpty());
            assertEquals(400, get(client, root.resolve(
                    "/api/admin/stats?tzOffsetMinutes=9999"), session).statusCode());

            HttpResponse<String> broadcast = post(client, root.resolve("/api/admin/network/broadcast"),
                    Map.of("message", "服务器将于 10 分钟后重启"), session);
            assertEquals(200, broadcast.statusCode());
            assertEquals("服务器将于 10 分钟后重启", broadcasted.get());
            assertEquals(3L, ((Number) Json.parseObject(broadcast.body()).get("delivered")).longValue());
            assertEquals(400, post(client, root.resolve("/api/admin/network/broadcast"),
                    Map.of("message", "  "), session).statusCode());

            HttpResponse<String> reset = post(client, root.resolve("/api/admin/players/reset"),
                    Map.of("uuid", carol.toString()), session);
            assertEquals(200, reset.statusCode());
            assertEquals(0, store.banCount(carol));
            assertFalse(store.findActiveBan(carol, now + 5).isPresent());
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void servesPanelWithAntiClickjackingHeaders() throws Exception {
        String database = "dashboard_headers_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, settings,
                LoggerFactory.getLogger("dashboard-headers-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(root.resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""));
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""));
            assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("")
                    .contains("frame-ancestors 'none'"));
        } finally {
            dashboard.stop();
        }
    }

    @Test
    void keysLoginRateLimitOnTheProxyAppendedHopNotTheSpoofableClientValue() throws Exception {
        String database = "dashboard_xff_" + UUID.randomUUID().toString().replace("-", "");
        DatabaseConfig databaseConfig = new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcNetworkStore store = new JdbcNetworkStore(databaseConfig);
        store.initialize();
        VelocitySettings settings = new VelocitySettings("velocity-test", databaseConfig,
                true, "127.0.0.1", 0, "", "test-token", 1, 1000L);
        AtomicLong now = new AtomicLong(1_000L);
        // 抬高验证码阈值，让本用例只考察失败锁定这一条防线
        AdminAuthService auth = new AdminAuthService("test-token", 10, 3,
                600_000L, 43_200_000L, now::get, new SecureRandom(), () -> "ABCDE",
                (answer, random) -> new byte[] {1, 2, 3});
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, ignored -> {}, settings,
                LoggerFactory.getLogger("dashboard-xff-test"), auth);
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();
            // 每发都伪造不同的最左侧 X-Forwarded-For，但可信反代追加的最后一跳固定为 9.9.9.9。
            assertEquals(401, spoofedLogin(client, root, "1.1.1.1").statusCode());
            assertEquals(401, spoofedLogin(client, root, "2.2.2.2").statusCode());
            // 若限流键取最左侧（可伪造）值，第三发仍是 401（锁定被绕过）；取最后一跳则触发 429。
            assertEquals(429, spoofedLogin(client, root, "3.3.3.3").statusCode());
        } finally {
            dashboard.stop();
        }
    }

    private static HttpResponse<String> spoofedLogin(HttpClient client, URI root, String spoofed)
            throws Exception {
        return client.send(HttpRequest.newBuilder(root.resolve("/api/admin/login"))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", spoofed + ", 9.9.9.9")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("token", "wrong"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String login(HttpClient client, URI root, String token) throws Exception {
        HttpResponse<String> response = loginResponse(client, root, token, null, null);
        assertEquals(200, response.statusCode());
        return String.valueOf(Json.parseObject(response.body()).get("sessionToken"));
    }

    private static HttpResponse<String> loginResponse(HttpClient client, URI root, String token,
                                                       String captchaId, String captchaAnswer)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("token", token);
        if (captchaId != null) body.put("captchaId", captchaId);
        if (captchaAnswer != null) body.put("captchaAnswer", captchaAnswer);
        return client.send(HttpRequest.newBuilder(root.resolve("/api/admin/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> captcha(HttpClient client, URI root) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        root.resolve("/api/admin/login/captcha")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(String.valueOf(Json.parseObject(response.body()).get("imageDataUrl"))
                .startsWith("data:image/png;base64,"));
        return Json.parseObject(response.body());
    }

    private static Map<String, Object> appealCaptcha(HttpClient client, URI root) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        root.resolve("/api/appeal/captcha")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(String.valueOf(Json.parseObject(response.body()).get("imageDataUrl"))
                .startsWith("data:image/png;base64,"));
        return Json.parseObject(response.body());
    }

    private static HttpResponse<String> lookup(HttpClient client, URI root, String query,
                                               String captchaId, String captchaAnswer)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        if (captchaId != null) body.put("captchaId", captchaId);
        if (captchaAnswer != null) body.put("captchaAnswer", captchaAnswer);
        return client.send(HttpRequest.newBuilder(root.resolve("/api/appeal/lookup"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> lookupGet(HttpClient client, URI root, String query,
                                                  String captchaId, String captchaAnswer)
            throws Exception {
        return client.send(HttpRequest.newBuilder(root.resolve(
                                "/api/appeal/lookup?query=" + java.net.URLEncoder.encode(
                                        query, java.nio.charset.StandardCharsets.UTF_8)))
                        .header("X-Captcha-Id", captchaId)
                        .header("X-Captcha-Answer", captchaAnswer)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, URI uri, String session) throws Exception {
        return clientGet(client, uri, session);
    }

    private static HttpResponse<String> clientGet(HttpClient client, URI uri, String session) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("X-Admin-Session", session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, URI uri, Map<String, Object> body,
                                             String session)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri)
                        .header("X-Admin-Session", session)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static NetworkModels.EnforcementRequest request(UUID player, String name, String serverId) {
        return new NetworkModels.EnforcementRequest(player, name, serverId, "speed", 20.0,
                24, 1, List.of(1, 6, 24), List.of(), List.of());
    }
}
