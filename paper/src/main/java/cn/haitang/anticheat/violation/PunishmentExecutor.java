package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.data.PersistentStore;
import cn.haitang.anticheat.data.NetworkPersistentStore;
import cn.haitang.anticheat.shared.NetworkModels;
import io.papermc.paper.ban.BanListType;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

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

    private static final java.time.format.DateTimeFormatter TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Boolean> kickOutcomes = new ConcurrentHashMap<>();

    public PunishmentExecutor(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public void kickOrBan(Player player, CheckType type, double vl) {
        if (plugin.getStore().isWhitelisted(player.getUniqueId())) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data.isPunishing()) return;

        // 第三方 API：可在处罚落地前否决（玩家保留，VL 不清空）
        cn.haitang.anticheat.api.PlayerPunishEvent punishEvent =
                new cn.haitang.anticheat.api.PlayerPunishEvent(
                        player, type.id(), type.display(), vl);
        Bukkit.getPluginManager().callEvent(punishEvent);
        if (punishEvent.isCancelled()) return;

        data.setPunishmentState(PlayerData.PunishmentState.PENDING);
        UUID playerId = player.getUniqueId();
        if (plugin.getStore() instanceof NetworkPersistentStore networkStore) {
            prepareNetworkEnforcement(player, data, type, vl, networkStore);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> execute(playerId, type, vl));
    }

    private void prepareNetworkEnforcement(Player player, PlayerData data, CheckType type, double vl,
                                           NetworkPersistentStore store) {
        int windowHours = plugin.config().getInt("punishment.strikes.window-hours", 24);
        int strikesToTempban = plugin.config().getInt("punishment.strikes.to-tempban", 3);
        List<Integer> ladder = plugin.config().getIntegerList("punishment.tempban-hours");
        if (ladder.isEmpty()) ladder = List.of(1, 6, 24, 72);
        NetworkModels.EnforcementRequest request = new NetworkModels.EnforcementRequest(
                player.getUniqueId(), player.getName(), store.serverId(), type.id(), vl,
                windowHours, strikesToTempban, ladder,
                data.getRecentWarnings().stream().map(warning -> new NetworkModels.WarningEvidence(
                        warning.at(), warning.type().id(), warning.stage(), warning.vl())).toList(),
                data.getRecentViolations().stream().map(detection -> new NetworkModels.DetectionEvidence(
                        detection.at(), detection.type().id(), detection.vl(), detection.detail())).toList());
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            NetworkModels.EnforcementDecision decision;
            try {
                decision = store.prepareEnforcement(request);
            } catch (java.sql.SQLException error) {
                plugin.getLogger().warning("群组处罚写入失败，已降级为普通踢出: " + error.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> fallbackNetworkKick(playerId, type, vl, strikesToTempban));
                return;
            }
            Bukkit.getScheduler().runTask(plugin,
                    () -> applyNetworkDecision(playerId, type, vl, decision));
        });
    }

    private void applyNetworkDecision(UUID playerId, CheckType type, double vl,
                                      NetworkModels.EnforcementDecision decision) {
        Player player = Bukkit.getPlayer(playerId);
        PlayerData data = plugin.getDataManager().getIfPresent(playerId);
        if (decision.kind() == NetworkModels.EnforcementKind.TEMPBAN) {
            NetworkModels.Punishment punishment = decision.punishment();
            if (player != null && player.isOnline()) {
                Map<String, String> ph = Map.of(
                        "player", player.getName(), "check", type.display(),
                        "vl", String.format("%.1f", vl), "hours", String.valueOf(punishment.hours()),
                        "time", TIME.format(java.time.Instant.ofEpochMilli(punishment.expiresAt())),
                        "punishment-id", punishment.id());
                String screen = plugin.getMessages().get("ban-screen", ph);
                if (!screen.contains(punishment.id())) screen += "\n\n§8处罚 ID: §f" + punishment.id();
                player.kickPlayer(screen);
                plugin.getAlertManager().announce("broadcast-ban", ph);
                runHookCommands("punishment.commands.on-tempban", player.getName(),
                        player.getUniqueId(), type, punishment.hours(), punishment.id());
            }
            if (data != null) commitNetwork(data);
            plugin.getLogger().info("已建立群组临时封禁: " + punishment.playerName()
                    + "，来源 " + punishment.serverId() + "，处罚 ID " + punishment.id());
            return;
        }

        if (player == null || !player.isOnline()) {
            abort(data);
            return;
        }
        Map<String, String> ph = Map.of(
                "player", player.getName(), "check", type.display(), "vl", String.format("%.1f", vl),
                "strikes", String.valueOf(decision.strikes()),
                "max-strikes", String.valueOf(decision.strikesToTempban()));
        player.kickPlayer(plugin.getMessages().get("kick-screen", ph));
        if (data != null) commitNetwork(data);
        plugin.getAlertManager().announce("broadcast-kick", ph);
        runHookCommands("punishment.commands.on-kick", player.getName(),
                player.getUniqueId(), type, 0, "");
    }

    private void fallbackNetworkKick(UUID playerId, CheckType type, double vl, int strikesToTempban) {
        Player player = Bukkit.getPlayer(playerId);
        PlayerData data = plugin.getDataManager().getIfPresent(playerId);
        if (player == null || !player.isOnline()) {
            abort(data);
            return;
        }
        Map<String, String> ph = Map.of(
                "player", player.getName(), "check", type.display(), "vl", String.format("%.1f", vl),
                "strikes", "?", "max-strikes", String.valueOf(strikesToTempban));
        player.kickPlayer(plugin.getMessages().get("kick-screen", ph));
        abort(data);
    }

    private static void commitNetwork(PlayerData data) {
        data.resetAllVl();
        data.setPunishmentState(PlayerData.PunishmentState.COMMITTED);
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

        // 兜底：极少数平台行为下 PlayerKickEvent 可能不触发，条目会残留。
        // 1 tick 后若仍在，说明事件确实没来，清掉防止集合泄漏。
        Bukkit.getScheduler().runTaskLater(plugin, () -> kickOutcomes.remove(playerId), 1L);

        plugin.getStore().addStrike(playerId, player.getName());
        plugin.getStore().addHistory(playerId,
                String.format("[踢出] %s VL %.1f (strike %d/%d)", type.display(), vl, strikes, maxStrikes));
        commit(data);
        plugin.getAlertManager().announce("broadcast-kick", ph);
        runHookCommands("punishment.commands.on-kick", player.getName(),
                player.getUniqueId(), type, 0, "");
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
                "time", TIME.format(expiry.toInstant()),
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
        runHookCommands("punishment.commands.on-tempban", player.getName(),
                player.getUniqueId(), type, hours, punishmentId);
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

    /**
     * 玩家名进入控制台命令前的白名单。
     *
     * <p>{@code dispatchCommand} 不解释 {@code ;} / {@code &&}，所以无法串起另一条命令，
     * 但**空格可以注入参数**。正版在线模式玩家名限定 {@code [A-Za-z0-9_]{3,16}}，
     * 而离线模式、代理转发和 Geyser/基岩玩家名常含 {@code .} 甚至空格；
     * 配了 {@code lp user %player% parent add suspect} 时，名为
     * {@code x parent set admin x} 的玩家就会把自己提权。
     */
    private static final java.util.regex.Pattern SAFE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.]{1,16}");

    static boolean isSafeHookName(String playerName) {
        return playerName != null && SAFE_NAME.matcher(playerName).matches();
    }

    static String buildHookCommand(String template, String playerName, UUID playerId,
                                   CheckType type, int hours, String punishmentId) {
        return template.replace("%player%", playerName)
                .replace("%uuid%", playerId == null ? "" : playerId.toString())
                .replace("%check%", type.id())
                .replace("%hours%", String.valueOf(hours))
                .replace("%punishment-id%", punishmentId);
    }

    private void runHookCommands(String configPath, String playerName, UUID playerId,
                                 CheckType type, int hours, String punishmentId) {
        List<String> templates = plugin.config().getStringList(configPath);
        if (templates.isEmpty()) return;
        if (!isSafeHookName(playerName)) {
            plugin.getLogger().warning("跳过 " + configPath + " 的钩子命令：玩家名含不安全字符，"
                    + "无法安全拼进控制台命令（可改用 %uuid% 占位符）: " + playerName);
            return;
        }
        for (String cmd : templates) {
            String parsed = buildHookCommand(cmd, playerName, playerId, type, hours, punishmentId);
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
