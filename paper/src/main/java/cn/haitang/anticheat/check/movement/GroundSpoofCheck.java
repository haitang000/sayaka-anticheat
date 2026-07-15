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
 * 落地欺骗检测（NoFall 类客户端的核心手法）：
 * 客户端在移动包里谎称"在地面"，服务端便不会结算摔落伤害。
 * 对比客户端声明的 onGround 与服务端实际方块碰撞，连续不一致即违规。
 */
public class GroundSpoofCheck extends Check {

    public GroundSpoofCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.GROUND_SPOOF);
    }

    @SuppressWarnings("deprecation") // isOnGround 即客户端声明值，此处正是要读取它
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || isExempt(player)) return;
        PlayerData data = data(player);

        if (player.isInsideVehicle() || player.isGliding() || player.isFlying()) return;
        if (data.teleportedWithin(2000) || data.velocityWithin(2000)) return;

        boolean claimsGround = player.isOnGround();
        // 客户端说在地上，但脚下 0.5 格内无任何碰撞体，也没站在实体上
        if (claimsGround && !data.isCollisionBelow()
                && !MoveUtil.standingOnEntity(player)) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 5.0)) {
                data.resetBuffer(type());
                flag(player, 1.5, String.format("谎报落地 滞空%d包 y=%.1f",
                        data.getAirTicks(), to.getY()));
            }
        } else {
            data.buffer(type(), -1.0);
        }
    }
}
