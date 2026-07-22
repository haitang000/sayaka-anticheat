package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 非法疾跑检测（OmniSprint / NoHunger-Sprint），两条判定线：
 * 1. 倒退疾跑：原版疾跑必须按住前进键，移动方向与视角最多偏 45°
 *    （前进+平移的合成），持续朝视角后方疾跑只能来自全向疾跑挂。
 *    快速转身时动量短暂滞后视角，由角度余量与连续计数吸收。
 * 2. 低饥饿疾跑：饥饿值 ≤ 6 时原版客户端强制停止疾跑并上报状态；
 *    持续保持疾跑标记只能来自忽略饥饿约束的客户端。状态包在途的
 *    延迟由连续计数吸收。
 * 疾跑加速本身的收益由 Speed 检测约束，本检测约束的是"疾跑状态合法性"。
 */
public class SprintCheck extends Check {

    private static final String BUFFER_BACKWARDS = "sprint.backwards";
    private static final String BUFFER_HUNGER = "sprint.hunger";

    /** 原版可疾跑的饥饿值下限（> 6 才能疾跑） */
    private static final int SPRINT_FOOD_THRESHOLD = 6;

    public SprintCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.SPRINT);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || MovementTracker.isTeleport(event) || isExempt(player)) return;
        PlayerData data = data(player);

        if (!player.isSprinting()) {
            data.buffer(BUFFER_BACKWARDS, -1.0);
            data.buffer(BUFFER_HUNGER, -1.0);
            return;
        }
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return;
        if (player.isGliding() || player.isRiptiding() || data.riptideWithin(2000)) return;
        if (data.teleportedWithin(1500) || data.velocityWithin(1500)) return;
        if (data.iceWithin(2500) || data.bouncedWithin(2500) || data.liquidWithin(1000)) return;
        if (data.climbedWithin(1000) || data.isInWeb()) return;

        // ---- 2. 低饥饿疾跑（不要求位移，站桩保持疾跑标记同样非法） ----
        if (player.getFoodLevel() <= SPRINT_FOOD_THRESHOLD) {
            double buffered = data.buffer(BUFFER_HUNGER, 1.0);
            if (buffered >= cfgD("hunger-buffer-to-flag", 12.0)) {
                data.resetBuffer(BUFFER_HUNGER);
                flag(player, 1.5, String.format("饥饿值 %d 仍保持疾跑", player.getFoodLevel()));
            }
        } else {
            data.buffer(BUFFER_HUNGER, -1.0);
        }

        // ---- 1. 倒退疾跑（需要有意义的水平位移） ----
        Location from = event.getFrom();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.hypot(dx, dz) < cfgD("min-move-per-tick", 0.15)) return;

        double angle = moveViewAngle(dx, dz, to.getYaw());
        if (angle > cfgD("max-move-angle", 100.0)) {
            double buffered = data.buffer(BUFFER_BACKWARDS, 1.0);
            if (buffered >= cfgD("backwards-buffer-to-flag", 4.0)) {
                data.resetBuffer(BUFFER_BACKWARDS);
                flag(player, 1.5, String.format("疾跑方向偏离视角 %.0f°", angle));
            }
        } else {
            data.buffer(BUFFER_BACKWARDS, -1.0);
        }
    }

    /**
     * 水平移动方向与视角朝向的夹角（度，0~180）。
     * MC 约定：yaw 0 = +Z，90 = -X，移动方向按同一约定换算。
     */
    static double moveViewAngle(double dx, double dz, float yaw) {
        double moveYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = Math.abs(wrapDegrees(moveYaw - yaw));
        return diff;
    }

    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }
}
