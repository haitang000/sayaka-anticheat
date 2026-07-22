package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

/** 检测未使用鞘翅或药水效果时持续以异常低速下降的 Glide。 */
public class GlideCheck extends Check {

    public GlideCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.GLIDE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || MovementTracker.isTeleport(event)) return;

        PlayerData data = data(player);
        if (isExempt(player) || isMovementExempt(player, data)) {
            data.resetBuffer(type());
            return;
        }

        int minAirTicks = cfgI("min-air-ticks", 10);
        double minDescent = cfgD("min-descent-per-move", 0.02);
        double maxDescent = cfgD("max-descent-per-move", 0.18);
        double clearance = cfgD("ground-clearance", 2.5);
        double deltaY = to.getY() - event.getFrom().getY();

        boolean suspicious = isSuspiciousDescent(
                data.getAirTicks(), deltaY, minAirTicks, minDescent, maxDescent);
        if (suspicious && !MoveUtil.hasCollisionBelow(to, clearance)) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 6.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, String.format(
                        "缓降 %d 包 dy=%.3f", data.getAirTicks(), deltaY));
                trySetback(event, data);
            }
        } else {
            // 正常重力会迅速离开可疑区间；较快衰减可避免多次普通跳跃拼接缓冲。
            data.buffer(type(), -1.25);
        }
    }

    static boolean isSuspiciousDescent(int airTicks, double deltaY, int minAirTicks,
                                       double minDescent, double maxDescent) {
        if (airTicks < minAirTicks || minDescent < 0 || maxDescent < minDescent) return false;
        double descent = -deltaY;
        return descent >= minDescent && descent <= maxDescent;
    }

    private boolean isMovementExempt(Player player, PlayerData data) {
        if (cfgB("exclude-bedrock", true) && data.isBedrock()) return true;
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding() || data.glidedWithin(2500)) return true;
        if (player.isRiptiding() || data.riptideWithin(2000)) return true;
        if (data.levitationWithin(2000) || data.slowFallWithin(2000)) return true;
        if (data.liquidWithin(1500) || data.climbedWithin(1500)) return true;
        if (data.isInWeb() || data.isNearHoney()) return true;
        if (data.teleportedWithin(3000) || data.velocityWithin(3000)) return true;
        if (data.damagedWithin(2000) || data.bouncedWithin(4000)) return true;
        return MoveUtil.standingOnEntity(player);
    }

    private void trySetback(PlayerMoveEvent event, PlayerData data) {
        Player player = event.getPlayer();
        if (!cfgB("setback", true) || !shouldMitigate(player)) return;

        Location from = event.getFrom();
        Location target = data.getLastValidLocation();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())
                || target.distanceSquared(from) > 256) {
            target = from;
        }
        data.touchSetback();
        event.setTo(target);
    }
}
