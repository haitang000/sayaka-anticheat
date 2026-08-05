package cn.haitang.anticheat.command;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.alert.AlertManager;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.data.PersistentStore;
import cn.haitang.anticheat.data.NetworkPersistentStore;
import io.papermc.paper.ban.BanListType;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * /sac 管理命令：
 *   status <玩家>   查看当前 VL 与状态
 *   history <玩家>  查看违规与惩罚历史
 *   punishment <处罚ID>  查询封禁详情及封禁前证据
 *   reset <玩家> [all]  清空实时 VL（all 连同 strike/封禁档案）
 *   whitelist <add|remove|list> [玩家]  管理反作弊白名单
 *   unban <玩家> [reset]  解封并可选重置封禁次数阶梯
 *   web             生成 Velocity 管理后台的一次性登录链接
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
            case "preset" -> handlePreset(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean denyIfNoPerm(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return false;
        sender.sendMessage(plugin.getMessages().prefixed("no-permission", null));
        return true;
    }

    /**
     * 在异步线程里执行 store 读/写（群组模式会触发 MariaDB 查询，不能占住主线程），
     * 完成后回主线程依次发送消息行。produce 中不得访问 Bukkit 对象与 PlayerData。
     */
    private void asyncStore(CommandSender sender, Supplier<List<String>> produce) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines;
            try {
                lines = produce.get();
            } catch (RuntimeException error) {
                plugin.getLogger().warning("store 操作失败: " + error.getMessage());
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> lines.forEach(sender::sendMessage));
        });
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

    private void handlePreset(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        if (args.length < 2) {
            String current = plugin.config().getString("settings.preset", "balanced");
            sender.sendMessage(plugin.getMessages().prefix() + "§7当前预设档: §f" + current
                    + " §7（§estrict§7/§ebalanced§7/§elenient§7）");
            return;
        }
        String preset = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!preset.equals("strict") && !preset.equals("balanced") && !preset.equals("lenient")) {
            sender.sendMessage(plugin.getMessages().prefix() + "§c无效预设档，可用: strict / balanced / lenient");
            return;
        }
        // 写入磁盘配置并热重载
        plugin.getConfig().set("settings.preset", preset);
        plugin.saveConfig();
        List<String> errors = plugin.reloadRuntimeConfig();
        if (!errors.isEmpty()) {
            sender.sendMessage(plugin.getMessages().prefix() + "§c预设档已写入，但配置重载失败：");
            errors.forEach(error -> sender.sendMessage("  §7- §c" + error));
            return;
        }
        sender.sendMessage(plugin.getMessages().prefix() + "§a预设档已切换为 §f" + preset
                + "§a（strict=少漏判 / balanced=均衡 / lenient=少误判）");
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
        String name = target.getName();

        sender.sendMessage(plugin.getMessages().prefix() + "§f" + name
                + " §7的实时状态（ping " + target.getPing() + "ms）:");
        boolean any = false;
        for (Map.Entry<CheckType, Double> e : data.getAllVl().entrySet()) {
            if (e.getValue() <= 0) continue;
            any = true;
            sender.sendMessage(String.format("  §7- %s§7: VL §c%.1f", e.getKey().display(), e.getValue()));
        }
        if (!any) sender.sendMessage("  §a当前无任何违规值。");
        sender.sendMessage(String.format("  §7综合 VL: §c%.1f", data.getTotalVl()));

        int windowHours = plugin.config().getInt("punishment.strikes.window-hours", 24);
        java.util.UUID playerId = target.getUniqueId();
        asyncStore(sender, () -> {
            int strikes = plugin.getStore().strikeCount(playerId, windowHours);
            int banCount = plugin.getStore().getBanCount(playerId);
            boolean whitelisted = plugin.getStore().isWhitelisted(playerId);
            return List.of(
                    String.format("  §7近 %d 小时 strike: §c%d§7，历史封禁: §c%d 次",
                            windowHours, strikes, banCount),
                    "  §7反作弊白名单: " + (whitelisted ? "§a是" : "§c否"));
        });
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        Player target = requireTarget(sender, args);
        if (target == null) return;
        PlayerData data = plugin.getDataManager().get(target);
        String name = target.getName();

        sender.sendMessage(plugin.getMessages().prefix() + "§f" + name + " §7本次会话的违规记录:");
        if (data.getRecentViolations().isEmpty()) {
            sender.sendMessage("  §a无。");
        } else {
            for (PlayerData.ViolationRecord r : data.getRecentViolations()) {
                sender.sendMessage(String.format("  §8%s §7%s VL §c%.1f §8(%s)",
                        TIME.format(new Date(r.at())), r.type().display(), r.vl(), r.detail()));
            }
        }
        java.util.UUID playerId = target.getUniqueId();
        asyncStore(sender, () -> {
            List<String> history = plugin.getStore().getHistory(playerId);
            if (history.isEmpty()) return List.of();
            List<String> lines = new ArrayList<>();
            lines.add(plugin.getMessages().prefix() + "§7历史惩罚:");
            for (String line : history) lines.add("  §8" + line);
            return lines;
        });
    }

    private void handlePunishment(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        String idArg = args[1];
        asyncStore(sender, () -> {
            PersistentStore.PunishmentRecord punishment = plugin.getStore().getPunishment(idArg);
            if (punishment == null) {
                return List.of(plugin.getMessages().prefix() + "§c找不到处罚 ID §f" + idArg + "§c。");
            }

            List<String> lines = new ArrayList<>();
            lines.add(plugin.getMessages().prefix() + "§7处罚 ID: §f" + punishment.id());
            lines.add("  §7玩家: §f" + punishment.playerName() + " §8(" + punishment.playerId() + ")");
            lines.add(String.format("  §7封禁: §f%s §7至 §f%s §8(%d 小时，第 %d 次)",
                    DATE_TIME.format(new Date(punishment.bannedAt())),
                    DATE_TIME.format(new Date(punishment.expiresAt())),
                    punishment.hours(), punishment.banNumber()));
            lines.add(String.format("  §7触发检测: §f%s §7VL §c%.1f",
                    displayCheck(punishment.check()), punishment.vl()));

            lines.add("  §7封禁前玩家警告:");
            if (punishment.warnings().isEmpty()) {
                lines.add("    §8无");
            } else {
                for (PersistentStore.WarningEvidence warning : punishment.warnings()) {
                    lines.add(String.format("    §8%s §e第 %d 级 §7%s VL §c%.1f",
                            TIME.format(new Date(warning.at())), warning.stage(),
                            displayCheck(warning.check()), warning.vl()));
                }
            }

            lines.add("  §7封禁前检测失败日志:");
            if (punishment.detections().isEmpty()) {
                lines.add("    §8无");
            } else {
                for (PersistentStore.DetectionEvidence detection : punishment.detections()) {
                    lines.add(String.format("    §8%s §7%s VL §c%.1f §8(%s)",
                            TIME.format(new Date(detection.at())), displayCheck(detection.check()),
                            detection.vl(), detection.detail()));
                }
            }
            return lines;
        });
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
        String name = target.getName();

        plugin.getDataManager().get(target).resetAllVl();
        if (args.length >= 3 && args[2].equalsIgnoreCase("all")) {
            java.util.UUID playerId = target.getUniqueId();
            asyncStore(sender, () -> {
                plugin.getStore().resetPlayer(playerId);
                return List.of(plugin.getMessages().prefixed("reset-done", Map.of("player", name)));
            });
            return;
        }
        sender.sendMessage(plugin.getMessages().prefixed("reset-done", Map.of("player", name)));
    }

    private void handleWhitelist(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_WHITELIST)) return;
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "list" -> asyncStore(sender, () -> {
                List<PersistentStore.WhitelistEntry> entries = plugin.getStore().getWhitelist();
                if (entries.isEmpty()) {
                    return List.of(plugin.getMessages().prefixed("whitelist-empty", null));
                }
                List<String> lines = new ArrayList<>();
                lines.add(plugin.getMessages().prefix() + "§f反作弊白名单 §7(" + entries.size() + "): ");
                lines.add("  §a" + String.join("§7, §a",
                        entries.stream().map(PersistentStore.WhitelistEntry::name).toList()));
                return lines;
            });
            case "add" -> {
                OfflinePlayer target = requireOfflineTarget(sender, args, 2);
                if (target == null) return;
                java.util.UUID id = target.getUniqueId();
                String name = target.getName() != null ? target.getName() : args[2];
                asyncStore(sender, () -> {
                    if (plugin.getStore().isWhitelisted(id)) {
                        return List.of(plugin.getMessages().prefixed("whitelist-already-added",
                                Map.of("player", name)));
                    }
                    plugin.getStore().addWhitelist(id, name);
                    plugin.getStore().saveAsync();
                    // 在线玩家实时状态回主线程更新
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player online = Bukkit.getPlayer(id);
                        if (online != null) {
                            PlayerData data = plugin.getDataManager().get(online);
                            data.resetAllVl();
                            data.setPunishing(false);
                        }
                    });
                    return List.of(plugin.getMessages().prefixed("whitelist-added",
                            Map.of("player", name)));
                });
            }
            case "remove" -> {
                if (args.length < 3) {
                    sendHelp(sender);
                    return;
                }
                if (!PLAYER_NAME.matcher(args[2]).matches()) {
                    sender.sendMessage(plugin.getMessages().prefixed("invalid-player-name",
                            Map.of("player", args[2])));
                    return;
                }
                String targetName = args[2];
                asyncStore(sender, () -> {
                    PersistentStore.WhitelistEntry entry = plugin.getStore().findWhitelistByName(targetName);
                    Player online = Bukkit.getPlayerExact(targetName);
                    if (entry == null && online != null
                            && plugin.getStore().isWhitelisted(online.getUniqueId())) {
                        entry = new PersistentStore.WhitelistEntry(online.getUniqueId(), online.getName());
                    }
                    if (entry == null || !plugin.getStore().removeWhitelist(entry.uuid())) {
                        return List.of(plugin.getMessages().prefixed("whitelist-not-found",
                                Map.of("player", targetName)));
                    }
                    plugin.getStore().saveAsync();
                    return List.of(plugin.getMessages().prefixed("whitelist-removed",
                            Map.of("player", entry.name())));
                });
            }
            default -> sendHelp(sender);
        }
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (denyIfNoPerm(sender, PERM_UNBAN)) return;
        OfflinePlayer target = requireOfflineTarget(sender, args, 1);
        if (target == null) return;
        String name = target.getName() != null ? target.getName() : args[1];
        boolean reset = args.length >= 3 && args[2].equalsIgnoreCase("reset");
        if (plugin.getStore() instanceof NetworkPersistentStore networkStore) {
            java.util.UUID playerId = target.getUniqueId();
            String senderName = sender.getName();
            asyncStore(sender, () -> {
                try {
                    networkStore.pardonNetwork(playerId, reset);
                } catch (java.sql.SQLException error) {
                    plugin.getLogger().warning("群组解封失败: " + error.getMessage());
                    return List.of(plugin.getMessages().prefix() + "§c群组数据库不可用，解封未执行。");
                }
                plugin.getStore().addHistory(playerId, "[解封] 管理员 " + senderName
                        + (reset ? "（已重置封禁次数）" : ""));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player current = Bukkit.getPlayer(playerId);
                    if (current != null) {
                        PlayerData data = plugin.getDataManager().get(current);
                        data.resetAllVl();
                        data.setPunishing(false);
                    }
                });
                return List.of(plugin.getMessages().prefixed(reset ? "unbanned-reset" : "unbanned",
                        Map.of("player", name)));
            });
            return;
        }
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        if (!banList.isBanned(target.getPlayerProfile())) {
            sender.sendMessage(plugin.getMessages().prefixed("not-banned", Map.of("player", name)));
            return;
        }

        banList.pardon(target.getPlayerProfile());
        plugin.getStore().clearStrikes(target.getUniqueId());
        if (reset) plugin.getStore().resetBanCount(target.getUniqueId());
        plugin.getStore().addHistory(target.getUniqueId(), "[解封] 管理员 " + sender.getName()
                + (reset ? "（已重置封禁次数）" : ""));
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

    private void handleWeb(CommandSender sender) {
        if (denyIfNoPerm(sender, PERM_ADMIN)) return;
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessages().prefix() + "§e该命令需要由在线管理员执行，以连接 Velocity。");
            return;
        }
        if (!plugin.isNetworkMode()) {
            sender.sendMessage(plugin.getMessages().prefix() + "§eWeb 面板仅在 Velocity 群组模式下可用。");
            return;
        }
        player.sendPluginMessage(plugin, AntiCheatPlugin.WEB_LOGIN_CHANNEL, new byte[] {1});
        sender.sendMessage(plugin.getMessages().prefix() + "§7正在向 Velocity 请求一次性登录链接…");
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
        sender.sendMessage("  §e/sac preset [strict|balanced|lenient] §7- 查看/切换预设档");
        sender.sendMessage("  §e/sac reload §7- 重载配置");
        sender.sendMessage("  §e/sac update [check] §7- 安装更新并热重载（check 仅检查）");
    }

    /**
     * 补全某个子命令所需的权限。
     *
     * <p>onCommand 侧本来就有 denyIfNoPerm 门控，但补全过去完全不校验，于是任何能敲
     * {@code /sac } 的玩家都能看到管理子命令列表、在线玩家名单，以及
     * {@code /sac whitelist remove <TAB>} 列出的**整份反作弊白名单**——
     * 也就是哪些账号会被反作弊忽略，对作弊者是现成情报。
     */
    private static String permissionFor(String subCommand) {
        return switch (subCommand.toLowerCase()) {
            case "alerts" -> AlertManager.PERM_ALERTS;
            case "whitelist" -> PERM_WHITELIST;
            case "unban" -> PERM_UNBAN;
            default -> PERM_ADMIN;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("status", "history", "punishment", "reset", "whitelist", "unban", "web", "alerts", "reload", "update", "preset")) {
                if (sub.startsWith(args[0].toLowerCase()) && sender.hasPermission(permissionFor(sub))) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (!sender.hasPermission(permissionFor(args[0]))) return out;
        if (args.length == 2 && args[0].equalsIgnoreCase("preset")) {
            for (String p : List.of("strict", "balanced", "lenient")) {
                if (p.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) out.add(p);
            }
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
            if ("check".startsWith(args[1].toLowerCase())) out.add("check");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            for (String action : List.of("add", "remove", "list")) {
                if (action.startsWith(args[1].toLowerCase())) out.add(action);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("alerts")
                && !args[0].equalsIgnoreCase("reload")
                && !args[0].equalsIgnoreCase("web")
                && !args[0].equalsIgnoreCase("update")
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
