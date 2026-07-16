package cn.haitang.anticheat.velocity;

import cn.haitang.anticheat.shared.JdbcNetworkStore;
import cn.haitang.anticheat.shared.NetworkModels.ActiveBan;
import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "sayaka-anticheat",
        name = "Sayaka AntiCheat Velocity",
        version = "2.1.0.3",
        authors = {"haitang"}
)
public final class SayakaVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<UUID, CacheEntry> banCache = new ConcurrentHashMap<>();

    private VelocitySettings settings;
    private JdbcNetworkStore store;
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
        recoverServices();
        recoveryTask = proxy.getScheduler().buildTask(this, this::recoverServices)
                .repeat(30, TimeUnit.SECONDS).schedule();
        logger.info("Sayaka Velocity 已启动，节点 ID: {}", settings.serverId());
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        if (store == null) return null;
        return EventTask.async(() -> lookupBan(event.getPlayer().getUniqueId(), false)
                .ifPresent(ban -> event.setResult(ResultedEvent.ComponentResult.denied(denial(ban)))));
    }

    @Subscribe
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        if (store == null) return null;
        return EventTask.async(() -> lookupBan(event.getPlayer().getUniqueId(), true).ifPresent(ban -> {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().disconnect(denial(ban));
        }));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
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
        }
        if (settings.webEnabled() && dashboard == null) {
            try {
                dashboard = DashboardServer.start(store, proxy::getPlayerCount, banCache::remove, settings, logger);
            } catch (Exception error) {
                logger.warn("Sayaka Web 面板暂未启动，将在 30 秒后重试: {}", error.getMessage());
            }
        }
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
