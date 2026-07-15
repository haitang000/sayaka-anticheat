package cn.haitang.anticheat.command;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.alert.AlertManager;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.EnforcementMode;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.data.PersistentStore;
import cn.haitang.anticheat.violation.PunishmentExecutor;
import io.papermc.paper.ban.BanListType;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * /sac 管理命令：
 *   status <玩家>   查看当前 VL 与状态
 *   history <玩家>  查看违规与惩罚历史
 *   punishment <处罚ID>  查询封禁详情及封禁前证据
 *   reset <玩家> [all]  清空实时 VL（all 连同 strike/封禁档案）
 *   whitelist <add|remove|list> [玩家]  管理反作弊白名单
 *   unban <玩家> [reset]  解封并可选重置封禁次数阶梯
 *   alerts          开关个人警报
 *   reload          重载配置
 *   update [check]  安装更新并热重载，或仅检查更新
 */
public class AntiCheatCommand implements TabExecutor {

    private static final String PERM_ADMIN = "anticheat.admin";
    private static final String PERM_WHITELIST = "anticheat.whitelist";
    private static final String PERM_UNBAN = "anticheat.unban";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");
    private static final SimpleDateFormat DATE_TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final AntiCheatPlugin plugin;

    public AntiCheatCommand(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "alerts" -> handleAlerts(sender);
            case "reload" -> handleReload(sender);
            case "update" -> handleUpdate(sender, args);
            case "status" -> handleStatus(sender, args);
            case "history" -> handleHistory(sender, args);
            case "punishment" -> handlePunishment(sender, args);
            case "reset" -> handleReset(sender, args);
            case "whitelist" -> handleWhitelist(sender, args);
            case "unban" -> handleUnban(sender, args);
            case "web" -> handleWeb(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean denyIfNoPerm(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return false;
        sender.sendMessage(plugin.getMessages().prefixed("no-permission", null));
        return true;
    }

    private void handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("仅玩家可用。");
            return;
        }
        if (denyIfNoPerm(sender, AlertManager.PERM_ALERTS)) return;
        boolean on = plugin.getAlertManager().toggleAlerts(player);
        sender.sendMessage(plugin.getMessages().prefixed(on ? "alerts-on" : "alerts-off", null));
    }

    private void handleReload(CommandSender sender) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        List<String> errors = plugin.reloadRuntimeConfig();
        if (!errors.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix() + "§c配置重载失败，继续使用上一份有效配置：");
            errors.forEach(error -> sender.sendMessage("  §7- §c" + error));
            return;
        }
        if (plugin.getPacketBridge() != null) plugin.getPacketBridge().reload();
        sender.sendMessage(plugin.getMessages().prefixed("reloaded", null));
    }

    private void handleUpdate(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        if (args.length >= 2 && args[1].equalsIgnoreCase("check")) {
            plugin.getUpdateManager().check(sender);
            return;
        }
        if (args.length >= 2) {
            sendHelp(sender);
            return;
        }
        plugin.getUpdateManager().install(sender);
    }

    private void handleStatus(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        Player target = requireTarget(sender, args);
        if (target == null) return;
        PlayerData data = plugin.getDataManager().get(target);

        sender.sendMessage(plugin.getMessages().prefix() + "§f" + target.getName()
                + " §7的实时状态（ping " + target.getPing() + "ms）:");
        boolean any = false;
        for (Map.Entry<CheckType, Double> e : data.getAllVl().entrySet()) {
            if (e.getValue() <= 0) continue;
            any = true;
            EnforcementMode enforcement = plugin.getViolationManager()
                    .effectiveEnforcement(target, e.getKey());
            double kickThreshold = plugin.getViolationManager()
                    .punishmentThreshold(target, e.getKey());
            String action;
            if (plugin.getViolationManager().nextViolationPunishes(target, e.getKey())) {
                action = enforcement.name() + ", kick@next flag";
            } else {
                action = Double.isFinite(kickThreshold)
                        ? String.format("%s, kick@%.1f", enforcement.name(), kickThreshold)
                        : enforcement.name() + ", no kick";
            }
            sender.sendMessage(String.format("  §7- %s§7: VL §c%.1f §8[%s]",
                    e.getKey().display(), e.getValue(), action));
        }
        if (!any) sender.sendMessage("  §a当前无任何违规值。");
        sender.sendMessage(String.format("  §7综合 VL: §c%.1f", data.getTotalVl()));
        int windowHours = plugin.config().getInt("punishment.strikes.window-hours", 24);
        int strikes = plugin.getStore().strikeCount(target.getUniqueId(), windowHours);
        int banCount = plugin.getStore().getBanCount(target.getUniqueId());
        sender.sendMessage(String.format("  §7近 %d 小时 strike: §c%d§7，历史封禁: §c%d 次", windowHours, strikes, banCount));
        sender.sendMessage("  §7反作弊白名单: "
                + (plugin.getStore().isWhitelisted(target.getUniqueId()) ? "§a是" : "§c否"));
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        Player target = requireTarget(sender, args);
        if (target == null) return;
        PlayerData data = plugin.getDataManager().get(target);

        sender.sendMessage(plugin.getMessages().prefix() + "§f" + target.getName() + " §7本次会话的违规记录:");
        if (data.getRecentViolations().isEmpty()) {
            sender.sendMessage("  §a无。");
        } else {
            for (PlayerData.ViolationRecord r : data.getRecentViolations()) {
                sender.sendMessage(String.format("  §8%s §7%s VL §c%.1f §8(%s)",
                        TIME.format(new Date(r.at())), r.type().display(), r.vl(), r.detail()));
            }
        }
        List<String> history = plugin.getStore().getHistory(target.getUniqueId());
        if (!history.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix() + "§7历史惩罚:");
            for (String line : history) sender.sendMessage("  §8" + line);
        }
    }

    private void handlePunishment(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        PersistentStore.PunishmentRecord punishment = plugin.getStore().getPunishment(args[1]);
        if (punishment == null) {
            sender.sendMessage(plugin.getMessages().prefix() + "§c找不到处罚 ID §f" + args[1] + "§c。");
            return;
        }

        sender.sendMessage(plugin.getMessages().prefix() + "§7处罚 ID: §f" + punishment.id());
        sender.sendMessage("  §7玩家: §f" + punishment.playerName() + " §8(" + punishment.playerId() + ")");
        sender.sendMessage(String.format("  §7封禁: §f%s §7至 §f%s §8(%d 小时，第 %d 次)",
                DATE_TIME.format(new Date(punishment.bannedAt())),
                DATE_TIME.format(new Date(punishment.expiresAt())),
                punishment.hours(), punishment.banNumber()));
        sender.sendMessage(String.format("  §7触发检测: §f%s §7VL §c%.1f",
                displayCheck(punishment.check()), punishment.vl()));

        sender.sendMessage("  §7封禁前玩家警告:");
        if (punishment.warnings().isEmpty()) {
            sender.sendMessage("    §8无");
        } else {
            for (PersistentStore.WarningEvidence warning : punishment.warnings()) {
                sender.sendMessage(String.format("    §8%s §e第 %d 级 §7%s VL §c%.1f",
                        TIME.format(new Date(warning.at())), warning.stage(),
                        displayCheck(warning.check()), warning.vl()));
            }
        }

        sender.sendMessage("  §7封禁前检测失败日志:");
        if (punishment.detections().isEmpty()) {
            sender.sendMessage("    §8无");
        } else {
            for (PersistentStore.DetectionEvidence detection : punishment.detections()) {
                sender.sendMessage(String.format("    §8%s §7%s VL §c%.1f §8(%s)",
                        TIME.format(new Date(detection.at())), displayCheck(detection.check()),
                        detection.vl(), detection.detail()));
            }
        }
    }

    private static String displayCheck(String checkId) {
        for (CheckType type : CheckType.values()) {
            if (type.id().equalsIgnoreCase(checkId)) return type.display();
        }
        return checkId;
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        Player target = requireTarget(sender, args);
        if (target == null) return;

        plugin.getDataManager().get(target).resetAllVl();
        if (args.length >= 3 && args[2].equalsIgnoreCase("all")) {
            plugin.getStore().resetPlayer(target.getUniqueId());
            if (plugin.getCrossServerSync() != null) {
                plugin.getCrossServerSync().publishState(target.getUniqueId());
            }
        }
        sender.sendMessage(plugin.getMessages().prefixed("reset-done",
                Map.of("player", target.getName())));
    }

    private void handleWhitelist(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_WHITELIST)) return;
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "list" -> {
                List<PersistentStore.WhitelistEntry> entries = plugin.getStore().getWhitelist();
                if (entries.isEmpty()) {
                    sender.sendMessage(plugin.getMessages().prefixed("whitelist-empty", null));
                    return;
                }
                sender.sendMessage(plugin.getMessages().prefix() + "§f反作弊白名单 §7(" + entries.size() + "): ");
                sender.sendMessage("  §a" + String.join("§7, §a",
                        entries.stream().map(PersistentStore.WhitelistEntry::name).toList()));
            }
            case "add" -> {
                OfflinePlayer target = requireOfflineTarget(sender, args, 2);
                if (target == null) return;
                String name = target.getName() != null ? target.getName() : args[2];
                if (plugin.getStore().isWhitelisted(target.getUniqueId())) {
                    sender.sendMessage(plugin.getMessages().prefixed("whitelist-already-added",
                            Map.of("player", name)));
                    return;
                }
                plugin.getStore().addWhitelist(target.getUniqueId(), name);
                Player online = target.getPlayer();
                if (online != null) {
                    PlayerData data = plugin.getDataManager().get(online);
                    data.resetAllVl();
                    data.setPunishing(false);
                }
                plugin.getStore().saveAsync();
                sender.sendMessage(plugin.getMessages().prefixed("whitelist-added", Map.of("player", name)));
            }
            case "remove" -> {
                if (args.length < 3) {
                    sendHelp(sender);
                    return;
                }
                PersistentStore.WhitelistEntry entry = plugin.getStore().findWhitelistByName(args[2]);
                Player online = Bukkit.getPlayerExact(args[2]);
                if (entry == null && online != null && plugin.getStore().isWhitelisted(online.getUniqueId())) {
                    entry = new PersistentStore.WhitelistEntry(online.getUniqueId(), online.getName());
                }
                if (entry == null || !plugin.getStore().removeWhitelist(entry.uuid())) {
                    sender.sendMessage(plugin.getMessages().prefixed("whitelist-not-found",
                            Map.of("player", args[2])));
                    return;
                }
                plugin.getStore().saveAsync();
                sender.sendMessage(plugin.getMessages().prefixed("whitelist-removed",
                        Map.of("player", entry.name())));
            }
            default -> sendHelp(sender);
        }
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_UNBAN)) return;
        OfflinePlayer target = requireOfflineTarget(sender, args, 1);
        if (target == null) return;
        String name = target.getName() != null ? target.getName() : args[1];
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        BanEntry<?> localBan = banList.getBanEntry(target.getPlayerProfile());
        boolean sayakaBanned = localBan != null
                && PunishmentExecutor.BAN_SOURCE.equals(localBan.getSource());
        boolean networkBanned = plugin.getCrossServerSync() != null
                && plugin.getCrossServerSync().isActive(target.getUniqueId());
        if (!sayakaBanned && !networkBanned) {
            sender.sendMessage(plugin.getMessages().prefixed("not-banned", Map.of("player", name)));
            return;
        }

        if (sayakaBanned) banList.pardon(target.getPlayerProfile());
        plugin.getStore().clearStrikes(target.getUniqueId());
        boolean reset = args.length >= 3 && args[2].equalsIgnoreCase("reset");
        if (reset) plugin.getStore().resetBanCount(target.getUniqueId());
        plugin.getStore().addHistory(target.getUniqueId(), "[解封] 管理员 " + sender.getName()
                + (reset ? "（已重置封禁次数）" : ""));
        if (plugin.getCrossServerSync() != null) {
            plugin.getCrossServerSync().publishUnban(target.getUniqueId(), reset);
        }
        plugin.getStore().saveAsync();

        Player online = target.getPlayer();
        if (online != null) {
            PlayerData data = plugin.getDataManager().get(online);
            data.resetAllVl();
            data.setPunishing(false);
        }
        sender.sendMessage(plugin.getMessages().prefixed(reset ? "unbanned-reset" : "unbanned",
                Map.of("player", name)));
    }

    private OfflinePlayer requireOfflineTarget(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sendHelp(sender);
            return null;
        }
        String name = args[index];
        if (!PLAYER_NAME.matcher(name).matches()) {
            sender.sendMessage(plugin.getMessages().prefixed("invalid-player-name", Map.of("player", name)));
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (OfflinePlayer banned : Bukkit.getBannedPlayers()) {
            if (banned.getName() != null && banned.getName().equalsIgnoreCase(name)) return banned;
        }
        PersistentStore.WhitelistEntry whitelisted = plugin.getStore().findWhitelistByName(name);
        if (whitelisted != null) return Bukkit.getOfflinePlayer(whitelisted.uuid());
        return Bukkit.getOfflinePlayer(name);
    }

    private Player requireTarget(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getMessages().prefixed("player-not-found",
                    Map.of("player", args[1])));
        }
        return target;
    }

    private void handleWeb(CommandSender sender) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        var web = plugin.getWebServer();
        if (web == null) {
            sender.sendMessage(plugin.getMessages().prefix()
                    + "§eWeb 面板未启用或启动失败。请检查 config.yml 的 §fweb §e段与控制台日志。");
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§f反作弊 Web 面板");
        sender.sendMessage("  §7一次性登录链接: §b" + web.createOneTimeLoginUrl());
        sender.sendMessage("  §8链接在 2 分钟内有效且只能使用一次；打开后将自动进入管理后台。");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(plugin.getMessages().prefix() + "§fSayaka AntiCheat §7命令:");
        sender.sendMessage("  §e/sac status <玩家> §7- 实时违规值与 strike");
        sender.sendMessage("  §e/sac history <玩家> §7- 违规与惩罚历史");
        sender.sendMessage("  §e/sac punishment <处罚ID> §7- 查询封禁详情与封禁前证据");
        sender.sendMessage("  §e/sac reset <玩家> [all] §7- 清空违规值（all 含档案）");
        sender.sendMessage("  §e/sac whitelist add|remove|list [玩家] §7- 管理检测白名单");
        sender.sendMessage("  §e/sac unban <玩家> [reset] §7- 解封（reset 重置处罚档位）");
        sender.sendMessage("  §e/sac web §7- 生成管理后台一次性登录链接");
        sender.sendMessage("  §e/sac alerts §7- 开关个人实时警报");
        sender.sendMessage("  §e/sac reload §7- 重载配置");
        sender.sendMessage("  §e/sac update [check] §7- 安装更新并热重载（check 仅检查）");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("status", "history", "punishment", "reset", "whitelist", "unban", "web", "alerts", "reload", "update")) {
                if (sub.startsWith(args[0].toLowerCase())) out.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
            if ("check".startsWith(args[1].toLowerCase())) out.add("check");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            for (String action : List.of("add", "remove", "list")) {
                if (action.startsWith(args[1].toLowerCase())) out.add(action);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("alerts")
                && !args[0].equalsIgnoreCase("reload")
                && !args[0].equalsIgnoreCase("update")
                && !args[0].equalsIgnoreCase("web")
                && !args[0].equalsIgnoreCase("punishment")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            out.add("all");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("unban")) {
            out.add("reset");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")) {
            if (args[1].equalsIgnoreCase("remove")) {
                for (PersistentStore.WhitelistEntry entry : plugin.getStore().getWhitelist()) {
                    if (entry.name().toLowerCase().startsWith(args[2].toLowerCase())) out.add(entry.name());
                }
            } else if (args[1].equalsIgnoreCase("add")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(args[2].toLowerCase())) out.add(p.getName());
                }
            }
        }
        return out;
    }
}
