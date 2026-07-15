package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 异常跨越方块检测（Step）。
 *
 * 原版玩家无需跳跃可跨越的最大高度为 0.6 格。检测只处理同时带有水平位移、
 * 且单个移动包上升超过宽松阈值的情况；正常跳跃首包约为 0.42 格，不会命中。
 * 跳跃提升和所有可能改变竖直物理的服务端信号均直接豁免，优先避免误判。
 */
public class StepCheck extends Check {

    public StepCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.STEP);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || event instanceof PlayerTeleportEvent || isExempt(player)) return;
        if (event.getFrom().getWorld() == null || !event.getFrom().getWorld().equals(to.getWorld())) return;

        PlayerData data = data(player);
        if (isMovementExempt(player, data)) {
            data.resetBuffer(type());
            return;
        }

        double dy = to.getY() - event.getFrom().getY();
        double dx = to.getX() - event.getFrom().getX();
        double dz = to.getZ() - event.getFrom().getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double maxHeight = cfgD("max-height-per-move", 0.70);
        double minHorizontal = cfgD("min-horizontal-per-move", 0.03);

        if (dy > maxHeight && horizontal >= minHorizontal) {
            double severity = Math.min(2.0, dy / maxHeight);
            double buffered = data.buffer(type(), severity);
            if (buffered >= cfgD("buffer-to-flag", 2.5)) {
                data.resetBuffer(type());
                flag(player, Math.min(2.0, severity),
                        String.format("单包上升 %.2f 格，水平位移 %.2f 格", dy, horizontal));
                if (cfgB("setback", true) && shouldMitigate(player)) {
                    setback(event, data);
                }
            }
        } else if (dy <= maxHeight) {
            // 缓慢衰减，允许相邻两个方块之间的正常水平移动包存在。
            data.buffer(type(), -0.08);
        }
    }

    private boolean isMovementExempt(Player player, PlayerData data) {
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding() || data.glidedWithin(2500)) return true;
        if (player.isRiptiding() || data.riptideWithin(2000)) return true;
        if (MoveUtil.effectLevel(player, MoveUtil.jumpBoostType()) > 0) return true;
        if (data.liquidWithin(1500) || data.climbedWithin(1500)) return true;
        if (data.levitationWithin(2500) || data.slowFallWithin(1500)) return true;
        if (data.teleportedWithin(3000) || data.velocityWithin(3000)) return true;
        if (data.bouncedWithin(4000)) return true;
        if (data.isInWeb() || data.isNearHoney()) return true;
        return MoveUtil.standingOnEntity(player);
    }

    private void setback(PlayerMoveEvent event, PlayerData data) {
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())
                || target.distanceSquared(from) > 64) {
            target = from;
        }
        data.touchSetback();
        event.setTo(target);
    }
}
