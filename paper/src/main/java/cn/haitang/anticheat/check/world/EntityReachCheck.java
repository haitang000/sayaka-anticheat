package cn.haitang.anticheat.check.world;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.util.BoundingBox;

/**
 * 实体交互距离检测（EntityReach）。
 *
 * 右键实体（喂食/交易/牵绳/上车/物品展示框/盔甲架等）时，眼睛到实体
 * 碰撞箱最近点的距离必须落在原版实体交互范围内（3.0 格；1.20.5+ 读取
 * attribute，兼容插件扩距）。表观距离按延迟与双方近期移动放宽，
 * 明显超限的交互直接取消——隔墙交互 / 超远交互在协议层就不可能。
 */
public class EntityReachCheck extends Check {

    public EntityReachCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.ENTITY_REACH);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        Entity target = event.getRightClicked();
        if (!target.getWorld().equals(player.getWorld())
                || target.isDead() || !target.isValid()) return;
        PlayerData data = data(player);
        if (data.teleportedWithin(1000) || data.velocityWithin(750)) return;
        if (target instanceof Player targetPlayer) {
            PlayerData targetData = plugin.getDataManager().getIfPresent(targetPlayer.getUniqueId());
            if (targetData != null
                    && (targetData.teleportedWithin(1000) || targetData.velocityWithin(750))) {
                return;
            }
        }

        Location eye = player.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        double distance = InteractionMath.eyeToBoxDistance(eye, box);

        int ping = Math.min(player.getPing(), cfgI("max-ping-ms", 200));
        double movement = data.getLastDeltaXZ();
        if (target instanceof Player targetPlayer) {
            PlayerData targetData = plugin.getDataManager().getIfPresent(targetPlayer.getUniqueId());
            if (targetData != null) {
                movement += targetData.getLastDeltaXZ();
            }
        }
        double allowance = movement * cfgD("movement-compensation-multiplier", 0.45);
        if (player.isSprinting()
                || (target instanceof Player targetPlayer && targetPlayer.isSprinting())) {
            allowance += cfgD("sprint-compensation", 0.05);
        }
        double threshold = InteractionMath.entityInteractionRange(player,
                cfgD("base-reach", 3.05))
                + ping / 1000.0 * cfgD("ping-compensation", 2.0)
                + Math.min(allowance, cfgD("max-movement-compensation", 0.25));

        if (distance <= threshold) {
            data.buffer(type(), -0.5);
            return;
        }
        if (cfgB("cancel", true)
                && (distance > threshold + cfgD("cancel-margin", 0.3)
                || shouldMitigate(player))) {
            event.setCancelled(true);
        }
        double over = distance - threshold;
        double buffered = data.buffer(type(), 1.0 + Math.min(over * 2.0, 1.5));
        if (buffered >= cfgD("buffer-to-flag", 3.0)) {
            data.resetBuffer(type());
            flag(player, Math.min(2.5, 1.25 + over), String.format(
                    "交互距 %.2f格 > %.2f格 (%s, ping=%dms)",
                    distance, threshold, target.getType(), ping));
        }
    }
}
