package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 非法视角检测。
 *
 * 原版客户端俯仰角严格限制在 ±90°，超出该范围（Derp 挂、部分光环挂的
 * 特征包）或出现非有限值，只可能来自作弊客户端——确定性证据，无需缓冲。
 */
public class RotationCheck extends Check {

    public RotationCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.ROTATION);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        // 服务端设置视角（传送）的回包宽限
        if (data(player).teleportedWithin(1000)) return;

        float pitch = to.getPitch();
        float yaw = to.getYaw();
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
            flag(player, 3.0, "非有限视角数据");
            event.setTo(event.getFrom());
            return;
        }
        if (Math.abs(pitch) > 90.1f) {
            flag(player, 3.0, String.format("俯仰角 %.1f°", pitch));
            Location fixed = to.clone();
            fixed.setPitch(Math.max(-90f, Math.min(90f, pitch)));
            event.setTo(fixed);
        }
    }
}
