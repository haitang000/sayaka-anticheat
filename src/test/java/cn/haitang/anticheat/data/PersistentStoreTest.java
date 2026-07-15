package cn.haitang.anticheat.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentStoreTest {

    @TempDir
    Path tempDir;

    private File dataFile;
    private Logger logger;

    @BeforeEach
    void setUp() {
        dataFile = tempDir.resolve("data.yml").toFile();
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
    }

    private PersistentStore newStore() {
        return new PersistentStore(dataFile, logger, task -> {
            task.run(); // 测试里同步执行"异步"保存
            return true;
        });
    }

    // ---- strike 窗口 ----

    @Test
    void countsStrikesInsideTheRollingWindow() {
        PersistentStore store = newStore();
        UUID uuid = UUID.randomUUID();

        store.addStrike(uuid, "Cheater");
        store.addStrike(uuid, "Cheater");

        assertEquals(2, store.strikeCount(uuid, 24));
    }

    @Test
    void expiredStrikesAreDroppedAndTheCleanupIsPersisted() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expired = now - 25 * 3600_000L;

        // 直接构造磁盘上的历史数据：一条已过 24h 窗口，一条仍然有效
        YamlConfiguration seed = new YamlConfiguration();
        seed.set("players." + uuid + ".name", "Cheater");
        seed.set("players." + uuid + ".strikes", List.of(expired, now - 1000));
        seed.save(dataFile);

        PersistentStore store = newStore();
        assertEquals(1, store.strikeCount(uuid, 24));

        store.saveNow();
        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(dataFile);
        assertEquals(1, reloaded.getLongList("players." + uuid + ".strikes").size());
    }

    @Test
    void clearingStrikesLeavesOtherPlayerDataIntact() {
        PersistentStore store = newStore();
        UUID uuid = UUID.randomUUID();
        store.addStrike(uuid, "Cheater");
        store.incrementBanCount(uuid);

        store.clearStrikes(uuid);

        assertEquals(0, store.strikeCount(uuid, 24));
        assertEquals(1, store.getBanCount(uuid));
    }

    // ---- 封禁档案 ----

    @Test
    void banCountIncrementsAndResets() {
        PersistentStore store = newStore();
        UUID uuid = UUID.randomUUID();

        assertEquals(0, store.getBanCount(uuid));
        store.incrementBanCount(uuid);
        store.incrementBanCount(uuid);
        assertEquals(2, store.getBanCount(uuid));

        store.resetBanCount(uuid);
        assertEquals(0, store.getBanCount(uuid));
    }

    @Test
    void punishmentSnapshotRoundTripsWithWarningsAndDetections() {
        PersistentStore store = newStore();
        UUID playerId = UUID.randomUUID();
        String punishmentId = store.newPunishmentId();
        long bannedAt = 1_700_000_000_000L;
        PersistentStore.PunishmentRecord punishment = new PersistentStore.PunishmentRecord(
                punishmentId, playerId, "Cheater", bannedAt, bannedAt + 6 * 3600_000L,
                "speed", 18.5, 6, 2,
                List.of(new PersistentStore.WarningEvidence(bannedAt - 2_000L, "speed", 2, 12.5)),
                List.of(new PersistentStore.DetectionEvidence(
                        bannedAt - 1_000L, "speed", 18.5, "horizontal=1.20 max=0.42")));

        store.addPunishment(punishment);
        assertTrue(store.saveNow());

        PersistentStore.PunishmentRecord restored = newStore()
                .getPunishment(punishmentId.toUpperCase());
        assertEquals(punishmentId, restored.id());
        assertEquals(playerId, restored.playerId());
        assertEquals("Cheater", restored.playerName());
        assertEquals(bannedAt, restored.bannedAt());
        assertEquals(6, restored.hours());
        assertEquals(2, restored.banNumber());
        assertEquals(1, restored.warnings().size());
        assertEquals(2, restored.warnings().get(0).stage());
        assertEquals(12.5, restored.warnings().get(0).vl());
        assertEquals(1, restored.detections().size());
        assertEquals("horizontal=1.20 max=0.42", restored.detections().get(0).detail());

        store.resetPlayer(playerId);
        assertNotNull(store.getPunishment(punishmentId), "重置玩家状态不应删除独立的处罚审计记录");
    }

    @Test
    void punishmentIdsCannotBeReused() {
        PersistentStore store = newStore();
        String punishmentId = store.newPunishmentId();
        PersistentStore.PunishmentRecord punishment = new PersistentStore.PunishmentRecord(
                punishmentId, UUID.randomUUID(), "Cheater", 10L, 20L,
                "flight", 20.0, 1, 1, List.of(), List.of());

        store.addPunishment(punishment);

        assertThrows(IllegalArgumentException.class, () -> store.addPunishment(punishment));
        assertNull(store.getPunishment("not-a-punishment-id"));
    }

    @Test
    void resetPlayerWipesStrikesBansAndHistory() {
        PersistentStore store = newStore();
        UUID uuid = UUID.randomUUID();
        store.addStrike(uuid, "Cheater");
        store.incrementBanCount(uuid);
        store.addHistory(uuid, "[踢出] 测试");

        store.resetPlayer(uuid);

        assertEquals(0, store.strikeCount(uuid, 24));
        assertEquals(0, store.getBanCount(uuid));
        assertTrue(store.getHistory(uuid).isEmpty());
    }

    // ---- 申诉 ----

    private String seedPunishment(PersistentStore store) {
        String id = store.newPunishmentId();
        store.addPunishment(new PersistentStore.PunishmentRecord(
                id, UUID.randomUUID(), "Cheater", 10L, 20L,
                "speed", 18.0, 6, 1, List.of(), List.of()));
        return id;
    }

    @Test
    void appealCanOnlyBeSubmittedForAnExistingPunishment() {
        PersistentStore store = newStore();
        assertEquals(PersistentStore.AppealSubmitResult.PUNISHMENT_NOT_FOUND,
                store.submitAppeal(UUID.randomUUID().toString(), "误判了", ""));
        assertNull(store.getAppeal(UUID.randomUUID().toString()));
    }

    @Test
    void appealRoundTripsAndResubmitUpdatesReasonWhilePending() {
        PersistentStore store = newStore();
        String id = seedPunishment(store);

        assertEquals(PersistentStore.AppealSubmitResult.OK,
                store.submitAppeal(id, "第一次理由", "qq:123"));
        PersistentStore.AppealRecord appeal = store.getAppeal(id.toUpperCase());
        assertNotNull(appeal);
        assertEquals("第一次理由", appeal.reason());
        assertEquals("qq:123", appeal.contact());
        assertEquals(PersistentStore.AppealStatus.PENDING, appeal.status());
        assertEquals("Cheater", appeal.playerName());

        // 待处理期间允许更新理由
        assertEquals(PersistentStore.AppealSubmitResult.OK,
                store.submitAppeal(id, "补充理由", "qq:456"));
        assertEquals("补充理由", store.getAppeal(id).reason());
        assertEquals(1, store.listAppeals().size());
    }

    @Test
    void resolvedAppealCannotBeResubmitted() {
        PersistentStore store = newStore();
        String id = seedPunishment(store);
        store.submitAppeal(id, "理由", "");

        assertTrue(store.resolveAppeal(id, false, "证据充分"));
        PersistentStore.AppealRecord appeal = store.getAppeal(id);
        assertEquals(PersistentStore.AppealStatus.REJECTED, appeal.status());
        assertEquals("证据充分", appeal.note());

        assertEquals(PersistentStore.AppealSubmitResult.ALREADY_RESOLVED,
                store.submitAppeal(id, "再试一次", ""));
    }

    @Test
    void resolveWithoutAppealReturnsFalse() {
        PersistentStore store = newStore();
        String id = seedPunishment(store);
        assertFalse(store.resolveAppeal(id, true, ""));
    }

    @Test
    void appealsSurviveReloadAndListPunishmentsIsNewestFirst() {
        PersistentStore store = newStore();
        String older = store.newPunishmentId();
        store.addPunishment(new PersistentStore.PunishmentRecord(
                older, UUID.randomUUID(), "A", 1_000L, 2_000L, "speed", 1, 1, 1, List.of(), List.of()));
        String newer = store.newPunishmentId();
        store.addPunishment(new PersistentStore.PunishmentRecord(
                newer, UUID.randomUUID(), "B", 5_000L, 6_000L, "flight", 1, 1, 1, List.of(), List.of()));
        store.submitAppeal(newer, "申诉理由", "");
        assertTrue(store.saveNow());

        PersistentStore reloaded = newStore();
        List<PersistentStore.PunishmentRecord> all = reloaded.listPunishments();
        assertEquals(2, all.size());
        assertEquals(newer, all.get(0).id(), "listPunishments 应按封禁时间倒序");
        assertNotNull(reloaded.getAppeal(newer));
        assertNull(reloaded.getAppeal(older));
    }

    // ---- 白名单 ----

    @Test
    void whitelistSupportsAddRemoveAndCaseInsensitiveLookup() {
        PersistentStore store = newStore();
        UUID uuid = UUID.randomUUID();

        assertFalse(store.isWhitelisted(uuid));
        store.addWhitelist(uuid, "Builder");
        assertTrue(store.isWhitelisted(uuid));

        PersistentStore.WhitelistEntry found = store.findWhitelistByName("bUiLdEr");
        assertEquals(uuid, found.uuid());
        assertNull(store.findWhitelistByName("nobody"));

        assertTrue(store.removeWhitelist(uuid));
        assertFalse(store.removeWhitelist(uuid));
        assertFalse(store.isWhitelisted(uuid));
    }

    @Test
    void whitelistIsSortedByNameAndSkipsCorruptEntries() throws Exception {
        UUID zed = UUID.randomUUID();
        UUID amy = UUID.randomUUID();
        YamlConfiguration seed = new YamlConfiguration();
        seed.set("whitelist." + zed, "Zed");
        seed.set("whitelist." + amy, "amy");
        seed.set("whitelist.not-a-uuid", "Broken");
        seed.save(dataFile);

        List<PersistentStore.WhitelistEntry> entries = newStore().getWhitelist();

        assertEquals(2, entries.size(), "损坏的 UUID 条目应被忽略");
        assertEquals("amy", entries.get(0).name());
        assertEquals("Zed", entries.get(1).name());
    }

    // ---- 惩罚历史 ----

    @Test
    void historyKeepsOnlyTheMostRecent25Lines() {
        PersistentStore store = newStore();
        UUID uuid = UUID.randomUUID();

        for (int i = 1; i <= 30; i++) {
            store.addHistory(uuid, "line-" + i);
        }

        List<String> history = store.getHistory(uuid);
        assertEquals(25, history.size());
        assertTrue(history.get(0).endsWith("line-6"), "最旧的记录应被淘汰");
        assertTrue(history.get(24).endsWith("line-30"));
    }

    // ---- 保存 ----

    @Test
    void saveNowRoundTripsAllData() {
        UUID banned = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        PersistentStore store = newStore();
        store.addStrike(banned, "Cheater");
        store.incrementBanCount(banned);
        store.addHistory(banned, "[封禁] 测试");
        store.addWhitelist(trusted, "Builder");

        store.saveNow();

        PersistentStore reloaded = newStore();
        assertEquals(1, reloaded.strikeCount(banned, 24));
        assertEquals(1, reloaded.getBanCount(banned));
        assertEquals(1, reloaded.getHistory(banned).size());
        assertTrue(reloaded.isWhitelisted(trusted));
    }

    @Test
    void saveAsyncSkipsCleanStateAndCoalescesDirtyWrites() {
        AtomicInteger submissions = new AtomicInteger();
        PersistentStore store = new PersistentStore(dataFile, logger, task -> {
            submissions.incrementAndGet();
            task.run();
            return true;
        });

        store.saveAsync();
        assertEquals(0, submissions.get(), "没有改动时不应提交保存任务");

        UUID uuid = UUID.randomUUID();
        store.addStrike(uuid, "Cheater");
        store.saveAsync();
        assertEquals(1, submissions.get());
        assertTrue(dataFile.exists(), "脏数据应已写入磁盘");

        // 上一轮保存完成后，新的改动可以再次触发提交
        store.addStrike(uuid, "Cheater");
        store.saveAsync();
        assertEquals(2, submissions.get());
    }

    @Test
    void rejectedSaveSubmissionAllowsRetryOnNextCall() {
        AtomicInteger attempts = new AtomicInteger();
        PersistentStore store = new PersistentStore(dataFile, logger, task -> {
            attempts.incrementAndGet();
            return false; // 模拟工作队列已满
        });
        store.addStrike(UUID.randomUUID(), "Cheater");

        store.saveAsync();
        store.saveAsync();

        assertEquals(2, attempts.get(), "被拒绝后应允许下一次重试而不是永久卡死");
        assertFalse(dataFile.exists());
    }

    @Test
    void failedWriteKeepsDirtyStateAndPreservesThePreviousFile() throws Exception {
        Files.writeString(dataFile.toPath(), "sentinel: intact\n", StandardCharsets.UTF_8);
        PersistentStore store = new PersistentStore(dataFile, logger, task -> true,
                (file, content) -> { throw new java.io.IOException("disk full"); });
        store.addStrike(UUID.randomUUID(), "Cheater");

        assertFalse(store.saveNow());
        assertTrue(store.isDirty());
        assertEquals("sentinel: intact\n",
                Files.readString(dataFile.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    void failedAsyncWriteDoesNotSpinAndCanBeRetriedAfterAnotherMutation() {
        AtomicInteger submissions = new AtomicInteger();
        PersistentStore store = new PersistentStore(dataFile, logger, task -> {
            submissions.incrementAndGet();
            task.run();
            return true;
        }, (file, content) -> { throw new java.io.IOException("offline volume"); });
        store.addStrike(UUID.randomUUID(), "Cheater");

        store.saveAsync();
        assertEquals(1, submissions.get());
        assertTrue(store.isDirty());

        store.addHistory(UUID.randomUUID(), "new evidence resets retry delay");
        store.saveAsync();
        assertEquals(2, submissions.get());
    }
}
