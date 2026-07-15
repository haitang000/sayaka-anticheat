package cn.haitang.anticheat.shared;

import cn.haitang.anticheat.shared.NetworkModels.AppealStatus;
import cn.haitang.anticheat.shared.NetworkModels.AppealSubmitResult;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementDecision;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementKind;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcNetworkStoreTest {
    private JdbcNetworkStore store;

    @BeforeEach
    void setUp() throws Exception {
        String database = "sayaka_" + UUID.randomUUID().toString().replace("-", "");
        store = new JdbcNetworkStore(new DatabaseConfig(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""));
        store.initialize();
    }

    @Test
    void escalatesSharedStrikesIntoOneActiveBan() throws Exception {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000_000L;
        EnforcementRequest request = request(player, "lobby", 3);

        EnforcementDecision first = store.prepareEnforcement(request, now);
        EnforcementDecision second = store.prepareEnforcement(request, now + 1);
        EnforcementDecision third = store.prepareEnforcement(request, now + 2);

        assertEquals(EnforcementKind.KICK, first.kind());
        assertEquals(1, first.strikes());
        assertEquals(EnforcementKind.KICK, second.kind());
        assertEquals(2, second.strikes());
        assertEquals(EnforcementKind.TEMPBAN, third.kind());
        assertEquals("lobby", third.punishment().serverId());
        assertEquals(1, third.punishment().banNumber());
        assertTrue(store.findActiveBan(player, now + 3).isPresent());
        assertEquals(0, store.strikeCount(player, 24, now + 3));
    }

    @Test
    void concurrentServersReuseTheSameActivePunishment() throws Exception {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<EnforcementDecision> lobby = () ->
                    store.prepareEnforcement(request(player, "lobby", 1), now);
            Callable<EnforcementDecision> survival = () ->
                    store.prepareEnforcement(request(player, "survival", 1), now);
            List<Future<EnforcementDecision>> futures = executor.invokeAll(List.of(lobby, survival));
            EnforcementDecision first = futures.get(0).get();
            EnforcementDecision second = futures.get(1).get();

            assertEquals(EnforcementKind.TEMPBAN, first.kind());
            assertEquals(EnforcementKind.TEMPBAN, second.kind());
            assertEquals(first.punishment().id(), second.punishment().id());
            assertEquals(1, store.listPunishments().size());
            assertEquals(1, store.banCount(player));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void approvingAppealRemovesTheNetworkBanAtomically() throws Exception {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EnforcementDecision decision = store.prepareEnforcement(request(player, "games", 1), now);
        String id = decision.punishment().id();

        assertEquals(AppealSubmitResult.OK,
                store.submitAppeal(id, "这是一次误判，请复查证据", "qq:123", now + 1));
        assertTrue(store.resolveAppeal(id, true, "复查通过", now + 2));

        assertFalse(store.findActiveBan(player, now + 3).isPresent());
        assertEquals(AppealStatus.APPROVED, store.getAppeal(id).orElseThrow().status());
        assertFalse(store.resolveAppeal(id, false, "不能重复处理", now + 4));
    }

    @Test
    void serverIdAndEvidenceRoundTripThroughSharedStorage() throws Exception {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        EnforcementDecision decision = store.prepareEnforcement(request(player, "survival-2", 1), now);
        var restored = store.getPunishment(decision.punishment().id()).orElseThrow();

        assertEquals("survival-2", restored.serverId());
        assertEquals(1, restored.warnings().size());
        assertEquals(1, restored.detections().size());
        assertNotEquals("", restored.detections().get(0).detail());
    }

    private static EnforcementRequest request(UUID player, String serverId, int threshold) {
        return new EnforcementRequest(player, "Cheater", serverId, "speed", 20.0,
                24, threshold, List.of(1, 6, 24),
                List.of(new NetworkModels.WarningEvidence(10L, "speed", 2, 12.0)),
                List.of(new NetworkModels.DetectionEvidence(11L, "speed", 20.0, "bps=12.0")));
    }
}
