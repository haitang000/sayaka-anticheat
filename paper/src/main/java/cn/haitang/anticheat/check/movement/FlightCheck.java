package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 飞行检测，三条判定线：
 * 1. 上升超限：一次滞空内的总上升高度超过跳跃物理极限（1.25 + 跳跃提升加成）
 * 2. 移动悬浮：连续多个移动包滞空且竖直速度≈0（正常跳跃抛物线不可能）
 * 3. 静止悬浮：每秒扫描一次，长时间静止浮空的玩家（悬浮时客户端可能不发移动包，
 *    移动事件驱动的检测会失效，需主动扫描兜底）
 */
public class FlightCheck extends Check {

    private static final String ASCENT_BUFFER = "flight.ascent";
    private static final String HOVER_BUFFER = "flight.hover";
    private static final String GRAVITY_BUFFER = "flight.gravity";

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
        if (to == null || MovementTracker.isTeleport(event)) return;
        PlayerData data = data(player);
        if (isExempt(player) || isMovementExempt(player)) {
            resetEvidence(data, to.getY());
            return;
        }
        Location from = event.getFrom();
        if (!MovementTracker.isPositionChange(to.getX() - from.getX(),
                to.getY() - from.getY(), to.getZ() - from.getZ())) return;
        if (data.getFlightAirTicks() == 0) {
            clearBuffers(data);
            return;
        }

        // ---- 1. 上升超限 ----
        double ascent = to.getY() - data.getFlightStartY();
        double maxJump = cfgD("max-jump", 1.35)
                + MoveUtil.effectLevel(player, MoveUtil.jumpBoostType()) * cfgD("jump-boost-bonus", 0.7);
        boolean excessiveAscent = ascent > maxJump;
        double ascentBuffer = data.buffer(ASCENT_BUFFER, excessiveAscent ? 1.0 : -0.5);
        if (excessiveAscent) {
            double buffered = ascentBuffer;
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(ASCENT_BUFFER);
                flag(player, 2.0, String.format("上升 %.2f > %.2f", ascent, maxJump));
                trySetback(event, data);
                return;
            }
        }

        // ---- 2. 移动悬浮 ----
        int hoverAirTicks = cfgI("hover-air-ticks", 8);
        boolean movingHover = data.getFlightAirTicks() >= hoverAirTicks
                && data.getHoverTicks() >= 5 && !MoveUtil.hasCollisionBelow(to, 3.0);
        double hoverBuffer = data.buffer(HOVER_BUFFER, movingHover ? 1.0 : -0.5);
        if (movingHover) {
            double buffered = hoverBuffer;
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(HOVER_BUFFER);
                flag(player, 2.0, String.format("悬浮 %d 包 dy=%.3f",
                        data.getFlightAirTicks(), data.getLastDeltaY()));
                trySetback(event, data);
                return;
            }
        }

        // ---- 3. 重力轨迹残差 ----
        int gravityMinTicks = cfgI("gravity-min-air-ticks", 3);
        double tolerance = cfgD("gravity-tolerance", 0.06);
        boolean gravityReady = data.getFlightAirTicks() >= gravityMinTicks;
        double excess = gravityExcess(data.getPreviousDeltaY(), data.getLastDeltaY());
        boolean antiGravity = gravityReady && excess > tolerance
                && !MoveUtil.hasCollisionBelow(to, 0.5);
        double gravityBuffer = data.buffer(GRAVITY_BUFFER, antiGravity ? 1.0 : -0.5);
        if (antiGravity && gravityBuffer >= cfgD("gravity-buffer-to-flag", 4.0)) {
            data.resetBuffer(GRAVITY_BUFFER);
            flag(player, 2.0, String.format("重力偏差 %.3f > %.3f (dy=%.3f)",
                    excess, tolerance, data.getLastDeltaY()));
            trySetback(event, data);
        }
    }

    static double predictedNextDeltaY(double previousDeltaY) {
        return (previousDeltaY - 0.08) * 0.98;
    }

    static double gravityExcess(double previousDeltaY, double currentDeltaY) {
        return currentDeltaY - predictedNextDeltaY(previousDeltaY);
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
        if (!cfgB("setback", true) || !allowsMitigation(event.getPlayer())) return;
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())) {
            target = from;
        }
        data.touchSetback();
        clearBuffers(data);
        data.getSpeedWindow().clear();
        data.resetAirborneState(target.getY());
        event.setTo(target.clone());
    }

    /** 4. 静止悬浮兜底扫描（每秒一次） */
    private void sweepStaticHover() {
        if (!isEnabled()) return;
        double[] tps = plugin.getServer().getTPS();
        if (tps.length > 0 && tps[0] < cfgD("static-hover-min-tps", 18.0)) {
            for (PlayerData data : plugin.getDataManager().all()) data.setStaticHoverCount(0);
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = data(player);
            Location loc = player.getLocation();
            if (isExempt(player) || isMovementExempt(player)) {
                resetEvidence(data, loc.getY());
                continue;
            }
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
                    tryStaticSetback(player, data, loc);
                }
            } else {
                data.setStaticHoverCount(0);
            }
        }
    }

    private void tryStaticSetback(Player player, PlayerData data, Location current) {
        if (!cfgB("setback", true) || !allowsMitigation(player)) return;
        Location target = data.getLastValidLocation();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(current.getWorld())) return;
        if (player.teleport(target.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            data.touchSetback();
            clearBuffers(data);
            data.getSpeedWindow().clear();
            data.resetAirborneState(target.getY());
        }
    }

    private void resetEvidence(PlayerData data, double currentY) {
        clearBuffers(data);
        data.resetFlightTracking(currentY);
    }

    private void clearBuffers(PlayerData data) {
        data.resetBuffer(type());
        data.resetBuffer(ASCENT_BUFFER);
        data.resetBuffer(HOVER_BUFFER);
        data.resetBuffer(GRAVITY_BUFFER);
    }
}
