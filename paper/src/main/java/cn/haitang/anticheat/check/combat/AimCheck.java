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

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 杀戮光环（KillAura）检测，七条判定线：
 * 1. 视角外攻击：攻击方向与目标方向夹角过大（正常玩家必须看着目标才能命中，
 *    KillAura 可以打到背后的实体）。近身混战角度天然偏大，距离过近不判。
 * 2. 快速多目标：在极短的游戏刻间隔内连续切换并命中不同实体（横扫攻击已按
 *    伤害原因排除，网络抖动或混战中的偶发切换由滚动次数阈值吸收）。
 * 3. 攻击突发：同一服务端刻内出现 3 次以上绑定真实攻击包的命中。实体受击后
 *    有约 10 刻无敌帧，同刻多次命中必然指向不同目标，是多目标扫描的强特征。
 * 4. 容器攻击：打开服务端容器界面后原版客户端无法继续攻击世界实体；
 *    界面打开瞬间在途的攻击包由宽限与延迟补偿吸收。
 * 5. 吸附回正：攻击前视角瞬间吸附到目标、攻击后又精确弹回原视角的
 *    自动回正轨迹。人手甩枪极少能在几刻内回到原视角 4° 以内。
 * 6. 追踪误差：连续命中相对方位持续变化的目标时，攻击包视角与目标中心的
 *    角度误差序列几乎零均值、零抖动——人手瞄准必有噪声，平滑瞄准没有。
 * 7. 主动探针：真实近战后在玩家侧后方生成环绕的隐身假人；正常客户端不会
 *    主动选择不可见目标，而 KillAura 的实体扫描可能连续命中这些假人。
 */
public class AimCheck extends Check {

    private static final String BUFFER_SNAPBACK = "kill-aura.snapback";
    private static final String BUFFER_TRACKING = "kill-aura.tracking";
    private static final String BUFFER_INVENTORY = "kill-aura.inventory";

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

        Location eye = attackLocation(attacker, attack);
        BoundingBox box = victim instanceof Player victimPlayer && attack.packetBacked()
                ? plugin.getEntityPositionHistory().boxAt(victimPlayer, attack)
                : victim.getBoundingBox();

        checkBurst(event, attacker, data);
        checkInventoryAttack(event, attacker, data);
        checkAngle(event, attacker, data, eye, box);
        checkMultiTarget(event, attacker, victim, data);
        checkSnapback(attacker, data, attack, eye, box);
        checkTracking(attacker, data, attack, eye, box);
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
                            PlayerData data, Location eye, BoundingBox box) {
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

    /** 同刻多次命中：无敌帧决定同刻命中必为不同目标，第 3 次起视为扫描突发。 */
    private void checkBurst(EntityDamageByEntityEvent event, Player attacker, PlayerData data) {
        if (!cfgB("burst.enabled", true)) return;
        if (data.isBedrock() && cfgB("burst.exclude-bedrock", true)) return;

        int tick = org.bukkit.Bukkit.getCurrentTick();
        int count = data.countAttackInTick(tick);
        if (count <= Math.max(1, cfgI("burst.max-attacks-per-tick", 2))) return;

        if (cfgB("burst.cancel-excess-hits", true)) {
            event.setCancelled(true);
        }
        if (data.markBurstFlagged(tick)) {
            flag(attacker, cfgD("burst.flag-weight", 2.5),
                    String.format("同刻第 %d 次命中", count));
        }
    }

    /** 打开服务端容器界面后的攻击；宽限取配置与实测往返延迟的较大者。 */
    private void checkInventoryAttack(EntityDamageByEntityEvent event, Player attacker,
                                      PlayerData data) {
        if (!cfgB("inventory.enabled", true)) return;
        if (data.isBedrock() && cfgB("inventory.exclude-bedrock", true)) return;
        if (!data.isContainerOpen()) {
            data.buffer(BUFFER_INVENTORY, -cfgD("inventory.buffer-decay", 0.5));
            return;
        }

        long sinceOpen = System.currentTimeMillis() - data.getContainerOpenAt();
        long rtt = plugin.getPacketTimeline().roundTripMillis(attacker.getUniqueId());
        long grace = Math.min(1000L, Math.max(cfgI("inventory.grace-ms", 250), rtt + 100L));
        if (sinceOpen < grace) return;

        if (cfgB("inventory.cancel-hits", true) && shouldMitigate(attacker)) {
            event.setCancelled(true);
        }
        double buffered = data.buffer(BUFFER_INVENTORY, cfgD("inventory.buffer-increment", 1.0));
        if (buffered >= cfgD("inventory.buffer-to-flag", 2.0)) {
            data.resetBuffer(BUFFER_INVENTORY);
            flag(attacker, cfgD("inventory.flag-weight", 2.5),
                    String.format("开容器攻击 (界面已打开 %dms)", sinceOpen));
        }
    }

    /**
     * 吸附回正：攻击前若干刻的视角与攻击视角相差一次大幅吸附，
     * 攻击后又在几刻内精确回到原视角。先做吸附幅度预筛，
     * 绝大多数正常命中不会产生延迟校验任务。
     */
    private void checkSnapback(Player attacker, PlayerData data,
                               CombatAttackContext.Attack attack, Location eye, BoundingBox box) {
        if (!cfgB("snapback.enabled", true)) return;
        if (data.isBedrock() && cfgB("snapback.exclude-bedrock", true)) return;

        Vector toCenter = box.getCenter().subtract(eye.toVector());
        if (toCenter.length() < cfgD("snapback.min-distance", 1.5)) return;

        float attackYaw = attack.packetBacked() ? attack.yaw() : eye.getYaw();
        float attackPitch = attack.packetBacked() ? attack.pitch() : eye.getPitch();

        // 吸附必须真的落在目标上，排除与目标无关的甩视角
        double targetAngle = AimPatternAnalyzer.viewAngleToTarget(attackYaw, attackPitch,
                toCenter.getX(), toCenter.getY(), toCenter.getZ());
        if (Double.isNaN(targetAngle)
                || targetAngle > cfgD("snapback.max-target-angle", 8.0)) return;

        int attackTick = org.bukkit.Bukkit.getCurrentTick();
        int acquisitionWindow = Math.max(1, cfgI("snapback.acquisition-window-ticks", 3));
        PlayerData.RotationSample before = data.rotationAtOrBefore(attackTick - acquisitionWindow);
        if (before == null) return;

        double minSnapAngle = cfgD("snapback.min-snap-angle", 25.0);
        if (AimPatternAnalyzer.rotationDistance(before.yaw(), before.pitch(),
                attackYaw, attackPitch) < minSnapAngle) return;

        UUID attackerId = attacker.getUniqueId();
        int returnWindow = Math.max(1, cfgI("snapback.return-window-ticks", 3));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player player = plugin.getServer().getPlayer(attackerId);
            PlayerData current = plugin.getDataManager().getIfPresent(attackerId);
            if (player == null || !player.isOnline() || current == null) return;
            // 期间被服务端传送过则视角不可比
            if (current.teleportedWithin(1000)) return;

            Location after = player.getLocation();
            boolean snapBack = AimPatternAnalyzer.isSnapBack(
                    before.yaw(), before.pitch(), attackYaw, attackPitch,
                    after.getYaw(), after.getPitch(), minSnapAngle,
                    cfgD("snapback.max-return-angle", 4.0),
                    cfgD("snapback.min-return-ratio", 0.70));
            if (snapBack) {
                double buffered = current.buffer(BUFFER_SNAPBACK,
                        cfgD("snapback.buffer-increment", 1.0));
                if (buffered >= cfgD("snapback.buffer-to-flag", 3.0)) {
                    current.resetBuffer(BUFFER_SNAPBACK);
                    flag(player, cfgD("snapback.flag-weight", 2.0), String.format(
                            "吸附回正 (吸附 %.0f° 命中后回正)", AimPatternAnalyzer.rotationDistance(
                                    before.yaw(), before.pitch(), attackYaw, attackPitch)));
                }
            } else {
                current.buffer(BUFFER_SNAPBACK, -cfgD("snapback.buffer-decay", 0.35));
            }
        }, returnWindow);
    }

    /**
     * 追踪误差：目标相对方位持续变化时，人手瞄准误差必然抖动，
     * 平滑瞄准的误差序列均值与离散度都趋近于零。只使用攻击包视角，
     * 事件级视角按服务端刻量化，达不到亚度精度。
     */
    private void checkTracking(Player attacker, PlayerData data,
                               CombatAttackContext.Attack attack, Location eye, BoundingBox box) {
        if (!cfgB("tracking.enabled", true)) return;
        if (data.isBedrock() && cfgB("tracking.exclude-bedrock", true)) return;
        if (!attack.packetBacked()) return;

        Vector toCenter = box.getCenter().subtract(eye.toVector());
        double bearing = AimPatternAnalyzer.horizontalBearing(toCenter.getX(), toCenter.getZ());
        double lastBearing = data.getLastTrackBearing();
        data.setLastTrackBearing(bearing);

        if (toCenter.length() < cfgD("tracking.min-distance", 1.6)) return;
        if (Double.isNaN(lastBearing)
                || Math.abs(AimPatternAnalyzer.wrappedAngleDelta(lastBearing, bearing))
                < cfgD("tracking.min-target-angle-change", 0.6)) return;

        double error = AimPatternAnalyzer.viewAngleToTarget(attack.yaw(), attack.pitch(),
                toCenter.getX(), toCenter.getY(), toCenter.getZ());
        if (Double.isNaN(error)) return;

        long now = System.currentTimeMillis();
        Deque<PlayerData.TrackSample> samples = data.getTrackSamples();
        samples.addLast(new PlayerData.TrackSample(now, error));
        long windowMs = Math.max(1000L, cfgI("tracking.window-ms", 5000));
        while (!samples.isEmpty() && now - samples.peekFirst().at() > windowMs) {
            samples.removeFirst();
        }

        if (samples.size() < Math.max(4, cfgI("tracking.sample-size", 8))) return;
        List<Double> errors = new ArrayList<>(samples.size());
        for (PlayerData.TrackSample sample : samples) errors.add(sample.error());
        samples.clear();

        AimTrackingAnalyzer.TrackingStats stats = AimTrackingAnalyzer.analyze(errors);
        if (stats == null) return;
        if (stats.mean() <= cfgD("tracking.max-mean-error", 0.8)
                && stats.stddev() <= cfgD("tracking.max-stddev", 0.22)
                && stats.range() <= cfgD("tracking.max-range", 0.75)) {
            double buffered = data.buffer(BUFFER_TRACKING, cfgD("tracking.buffer-increment", 1.0));
            if (buffered >= cfgD("tracking.buffer-to-flag", 2.0)) {
                data.resetBuffer(BUFFER_TRACKING);
                flag(attacker, cfgD("tracking.flag-weight", 2.0), String.format(
                        "追踪误差过稳 μ=%.2f° σ=%.2f° 极差=%.2f°",
                        stats.mean(), stats.stddev(), stats.range()));
            }
        } else {
            data.buffer(BUFFER_TRACKING, -cfgD("tracking.buffer-decay", 1.0));
        }
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
