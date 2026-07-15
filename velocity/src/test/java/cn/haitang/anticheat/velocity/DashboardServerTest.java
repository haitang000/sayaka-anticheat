package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.DatabaseConfig;
import cn.haitang.anticheat.shared.JdbcNetworkStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
