package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.JdbcNetworkStore;
import cn.haitang.anticheat.shared.NetworkModels.ActiveBan;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "sayaka-anticheat",
        name = "Sayaka AntiCheat Velocity",
        version = "2.1.0.7-beta.3",
        authors = {"haitang"}
)
public final class SayakaVelocityPlugin {
    private static final MinecraftChannelIdentifier WEB_LOGIN_CHANNEL =
            MinecraftChannelIdentifier.create("sayaka", "web");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, CacheEntry> banCache = new ConcurrentHashMap<>();

    private VelocitySettings settings;
    private JdbcNetworkStore store;
    private ProtectionState protection;
    private VelocityUpdateManager updateManager;
    private DashboardServer dashboard;
    private ScheduledTask recoveryTask;
    private volatile boolean databaseReady;

    @Inject
    public SayakaVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            settings = VelocitySettings.load(dataDirectory);
            store = new JdbcNetworkStore(settings.database());
        } catch (Exception error) {
            logger.error("Sayaka Velocity 配置加载失败，插件未启动", error);
            return;
        }
        protection = ProtectionState.fromSettings(settings);
        updateManager = new VelocityUpdateManager(pluginVersion(), dataDirectory.resolve("updates"));
        recoverServices();
        proxy.getChannelRegistrar().register(WEB_LOGIN_CHANNEL);
        recoveryTask = proxy.getScheduler().buildTask(this, this::recoverServices)
                .repeat(30, TimeUnit.SECONDS).schedule();
        logger.info("Sayaka Velocity 已启动，节点 ID: {}", settings.serverId());
    }

    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        String serverName = event.getOriginalServer().getServerInfo().getName();
        if (store == null || !protection.enabledFor(serverName)) return null;
        return EventTask.async(() -> lookupBan(event.getPlayer().getUniqueId(), true).ifPresent(ban -> {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().disconnect(denial(ban));
        }));
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(WEB_LOGIN_CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)
                || event.getTarget() != connection.getPlayer()) return;
        byte[] data = event.getData();
        if (data.length != 1 || data[0] != 1) return;
        if (dashboard == null) {
            connection.getPlayer().sendMessage(Component.text(
                    "[Sayaka] Web 面板未启用或尚未启动，请检查 Velocity 控制台。", NamedTextColor.YELLOW));
            return;
        }
        String url = dashboard.createOneTimeLoginUrl();
        connection.getPlayer().sendMessage(Component.text("[Sayaka] ", NamedTextColor.DARK_RED)
                .append(Component.text("点击打开管理后台", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("链接在 2 分钟内有效且只能使用一次")))));
        connection.getPlayer().sendMessage(Component.text(
                "链接在 2 分钟内有效且只能使用一次。", NamedTextColor.DARK_GRAY));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        proxy.getChannelRegistrar().unregister(WEB_LOGIN_CHANNEL);
        if (dashboard != null) dashboard.stop();
        if (recoveryTask != null) recoveryTask.cancel();
        banCache.clear();
    }

    private synchronized void recoverServices() {
        if (store == null) return;
        if (!databaseReady || !store.healthCheck()) {
            try {
                store.initialize();
                databaseReady = true;
                logger.info("Sayaka 群组数据库已连接");
            } catch (SQLException error) {
                databaseReady = false;
                logger.warn("Sayaka 群组数据库不可用；缓存封禁继续生效，未知玩家放行: {}", error.getMessage());
                return;
            }
            try {
                protection.loadRuntimeOverrides(store.protectionOverrides());
            } catch (SQLException error) {
                logger.warn("Sayaka 保护开关覆盖读取失败，暂用配置文件默认值: {}", error.getMessage());
            }
        }
        if (settings.webEnabled() && dashboard == null) {
            try {
                dashboard = DashboardServer.start(store, networkControl(), updateManager, protection,
                        banCache::remove, settings, logger);
            } catch (Exception error) {
                logger.warn("Sayaka Web 面板暂未启动，将在 30 秒后重试: {}", error.getMessage());
            }
        }
    }

    private String pluginVersion() {
        return proxy.getPluginManager().getPlugin("sayaka-anticheat")
                .flatMap(container -> container.getDescription().getVersion())
                .orElse("0.0.0");
    }

    private NetworkControl networkControl() {
        return new NetworkControl() {
            @Override
            public int onlineCount() {
                return proxy.getPlayerCount();
            }

            @Override
            public List<OnlinePlayer> onlinePlayers() {
                List<OnlinePlayer> players = new ArrayList<>();
                for (var player : proxy.getAllPlayers()) {
                    String server = player.getCurrentServer()
                            .map(connection -> connection.getServerInfo().getName()).orElse("—");
                    players.add(new OnlinePlayer(player.getUniqueId(), player.getUsername(),
                            server, Math.max(-1L, player.getPing())));
                }
                players.sort(Comparator.comparing(OnlinePlayer::name, String.CASE_INSENSITIVE_ORDER));
                return players;
            }

            @Override
            public boolean kick(UUID playerId, String reason) {
                return proxy.getPlayer(playerId).map(player -> {
                    player.disconnect(Component.text(reason, NamedTextColor.RED));
                    return true;
                }).orElse(false);
            }

            @Override
            public List<ServerNode> servers() {
                List<RegisteredServer> registered = new ArrayList<>(proxy.getAllServers());
                List<CompletableFuture<ServerPing>> pings = registered.stream()
                        .map(RegisteredServer::ping).toList();
                long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
                List<ServerNode> nodes = new ArrayList<>();
                for (int i = 0; i < registered.size(); i++) {
                    RegisteredServer server = registered.get(i);
                    boolean reachable = false;
                    long pingMillis = -1L;
                    long start = System.nanoTime();
                    try {
                        long remaining = Math.max(1L, (deadline - start) / 1_000_000L);
                        pings.get(i).get(remaining, TimeUnit.MILLISECONDS);
                        reachable = true;
                        pingMillis = (System.nanoTime() - start) / 1_000_000L;
                    } catch (Exception unreachable) {
                        if (unreachable instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    nodes.add(new ServerNode(server.getServerInfo().getName(),
                            server.getPlayersConnected().size(), reachable, pingMillis));
                }
                nodes.sort(Comparator.comparing(ServerNode::name, String.CASE_INSENSITIVE_ORDER));
                return nodes;
            }
        };
    }

    private Optional<ActiveBan> lookupBan(UUID playerId, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        CacheEntry cached = banCache.get(playerId);
        if (!forceRefresh && cached != null && now - cached.loadedAt() < settings.banCacheMillis()) {
            return unexpired(cached.ban(), now);
        }
        try {
            Optional<ActiveBan> found = store.findActiveBan(playerId, now);
            banCache.put(playerId, new CacheEntry(found.orElse(null), now));
            return found;
        } catch (SQLException error) {
            logger.warn("查询玩家 {} 的群组封禁失败；使用最后一次成功缓存: {}", playerId, error.getMessage());
            return cached == null ? Optional.empty() : unexpired(cached.ban(), now);
        }
    }

    private static Optional<ActiveBan> unexpired(ActiveBan ban, long now) {
        return ban != null && ban.expiresAt() > now ? Optional.of(ban) : Optional.empty();
    }

    private static Component denial(ActiveBan ban) {
        long millis = Math.max(0L, ban.expiresAt() - System.currentTimeMillis());
        long minutes = Math.max(1L, Duration.ofMillis(millis).toMinutes());
        return Component.text("你已被 Sayaka AntiCheat 临时封禁", NamedTextColor.RED)
                .append(Component.newline())
                .append(Component.text("剩余约 " + minutes + " 分钟", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("处罚 ID: " + ban.punishmentId(), NamedTextColor.DARK_GRAY));
    }

    private record CacheEntry(ActiveBan ban, long loadedAt) {}
}
