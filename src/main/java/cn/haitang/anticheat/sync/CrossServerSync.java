package cn.haitang.anticheat.sync;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.data.PersistentStore;
import cn.haitang.anticheat.violation.PunishmentExecutor;
import io.papermc.paper.ban.BanListType;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-backed punishment replication for a network of Paper servers. */
public final class CrossServerSync {

    private static final String TYPE_BAN = "BAN";
    private static final String TYPE_UNBAN = "UNBAN";
    private static final String TYPE_STATE = "STATE";

    private final AntiCheatPlugin plugin;
    private final String origin = UUID.randomUUID().toString();
    private final HostAndPort address;
    private final JedisClientConfig clientConfig;
    private final String statesKey;
    private final String punishmentsKey;
    private final String activeKey;
    private final String channel;
    private final ExecutorService publisher;
    private final Map<UUID, CrossServerCodec.SyncedPunishment> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestUnban = new ConcurrentHashMap<>();
    private final Map<UUID, CrossServerCodec.Envelope> pending = new ConcurrentHashMap<>();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final AtomicBoolean fullRefreshRequested = new AtomicBoolean();

    private JedisPooled client;
    private JedisPubSub subscriber;
    private Thread subscriberThread;
    private BukkitTask refreshTask;
    private volatile boolean running;

    public CrossServerSync(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        address = new HostAndPort(
                plugin.config().getString("cross-server.redis.host", "127.0.0.1"),
                plugin.config().getInt("cross-server.redis.port", 6379));
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(plugin.config().getInt(
                        "cross-server.redis.connect-timeout-ms", 2000))
                .socketTimeoutMillis(plugin.config().getInt(
                        "cross-server.redis.socket-timeout-ms", 2000))
                .database(plugin.config().getInt("cross-server.redis.database", 0))
                .ssl(plugin.config().getBoolean("cross-server.redis.ssl", false));
        String user = plugin.config().getString("cross-server.redis.username", "");
        String password = plugin.config().getString("cross-server.redis.password", "");
        if (!user.isBlank()) config.user(user);
        if (!password.isBlank()) config.password(password);
        clientConfig = config.build();

        String namespace = plugin.config().getString("cross-server.namespace", "sayaka");
        String prefix = namespace + ":punishments:v1";
        statesKey = prefix + ":player-states";
        punishmentsKey = prefix + ":records";
        activeKey = prefix + ":active";
        channel = prefix + ":events";
        publisher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sayaka-cross-server-publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Connects and performs the initial authoritative import on the server thread. */
    public boolean start() {
        try {
            client = new JedisPooled(address, clientConfig);
            client.ping();
            importInitialState();
        } catch (RuntimeException error) {
            closeClient();
            plugin.getLogger().severe("无法连接跨服 Redis " + address + ": " + error.getMessage());
            return false;
        }

        running = true;
        startSubscriber();
        long seconds = plugin.config().getInt("cross-server.refresh-seconds", 5);
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::requestRefresh, seconds * 20L, seconds * 20L);
        plugin.getLogger().info("跨服处罚同步已启用，Redis " + address);
        return true;
    }

    public boolean isActive(UUID playerId) {
        return activePunishment(playerId) != null;
    }

    public PersistentStore.PunishmentRecord activePunishment(UUID playerId) {
        CrossServerCodec.SyncedPunishment synced = active.get(playerId);
        if (synced == null) return null;
        if (synced.record().expiresAt() <= System.currentTimeMillis()) {
            active.remove(playerId, synced);
            return null;
        }
        return synced.record();
    }

    public String activeScreen(UUID playerId) {
        CrossServerCodec.SyncedPunishment synced = active.get(playerId);
        return synced == null || synced.record().expiresAt() <= System.currentTimeMillis()
                ? null : synced.screen();
    }

    public void publishState(UUID playerId) {
        publish(TYPE_STATE, plugin.getStore().getPunishmentState(playerId), null, false);
    }

    public void publishBan(PersistentStore.PunishmentRecord record, String screen) {
        CrossServerCodec.SyncedPunishment punishment =
                new CrossServerCodec.SyncedPunishment(record, screen);
        active.put(record.playerId(), punishment);
        publish(TYPE_BAN, plugin.getStore().getPunishmentState(record.playerId()), punishment, false);
    }

    public void publishUnban(UUID playerId, boolean reset) {
        active.remove(playerId);
        publish(TYPE_UNBAN, plugin.getStore().getPunishmentState(playerId), null, reset);
    }

    private void publish(String type, PersistentStore.PlayerPunishmentState state,
                         CrossServerCodec.SyncedPunishment punishment, boolean reset) {
        long updatedAt = System.currentTimeMillis();
        latestState.put(state.playerId(), updatedAt);
        CrossServerCodec.Envelope envelope = new CrossServerCodec.Envelope(
                type, origin, updatedAt, state, punishment, reset);
        if (TYPE_UNBAN.equals(type)) latestUnban.put(state.playerId(), updatedAt);
        pending.put(state.playerId(), envelope);
        publisher.execute(() -> flush(envelope));
    }

    private void flush(CrossServerCodec.Envelope envelope) {
        UUID playerId = envelope.state().playerId();
        if (pending.get(playerId) != envelope) return;
        try {
            String encoded = CrossServerCodec.encodeEnvelope(envelope);
            if (envelope.punishment() != null) {
                String encodedPunishment = CrossServerCodec.encodePunishment(envelope.punishment());
                client.eval("redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]); "
                                + "redis.call('HSET', KEYS[2], ARGV[3], ARGV[4]); "
                                + "redis.call('HSET', KEYS[3], ARGV[1], ARGV[4]); "
                                + "return redis.call('PUBLISH', KEYS[4], ARGV[2])",
                        java.util.List.of(statesKey, punishmentsKey, activeKey, channel),
                        java.util.List.of(playerId.toString(), encoded,
                                envelope.punishment().record().id(), encodedPunishment));
            } else if (TYPE_UNBAN.equals(envelope.type())) {
                client.eval("redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]); "
                                + "redis.call('HDEL', KEYS[2], ARGV[1]); "
                                + "return redis.call('PUBLISH', KEYS[3], ARGV[2])",
                        java.util.List.of(statesKey, activeKey, channel),
                        java.util.List.of(playerId.toString(), encoded));
            } else {
                client.eval("redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]); "
                                + "return redis.call('PUBLISH', KEYS[2], ARGV[2])",
                        java.util.List.of(statesKey, channel),
                        java.util.List.of(playerId.toString(), encoded));
            }
            pending.remove(playerId, envelope);
        } catch (RuntimeException error) {
            plugin.getLogger().warning("跨服处罚同步写入失败，将在下次对账时重试: "
                    + error.getMessage());
        }
    }

    private void importInitialState() {
        List<PersistentStore.PlayerPunishmentState> localStates =
                plugin.getStore().listPunishmentStates();
        List<PersistentStore.PunishmentRecord> localPunishments =
                plugin.getStore().listPunishments();
        Map<UUID, CrossServerCodec.SyncedPunishment> localActive =
                findLocalActivePunishments(localPunishments);

        for (Map.Entry<String, String> entry : client.hgetAll(statesKey).entrySet()) {
            try {
                applyStateEnvelope(CrossServerCodec.decodeEnvelope(entry.getValue()));
            } catch (RuntimeException error) {
                plugin.getLogger().warning("忽略损坏的跨服玩家状态 " + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : client.hgetAll(punishmentsKey).entrySet()) {
            try {
                CrossServerCodec.SyncedPunishment punishment =
                        CrossServerCodec.decodePunishment(entry.getValue());
                plugin.getStore().importPunishment(punishment.record());
            } catch (RuntimeException error) {
                plugin.getLogger().warning("忽略损坏的跨服处罚记录 " + entry.getKey());
            }
        }
        reconcileActive(client.hgetAll(activeKey));
        seedLocalData(localStates, localPunishments, localActive);
        plugin.getStore().saveAsync();
    }

    private Map<UUID, CrossServerCodec.SyncedPunishment> findLocalActivePunishments(
            List<PersistentStore.PunishmentRecord> punishments) {
        Map<UUID, CrossServerCodec.SyncedPunishment> result = new HashMap<>();
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        for (PersistentStore.PunishmentRecord record : punishments) {
            if (record.expiresAt() <= System.currentTimeMillis()) continue;
            OfflinePlayer player = Bukkit.getOfflinePlayer(record.playerId());
            BanEntry<?> entry = banList.getBanEntry(player.getPlayerProfile());
            if (entry == null || !PunishmentExecutor.BAN_SOURCE.equals(entry.getSource())) continue;
            CrossServerCodec.SyncedPunishment current = result.get(record.playerId());
            if (current == null || current.record().bannedAt() < record.bannedAt()) {
                String screen = entry.getReason();
                if (screen == null || screen.isBlank()) {
                    screen = "§c你正在被反作弊系统临时封禁\n§7处罚 ID: §f" + record.id();
                }
                result.put(record.playerId(), new CrossServerCodec.SyncedPunishment(record, screen));
            }
        }
        return result;
    }

    private void seedLocalData(
            List<PersistentStore.PlayerPunishmentState> states,
            List<PersistentStore.PunishmentRecord> punishments,
            Map<UUID, CrossServerCodec.SyncedPunishment> localActive) {
        long now = System.currentTimeMillis();
        for (PersistentStore.PlayerPunishmentState state : states) {
            CrossServerCodec.Envelope envelope = new CrossServerCodec.Envelope(
                    TYPE_STATE, origin, now, state, null, false);
            if (client.hsetnx(statesKey, state.playerId().toString(),
                    CrossServerCodec.encodeEnvelope(envelope)) == 1L) {
                latestState.put(state.playerId(), now);
            }
        }
        for (PersistentStore.PunishmentRecord record : punishments) {
            CrossServerCodec.SyncedPunishment activePunishment = localActive.get(record.playerId());
            CrossServerCodec.SyncedPunishment synced = activePunishment != null
                    && activePunishment.record().id().equals(record.id())
                    ? activePunishment : new CrossServerCodec.SyncedPunishment(record, "");
            client.hsetnx(punishmentsKey, record.id(), CrossServerCodec.encodePunishment(synced));
        }
        for (Map.Entry<UUID, CrossServerCodec.SyncedPunishment> entry : localActive.entrySet()) {
            String encoded = CrossServerCodec.encodePunishment(entry.getValue());
            if (seedActiveIfMissingOrExpired(entry.getKey(), encoded)) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean seedActiveIfMissingOrExpired(UUID playerId, String encoded) {
        String field = playerId.toString();
        String existing = client.hget(activeKey, field);
        if (existing == null) return client.hsetnx(activeKey, field, encoded) == 1L;
        try {
            if (CrossServerCodec.decodePunishment(existing).record().expiresAt()
                    > System.currentTimeMillis()) return false;
        } catch (RuntimeException ignored) {
            // A corrupt active value may be replaced by a valid local ban.
        }
        Object result = client.eval(
                "if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then "
                        + "redis.call('HSET', KEYS[1], ARGV[1], ARGV[3]); return 1 end; return 0",
                List.of(activeKey), List.of(field, existing, encoded));
        return result instanceof Number number && number.longValue() == 1L;
    }

    private void startSubscriber() {
        subscriber = new JedisPubSub() {
            @Override
            public void onSubscribe(String subscribedChannel, int subscribedChannels) {
                fullRefreshRequested.set(true);
                requestRefresh();
            }

            @Override
            public void onMessage(String subscribedChannel, String message) {
                try {
                    CrossServerCodec.Envelope envelope = CrossServerCodec.decodeEnvelope(message);
                    if (origin.equals(envelope.origin())) return;
                    Bukkit.getScheduler().runTask(plugin, () -> applyEnvelope(envelope));
                } catch (RuntimeException error) {
                    plugin.getLogger().warning("忽略无效的跨服处罚事件: " + error.getMessage());
                }
            }
        };
        subscriberThread = new Thread(() -> {
            while (running) {
                try (Jedis jedis = new Jedis(address, clientConfig)) {
                    jedis.subscribe(subscriber, channel);
                } catch (RuntimeException error) {
                    if (!running) return;
                    plugin.getLogger().warning("跨服处罚订阅断开，正在重连: " + error.getMessage());
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "sayaka-cross-server-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void requestRefresh() {
        if (!running || publisher.isShutdown()) return;
        publisher.execute(this::refreshFromRedis);
    }

    private void refreshFromRedis() {
        boolean fullRefresh = fullRefreshRequested.getAndSet(false)
                || refreshes.incrementAndGet() % 12 == 0;
        try {
            for (CrossServerCodec.Envelope envelope : pending.values()) flush(envelope);
            Map<String, String> states = fullRefresh ? client.hgetAll(statesKey) : Map.of();
            Map<String, String> activeRecords = client.hgetAll(activeKey);
            Map<String, String> punishments = fullRefresh
                    ? client.hgetAll(punishmentsKey) : Map.of();
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<String, String> entry : states.entrySet()) {
                    try {
                        applyStateEnvelope(CrossServerCodec.decodeEnvelope(entry.getValue()));
                    } catch (RuntimeException error) {
                        plugin.getLogger().warning("忽略损坏的跨服玩家状态 " + entry.getKey());
                    }
                }
                for (Map.Entry<String, String> entry : punishments.entrySet()) {
                    try {
                        plugin.getStore().importPunishment(
                                CrossServerCodec.decodePunishment(entry.getValue()).record());
                    } catch (RuntimeException error) {
                        plugin.getLogger().warning("忽略损坏的跨服处罚记录 " + entry.getKey());
                    }
                }
                reconcileActive(activeRecords);
                plugin.getStore().saveAsync();
            });
        } catch (RuntimeException error) {
            if (fullRefresh) fullRefreshRequested.set(true);
            plugin.getLogger().warning("跨服处罚定期对账失败: " + error.getMessage());
        }
    }

    private void applyEnvelope(CrossServerCodec.Envelope envelope) {
        if (envelope.state() != null) applyStateEnvelope(envelope);
        if (envelope.punishment() != null) {
            plugin.getStore().importPunishment(envelope.punishment().record());
        }
        if (TYPE_BAN.equals(envelope.type()) && envelope.punishment() != null) {
            CrossServerCodec.SyncedPunishment punishment = envelope.punishment();
            Long unbannedAt = latestUnban.get(punishment.record().playerId());
            if (unbannedAt == null || unbannedAt < punishment.record().bannedAt()) {
                active.put(punishment.record().playerId(), punishment);
                installBan(punishment);
            }
        } else if (TYPE_UNBAN.equals(envelope.type()) && envelope.state() != null) {
            UUID playerId = envelope.state().playerId();
            CrossServerCodec.SyncedPunishment current = active.get(playerId);
            if (current == null || current.record().bannedAt() <= envelope.updatedAt()) {
                latestUnban.merge(playerId, envelope.updatedAt(), Math::max);
                active.remove(playerId);
                pardonLocalBan(playerId);
                resetOnlineState(playerId);
            }
        }
        plugin.getStore().saveAsync();
    }

    private boolean applyStateEnvelope(CrossServerCodec.Envelope envelope) {
        PersistentStore.PlayerPunishmentState state = envelope.state();
        if (state == null) return true;
        Long previous = latestState.get(state.playerId());
        if (previous != null && previous >= envelope.updatedAt()) return false;
        latestState.put(state.playerId(), envelope.updatedAt());
        if (TYPE_UNBAN.equals(envelope.type())) {
            latestUnban.put(state.playerId(), envelope.updatedAt());
        }
        plugin.getStore().applyPunishmentState(state);
        return true;
    }

    private void reconcileActive(Map<String, String> encodedRecords) {
        long now = System.currentTimeMillis();
        Map<UUID, CrossServerCodec.SyncedPunishment> refreshed = new HashMap<>();
        for (Map.Entry<String, String> entry : encodedRecords.entrySet()) {
            try {
                CrossServerCodec.SyncedPunishment punishment =
                        CrossServerCodec.decodePunishment(entry.getValue());
                if (punishment.record().expiresAt() <= now) {
                    publisher.execute(() -> client.eval(
                            "if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then "
                                    + "return redis.call('HDEL', KEYS[1], ARGV[1]) end; return 0",
                            List.of(activeKey), List.of(entry.getKey(), entry.getValue())));
                    continue;
                }
                Long unbannedAt = latestUnban.get(punishment.record().playerId());
                if (unbannedAt != null && unbannedAt >= punishment.record().bannedAt()) continue;
                refreshed.put(punishment.record().playerId(), punishment);
                plugin.getStore().importPunishment(punishment.record());
                installBan(punishment);
            } catch (RuntimeException error) {
                plugin.getLogger().warning("忽略损坏的跨服有效处罚 " + entry.getKey()
                        + ": " + error.getMessage());
            }
        }
        for (UUID oldPlayer : active.keySet()) {
            if (!refreshed.containsKey(oldPlayer) && !pending.containsKey(oldPlayer)) {
                pardonLocalBan(oldPlayer);
            } else if (!refreshed.containsKey(oldPlayer)) {
                refreshed.put(oldPlayer, active.get(oldPlayer));
            }
        }
        active.clear();
        active.putAll(refreshed);
    }

    private void installBan(CrossServerCodec.SyncedPunishment synced) {
        PersistentStore.PunishmentRecord record = synced.record();
        if (record.expiresAt() <= System.currentTimeMillis()) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(record.playerId());
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        BanEntry<?> current = banList.getBanEntry(target.getPlayerProfile());
        if (current != null && !PunishmentExecutor.BAN_SOURCE.equals(current.getSource())) return;
        if (current == null || current.getExpiration() == null
                || current.getExpiration().getTime() < record.expiresAt()) {
            banList.addBan(target.getPlayerProfile(), synced.screen(),
                    new Date(record.expiresAt()), PunishmentExecutor.BAN_SOURCE);
        }
        Player online = target.getPlayer();
        if (online != null && online.isOnline()) online.kickPlayer(synced.screen());
    }

    private void pardonLocalBan(UUID playerId) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerId);
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        BanEntry<?> entry = banList.getBanEntry(target.getPlayerProfile());
        if (entry != null && PunishmentExecutor.BAN_SOURCE.equals(entry.getSource())) {
            banList.pardon(target.getPlayerProfile());
        }
    }

    private void resetOnlineState(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null) return;
        var data = plugin.getDataManager().get(online);
        data.resetAllVl();
        data.setPunishing(false);
    }

    public void shutdown() {
        running = false;
        if (refreshTask != null) refreshTask.cancel();
        if (subscriber != null) {
            try {
                subscriber.unsubscribe();
            } catch (RuntimeException ignored) {
                // The subscriber may already be disconnected.
            }
        }
        if (subscriberThread != null) subscriberThread.interrupt();
        publisher.shutdown();
        try {
            if (!publisher.awaitTermination(2, TimeUnit.SECONDS)) publisher.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        closeClient();
    }

    private void closeClient() {
        if (client != null) client.close();
    }
}
