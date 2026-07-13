package cn.haitang.anticheat.check.chat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiSpamCheck extends ChatCheck {

    public static final String PERM_BYPASS = "anticheat.antispam.bypass";

    private record Settings(AntiSpamDetector.Settings detector, boolean cancel,
                            double floodWeight, double duplicateWeight) {
    }

    private final Map<UUID, AntiSpamDetector> detectors = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public AntiSpamCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.ANTI_SPAM);
        reloadConfiguration();
    }

    @Override
    public void reloadConfiguration() {
        long floodWindow = Math.max(250, plugin.config()
                .getLong("checks.anti-spam.flood-window-ms", 4_000));
        int maxMessages = Math.max(1, plugin.config()
                .getInt("checks.anti-spam.max-messages", 5));
        long duplicateWindow = Math.max(1_000, plugin.config()
                .getLong("checks.anti-spam.duplicate-window-ms", 15_000));
        int maxDuplicates = Math.max(2, plugin.config()
                .getInt("checks.anti-spam.max-duplicates", 3));
        int minDuplicateLength = Math.max(1, plugin.config()
                .getInt("checks.anti-spam.min-duplicate-length", 4));
        long flagCooldown = Math.max(0, plugin.config()
                .getLong("checks.anti-spam.flag-cooldown-ms", 1_000));
        AntiSpamDetector.Settings detectorSettings = new AntiSpamDetector.Settings(
                floodWindow, maxMessages, duplicateWindow, maxDuplicates,
                minDuplicateLength, flagCooldown);
        settings = new Settings(
                detectorSettings,
                plugin.config().getBoolean("checks.anti-spam.cancel", true),
                Math.max(0, plugin.config().getDouble("checks.anti-spam.flood-weight", 1.0)),
                Math.max(0, plugin.config().getDouble("checks.anti-spam.duplicate-weight", 1.5)));
        detectors.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isChatExempt(player, PERM_BYPASS)) return;

        Settings current = settings;
        AntiSpamDetector.Result result = detectors
                .computeIfAbsent(player.getUniqueId(), ignored -> new AntiSpamDetector())
                .inspect(event.getMessage(), System.currentTimeMillis(), current.detector());
        if (result == null) return;

        if (current.cancel()) event.setCancelled(true);
        double weight = switch (result.reason()) {
            case FLOOD -> current.floodWeight();
            case DUPLICATE -> current.duplicateWeight();
        };
        String detail = switch (result.reason()) {
            case FLOOD -> String.format("%dms 内超过 %d 条消息",
                    current.detector().floodWindowMs(), current.detector().maxMessages());
            case DUPLICATE -> String.format("%dms 内重复发送 %d 次",
                    current.detector().duplicateWindowMs(), current.detector().maxDuplicates());
        };
        dispatchViolation(player, result.shouldFlag() ? weight : 0, detail,
                current.cancel() && result.shouldFlag() ? "anti-spam-blocked" : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        detectors.remove(event.getPlayer().getUniqueId());
    }
}
