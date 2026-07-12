package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 终局惩罚：踢出与临时封禁。
 *
 * 每次踢出记一次 strike（持久化，重进不清零）；
 * 窗口期内 strike 达标 → 临时封禁，时长按历史封禁次数递增（如 1h → 6h → 24h → 72h）。
 */
public class PunishmentExecutor {

    public static final String BAN_SOURCE = "Sayaka AntiCheat";

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final AntiCheatPlugin plugin;

    public PunishmentExecutor(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public void kickOrBan(Player player, CheckType type, double vl) {
        if (plugin.getStore().isWhitelisted(player.getUniqueId())) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data.isPunishing()) return;
        data.setPunishing(true);
        data.resetAllVl();

        plugin.getStore().addStrike(player.getUniqueId(), player.getName());
        int windowHours = plugin.getConfig().getInt("punishment.strikes.window-hours", 24);
        int strikes = plugin.getStore().strikeCount(player.getUniqueId(), windowHours);
        int toTempban = plugin.getConfig().getInt("punishment.strikes.to-tempban", 3);

        if (strikes >= toTempban) {
            tempban(player, type);
        } else {
            kick(player, type, vl, strikes, toTempban);
        }
    }

    private void kick(Player player, CheckType type, double vl, int strikes, int maxStrikes) {
        Map<String, String> ph = Map.of(
                "player", player.getName(),
                "check", type.display(),
                "vl", String.format("%.1f", vl),
                "strikes", String.valueOf(strikes),
                "max-strikes", String.valueOf(maxStrikes)
        );
        plugin.getStore().addHistory(player.getUniqueId(),
                String.format("[踢出] %s VL %.1f (strike %d/%d)", type.display(), vl, strikes, maxStrikes));
        plugin.getAlertManager().announce("broadcast-kick", ph);
        runHookCommands("punishment.commands.on-kick", player.getName(), type, 0);

        String screen = plugin.getMessages().get("kick-screen", ph);
        // 踢出延迟到下一 tick：避免在移动/伤害事件处理中途操作玩家连接
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.getStore().isWhitelisted(player.getUniqueId())) {
                plugin.getDataManager().get(player).setPunishing(false);
                return;
            }
            if (player.isOnline()) player.kickPlayer(screen);
        });
        plugin.getLogger().info(String.format("已踢出 %s：%s VL %.1f（strike %d/%d）",
                player.getName(), type.id(), vl, strikes, maxStrikes));
    }

    private void tempban(Player player, CheckType type) {
        int banCount = plugin.getStore().getBanCount(player.getUniqueId());
        List<Integer> ladder = plugin.getConfig().getIntegerList("punishment.tempban-hours");
        if (ladder.isEmpty()) ladder = List.of(1, 6, 24, 72);
        int hours = ladder.get(Math.min(banCount, ladder.size() - 1));
        Date expiry = new Date(System.currentTimeMillis() + hours * 3600_000L);

        plugin.getStore().incrementBanCount(player.getUniqueId());
        plugin.getStore().clearStrikes(player.getUniqueId());
        plugin.getStore().addHistory(player.getUniqueId(),
                String.format("[封禁] %s，时长 %d 小时（第 %d 次封禁）", type.display(), hours, banCount + 1));

        Map<String, String> ph = Map.of(
                "player", player.getName(),
                "check", type.display(),
                "hours", String.valueOf(hours),
                "time", TIME.format(expiry)
        );
        plugin.getAlertManager().announce("broadcast-ban", ph);
        runHookCommands("punishment.commands.on-tempban", player.getName(), type, hours);

        String screen = plugin.getMessages().get("ban-screen", ph);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.getStore().isWhitelisted(player.getUniqueId())) {
                plugin.getDataManager().get(player).setPunishing(false);
                return;
            }
            if (!player.isOnline()) return;
            // Persist the rendered screen so reconnects can show the exact same message.
            player.ban(screen, expiry, BAN_SOURCE, false);
            player.kickPlayer(screen);
        });
        plugin.getLogger().info(String.format("已临时封禁 %s：%s，%d 小时（第 %d 次）",
                player.getName(), type.id(), hours, banCount + 1));
    }

    private void runHookCommands(String configPath, String playerName, CheckType type, int hours) {
        for (String cmd : plugin.getConfig().getStringList(configPath)) {
            String parsed = cmd.replace("%player%", playerName)
                    .replace("%check%", type.id())
                    .replace("%hours%", String.valueOf(hours));
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed));
        }
    }
}
