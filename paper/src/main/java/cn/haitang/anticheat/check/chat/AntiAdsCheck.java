package cn.haitang.anticheat.check.chat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.List;
import java.util.Set;

public class AntiAdsCheck extends ChatCheck {

    public static final String PERM_BYPASS = "anticheat.antiads.bypass";
    private static final List<String> DEFAULT_TLDS = List.of(
            "com", "net", "org", "cn", "gg", "io", "me", "cc", "xyz", "top",
            "club", "online", "site", "fun", "vip", "win", "pro", "link");

    private record Settings(boolean cancel, double weight, List<String> allowedHosts,
                            Set<String> blockedTlds) {
    }

    private final AntiAdsDetector detector = new AntiAdsDetector();
    private volatile Settings settings;

    public AntiAdsCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.ANTI_ADS);
        reloadConfiguration();
    }

    @Override
    public void reloadConfiguration() {
        List<String> tlds = plugin.config().isList("checks.anti-ads.blocked-tlds")
                ? plugin.config().getStringList("checks.anti-ads.blocked-tlds")
                : DEFAULT_TLDS;
        settings = new Settings(
                plugin.config().getBoolean("checks.anti-ads.cancel", true),
                Math.max(0, plugin.config().getDouble("checks.anti-ads.flag-weight", 3.0)),
                List.copyOf(plugin.config().getStringList("checks.anti-ads.allowed-hosts")),
                AntiAdsDetector.normalizeTlds(tlds));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isChatExempt(player, PERM_BYPASS)) return;

        Settings current = settings;
        AntiAdsDetector.Match match = detector.find(
                event.getMessage(), current.allowedHosts(), current.blockedTlds());
        if (match == null) return;

        if (current.cancel()) event.setCancelled(true);
        dispatchViolation(player, current.weight(),
                match.kind() + ": " + match.value(),
                current.cancel() ? "anti-ads-blocked" : null);
    }
}
