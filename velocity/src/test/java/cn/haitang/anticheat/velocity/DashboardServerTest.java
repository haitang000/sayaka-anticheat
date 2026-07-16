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
        DashboardServer first = DashboardServer.start(store, () -> 0, dynamic,
                LoggerFactory.getLogger("dashboard-test"));
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + first.port() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Sayaka AntiCheat"));

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
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, settings,
                LoggerFactory.getLogger("dashboard-player-appeal-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> lookup = client.send(HttpRequest.newBuilder(
                            root.resolve("/api/appeal/lookup?query=appealer")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, lookup.statusCode());
            Map<?, ?> punishment = (Map<?, ?>) Json.parseObject(lookup.body()).get("punishment");
            assertEquals(id, punishment.get("id"));

            HttpResponse<String> submit = client.send(HttpRequest.newBuilder(root.resolve("/api/appeal/submit"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of(
                                    "id", punishment.get("id"), "reason", "请重新检查这次处罚")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, submit.statusCode());
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
