package cn.haitang.anticheat.alert;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 消息出口：
 * 1. 给违规玩家的递进式警告（标题 + 聊天 + 音效）
 * 2. 给管理员的实时警报（anticheat.alerts 权限，可 /sac alerts 开关）
 * 3. 惩罚时的全服公告
 */
public class AlertManager {

    public static final String PERM_ALERTS = "anticheat.alerts";

    /** 警报同玩家同检测项的最小间隔，防止刷屏 */
    private static final long ALERT_THROTTLE_MS = 3000;
    /** 对玩家本人的警告最小间隔 */
    private static final long WARN_THROTTLE_MS = 2500;

    private final AntiCheatPlugin plugin;
    private final Set<UUID> alertsDisabled = new HashSet<>();

    public AlertManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- 管理员警报 ----

    public void staffAlert(Player subject, CheckType type, double vl, String detail) {
        PlayerData data = plugin.getDataManager().get(subject);
        long now = System.currentTimeMillis();
        Long last = data.getLastAlertAt().get(type);
        if (last != null && now - last < ALERT_THROTTLE_MS) return;
        data.getLastAlertAt().put(type, now);

        String msg = plugin.getMessages().get("alert", Map.of(
                "player", subject.getName(),
                "check", type.display(),
                "vl", String.format("%.1f", vl),
                "detail", detail,
                "ping", String.valueOf(subject.getPing())
        ));
        plugin.getLogger().info(stripColor(msg));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(PERM_ALERTS) && !alertsDisabled.contains(online.getUniqueId())) {
                online.sendMessage(msg);
            }
        }
    }

    /**
     * 向在线管理员（持 {@link #PERM_ALERTS} 且未用 /sac alerts 关闭）推送一条消息。
     * 用于玩家举报等低频但重要的通知，不受违规警报节流影响。
     */
    public void notifyStaff(String message) {
        plugin.getLogger().info(stripColor(message));
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(PERM_ALERTS) && !alertsDisabled.contains(online.getUniqueId())) {
                online.sendMessage(message);
            }
        }
    }

    public boolean toggleAlerts(Player player) {
        UUID id = player.getUniqueId();
        if (alertsDisabled.remove(id)) return true;   // 重新开启
        alertsDisabled.add(id);
        return false;                                  // 已关闭
    }

    // ---- 玩家警告（递进第 1、2 级） ----

    public boolean warnPlayer(Player player, CheckType type, int stage) {
        PlayerData data = plugin.getDataManager().get(player);
        long now = System.currentTimeMillis();
        if (now - data.getLastPlayerWarnAt() < WARN_THROTTLE_MS) return false;
        data.setLastPlayerWarnAt(now);

        String keyBase = stage >= 2 ? "warn-2" : "warn-1";
        Map<String, String> ph = Map.of("check", type.display());
        player.sendTitle(
                plugin.getMessages().get(keyBase + "-title", ph),
                plugin.getMessages().get(keyBase + "-subtitle", ph),
                5, 50, 10);
        for (String line : plugin.getMessages().getList(keyBase + "-chat", ph)) {
            player.sendMessage(line);
        }
        player.playSound(player.getLocation(),
                stage >= 2 ? Sound.ENTITY_ENDER_DRAGON_GROWL : Sound.BLOCK_NOTE_BLOCK_BASS,
                0.8f, stage >= 2 ? 1.0f : 0.6f);
        data.addWarning(new PlayerData.WarningRecord(now, type, stage, data.getVl(type)));
        return true;
    }

    // ---- 公告 ----

    public void announce(String key, Map<String, String> placeholders) {
        if (!plugin.config().getBoolean("punishment.announce", true)) return;
        String msg = plugin.getMessages().prefixed(key, placeholders);
        Bukkit.broadcastMessage(msg);
    }

    private static String stripColor(String s) {
        return org.bukkit.ChatColor.stripColor(s);
    }
}
