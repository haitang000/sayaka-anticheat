package cn.haitang.anticheat.data;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.shared.JdbcNetworkStore;
import cn.haitang.anticheat.shared.NetworkModels;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts the shared MariaDB repository to the Paper plugin's existing store API. */
public final class NetworkPersistentStore extends PersistentStore {
    private static final long WHITELIST_REFRESH_MS = 30_000L;

    private final AntiCheatPlugin plugin;
    private final JdbcNetworkStore repository;
    private final String serverId;
    private final Map<UUID, String> whitelist = new ConcurrentHashMap<>();
    private final AtomicBoolean whitelistRefreshRunning = new AtomicBoolean();
    private volatile long nextWhitelistRefresh;
    private volatile long nextErrorLog;

    public NetworkPersistentStore(AntiCheatPlugin plugin, JdbcNetworkStore repository, String serverId) {
        super(plugin);
        this.plugin = plugin;
        this.repository = repository;
        this.serverId = serverId;
        try {
            repository.initialize();
            refreshWhitelistNow();
            plugin.getLogger().info("已连接群组服 MariaDB，共享节点 ID: " + serverId);
        } catch (SQLException error) {
            logDatabaseError("初始化", error);
        }
    }

    public String serverId() {
        return serverId;
    }

    public NetworkModels.EnforcementDecision prepareEnforcement(NetworkModels.EnforcementRequest request)
            throws SQLException {
        return repository.prepareEnforcement(request, System.currentTimeMillis());
    }

    public void pardonNetwork(UUID playerId, boolean resetBanCount) throws SQLException {
        repository.pardon(playerId, resetBanCount);
    }

    @Override
    public boolean isWhitelisted(UUID uuid) {
        refreshWhitelistIfNeeded();
        return whitelist.containsKey(uuid);
    }

    @Override
    public void addWhitelist(UUID uuid, String name) {
        try {
            repository.addWhitelist(uuid, name);
            whitelist.put(uuid, name);
        } catch (SQLException error) {
            logDatabaseError("添加白名单", error);
        }
    }

    @Override
    public boolean removeWhitelist(UUID uuid) {
        try {
            boolean removed = repository.removeWhitelist(uuid);
            if (removed) whitelist.remove(uuid);
            return removed;
        } catch (SQLException error) {
            logDatabaseError("移除白名单", error);
            return false;
        }
    }

    @Override
    public WhitelistEntry findWhitelistByName(String name) {
        refreshWhitelistIfNeeded();
        return whitelist.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(name))
                .map(entry -> new WhitelistEntry(entry.getKey(), entry.getValue()))
                .findFirst().orElse(null);
    }

    @Override
    public List<WhitelistEntry> getWhitelist() {
        refreshWhitelistIfNeeded();
        return whitelist.entrySet().stream()
                .map(entry -> new WhitelistEntry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(WhitelistEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public void addStrike(UUID uuid, String name) {
        try {
            repository.addStrike(uuid, name, System.currentTimeMillis());
        } catch (SQLException error) {
            logDatabaseError("写入 strike", error);
        }
    }

    @Override
    public int strikeCount(UUID uuid, int windowHours) {
        try {
            return repository.strikeCount(uuid, windowHours, System.currentTimeMillis());
        } catch (SQLException error) {
            logDatabaseError("读取 strike", error);
            return 0;
        }
    }

    @Override
    public void clearStrikes(UUID uuid) {
        try {
            repository.clearStrikes(uuid);
        } catch (SQLException error) {
            logDatabaseError("清理 strike", error);
        }
    }

    @Override
    public int getBanCount(UUID uuid) {
        try {
            return repository.banCount(uuid);
        } catch (SQLException error) {
            logDatabaseError("读取封禁次数", error);
            return 0;
        }
    }

    @Override
    public void incrementBanCount(UUID uuid) {
        try {
            repository.incrementBanCount(uuid, uuid.toString());
        } catch (SQLException error) {
            logDatabaseError("更新封禁次数", error);
        }
    }

    @Override
    public void resetBanCount(UUID uuid) {
        try {
            repository.resetBanCount(uuid);
        } catch (SQLException error) {
            logDatabaseError("重置封禁次数", error);
        }
    }

    @Override
    public void resetPlayer(UUID uuid) {
        try {
            repository.resetPlayer(uuid);
        } catch (SQLException error) {
            logDatabaseError("重置玩家档案", error);
        }
    }

    @Override
    public void addHistory(UUID uuid, String line) {
        try {
            repository.addHistory(uuid, line);
        } catch (SQLException error) {
            logDatabaseError("写入历史", error);
        }
    }

    @Override
    public List<String> getHistory(UUID uuid) {
        try {
            return repository.history(uuid);
        } catch (SQLException error) {
            logDatabaseError("读取历史", error);
            return List.of();
        }
    }

    @Override
    public String newPunishmentId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public void addPunishment(PunishmentRecord punishment) {
        throw new IllegalStateException("群组服处罚必须通过原子 prepareEnforcement 写入");
    }

    @Override
    public PunishmentRecord getPunishment(String punishmentId) {
        try {
            return repository.getPunishment(punishmentId).map(NetworkPersistentStore::fromNetwork).orElse(null);
        } catch (SQLException error) {
            logDatabaseError("读取处罚", error);
            return null;
        }
    }

    @Override
    public List<PunishmentRecord> listPunishments() {
        try {
            return repository.listPunishments().stream().map(NetworkPersistentStore::fromNetwork).toList();
        } catch (SQLException error) {
            logDatabaseError("列出处罚", error);
            return List.of();
        }
    }

    @Override
    public AppealSubmitResult submitAppeal(String punishmentId, String reason, String contact) {
        try {
            return AppealSubmitResult.valueOf(repository.submitAppeal(
                    punishmentId, reason, contact, System.currentTimeMillis()).name());
        } catch (SQLException error) {
            logDatabaseError("提交申诉", error);
            return AppealSubmitResult.PUNISHMENT_NOT_FOUND;
        }
    }

    @Override
    public AppealRecord getAppeal(String punishmentId) {
        try {
            return repository.getAppeal(punishmentId).map(NetworkPersistentStore::fromNetwork).orElse(null);
        } catch (SQLException error) {
            logDatabaseError("读取申诉", error);
            return null;
        }
    }

    @Override
    public List<AppealRecord> listAppeals() {
        try {
            return repository.listAppeals().stream().map(NetworkPersistentStore::fromNetwork).toList();
        } catch (SQLException error) {
            logDatabaseError("列出申诉", error);
            return List.of();
        }
    }

    @Override
    public boolean resolveAppeal(String punishmentId, boolean approved, String note) {
        try {
            return repository.resolveAppeal(
                    punishmentId, approved, note == null ? "" : note, System.currentTimeMillis());
        } catch (SQLException error) {
            logDatabaseError("处理申诉", error);
            return false;
        }
    }

    @Override
    public void saveAsync() {
        // Every MariaDB mutation is committed before returning.
    }

    @Override
    public boolean saveNow() {
        return repository.healthCheck();
    }

    private void refreshWhitelistIfNeeded() {
        long now = System.currentTimeMillis();
        if (now < nextWhitelistRefresh || !whitelistRefreshRunning.compareAndSet(false, true)) return;
        boolean accepted = plugin.getAnalysisExecutor().execute(() -> {
            try {
                refreshWhitelistNow();
            } catch (SQLException error) {
                logDatabaseError("刷新白名单", error);
            } finally {
                nextWhitelistRefresh = System.currentTimeMillis() + WHITELIST_REFRESH_MS;
                whitelistRefreshRunning.set(false);
            }
        });
        if (!accepted) whitelistRefreshRunning.set(false);
    }

    private void refreshWhitelistNow() throws SQLException {
        List<Map.Entry<UUID, String>> entries = repository.listWhitelist();
        whitelist.clear();
        for (Map.Entry<UUID, String> entry : entries) whitelist.put(entry.getKey(), entry.getValue());
        nextWhitelistRefresh = System.currentTimeMillis() + WHITELIST_REFRESH_MS;
    }

    private void logDatabaseError(String operation, SQLException error) {
        long now = System.currentTimeMillis();
        if (now < nextErrorLog) return;
        nextErrorLog = now + 30_000L;
        plugin.getLogger().warning("群组服数据库" + operation + "失败，检测继续运行: " + error.getMessage());
    }

    private static PunishmentRecord fromNetwork(NetworkModels.Punishment value) {
        return new PunishmentRecord(value.id(), value.playerId(), value.playerName(), value.serverId(),
                value.bannedAt(), value.expiresAt(), value.check(), value.vl(), value.hours(), value.banNumber(),
                value.warnings().stream().map(item -> new WarningEvidence(
                        item.at(), item.check(), item.stage(), item.vl())).toList(),
                value.detections().stream().map(item -> new DetectionEvidence(
                        item.at(), item.check(), item.vl(), item.detail())).toList());
    }

    private static AppealRecord fromNetwork(NetworkModels.Appeal value) {
        return new AppealRecord(value.punishmentId(), value.playerName(), value.reason(), value.contact(),
                value.submittedAt(), AppealStatus.valueOf(value.status().name()), value.resolvedAt(), value.note());
    }
}
