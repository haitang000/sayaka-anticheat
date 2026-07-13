package cn.haitang.anticheat.listener;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.violation.PunishmentExecutor;
import io.papermc.paper.ban.BanListType;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

/**
 * 玩家生命周期与"宽限信号"采集：
 * 传送、受击、服务端赋速等事件之后的短时间内，移动物理会出现合法突变，
 * 各检测据此临时放行，这是抑制误判的关键。
 */
public class ConnectionListener implements Listener {

    private final AntiCheatPlugin plugin;

    public ConnectionListener(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.KICK_BANNED) return;

        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        BanEntry<?> banEntry = banList.getBanEntry(event.getPlayer().getPlayerProfile());
        if (banEntry == null || !PunishmentExecutor.BAN_SOURCE.equals(banEntry.getSource())) return;

        String screen = banEntry.getReason();
        if (screen != null && !screen.isBlank()) {
            event.setKickMessage(screen);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.setBedrock(plugin.getBedrockSupport().isBedrock(event.getPlayer()));
        data.resetMovement(event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.getDataManager().get(player).touchDamage();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getCombatAttackContext().remove(event.getPlayer().getUniqueId());
        if (plugin.getPacketTimeline() != null) {
            plugin.getPacketTimeline().remove(event.getPlayer().getUniqueId());
        }
        if (plugin.getEntityPositionHistory() != null) {
            plugin.getEntityPositionHistory().remove(event.getPlayer().getUniqueId());
        }
        plugin.getDataManager().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.touchTeleport();
        data.resetMovement(event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.touchTeleport();
        data.resetMovement(event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.touchTeleport();
        data.resetMovement(event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.resetMovement(event.getPlayer().getLocation());
    }

    /** 服务端主动赋予速度（击退、TNT、跳板/钩爪等插件位移技能）宽限，带强度 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        plugin.getDataManager().get(event.getPlayer())
                .startImpulse(event.getVelocity());
    }
}
