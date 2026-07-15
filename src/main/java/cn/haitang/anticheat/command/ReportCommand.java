package cn.haitang.anticheat.command;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /report &lt;玩家&gt; [原因] —— 玩家举报其他玩家。
 *
 * 举报写入 {@link cn.haitang.anticheat.data.PersistentStore}（可在管理面板查看），
 * 并实时通知在线管理员。每名举报者有冷却时间，避免刷屏。
 */
public class ReportCommand implements TabExecutor {

    public static final String PERM_REPORT = "anticheat.report";

    private static final int MAX_REASON_LENGTH = 200;

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Long> lastReportAt = new ConcurrentHashMap<>();

    public ReportCommand(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player reporter)) {
            sender.sendMessage("仅玩家可以举报。");
            return true;
        }
        if (!reporter.hasPermission(PERM_REPORT)) {
            reporter.sendMessage(plugin.getMessages().prefixed("no-permission", null));
            return true;
        }
        if (args.length == 0) {
            reporter.sendMessage(plugin.getMessages().prefixed("report-usage", null));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            reporter.sendMessage(plugin.getMessages().prefixed("report-target-offline",
                    Map.of("player", args[0])));
            return true;
        }
        if (target.getUniqueId().equals(reporter.getUniqueId())) {
            reporter.sendMessage(plugin.getMessages().prefixed("report-self", null));
            return true;
        }

        long cooldownMs = Math.max(0, plugin.config().getLong("report.cooldown-seconds", 60)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastReportAt.get(reporter.getUniqueId());
        if (cooldownMs > 0 && last != null && now - last < cooldownMs) {
            long remaining = (cooldownMs - (now - last) + 999) / 1000;
            reporter.sendMessage(plugin.getMessages().prefixed("report-cooldown",
                    Map.of("seconds", String.valueOf(remaining))));
            return true;
        }

        String reason = joinReason(args);
        lastReportAt.put(reporter.getUniqueId(), now);
        plugin.getStore().addReport(reporter.getUniqueId(), reporter.getName(),
                target.getUniqueId(), target.getName(), reason);
        plugin.getStore().saveAsync();

        plugin.getAlertManager().notifyStaff(plugin.getMessages().get("report-received", Map.of(
                "reporter", reporter.getName(),
                "player", target.getName(),
                "reason", reason)));
        reporter.sendMessage(plugin.getMessages().prefixed("report-success",
                Map.of("player", target.getName())));
        return true;
    }

    private String joinReason(String[] args) {
        if (args.length < 2) return "未填写原因";
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) builder.append(' ');
            builder.append(args[i]);
        }
        String reason = builder.toString().trim();
        if (reason.isEmpty()) return "未填写原因";
        return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player self && self.getUniqueId().equals(online.getUniqueId())) {
                    continue;
                }
                if (online.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(online.getName());
                }
            }
        }
        return out;
    }
}
