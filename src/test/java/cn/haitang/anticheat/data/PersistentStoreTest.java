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
import static org.junit.jupiter.api.Assertions.assertNull;
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
