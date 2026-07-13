package cn.haitang.anticheat.check.chat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread Bukkit state copied for safe reads from async chat events. */
public final class ChatExemptionCache implements Listener {

    private record State(boolean global, boolean antiSpam, boolean antiAds) { }

    private final AntiCheatPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final BukkitTask refreshTask;

    public ChatExemptionCache(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 1L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    public boolean isExempt(UUID playerId, String permission) {
        State state = states.get(playerId);
        if (state == null || state.global()) return true;
        if (AntiSpamCheck.PERM_BYPASS.equals(permission)) return state.antiSpam();
        if (AntiAdsCheck.PERM_BYPASS.equals(permission)) return state.antiAds();
        return false;
    }

    public void shutdown() {
        refreshTask.cancel();
        states.clear();
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) refresh(player);
    }

    private void refresh(Player player) {
        boolean disabledWorld = plugin.config().getStringList("settings.disabled-worlds").stream()
                .anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()));
        boolean global = !player.isOnline()
                || player.hasPermission(Check.PERM_BYPASS)
                || (plugin.config().getBoolean("settings.exempt-ops", false) && player.isOp())
                || player.hasMetadata("NPC")
                || disabledWorld;
        states.put(player.getUniqueId(), new State(global,
                player.hasPermission(AntiSpamCheck.PERM_BYPASS),
                player.hasPermission(AntiAdsCheck.PERM_BYPASS)));
    }
}
