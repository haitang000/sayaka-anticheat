package cn.haitang.anticheat.check.world;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.util.BoundingBox;

/**
 * 交互视角检测（InteractAim / 右击光环）。
 *
 * 原版交互必须把准星对准实体，视角方向与"眼睛→目标碰撞箱最近点"方向的
 * 夹角只会落在实体自身的视角半径内；不看目标却对其右键（KillAura 的
 * 交互变种 / 交互宏）只可能来自伪造交互包的作弊客户端。
 * 距离过近、目标过大时用碰撞箱最近点而非中心，天然避免近身与大实体误判。
 */
public class InteractAimCheck extends Check {

    public InteractAimCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.INTERACT_AIM);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = data(player);
        if (data.isBedrock() && cfgB("exclude-bedrock", true)) return;
        Entity target = event.getRightClicked();
        if (!target.getWorld().equals(player.getWorld())) return;
        if (data.teleportedWithin(1000) || data.velocityWithin(750)) return;

        BoundingBox box = target.getBoundingBox();
        double distance = InteractionMath.eyeToBoxDistance(player.getEyeLocation(), box);
        if (distance < cfgD("min-distance", 1.5)) return;

        double angle = InteractionMath.eyeToBoxAngleDegrees(player.getEyeLocation(), box);
        if (Double.isNaN(angle)) return;

        double maxAngle = cfgD("max-angle", 60.0);
        if (angle <= maxAngle) {
            data.buffer(type(), -cfgD("angle-buffer-decay", 0.25));
            return;
        }
        if (cfgB("cancel", true) && shouldMitigate(player)) {
            event.setCancelled(true);
        }
        double buffered = data.buffer(type(), angleBufferIncrement(angle, maxAngle));
        if (buffered >= cfgD("buffer-to-flag", 3.0)) {
            data.resetBuffer(type());
            flag(player, 1.5, String.format("夹角 %.0f° > %.0f° (%s)",
                    angle, maxAngle, target.getType()));
        }
    }

    static double angleBufferIncrement(double angle, double maxAngle) {
        double excess = angle - maxAngle;
        if (excess >= 60.0) return 2.0;
        if (excess >= 30.0) return 1.5;
        return 1.0;
    }
}
