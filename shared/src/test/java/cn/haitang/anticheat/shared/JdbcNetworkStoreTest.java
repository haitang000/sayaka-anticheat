package cn.haitang.anticheat.shared;

import cn.haitang.anticheat.shared.NetworkModels.AppealStatus;
import cn.haitang.anticheat.shared.NetworkModels.AppealSubmitResult;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementDecision;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementKind;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementRequest;
import cn.haitang.anticheat.shared.NetworkModels.PardonResult;
import cn.haitang.anticheat.shared.NetworkModels.PunishmentFilter;
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
    void initializationIsIdempotent() throws Exception {
        store.initialize();
        assertTrue(store.healthCheck());
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

    @Test
    void filtersAndPagesPunishmentsWithoutPerRowLookups() throws Exception {
        long now = 1_700_000_000_000L;
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        var first = store.prepareEnforcement(
                request(firstPlayer, "Alice", "lobby", "speed", 1), now - 2_000L).punishment();
        var second = store.prepareEnforcement(
                request(secondPlayer, "Bob", "survival", "reach", 1), now - 1_000L).punishment();
        store.submitAppeal(first.id(), "请复查这次速度判定", "alice@example.test", now - 500L);

        var page = store.queryPunishments(new PunishmentFilter(
                "alice", true, AppealStatus.PENDING, "lobby", "speed", now - 10_000L, now),
                1, 1, now);

        assertEquals(1, page.total());
        assertEquals(first.id(), page.items().get(0).punishment().id());
        assertTrue(page.items().get(0).active());
        assertEquals(AppealStatus.PENDING, page.items().get(0).appealStatus());

        var all = store.queryPunishments(new PunishmentFilter(
                "", null, null, null, null, 0, 0), 1, 1, now);
        assertEquals(2, all.total());
        assertEquals(second.id(), all.items().get(0).punishment().id());

        var overview = store.dashboardOverview(now - 3 * 86_400_000L, now, 86_400_000L, now);
        assertEquals(2, overview.periodPunishments());
        assertEquals(3, overview.trend().size());
        assertEquals(2, overview.activeBans());
        assertEquals(1, overview.pendingAppeals());
    }

    @Test
    void exposesPlayerProfilesAndAuditsWhitelistChanges() throws Exception {
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        store.prepareEnforcement(request(player, "Builder", "creative", "scaffold", 1), now);

        store.addWhitelistAudited(player, "Builder", now + 1);
        store.addWhitelistAudited(player, "Builder", now + 2);

        assertEquals(1, store.listWhitelist().size());
        assertEquals(player, store.searchPlayers("build", 20).get(0).playerId());
        var profile = store.playerProfile(player, now + 3).orElseThrow();
        assertTrue(profile.whitelisted());
        assertEquals(1, profile.punishments().size());
        assertTrue(profile.history().stream().anyMatch(entry -> entry.text().contains("白名单")));

        assertTrue(store.removeWhitelistAudited(player, now + 4));
        assertFalse(store.removeWhitelistAudited(player, now + 5));
        assertFalse(store.playerProfile(player, now + 6).orElseThrow().whitelisted());
    }

    @Test
    void oldPunishmentCannotPardonANewerActiveBan() throws Exception {
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        String first = store.prepareEnforcement(request(player, "Repeat", "lobby", "speed", 1), now)
                .punishment().id();
        assertEquals(PardonResult.OK, store.pardonPunishment(first, false, "第一次复查", now + 1));

        String second = store.prepareEnforcement(request(player, "Repeat", "lobby", "reach", 1), now + 2)
                .punishment().id();
        assertEquals(PardonResult.NOT_ACTIVE,
                store.pardonPunishment(first, true, "不应解除新处罚", now + 3));
        assertEquals(second, store.findActiveBan(player, now + 4).orElseThrow().punishmentId());
        assertEquals(2, store.banCount(player));

        assertEquals(PardonResult.OK,
                store.pardonPunishment(second, true, "复查通过", now + 5));
        assertFalse(store.findActiveBan(player, now + 6).isPresent());
        assertEquals(0, store.banCount(player));
        assertTrue(store.historyEntries(player).stream().anyMatch(entry -> entry.text().contains("复查通过")));
    }

    private static EnforcementRequest request(UUID player, String serverId, int threshold) {
        return request(player, "Cheater", serverId, "speed", threshold);
    }

    private static EnforcementRequest request(UUID player, String playerName, String serverId,
                                              String check, int threshold) {
        return new EnforcementRequest(player, playerName, serverId, check, 20.0,
                24, threshold, List.of(1, 6, 24),
                List.of(new NetworkModels.WarningEvidence(10L, "speed", 2, 12.0)),
                List.of(new NetworkModels.DetectionEvidence(11L, "speed", 20.0, "bps=12.0")));
    }
}
