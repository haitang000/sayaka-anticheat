package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.data.PersistentStore;
import io.papermc.paper.ban.BanListType;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终局惩罚：踢出与临时封禁。
 *
 * 每次踢出记一次 strike（持久化，重进不清零）；
 * 窗口期内 strike 达标 → 临时封禁，时长按历史封禁次数递增（如 1h → 6h → 24h → 72h）。
 */
public class PunishmentExecutor implements Listener {

    public static final String BAN_SOURCE = "Sayaka AntiCheat";

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Boolean> kickOutcomes = new ConcurrentHashMap<>();

    public PunishmentExecutor(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public void kickOrBan(Player player, CheckType type, double vl) {
        if (plugin.getStore().isWhitelisted(player.getUniqueId())) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data.isPunishing()) return;
        data.setPunishmentState(PlayerData.PunishmentState.PENDING);
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> execute(playerId, type, vl));
    }

    private void execute(UUID playerId, CheckType type, double vl) {
        Player player = Bukkit.getPlayer(playerId);
        PlayerData data = plugin.getDataManager().getIfPresent(playerId);
        if (player == null || !player.isOnline() || data == null
                || plugin.getStore().isWhitelisted(playerId)) {
            abort(data);
            return;
        }

        int windowHours = plugin.config().getInt("punishment.strikes.window-hours", 24);
        int strikes = plugin.getStore().strikeCount(playerId, windowHours) + 1;
        int toTempban = plugin.config().getInt("punishment.strikes.to-tempban", 3);
        if (strikes >= toTempban) tempban(player, data, type, vl);
        else kick(player, data, type, vl, strikes, toTempban);
    }

    private void kick(Player player, PlayerData data, CheckType type, double vl,
                      int strikes, int maxStrikes) {
        Map<String, String> ph = Map.of(
                "player", player.getName(),
                "check", type.display(),
                "vl", String.format("%.1f", vl),
                "strikes", String.valueOf(strikes),
                "max-strikes", String.valueOf(maxStrikes)
        );
        String screen = plugin.getMessages().get("kick-screen", ph);
        UUID playerId = player.getUniqueId();
        kickOutcomes.put(playerId, false);
        try {
            player.kickPlayer(screen);
        } catch (RuntimeException error) {
            kickOutcomes.remove(playerId);
            abort(data);
            plugin.getLogger().severe("执行踢出失败: " + error.getMessage());
            return;
        }
        boolean accepted = Boolean.TRUE.equals(kickOutcomes.remove(playerId));
        if (!accepted) {
            abort(data);
            plugin.getLogger().warning("踢出被其他插件取消，未记录 strike: " + player.getName());
            return;
        }

        plugin.getStore().addStrike(playerId, player.getName());
        plugin.getStore().addHistory(playerId,
                String.format("[踢出] %s VL %.1f (strike %d/%d)", type.display(), vl, strikes, maxStrikes));
        commit(data);
        plugin.getAlertManager().announce("broadcast-kick", ph);
        runHookCommands("punishment.commands.on-kick", player.getName(), type, 0, "");
        plugin.getLogger().info(String.format("已踢出 %s：%s VL %.1f（strike %d/%d）",
                player.getName(), type.id(), vl, strikes, maxStrikes));
    }

    private void tempban(Player player, PlayerData data, CheckType type, double vl) {
        int banCount = plugin.getStore().getBanCount(player.getUniqueId());
        List<Integer> ladder = plugin.config().getIntegerList("punishment.tempban-hours");
        if (ladder.isEmpty()) ladder = List.of(1, 6, 24, 72);
        int hours = ladder.get(Math.min(banCount, ladder.size() - 1));
        long bannedAt = System.currentTimeMillis();
        Date expiry = new Date(bannedAt + hours * 3600_000L);
        String punishmentId = plugin.getStore().newPunishmentId();

        Map<String, String> ph = Map.of(
                "player", player.getName(),
                "check", type.display(),
                "vl", String.format("%.1f", vl),
                "hours", String.valueOf(hours),
                "time", TIME.format(expiry),
                "punishment-id", punishmentId
        );
        String screen = plugin.getMessages().get("ban-screen", ph);
        if (!screen.contains(punishmentId)) {
            screen += "\n\n§8处罚 ID: §f" + punishmentId;
        }
        try {
            player.ban(screen, expiry, BAN_SOURCE, false);
        } catch (RuntimeException error) {
            abort(data);
            plugin.getLogger().severe("执行封禁失败: " + error.getMessage());
            return;
        }
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        if (!banList.isBanned(player.getPlayerProfile())) {
            abort(data);
            plugin.getLogger().severe("无法建立封禁条目，处罚已中止: " + player.getName());
            return;
        }

        plugin.getStore().incrementBanCount(player.getUniqueId());
        plugin.getStore().clearStrikes(player.getUniqueId());
        plugin.getStore().addPunishment(new PersistentStore.PunishmentRecord(
                punishmentId,
                player.getUniqueId(),
                player.getName(),
                bannedAt,
                expiry.getTime(),
                type.id(),
                vl,
                hours,
                banCount + 1,
                data.getRecentWarnings().stream()
                        .map(warning -> new PersistentStore.WarningEvidence(
                                warning.at(), warning.type().id(), warning.stage(), warning.vl()))
                        .toList(),
                data.getRecentViolations().stream()
                        .map(detection -> new PersistentStore.DetectionEvidence(
                                detection.at(), detection.type().id(), detection.vl(), detection.detail()))
                        .toList()));
        plugin.getStore().addHistory(player.getUniqueId(),
                String.format("[封禁] %s，时长 %d 小时（第 %d 次封禁，处罚 ID %s）",
                        type.display(), hours, banCount + 1, punishmentId));
        commit(data);
        plugin.getAlertManager().announce("broadcast-ban", ph);
        runHookCommands("punishment.commands.on-tempban", player.getName(), type, hours, punishmentId);
        try {
            player.kickPlayer(screen);
        } catch (RuntimeException error) {
            data.setPunishmentState(PlayerData.PunishmentState.IDLE);
            plugin.getLogger().warning("封禁已建立，但踢出失败: " + player.getName()
                    + " (" + error.getMessage() + ")");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                data.setPunishmentState(PlayerData.PunishmentState.IDLE);
                plugin.getLogger().warning("封禁已建立但踢出被取消: " + player.getName());
            }
        });
        plugin.getLogger().info(String.format("已临时封禁 %s：%s VL %.1f，%d 小时（第 %d 次，处罚 ID %s）",
                player.getName(), type.id(), vl, hours, banCount + 1, punishmentId));
    }

    private void runHookCommands(String configPath, String playerName, CheckType type, int hours,
                                 String punishmentId) {
        for (String cmd : plugin.config().getStringList(configPath)) {
            String parsed = cmd.replace("%player%", playerName)
                    .replace("%check%", type.id())
                    .replace("%hours%", String.valueOf(hours))
                    .replace("%punishment-id%", punishmentId);
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (kickOutcomes.containsKey(playerId)) {
            kickOutcomes.put(playerId, !event.isCancelled());
        }
    }

    private void commit(PlayerData data) {
        data.resetAllVl();
        data.setPunishmentState(PlayerData.PunishmentState.COMMITTED);
        if (!plugin.getStore().saveNow()) {
            plugin.getLogger().severe("处罚已执行但 data.yml 尚未落盘；保留脏状态等待重试");
        }
    }

    private static void abort(PlayerData data) {
        if (data != null) data.setPunishmentState(PlayerData.PunishmentState.IDLE);
    }
}
