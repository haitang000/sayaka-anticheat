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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                true, "127.0.0.1", 0, "test-token", 1, 1000L);
        DashboardServer first = DashboardServer.start(store, () -> 0, dynamic,
                LoggerFactory.getLogger("dashboard-test"));
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + first.port() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Sayaka AntiCheat"));

            VelocitySettings conflicting = new VelocitySettings("velocity-test", databaseConfig,
                    true, "127.0.0.1", first.port(), "test-token", 1, 1000L);
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
                true, "127.0.0.1", 0, "test-token", 1, 1000L);
        DashboardServer dashboard = DashboardServer.start(store, () -> 12, invalidated::set, settings,
                LoggerFactory.getLogger("dashboard-api-test"));
        try {
            URI root = URI.create("http://127.0.0.1:" + dashboard.port());
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(root.resolve("/api/admin/punishments")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> page = get(client, root.resolve(
                    "/api/admin/punishments?q=Formula&page=1&pageSize=20"));
            assertEquals(200, page.statusCode());
            Map<String, Object> pageJson = Json.parseObject(page.body());
            assertEquals(1L, ((Number) pageJson.get("total")).longValue());
            assertEquals(1, ((List<?>) pageJson.get("items")).size());

            HttpResponse<String> invalidPage = get(client, root.resolve(
                    "/api/admin/punishments?pageSize=101"));
            assertEquals(400, invalidPage.statusCode());

            HttpResponse<String> addWhitelist = post(client, root.resolve("/api/admin/whitelist/add"),
                    Map.of("uuid", player.toString()));
            assertEquals(200, addWhitelist.statusCode());
            assertTrue(store.isWhitelisted(player));

            HttpResponse<String> detail = get(client, root.resolve(
                    "/api/admin/players/detail?uuid=" + player));
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains("Formula"));

            HttpResponse<String> csv = get(client, root.resolve("/api/admin/punishments/export"));
            assertEquals(200, csv.statusCode());
            assertTrue(csv.headers().firstValue("Content-Disposition").orElse("").contains(".csv"));
            assertTrue(csv.body().startsWith("\ufeff"));
            assertTrue(csv.body().contains("\"'=Formula\""));

            HttpResponse<String> pardon = post(client, root.resolve("/api/admin/punishments/pardon"),
                    Map.of("id", punishment.id(), "note", "Web review", "resetBanCount", true));
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
                true, "127.0.0.1", 0, "test-token", 1, 1000L);
        DashboardServer dashboard = DashboardServer.start(store, () -> 0, invalidated::set, settings,
                LoggerFactory.getLogger("dashboard-appeal-test"));
        try {
            URI uri = URI.create("http://127.0.0.1:" + dashboard.port() + "/api/admin/appeals/resolve");
            HttpResponse<String> response = post(HttpClient.newHttpClient(), uri,
                    Map.of("id", id, "approved", true, "note", "evidence reviewed"));
            assertEquals(200, response.statusCode());
            assertEquals(player, invalidated.get());
            assertFalse(store.findActiveBan(player, now + 2).isPresent());
        } finally {
            dashboard.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("X-Admin-Token", "test-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, URI uri, Map<String, Object> body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri)
                        .header("X-Admin-Token", "test-token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static NetworkModels.EnforcementRequest request(UUID player, String name, String serverId) {
        return new NetworkModels.EnforcementRequest(player, name, serverId, "speed", 20.0,
                24, 1, List.of(1, 6, 24), List.of(), List.of());
    }
}
