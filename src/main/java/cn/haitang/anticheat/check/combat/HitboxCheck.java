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

/**
 * 攻击射线检测（Hitbox：准星未穿过目标碰撞箱 / 隔墙攻击）。
 *
 * 客户端在同一 tick 内先发攻击包、后发视角包，且目标在攻击者屏幕上的位置
 * 滞后服务端 2-3 tick（实体插值）加网络延迟。直接用伤害事件时刻的服务端
 * 视角与目标当前碰撞箱做射线判定，会把正常攻击判成脱靶。因此：
 *  - 射线取攻击包时刻的位置与视角（CombatAttackContext），事件时刻的
 *    服务端视角作为备选，任一命中即通过；
 *  - 目标碰撞箱按客户端已确认的服务端 tick 回溯，并对客户端插值窗口内的
 *    历史快照取并集（EntityPositionHistory），覆盖客户端实际渲染的位置；
 *  - 击退/传送宽限期内位置必然失配，不参与判定。
 * 脱靶只累积缓冲，连续净脱靶才上报；隔墙攻击要求所有命中射线都被方块
 * 阻挡，证据更强，累积更快且立即取消命中。
 */
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

        // 事件时刻的服务端视角/位置滞后于客户端实际瞄准的状态，
        // 没有攻击包上下文就无法可靠还原射线，不判
        CombatAttackContext.Attack attack = plugin.getCombatAttackContext().attack(event).orElse(null);
        if (attack == null) return;

        PlayerData attackerData = data(attacker);
        int teleportGraceMs = cfgI("teleport-grace-ms", 750);
        int velocityGraceMs = cfgI("velocity-grace-ms", 750);
        if (attackerData.teleportedWithin(teleportGraceMs)
                || attackerData.velocityWithin(velocityGraceMs)) return;

        PlayerData victimData = null;
        if (victim instanceof Player victimPlayer) {
            victimData = plugin.getDataManager().getIfPresent(victimPlayer.getUniqueId());
            if (victimData != null
                    && (victimData.teleportedWithin(teleportGraceMs)
                    || victimData.velocityWithin(velocityGraceMs))) return;
        }

        Location eye = CombatAttackContext.attackEye(attacker, attack);
        BoundingBox box = victim instanceof Player victimPlayer && attack.packetBacked()
                ? plugin.getEntityPositionHistory().interpolatedBoxAt(victimPlayer, attack,
                        cfgI("interpolation-window-ticks", 3))
                : victim.getBoundingBox();
        double closestDistance = distanceToBox(eye, box);
        if (closestDistance < cfgD("min-distance", 0.8)) return;

        double horizontalTolerance = tolerance(attacker, attackerData, victimData);
        double verticalTolerance = Math.max(0.0, cfgD("vertical-tolerance", 0.15));
        BoundingBox expanded = new BoundingBox(
                box.getMinX() - horizontalTolerance,
                box.getMinY() - verticalTolerance,
                box.getMinZ() - horizontalTolerance,
                box.getMaxX() + horizontalTolerance,
                box.getMaxY() + verticalTolerance,
                box.getMaxZ() + horizontalTolerance);

        double maxRayDistance = Math.max(0.0, Math.min(cfgD("max-ray-distance", 6.0),
                closestDistance + horizontalTolerance
                        + Math.max(0.0, cfgD("ray-distance-margin", 1.5))));

        // 攻击包携带的是包序在前的最后一个视角；事件时刻的服务端视角可能已被
        // 同 tick 的移动包更新。两者对应客户端瞄准时刻的前后两侧，任一命中即通过。
        Vector packetDirection = eye.getDirection();
        Vector currentDirection = attacker.getEyeLocation().getDirection();
        Vector[] rays = currentDirection.equals(packetDirection)
                ? new Vector[] {packetDirection}
                : new Vector[] {packetDirection, currentDirection};

        boolean anyHit = false;
        boolean anyClear = false;
        for (Vector direction : rays) {
            double hitDistance = CombatGeometry.rayBoxIntersectionDistance(
                    eye.getX(), eye.getY(), eye.getZ(),
                    direction.getX(), direction.getY(), direction.getZ(),
                    expanded.getMinX(), expanded.getMinY(), expanded.getMinZ(),
                    expanded.getMaxX(), expanded.getMaxY(), expanded.getMaxZ(),
                    maxRayDistance);
            if (Double.isNaN(hitDistance)) continue;
            anyHit = true;
            if (!isBlocked(eye, direction, hitDistance)) {
                anyClear = true;
                break;
            }
        }

        if (!anyHit) {
            handleRayMiss(event, attacker, attackerData, closestDistance, horizontalTolerance);
            return;
        }

        if (!anyClear) {
            if (cfgB("cancel-blocked-hits", true)) event.setCancelled(true);
            double buffered = attackerData.buffer(type(), cfgD("blocked-buffer-increment", 2.0));
            if (buffered >= cfgD("buffer-to-flag", 4.0)) {
                attackerData.resetBuffer(type());
                flag(attacker, 2.5, String.format("攻击射线被方块阻挡 (目标 %.2f 格)", closestDistance));
            }
            return;
        }

        attackerData.buffer(type(), -cfgD("buffer-decay", 1.0));
    }

    private void handleRayMiss(EntityDamageByEntityEvent event, Player attacker,
                               PlayerData data, double distance, double tolerance) {
        double buffered = data.buffer(type(), cfgD("miss-buffer-increment", 1.0));
        if (buffered < cfgD("buffer-to-flag", 4.0)) return;

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
        double tolerance = Math.max(0.0, cfgD("horizontal-tolerance", 0.18));
        int ping = Math.max(0, Math.min(attacker.getPing(), cfgI("max-ping-ms", 180)));
        tolerance += ping * Math.max(0.0, cfgD("ping-tolerance-per-ms", 0.00045));

        double movement = attackerData.getLastDeltaXZ();
        if (victimData != null) movement += victimData.getLastDeltaXZ();
        tolerance += Math.min(
                movement * Math.max(0.0, cfgD("movement-tolerance-multiplier", 0.35)),
                Math.max(0.0, cfgD("max-movement-tolerance", 0.25)));
        return Math.min(tolerance,
                Math.max(0.0, cfgD("max-horizontal-tolerance", 0.45)));
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
