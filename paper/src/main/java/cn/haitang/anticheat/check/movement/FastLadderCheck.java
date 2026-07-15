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

/**
 * 快速攀爬检测（FastLadder/FastClimb）。
 *
 * 原版沿梯子/藤蔓上爬速度恒定约 0.118 格/刻。贴梯跳跃起步的前几个包
 * 上升速度天然更高（0.42 → 0.33 → 0.25 衰减），按滞空计数豁免；
 * 上限取 0.29 恰好压在跳跃衰减曲线之上，只有持续超速上爬才会累积违规。
 */
public class FastLadderCheck extends Check {

    public FastLadderCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.FAST_LADDER);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null || isExempt(player)) return;
        if (!MoveUtil.isClimbing(player)) return;
        PlayerData data = data(player);

        if (player.isInsideVehicle() || player.isGliding() || player.isRiptiding()) return;
        if (data.liquidWithin(1000) || data.levitationWithin(3000)) return;
        if (data.velocityWithin(2000) || data.bouncedWithin(3000)) return;
        if (data.teleportedWithin(1500)) return;
        // 贴梯跳跃起步豁免
        if (data.getAirTicks() <= 2) return;

        double dy = data.getLastDeltaY();
        double max = cfgD("max-climb-per-move", 0.29);
        if (dy > max) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 4.0)) {
                data.resetBuffer(type());
                flag(player, 1.5, String.format("上爬 %.2f > %.2f 格/包", dy, max));
                if (cfgB("setback", true) && shouldMitigate(player)) {
                    setback(event, data);
                }
            }
        } else if (dy > 0) {
            data.buffer(type(), -0.5);
        }
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
