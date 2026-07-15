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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 杀戮光环（KillAura）检测，三条判定线：
 * 1. 视角外攻击：攻击方向与目标方向夹角过大（正常玩家必须看着目标才能命中，
 *    KillAura 可以打到背后的实体）。近身混战角度天然偏大，距离过近不判。
 * 2. 快速多目标：在极短的游戏刻间隔内连续切换并命中不同实体（横扫攻击已按
 *    伤害原因排除，网络抖动或混战中的偶发切换由滚动次数阈值吸收）。
 * 3. 主动探针：真实近战后在玩家侧后方生成环绕的隐身假人；正常客户端不会
 *    主动选择不可见目标，而 KillAura 的实体扫描可能连续命中这些假人。
 */
public class AimCheck extends Check {

    private final VirtualProbeManager probes;

    public AimCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.KILL_AURA);
        this.probes = new VirtualProbeManager(plugin);
    }

    public void start() {
        probes.start();
    }

    public void shutdown() {
        probes.shutdown();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (isExempt(attacker)) return;
        PlayerData data = data(attacker);
        CombatAttackContext.Attack attack = plugin.getCombatAttackContext().attack(event).orElse(null);
        if (attack == null) return;

        checkAngle(event, attacker, victim, data, attack);
        checkMultiTarget(event, attacker, victim, data);
        probes.activate(attacker);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        probes.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        probes.finish(event.getPlayer());
    }

    private void checkAngle(EntityDamageByEntityEvent event, Player attacker,
                            LivingEntity victim, PlayerData data,
                            CombatAttackContext.Attack attack) {
        Location eye = attackLocation(attacker, attack);
        BoundingBox box = victim instanceof Player victimPlayer && attack.packetBacked()
                ? plugin.getEntityPositionHistory().boxAt(victimPlayer, attack)
                : victim.getBoundingBox();
        Vector toTarget = new Vector(
                clamp(eye.getX(), box.getMinX(), box.getMaxX()) - eye.getX(),
                clamp(eye.getY(), box.getMinY(), box.getMaxY()) - eye.getY(),
                clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()) - eye.getZ());

        double distance = toTarget.length();
        if (distance < cfgD("min-distance", 1.25)) return;

        double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
        double maxAngle = cfgD("max-angle", 50.0);
        if (Double.isNaN(angle)) return;

        if (angle > maxAngle) {
            if (cfgB("cancel-hits", true) && shouldMitigate(attacker)) {
                event.setCancelled(true);
            }
            double buffered = data.buffer(type(), angleBufferIncrement(angle, maxAngle));
            if (buffered >= cfgD("buffer-to-flag", 2.5)) {
                data.resetBuffer(type());
                flag(attacker, 2.0, String.format("夹角 %.0f° > %.0f°", angle, maxAngle));
            }
        } else {
            data.buffer(type(), -cfgD("angle-buffer-decay", 0.25));
        }
    }

    private void checkMultiTarget(EntityDamageByEntityEvent event, Player attacker,
                                  LivingEntity victim, PlayerData data) {
        int tick = org.bukkit.Bukkit.getCurrentTick();
        int tickGap = tick - data.getLastHitTick();
        int maxTickGap = Math.max(0, cfgI("multi-target-max-tick-gap", 2));
        if (isRapidTargetSwitch(tickGap, maxTickGap,
                victim.getUniqueId(), data.getLastHitTarget())) {
            long now = System.currentTimeMillis();
            var hits = data.getMultiHits();
            hits.addLast(now);
            long windowMs = Math.max(250L, cfgI("multi-target-window-ms", 3000));
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) hits.removeFirst();

            int hitsToFlag = Math.max(1, cfgI("multi-hits-to-flag", 3));
            if (hits.size() >= hitsToFlag) {
                hits.clear();
                flag(attacker, 2.0, String.format("快速切换多目标 (间隔 %d tick)", tickGap));
                if (cfgB("cancel-hits", true) && shouldMitigate(attacker)) {
                    event.setCancelled(true);
                }
            }
        }
        data.setLastHitTick(tick);
        data.setLastHitTarget(victim.getUniqueId());
    }

    static double angleBufferIncrement(double angle, double maxAngle) {
        double excess = angle - maxAngle;
        if (excess >= 60.0) return 2.0;
        if (excess >= 30.0) return 1.5;
        return 1.0;
    }

    static boolean isRapidTargetSwitch(int tickGap, int maxTickGap,
                                       UUID target, UUID lastTarget) {
        return tickGap >= 0 && tickGap <= maxTickGap
                && lastTarget != null && !target.equals(lastTarget);
    }

    private static Location attackLocation(Player attacker, CombatAttackContext.Attack attack) {
        Location current = attacker.getLocation();
        double dx = current.getX() - attack.x();
        double dy = current.getY() - attack.y();
        double dz = current.getZ() - attack.z();
        if (!attack.packetBacked() || dx * dx + dy * dy + dz * dz > 16.0) {
            return attacker.getEyeLocation();
        }
        return new Location(attacker.getWorld(), attack.x(),
                attack.y() + attacker.getEyeHeight(), attack.z(), attack.yaw(), attack.pitch());
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
