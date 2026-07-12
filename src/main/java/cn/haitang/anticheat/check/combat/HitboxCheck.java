package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Validates that a melee attack ray intersects the target and is not blocked by terrain. */
public class HitboxCheck extends Check {

    public HitboxCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.HITBOX);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (isExempt(attacker)) return;
        if (cfgB("exclude-bedrock", true) && data(attacker).isBedrock()) return;
        if (!attacker.getWorld().equals(victim.getWorld()) || victim.isDead() || !victim.isValid()) return;

        PlayerData attackerData = data(attacker);
        if (attackerData.teleportedWithin(cfgI("teleport-grace-ms", 750))) return;

        PlayerData victimData = null;
        if (victim instanceof Player victimPlayer) {
            victimData = plugin.getDataManager().getIfPresent(victimPlayer.getUniqueId());
            if (victimData != null
                    && victimData.teleportedWithin(cfgI("teleport-grace-ms", 750))) return;
        }

        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();
        double closestDistance = distanceToBox(eye, box);
        if (closestDistance < cfgD("min-distance", 0.8)) return;

        double horizontalTolerance = tolerance(attacker, attackerData, victimData);
        double verticalTolerance = Math.max(0.0, cfgD("vertical-tolerance", 0.12));
        BoundingBox expanded = new BoundingBox(
                box.getMinX() - horizontalTolerance,
                box.getMinY() - verticalTolerance,
                box.getMinZ() - horizontalTolerance,
                box.getMaxX() + horizontalTolerance,
                box.getMaxY() + verticalTolerance,
                box.getMaxZ() + horizontalTolerance);

        Vector direction = eye.getDirection();
        double maxRayDistance = Math.max(0.0, Math.min(cfgD("max-ray-distance", 6.0),
                closestDistance + horizontalTolerance
                        + Math.max(0.0, cfgD("ray-distance-margin", 1.0))));
        double hitDistance = CombatGeometry.rayBoxIntersectionDistance(
                eye.getX(), eye.getY(), eye.getZ(),
                direction.getX(), direction.getY(), direction.getZ(),
                expanded.getMinX(), expanded.getMinY(), expanded.getMinZ(),
                expanded.getMaxX(), expanded.getMaxY(), expanded.getMaxZ(),
                maxRayDistance);

        if (Double.isNaN(hitDistance)) {
            handleRayMiss(event, attacker, attackerData, closestDistance, horizontalTolerance);
            return;
        }

        if (isBlocked(eye, direction, hitDistance)) {
            if (cfgB("cancel-blocked-hits", true)) event.setCancelled(true);
            double buffered = attackerData.buffer(type(), cfgD("blocked-buffer-increment", 2.0));
            if (buffered >= cfgD("buffer-to-flag", 2.5)) {
                attackerData.resetBuffer(type());
                flag(attacker, 2.5, String.format("攻击射线被方块阻挡 (目标 %.2f 格)", closestDistance));
            }
            return;
        }

        attackerData.buffer(type(), -cfgD("buffer-decay", 0.5));
    }

    private void handleRayMiss(EntityDamageByEntityEvent event, Player attacker,
                               PlayerData data, double distance, double tolerance) {
        double buffered = data.buffer(type(), cfgD("miss-buffer-increment", 1.0));
        if (buffered < cfgD("buffer-to-flag", 2.5)) return;

        data.resetBuffer(type());
        flag(attacker, 1.75, String.format(
                "攻击射线未命中碰撞箱 (距离 %.2f 格, 容差 %.2f)", distance, tolerance));
        if (cfgB("cancel-missed-hits", true) && shouldMitigate(attacker)) {
            event.setCancelled(true);
        }
    }

    private boolean isBlocked(Location eye, Vector direction, double targetDistance) {
        double margin = Math.max(0.0, cfgD("block-margin", 0.05));
        double traceDistance = targetDistance - margin;
        if (traceDistance <= 0.0) return false;
        RayTraceResult result = eye.getWorld().rayTraceBlocks(
                eye, direction, traceDistance, FluidCollisionMode.NEVER, true);
        return result != null && result.getHitBlock() != null;
    }

    private double tolerance(Player attacker, PlayerData attackerData, PlayerData victimData) {
        double tolerance = Math.max(0.0, cfgD("horizontal-tolerance", 0.12));
        int ping = Math.max(0, Math.min(attacker.getPing(), cfgI("max-ping-ms", 180)));
        tolerance += ping * Math.max(0.0, cfgD("ping-tolerance-per-ms", 0.00045));

        double movement = attackerData.getLastDeltaXZ();
        if (victimData != null) movement += victimData.getLastDeltaXZ();
        tolerance += Math.min(
                movement * Math.max(0.0, cfgD("movement-tolerance-multiplier", 0.35)),
                Math.max(0.0, cfgD("max-movement-tolerance", 0.10)));
        return Math.min(tolerance,
                Math.max(0.0, cfgD("max-horizontal-tolerance", 0.30)));
    }

    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = point.getX() - clamp(point.getX(), box.getMinX(), box.getMaxX());
        double dy = point.getY() - clamp(point.getY(), box.getMinY(), box.getMaxY());
        double dz = point.getZ() - clamp(point.getZ(), box.getMinZ(), box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
