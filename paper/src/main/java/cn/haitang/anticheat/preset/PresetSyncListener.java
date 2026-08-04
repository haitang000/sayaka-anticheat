package cn.haitang.anticheat.preset;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * 与 Velocity 的预设档同步：
 *
 * <p>Paper 端通过 {@code sayaka:preset} 插件消息通道向 Velocity 查询自己所属服务器
 * 应使用的预设档（strict/balanced/lenient）。Velocity 从连接来源取服务器名，
 * 命中面板/配置里按服务器单独设置的预设。查询在启用后延迟执行（等待代理连接建立），
 * 收到响应后若与本地 config.yml 的 settings.preset 不同，则写入并热重载配置。
 */
public final class PresetSyncListener implements PluginMessageListener {

    public static final String CHANNEL = "sayaka:preset";

    private static final byte[] QUERY = new byte[] {1};

    private final AntiCheatPlugin plugin;
    private final AtomicBoolean queried = new AtomicBoolean();

    public PresetSyncListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    /** 注册通道并在延迟后发起查询（等待 Velocity 连接与自身配置就绪）。 */
    public void start() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::query, 60L);
        // 每 5 分钟重试一次，直到收到响应（代理可能稍晚才接入）
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!queried.get()) query();
        }, 5L * 60L * 20L, 5L * 60L * 20L);
    }

    public void stop() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    private void query() {
        Player player = firstPlayer();
        if (player == null) return; // 无玩家在线时无法发插件消息，等定时重试
        player.sendPluginMessage(plugin, CHANNEL, QUERY);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        queried.set(true);
        String preset = new String(message, StandardCharsets.UTF_8).trim();
        if (!preset.equals("strict") && !preset.equals("balanced") && !preset.equals("lenient")) {
            plugin.getLogger().warning("Velocity 返回了无效预设档: " + preset);
            return;
        }
        String local = plugin.config().getString("settings.preset", "balanced");
        if (preset.equals(local)) return;
        plugin.getLogger().info("Velocity 指定本服务器预设档为 " + preset
                + "（本地为 " + local + "），正在应用...");
        plugin.getConfig().set("settings.preset", preset);
        plugin.saveConfig();
        java.util.List<String> errors = plugin.reloadRuntimeConfig();
        if (errors.isEmpty()) {
            plugin.getLogger().info("已应用 Velocity 预设档: " + preset);
        } else {
            plugin.getLogger().log(Level.WARNING, "应用预设档后配置重载失败，继续使用当前配置", new RuntimeException(String.join("; ", errors)));
        }
    }

    private Player firstPlayer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }
}
