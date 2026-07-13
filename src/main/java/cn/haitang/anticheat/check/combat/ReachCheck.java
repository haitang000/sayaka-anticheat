package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
        CombatAttackContext.Attack attack = plugin.getCombatAttackContext().attack(event).orElse(null);
        if (attack == null) return;
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
        Location eye = attackEye(attacker, attack);
        BoundingBox victimBox = victim instanceof Player victimPlayer && attack.packetBacked()
                ? plugin.getEntityPositionHistory().boxAt(victimPlayer, attack)
                : victim.getBoundingBox();
        // Reach measures distance only. Requiring the last reported rotation ray to intersect an
        // exact server-tick box rejects legitimate edge hits when rotation and attack packets are
        // ordered differently or the client is still interpolating the target. Aim owns direction.
        double distance = eyeToBoxDistance(eye, victimBox);

        int ping = Math.min(attacker.getPing(), cfgI("max-ping-ms", 200));
        double movementCompensation = movementCompensation(attacker, data, victim, victimData);
        double threshold = interactionRange(attacker)
                + movementCompensation
                + (attack.packetBacked() ? cfgD("history-tolerance", 0.10)
                : ping / 1000.0 * cfgD("ping-compensation", 4.0));

        if (distance > threshold) {
            // 明显超限的命中直接取消，让超距攻击完全无效
            if (cfgB("cancel-hits", true)
                    && (distance > threshold + cfgD("cancel-margin", 0.3) || shouldMitigate(attacker))) {
                event.setCancelled(true);
            }
            double over = distance - threshold;
            double buffered = data.buffer(type(), 1.0 + Math.min(over * 2.0, 1.5));
            if (buffered >= cfgD("buffer-to-flag", 3.0)) {
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
        double allowance = movement * cfgD("movement-compensation-multiplier", 0.6);
        if (attacker.isSprinting() || (victim instanceof Player victimPlayer && victimPlayer.isSprinting())) {
            allowance += cfgD("sprint-compensation", 0.08);
        }
        return Math.min(allowance, cfgD("max-movement-compensation", 0.25));
    }

    /** 眼睛位置到目标碰撞箱表面最近点的距离 */
    static double eyeToBoxDistance(Player attacker, LivingEntity victim) {
        return eyeToBoxDistance(attacker.getEyeLocation(), victim.getBoundingBox());
    }

    static double eyeToBoxDistance(Location eye, BoundingBox box) {
        double cx = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double cy = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double cz = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Location attackEye(Player attacker, CombatAttackContext.Attack attack) {
        Location current = attacker.getLocation();
        double delta = Math.pow(current.getX() - attack.x(), 2)
                + Math.pow(current.getY() - attack.y(), 2)
                + Math.pow(current.getZ() - attack.z(), 2);
        if (!attack.packetBacked() || delta > 16.0) return attacker.getEyeLocation();
        return new Location(attacker.getWorld(), attack.x(), attack.y() + attacker.getEyeHeight(),
                attack.z(), attack.yaw(), attack.pitch());
    }

    private double interactionRange(Player player) {
        try {
            Object registry = org.bukkit.Registry.class.getField("ATTRIBUTE").get(null);
            Object attribute = registry.getClass().getMethod("get", NamespacedKey.class)
                    .invoke(registry, NamespacedKey.minecraft("player.entity_interaction_range"));
            if (attribute instanceof org.bukkit.attribute.Attribute typed) {
                var instance = player.getAttribute(typed);
                if (instance != null && instance.getValue() > 0) return instance.getValue();
            }
        } catch (ReflectiveOperationException ignored) {
            // Attribute was introduced after the minimum supported server version.
        }
        return cfgD("base-reach", 3.1);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
