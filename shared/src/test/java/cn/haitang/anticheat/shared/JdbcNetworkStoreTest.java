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
    void persistsAndClearsPerServerProtectionOverrides() throws Exception {
        assertTrue(store.protectionOverrides().isEmpty());

        store.setProtectionOverride("Lobby", false, 1_000L);
        store.setProtectionOverride("survival", true, 1_001L);
        assertEquals(Boolean.FALSE, store.protectionOverrides().get("lobby"));
        assertEquals(Boolean.TRUE, store.protectionOverrides().get("survival"));

        store.setProtectionOverride("lobby", true, 1_002L);
        assertEquals(Boolean.TRUE, store.protectionOverrides().get("lobby"));

        assertTrue(store.clearProtectionOverride("LOBBY"));
        assertFalse(store.clearProtectionOverride("lobby"));
        assertFalse(store.protectionOverrides().containsKey("lobby"));
        assertEquals(1, store.protectionOverrides().size());
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
    void findsLatestPunishmentByPlayerNameIgnoringCase() throws Exception {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String first = store.prepareEnforcement(
                request(player, "Appealer", "lobby", "speed", 1), now).punishment().id();
        assertEquals(PardonResult.OK, store.pardonPunishment(first, false, "复查", now + 1));
        String second = store.prepareEnforcement(
                request(player, "Appealer", "games", "reach", 1), now + 2).punishment().id();

        assertEquals(second, store.findLatestPunishmentByPlayerName("  appealer  ").orElseThrow().id());
        assertFalse(store.findLatestPunishmentByPlayerName("unknown").isPresent());
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

    @Test
    void manualPunishmentBansImmediatelyAndConflictsWithExistingBan() throws Exception {
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        store.addStrike(player, "Manual", now);

        var created = store.createManualPunishment(player, "Manual", 48, "人工复查确认", now);

        assertTrue(created.isPresent());
        assertEquals("manual", created.get().check());
        assertEquals("web", created.get().serverId());
        assertEquals(1, created.get().banNumber());
        assertEquals(now + 48 * 3_600_000L, created.get().expiresAt());
        assertTrue(store.findActiveBan(player, now + 1).isPresent());
        assertEquals(1, store.banCount(player));
        assertEquals(0, store.strikeCount(player, 24, now + 2));
        assertTrue(store.historyEntries(player).stream()
                .anyMatch(entry -> entry.text().contains("手动封禁")));

        assertTrue(store.createManualPunishment(player, "Manual", 24, "", now + 3).isEmpty());
        assertEquals(1, store.listPunishments().size());
    }

    @Test
    void adjustsActiveBanExpiryAndKeepsPunishmentRowConsistent() throws Exception {
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        var punishment = store.createManualPunishment(player, "Adjuster", 24, "", now).orElseThrow();
        long newExpiry = now + 72 * 3_600_000L;

        assertTrue(store.adjustActiveBanExpiry(punishment.id(), newExpiry, "延长观察", now + 1));

        assertEquals(newExpiry, store.findActiveBan(player, now + 2).orElseThrow().expiresAt());
        var restored = store.getPunishment(punishment.id()).orElseThrow();
        assertEquals(newExpiry, restored.expiresAt());
        assertEquals(72, restored.hours());
        assertTrue(store.historyEntries(player).stream()
                .anyMatch(entry -> entry.text().contains("调整时长")));

        assertEquals(PardonResult.OK, store.pardonPunishment(punishment.id(), false, "", now + 3));
        assertFalse(store.adjustActiveBanExpiry(punishment.id(), newExpiry, "", now + 4));
        assertFalse(store.adjustActiveBanExpiry("not-a-uuid", newExpiry, "", now + 5));
    }

    @Test
    void recentActivityMergesPunishmentsAndAppealsNewestFirst() throws Exception {
        long now = 1_700_000_000_000L;
        UUID player = UUID.randomUUID();
        var punishment = store.prepareEnforcement(
                request(player, "Feed", "lobby", "speed", 1), now - 2_000L).punishment();
        store.submitAppeal(punishment.id(), "我要申诉这次封禁", "", now - 1_000L);

        var items = store.recentActivity(10, now);

        assertEquals(2, items.size());
        assertEquals("appeal", items.get(0).kind());
        assertEquals("PENDING", items.get(0).status());
        assertEquals("punishment", items.get(1).kind());
        assertEquals(player, items.get(1).playerId());
        assertEquals("active", items.get(1).status());
        assertTrue(items.get(1).summary().contains("speed"));

        assertEquals(1, store.recentActivity(1, now).size());
    }

    @Test
    void dashboardStatsAggregatesPlayersAppealsDurationsAndHeatmap() throws Exception {
        long now = 1_700_000_000_000L;
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        var firstBan = store.prepareEnforcement(
                request(alice, "Alice", "lobby", "speed", 1), now - 3_000L).punishment();
        assertEquals(PardonResult.OK, store.pardonPunishment(firstBan.id(), false, "", now - 2_500L));
        store.prepareEnforcement(request(alice, "Alice", "lobby", "reach", 1), now - 2_000L);
        store.prepareEnforcement(request(bob, "Bob", "lobby", "speed", 1), now - 1_000L);
        store.submitAppeal(firstBan.id(), "请复查这次处罚", "", now - 500L);

        var stats = store.dashboardStats(now - 10_000L, now + 1_000L, 0L);

        assertEquals("Alice", stats.topPlayers().get(0).name());
        assertEquals(2, stats.topPlayers().get(0).count());
        assertEquals(1, stats.appealOutcomes().size());
        assertEquals("PENDING", stats.appealOutcomes().get(0).name());
        assertEquals(5, stats.durations().size());
        assertEquals(3, stats.durations().get(0).count());
        assertEquals(1, stats.heatmap().size());
        assertEquals(3, stats.heatmap().get(0).count());
        assertEquals(1, stats.heatmap().get(0).day());
        assertEquals(22, stats.heatmap().get(0).hour());

        var shifted = store.dashboardStats(now - 10_000L, now + 1_000L, 8 * 3_600_000L);
        assertEquals(2, shifted.heatmap().get(0).day());
        assertEquals(6, shifted.heatmap().get(0).hour());
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
