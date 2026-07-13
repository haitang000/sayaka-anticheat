package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 飞行检测，三条判定线：
 * 1. 上升超限：一次滞空内的总上升高度超过跳跃物理极限（1.25 + 跳跃提升加成）
 * 2. 移动悬浮：连续多个移动包滞空且竖直速度≈0（正常跳跃抛物线不可能）
 * 3. 静止悬浮：每秒扫描一次，长时间静止浮空的玩家（悬浮时客户端可能不发移动包，
 *    移动事件驱动的检测会失效，需主动扫描兜底）
 */
public class FlightCheck extends Check {

    private final BukkitTask sweepTask;

    public FlightCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.FLIGHT);
        this.sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepStaticHover, 40L, 20L);
    }

    public void shutdown() {
        sweepTask.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || isExempt(player) || isMovementExempt(player)) return;
        PlayerData data = data(player);
        if (data.getAirTicks() == 0) return;

        // ---- 1. 上升超限 ----
        double ascent = to.getY() - data.getAirStartY();
        double maxJump = cfgD("max-jump", 1.35)
                + MoveUtil.effectLevel(player, MoveUtil.jumpBoostType()) * cfgD("jump-boost-bonus", 0.7);
        if (ascent > maxJump) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, String.format("上升 %.2f > %.2f", ascent, maxJump));
                trySetback(event, data);
            }
            return;
        }

        // ---- 2. 移动悬浮 ----
        int hoverAirTicks = cfgI("hover-air-ticks", 8);
        if (data.getAirTicks() >= hoverAirTicks && data.getHoverTicks() >= 5
                && !MoveUtil.hasCollisionBelow(to, 3.0)) {
            data.setHoverTicks(0);
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, String.format("悬浮 %d 包 dy=%.3f", data.getAirTicks(), data.getLastDeltaY()));
                trySetback(event, data);
            }
        }
    }

    /** 飞行类共用的物理豁免 */
    private boolean isMovementExempt(Player player) {
        PlayerData data = data(player);
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding() || data.glidedWithin(2000)) return true;
        if (player.isRiptiding() || data.riptideWithin(2000)) return true;
        if (data.levitationWithin(2000) || data.slowFallWithin(1500)) return true;
        if (data.liquidWithin(1200) || data.climbedWithin(1200)) return true;
        if (data.isInWeb() || data.isNearHoney()) return true;
        if (data.teleportedWithin(3000) || data.velocityWithin(3000)) return true;
        if (data.bouncedWithin(4000)) return true;
        return MoveUtil.standingOnEntity(player);
    }

    private void trySetback(PlayerMoveEvent event, PlayerData data) {
        if (!cfgB("setback", true) || !shouldMitigate(event.getPlayer())) return;
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())
                || target.distanceSquared(from) > 256) {
            target = from;
        }
        data.touchSetback();
        event.setTo(target);
    }

    /** 3. 静止悬浮兜底扫描（每秒一次） */
    private void sweepStaticHover() {
        if (!isEnabled()) return;
        double[] tps = plugin.getServer().getTPS();
        if (tps.length > 0 && tps[0] < cfgD("static-hover-min-tps", 18.0)) {
            for (PlayerData data : plugin.getDataManager().all()) data.setStaticHoverCount(0);
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isExempt(player) || isMovementExempt(player)) continue;
            PlayerData data = data(player);
            Location loc = player.getLocation();
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                data.setStaticHoverCount(0);
                continue;
            }

            boolean idle = now - data.getLastMoveAt() > 1500;
            // 不采信客户端 onGround 声明，只看服务端方块碰撞
            boolean airborne = !MoveUtil.hasCollisionBelow(loc, 3.0)
                    && !MoveUtil.isInWeb(player);
            if (idle && airborne) {
                int count = data.getStaticHoverCount() + 1;
                data.setStaticHoverCount(count);
                if (count >= 3) {
                    data.setStaticHoverCount(0);
                    observe(player, String.format("静止悬浮 %ds y=%.1f", count, loc.getY()));
                }
            } else {
                data.setStaticHoverCount(0);
            }
        }
    }
}
