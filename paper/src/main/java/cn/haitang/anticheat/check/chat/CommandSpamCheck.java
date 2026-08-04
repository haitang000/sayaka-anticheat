package cn.haitang.anticheat.check.chat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令刷屏（CommandSpam）：短时间内高频执行命令，或窗口内重复刷同一命令。
 *
 * <p>与 AntiSpam 同构：频率与重复两条判定线、冷却期继续拦截但不重复记 VL。
 * 可用 {@code anticheat.commandspam.bypass} 单独豁免。
 */
public class CommandSpamCheck extends ChatCheck {

    public static final String PERM_BYPASS = "anticheat.commandspam.bypass";

    private record Settings(CommandSpamDetector.Settings detector, boolean cancel,
                            double floodWeight, double duplicateWeight) {
    }

    private final Map<UUID, CommandSpamDetector> detectors = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public CommandSpamCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.COMMAND_SPAM);
        reloadConfiguration();
    }

    @Override
    public void reloadConfiguration() {
        long floodWindow = Math.max(250, plugin.config()
                .getLong("checks.command-spam.flood-window-ms", 2_000));
        int maxCommands = Math.max(1, plugin.config()
                .getInt("checks.command-spam.max-commands", 6));
        long duplicateWindow = Math.max(1_000, plugin.config()
                .getLong("checks.command-spam.duplicate-window-ms", 15_000));
        int maxDuplicates = Math.max(2, plugin.config()
                .getInt("checks.command-spam.max-duplicates", 3));
        int minDuplicateLength = Math.max(1, plugin.config()
                .getInt("checks.command-spam.min-duplicate-length", 5));
        long flagCooldown = Math.max(0, plugin.config()
                .getLong("checks.command-spam.flag-cooldown-ms", 1_000));
        CommandSpamDetector.Settings detectorSettings = new CommandSpamDetector.Settings(
                floodWindow, maxCommands, duplicateWindow, maxDuplicates,
                minDuplicateLength, flagCooldown);
        settings = new Settings(
                detectorSettings,
                plugin.config().getBoolean("checks.command-spam.cancel", true),
                Math.max(0, plugin.config().getDouble("checks.command-spam.flood-weight", 1.0)),
                Math.max(0, plugin.config().getDouble("checks.command-spam.duplicate-weight", 1.5)));
        detectors.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isChatExempt(player, PERM_BYPASS)) return;

        Settings current = settings;
        CommandSpamDetector.Result result = detectors
                .computeIfAbsent(player.getUniqueId(), ignored -> new CommandSpamDetector())
                .inspect(event.getMessage(), System.currentTimeMillis(), current.detector());
        if (result == null) return;

        if (current.cancel()) event.setCancelled(true);
        double weight = switch (result.reason()) {
            case FLOOD -> current.floodWeight();
            case DUPLICATE -> current.duplicateWeight();
        };
        String detail = switch (result.reason()) {
            case FLOOD -> String.format("%dms 内超过 %d 条命令",
                    current.detector().floodWindowMs(), current.detector().maxCommands());
            case DUPLICATE -> String.format("%dms 内重复执行 %d 次",
                    current.detector().duplicateWindowMs(), current.detector().maxDuplicates());
        };
        dispatchViolation(player, result.shouldFlag() ? weight : 0, detail,
                current.cancel() && result.shouldFlag() ? "command-spam-blocked" : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        detectors.remove(event.getPlayer().getUniqueId());
    }
}
