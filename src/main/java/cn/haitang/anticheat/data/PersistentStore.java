package cn.haitang.anticheat.data;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 跨会话数据（data.yml）：strike 记录、封禁次数、惩罚历史和反作弊白名单。
 * 玩家退出重进不会清空 strike，因此"踢出 → 重进 → 再踢出"最终仍会升级为封禁。
 */
public class PersistentStore {

    public record WhitelistEntry(UUID uuid, String name) {}

    public record WarningEvidence(long at, String check, int stage, double vl) {}

    public record DetectionEvidence(long at, String check, double vl, String detail) {}

    public record PlayerPunishmentState(
            UUID playerId,
            String playerName,
            List<Long> strikes,
            int banCount,
            List<String> history
    ) {
        public PlayerPunishmentState {
            strikes = List.copyOf(strikes);
            history = List.copyOf(history);
        }
    }

    public record PunishmentRecord(
            String id,
            UUID playerId,
            String playerName,
            long bannedAt,
            long expiresAt,
            String check,
            double vl,
            int hours,
            int banNumber,
            List<WarningEvidence> warnings,
            List<DetectionEvidence> detections
    ) {
        public PunishmentRecord {
            warnings = List.copyOf(warnings);
            detections = List.copyOf(detections);
        }
    }

    /** 申诉状态。PENDING = 待处理，APPROVED = 通过（通常伴随解封），REJECTED = 驳回。 */
    public enum AppealStatus { PENDING, APPROVED, REJECTED }

    public record AppealRecord(
            String punishmentId,
            String playerName,
            String reason,
            String contact,
            long submittedAt,
            AppealStatus status,
            long resolvedAt,
            String note
    ) {}

    /** submitAppeal 的结果：申诉是否被接受。 */
    public enum AppealSubmitResult { OK, PUNISHMENT_NOT_FOUND, ALREADY_RESOLVED }

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    private static final String PUNISHMENT_ID_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int PUNISHMENT_ID_LENGTH = 10;
    private static final SecureRandom PUNISHMENT_ID_RANDOM = new SecureRandom();

    /** 提交异步保存任务；返回 false 表示执行器拒绝（队列满或已关闭）。 */
    @FunctionalInterface
    interface AsyncTaskSubmitter {
        boolean submit(Runnable task);
    }

    @FunctionalInterface
    interface StoreWriter {
        void write(File file, String content) throws IOException;
    }

    private final File file;
    private final Logger logger;
    private final AsyncTaskSubmitter asyncSubmitter;
    private final StoreWriter writer;
    private final Object lock = new Object();
    private final Object saveOrderLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private YamlConfiguration yaml;
    private volatile long revision;
    private volatile long savedRevision;
    private volatile long nextRetryAt;
    private int consecutiveFailures;

    public PersistentStore(AntiCheatPlugin plugin) {
        this(new File(plugin.getDataFolder(), "data.yml"), plugin.getLogger(),
                task -> plugin.getAnalysisExecutor().execute(task), PersistentStore::writeAtomically);
    }

    PersistentStore(File file, Logger logger, AsyncTaskSubmitter asyncSubmitter) {
        this(file, logger, asyncSubmitter, PersistentStore::writeAtomically);
    }

    PersistentStore(File file, Logger logger, AsyncTaskSubmitter asyncSubmitter, StoreWriter writer) {
        this.file = file;
        this.logger = logger;
        this.asyncSubmitter = asyncSubmitter;
        this.writer = writer;
        load();
    }

    private void load() {
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    private String base(UUID uuid) {
        return "players." + uuid;
    }

    // ---- strike ----

    public void addStrike(UUID uuid, String name) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".name", name);
            List<Long> strikes = yaml.getLongList(base(uuid) + ".strikes");
            strikes.add(System.currentTimeMillis());
            yaml.set(base(uuid) + ".strikes", strikes);
            markDirty();
        }
    }

    /** 统计窗口期内的 strike 次数，并顺带清理过期记录 */
    public int strikeCount(UUID uuid, int windowHours) {
        synchronized (lock) {
            long cutoff = System.currentTimeMillis() - windowHours * 3600_000L;
            List<Long> strikes = yaml.getLongList(base(uuid) + ".strikes");
            List<Long> valid = new ArrayList<>();
            for (long s : strikes) {
                if (s >= cutoff) valid.add(s);
            }
            if (valid.size() != strikes.size()) {
                yaml.set(base(uuid) + ".strikes", valid);
            markDirty();
            }
            return valid.size();
        }
    }

    public void clearStrikes(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".strikes", new ArrayList<Long>());
            markDirty();
        }
    }

    // ---- 封禁次数（决定下一次临时封禁的时长档位） ----

    public int getBanCount(UUID uuid) {
        synchronized (lock) {
            return yaml.getInt(base(uuid) + ".ban-count", 0);
        }
    }

    public void incrementBanCount(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".ban-count", getBanCount(uuid) + 1);
            markDirty();
        }
    }

    public void resetBanCount(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid) + ".ban-count", 0);
            markDirty();
        }
    }

    public void resetPlayer(UUID uuid) {
        synchronized (lock) {
            yaml.set(base(uuid), null);
            markDirty();
        }
    }

    public PlayerPunishmentState getPunishmentState(UUID uuid) {
        synchronized (lock) {
            return new PlayerPunishmentState(
                    uuid,
                    yaml.getString(base(uuid) + ".name", uuid.toString()),
                    yaml.getLongList(base(uuid) + ".strikes"),
                    yaml.getInt(base(uuid) + ".ban-count", 0),
                    yaml.getStringList(base(uuid) + ".history"));
        }
    }

    public List<PlayerPunishmentState> listPunishmentStates() {
        synchronized (lock) {
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players == null) return List.of();
            List<PlayerPunishmentState> states = new ArrayList<>();
            for (String key : players.getKeys(false)) {
                try {
                    states.add(getPunishmentState(UUID.fromString(key)));
                } catch (IllegalArgumentException invalid) {
                    logger.warning("忽略 data.yml 中无效的玩家 UUID: " + key);
                }
            }
            return List.copyOf(states);
        }
    }

    /** Applies an authoritative cross-server state without touching local whitelist data. */
    public void applyPunishmentState(PlayerPunishmentState state) {
        synchronized (lock) {
            String path = base(state.playerId());
            if (yaml.getString(path + ".name", state.playerId().toString())
                            .equals(state.playerName())
                    && yaml.getLongList(path + ".strikes").equals(state.strikes())
                    && yaml.getInt(path + ".ban-count", 0) == Math.max(0, state.banCount())
                    && yaml.getStringList(path + ".history").equals(state.history())) {
                return;
            }
            yaml.set(path + ".name", state.playerName());
            yaml.set(path + ".strikes", state.strikes());
            yaml.set(path + ".ban-count", Math.max(0, state.banCount()));
            yaml.set(path + ".history", state.history());
            markDirty();
        }
    }

    // ---- 反作弊白名单 ----

    public boolean isWhitelisted(UUID uuid) {
        synchronized (lock) {
            return yaml.contains("whitelist." + uuid);
        }
    }

    public void addWhitelist(UUID uuid, String name) {
        synchronized (lock) {
            yaml.set("whitelist." + uuid, name);
            markDirty();
        }
    }

    public boolean removeWhitelist(UUID uuid) {
        synchronized (lock) {
            String path = "whitelist." + uuid;
            if (!yaml.contains(path)) return false;
            yaml.set(path, null);
            markDirty();
            return true;
        }
    }

    public WhitelistEntry findWhitelistByName(String name) {
        synchronized (lock) {
            for (WhitelistEntry entry : getWhitelistLocked()) {
                if (entry.name().equalsIgnoreCase(name)) return entry;
            }
            return null;
        }
    }

    public List<WhitelistEntry> getWhitelist() {
        synchronized (lock) {
            return getWhitelistLocked();
        }
    }

    private List<WhitelistEntry> getWhitelistLocked() {
        List<WhitelistEntry> entries = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("whitelist");
        if (section == null) return entries;
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                entries.add(new WhitelistEntry(uuid, section.getString(key, key)));
            } catch (IllegalArgumentException ignored) {
                logger.warning("忽略 data.yml 中无效的白名单 UUID: " + key);
            }
        }
        entries.sort(Comparator.comparing(WhitelistEntry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    // ---- 惩罚历史 ----

    public void addHistory(UUID uuid, String line) {
        synchronized (lock) {
            List<String> history = yaml.getStringList(base(uuid) + ".history");
            history.add(TIME.format(new Date()) + " " + line);
            while (history.size() > 25) history.remove(0);
            yaml.set(base(uuid) + ".history", history);
            markDirty();
        }
    }

    public List<String> getHistory(UUID uuid) {
        synchronized (lock) {
            return yaml.getStringList(base(uuid) + ".history");
        }
    }

    // ---- 封禁处罚快照 ----

    public String newPunishmentId() {
        synchronized (lock) {
            String id;
            do {
                StringBuilder compact = new StringBuilder(PUNISHMENT_ID_LENGTH);
                for (int i = 0; i < PUNISHMENT_ID_LENGTH; i++) {
                    compact.append(PUNISHMENT_ID_ALPHABET.charAt(
                            PUNISHMENT_ID_RANDOM.nextInt(PUNISHMENT_ID_ALPHABET.length())));
                }
                id = compact.substring(0, 5) + "-" + compact.substring(5);
            } while (yaml.contains("punishments." + id));
            return id;
        }
    }

    public void addPunishment(PunishmentRecord punishment) {
        synchronized (lock) {
            String id = canonicalPunishmentId(punishment.id());
            if (id == null || !id.equals(punishment.id())) {
                throw new IllegalArgumentException("处罚 ID 格式无效或不是规范格式");
            }
            String path = "punishments." + id;
            if (yaml.contains(path)) {
                throw new IllegalArgumentException("处罚 ID 已存在: " + id);
            }

            yaml.set(path + ".player-uuid", punishment.playerId().toString());
            yaml.set(path + ".player-name", punishment.playerName());
            yaml.set(path + ".banned-at", punishment.bannedAt());
            yaml.set(path + ".expires-at", punishment.expiresAt());
            yaml.set(path + ".check", punishment.check());
            yaml.set(path + ".vl", punishment.vl());
            yaml.set(path + ".hours", punishment.hours());
            yaml.set(path + ".ban-number", punishment.banNumber());
            yaml.set(path + ".warnings", punishment.warnings().stream()
                    .map(PersistentStore::warningMap).toList());
            yaml.set(path + ".detections", punishment.detections().stream()
                    .map(PersistentStore::detectionMap).toList());
            markDirty();
        }
    }

    /** Imports a cross-server record once. Returns false when it already exists. */
    public boolean importPunishment(PunishmentRecord punishment) {
        synchronized (lock) {
            String id = canonicalPunishmentId(punishment.id());
            if (id == null || !id.equals(punishment.id())) {
                throw new IllegalArgumentException("处罚 ID 格式无效或不是规范格式");
            }
            if (yaml.contains("punishments." + id)) return false;
            addPunishment(punishment);
            return true;
        }
    }

    public PunishmentRecord getPunishment(String punishmentId) {
        synchronized (lock) {
            String id = canonicalPunishmentId(punishmentId);
            if (id == null) return null;
            String path = "punishments." + id;
            if (!yaml.isConfigurationSection(path)) return null;

            String playerId = yaml.getString(path + ".player-uuid");
            if (playerId == null) return null;
            UUID uuid;
            try {
                uuid = UUID.fromString(playerId);
            } catch (IllegalArgumentException invalid) {
                logger.warning("忽略损坏的处罚记录 " + id + ": 无效玩家 UUID");
                return null;
            }

            List<WarningEvidence> warnings = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList(path + ".warnings")) {
                warnings.add(new WarningEvidence(
                        longValue(raw.get("at")), stringValue(raw.get("check")),
                        intValue(raw.get("stage")), doubleValue(raw.get("vl"))));
            }
            List<DetectionEvidence> detections = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList(path + ".detections")) {
                detections.add(new DetectionEvidence(
                        longValue(raw.get("at")), stringValue(raw.get("check")),
                        doubleValue(raw.get("vl")), stringValue(raw.get("detail"))));
            }

            return new PunishmentRecord(
                    id, uuid, yaml.getString(path + ".player-name", uuid.toString()),
                    yaml.getLong(path + ".banned-at"), yaml.getLong(path + ".expires-at"),
                    yaml.getString(path + ".check", "unknown"), yaml.getDouble(path + ".vl"),
                    yaml.getInt(path + ".hours"), yaml.getInt(path + ".ban-number"),
                    warnings, detections);
        }
    }

    /** 列出全部封禁处罚快照，按封禁时间倒序（最新在前）。供管理仪表盘使用。 */
    public List<PunishmentRecord> listPunishments() {
        synchronized (lock) {
            ConfigurationSection section = yaml.getConfigurationSection("punishments");
            if (section == null) return List.of();
            List<PunishmentRecord> records = new ArrayList<>();
            for (String id : section.getKeys(false)) {
                PunishmentRecord record = getPunishment(id);
                if (record != null) records.add(record);
            }
            records.sort(Comparator.comparingLong(PunishmentRecord::bannedAt).reversed());
            return records;
        }
    }

    // ---- 申诉 ----

    private String appealBase(String id) {
        return "appeals." + id;
    }

    /**
     * 提交或更新一条申诉。仅当处罚 ID 存在时才受理；若已存在且已被处理（通过/驳回），
     * 拒绝再次提交，避免玩家绕过管理员决定反复申诉。
     */
    public AppealSubmitResult submitAppeal(String punishmentId, String reason, String contact) {
        synchronized (lock) {
            String id = canonicalPunishmentId(punishmentId);
            if (id == null || !yaml.isConfigurationSection("punishments." + id)) {
                return AppealSubmitResult.PUNISHMENT_NOT_FOUND;
            }
            String path = appealBase(id);
            String existing = yaml.getString(path + ".status");
            if (existing != null && !existing.equalsIgnoreCase(AppealStatus.PENDING.name())) {
                return AppealSubmitResult.ALREADY_RESOLVED;
            }
            boolean isNew = existing == null;
            if (isNew) {
                yaml.set(path + ".player-name", yaml.getString("punishments." + id + ".player-name", ""));
                yaml.set(path + ".submitted-at", System.currentTimeMillis());
                yaml.set(path + ".status", AppealStatus.PENDING.name());
                yaml.set(path + ".resolved-at", 0L);
                yaml.set(path + ".note", "");
            }
            yaml.set(path + ".reason", reason);
            yaml.set(path + ".contact", contact);
            markDirty();
            return AppealSubmitResult.OK;
        }
    }

    public AppealRecord getAppeal(String punishmentId) {
        synchronized (lock) {
            String id = canonicalPunishmentId(punishmentId);
            if (id == null || !yaml.isConfigurationSection(appealBase(id))) return null;
            return readAppealLocked(id);
        }
    }

    public List<AppealRecord> listAppeals() {
        synchronized (lock) {
            ConfigurationSection section = yaml.getConfigurationSection("appeals");
            if (section == null) return List.of();
            List<AppealRecord> records = new ArrayList<>();
            for (String id : section.getKeys(false)) {
                AppealRecord record = readAppealLocked(id);
                if (record != null) records.add(record);
            }
            records.sort(Comparator.comparingLong(AppealRecord::submittedAt).reversed());
            return records;
        }
    }

    /** 处理一条申诉（通过/驳回）。返回 false 表示该处罚没有待处理的申诉。 */
    public boolean resolveAppeal(String punishmentId, boolean approved, String note) {
        synchronized (lock) {
            String id = canonicalPunishmentId(punishmentId);
            if (id == null || !yaml.isConfigurationSection(appealBase(id))) return false;
            String path = appealBase(id);
            yaml.set(path + ".status",
                    (approved ? AppealStatus.APPROVED : AppealStatus.REJECTED).name());
            yaml.set(path + ".resolved-at", System.currentTimeMillis());
            yaml.set(path + ".note", note == null ? "" : note);
            markDirty();
            return true;
        }
    }

    private AppealRecord readAppealLocked(String id) {
        String path = appealBase(id);
        if (!yaml.isConfigurationSection(path)) return null;
        AppealStatus status;
        try {
            status = AppealStatus.valueOf(yaml.getString(path + ".status", "PENDING"));
        } catch (IllegalArgumentException invalid) {
            status = AppealStatus.PENDING;
        }
        return new AppealRecord(
                id,
                yaml.getString(path + ".player-name", ""),
                yaml.getString(path + ".reason", ""),
                yaml.getString(path + ".contact", ""),
                yaml.getLong(path + ".submitted-at"),
                status,
                yaml.getLong(path + ".resolved-at"),
                yaml.getString(path + ".note", ""));
    }

    private static Map<String, Object> warningMap(WarningEvidence warning) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("at", warning.at());
        values.put("check", warning.check());
        values.put("stage", warning.stage());
        values.put("vl", warning.vl());
        return values;
    }

    private static Map<String, Object> detectionMap(DetectionEvidence detection) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("at", detection.at());
        values.put("check", detection.check());
        values.put("vl", detection.vl());
        values.put("detail", detection.detail());
        return values;
    }

    private static String canonicalPunishmentId(String id) {
        if (id == null) return null;
        String value = id.trim();
        String compact = value.replace("-", "").toUpperCase(Locale.ROOT);
        if (compact.length() == PUNISHMENT_ID_LENGTH
                && compact.chars().allMatch(character ->
                        PUNISHMENT_ID_ALPHABET.indexOf(character) >= 0)) {
            return compact.substring(0, 5) + "-" + compact.substring(5);
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    // ---- 保存 ----

    /** Coalesces serialization and disk I/O onto the bounded analysis pool. */
    public void saveAsync() {
        if (!isDirty() || System.currentTimeMillis() < nextRetryAt) return;
        if (!saveScheduled.compareAndSet(false, true)) return;
        boolean accepted = asyncSubmitter.submit(this::saveDirtyLoop);
        if (!accepted) {
            saveScheduled.set(false);
        }
    }

    /** 关服时同步保存 */
    public boolean saveNow() {
        synchronized (saveOrderLock) {
            while (true) {
                Snapshot snapshot = snapshot();
                if (snapshot == null) return true;
                if (!write(snapshot)) return false;
                if (!isDirty()) return true;
            }
        }
    }

    private void saveDirtyLoop() {
        try {
            while (true) {
                synchronized (saveOrderLock) {
                    Snapshot snapshot = snapshot();
                    if (snapshot == null) return;
                    if (!write(snapshot)) return;
                }
            }
        } finally {
            saveScheduled.set(false);
        }
    }

    private Snapshot snapshot() {
        synchronized (lock) {
            if (revision == savedRevision) return null;
            return new Snapshot(revision, yaml.saveToString());
        }
    }

    private boolean write(Snapshot snapshot) {
        try {
            writer.write(file, snapshot.content());
            synchronized (lock) {
                savedRevision = Math.max(savedRevision, snapshot.revision());
                consecutiveFailures = 0;
                nextRetryAt = 0L;
            }
            return true;
        } catch (IOException e) {
            synchronized (lock) {
                consecutiveFailures++;
                long backoff = Math.min(60_000L,
                        1_000L << Math.min(6, consecutiveFailures - 1));
                nextRetryAt = System.currentTimeMillis() + backoff;
            }
            logger.warning("data.yml 保存失败，旧文件保持不变，将稍后重试: " + e.getMessage());
            return false;
        }
    }

    private void markDirty() {
        revision++;
        nextRetryAt = 0L;
    }

    boolean isDirty() {
        return revision != savedRevision;
    }

    private static void writeAtomically(File file, String content) throws IOException {
        Path target = file.toPath();
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Snapshot(long revision, String content) { }
}
