package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;

/**
 * 攻击距离检测（Reach）。
 *
 * 计算攻击者眼睛到受害者碰撞箱最近点的距离。原版近战上限 3.0 格，
 * 阈值按攻击者延迟与双方近期移动动态放宽（相对移动 + 网络延迟会放大表观距离）：
 *   阈值 = base-reach + ping/1000 × ping-compensation + movement-compensation
 * 横扫攻击（ENTITY_SWEEP_ATTACK）天然可命中远处实体，已排除。
 */
public class ReachCheck extends Check {

    public ReachCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.REACH);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (isExempt(attacker)) return;
        if (!attacker.getWorld().equals(victim.getWorld()) || victim.isDead() || !victim.isValid()) return;

        PlayerData data = data(attacker);
        if (data.teleportedWithin(1000) || data.velocityWithin(750)) return;
        PlayerData victimData = null;
        if (victim instanceof Player victimPlayer) {
            victimData = plugin.getDataManager().getIfPresent(victimPlayer.getUniqueId());
            if (victimData != null
                    && (victimData.teleportedWithin(1000) || victimData.velocityWithin(750))) {
                return;
            }
        }
        double distance = eyeToBoxDistance(attacker, victim);

        int ping = Math.max(0, Math.min(attacker.getPing(), cfgI("max-ping-ms", 180)));
        double movementCompensation = movementCompensation(attacker, data, victim, victimData);
        double threshold = cfgD("base-reach", 3.05)
                + ping / 1000.0 * cfgD("ping-compensation", 2.0)
                + movementCompensation;

        if (distance > threshold) {
            // 明显超限的命中直接取消，让超距攻击完全无效
            if (cfgB("cancel-hits", true)
                    && (distance > threshold + cfgD("cancel-margin", 0.2) || shouldMitigate(attacker))) {
                event.setCancelled(true);
            }
            double over = distance - threshold;
            double buffered = data.buffer(type(), 1.0 + Math.min(over * 2.0, 1.5));
            if (buffered >= cfgD("buffer-to-flag", 2.5)) {
                data.resetBuffer(type());
                flag(attacker, Math.min(2.5, 1.25 + over),
                        String.format("%.2f格 > %.2f格 (ping=%dms, move+%.2f)",
                                distance, threshold, ping, movementCompensation));
            }
        } else {
            data.buffer(type(), -0.5);
        }
    }

    private double movementCompensation(Player attacker, PlayerData attackerData,
                                        LivingEntity victim, PlayerData victimData) {
        double movement = attackerData.getLastDeltaXZ();
        if (victimData != null) {
            movement += victimData.getLastDeltaXZ();
        }
        double allowance = movement * cfgD("movement-compensation-multiplier", 0.45);
        if (attacker.isSprinting() || (victim instanceof Player victimPlayer && victimPlayer.isSprinting())) {
            allowance += cfgD("sprint-compensation", 0.05);
        }
        return Math.min(allowance, cfgD("max-movement-compensation", 0.18));
    }

    /** 眼睛位置到目标碰撞箱表面最近点的距离 */
    static double eyeToBoxDistance(Player attacker, LivingEntity victim) {
        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();
        double cx = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double cy = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double cz = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
