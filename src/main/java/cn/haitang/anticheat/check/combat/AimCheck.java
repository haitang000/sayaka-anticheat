package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 杀戮光环（KillAura）检测，七条判定线：
 * 1. 视角外攻击：攻击方向与目标方向夹角过大（正常玩家必须看着目标才能命中，
 *    KillAura 可以打到背后的实体）。近身混战角度天然偏大，距离过近不判。
 * 2. 快速多目标：在极短的游戏刻间隔内连续切换并命中不同实体（横扫攻击已按
 *    伤害原因排除，网络抖动或混战中的偶发切换由滚动次数阈值吸收）。
 * 3. 主动探针：真实近战后在玩家侧后方生成环绕的隐身假人；正常客户端不会
 *    主动选择不可见目标，而 KillAura 的实体扫描可能连续命中这些假人。
 * 4. 视角吸附回正：攻击前快速转向目标，命中后立即精确返回原视角。
 * 5. 容器攻击：原版客户端打开容器界面后无法继续点击世界实体。
 * 6. 攻击突发：单个服务端 tick 内发送超过正常网络聚合上限的攻击包。
 * 7. 完美追踪：目标相对方位持续改变时，中心瞄准误差长期过低且无人工抖动。
 */
public class AimCheck extends Check {

    private final KillAuraProbeManager probes;
    private final Map<UUID, RotationHistory> rotationHistory = new HashMap<>();
    private final Map<UUID, Double> snapbackBuffers = new HashMap<>();
    private final Map<UUID, Double> inventoryBuffers = new HashMap<>();
    private final Map<UUID, AttackBurstState> attackBursts = new HashMap<>();
    private final Map<UUID, TrackingSession> trackingSessions = new HashMap<>();
    private final Map<UUID, Double> trackingBuffers = new HashMap<>();

    public AimCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.KILL_AURA);
        this.probes = new KillAuraProbeManager(plugin);
    }

    public void start() {
        probes.start();
    }

    public void shutdown() {
        probes.shutdown();
        rotationHistory.clear();
        snapbackBuffers.clear();
        inventoryBuffers.clear();
        attackBursts.clear();
        trackingSessions.clear();
        trackingBuffers.clear();
    }

    /** 假人不会承受伤害；只有所属玩家的近战命中才作为 KillAura 证据。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProbeDamage(EntityDamageEvent event) {
        if (!probes.isProbe(event.getEntity())) return;
        event.setCancelled(true);

        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(byEntity.getDamager() instanceof Player attacker) || isExempt(attacker)) return;

        KillAuraProbeManager.ProbeHit hit = probes.recordHit(attacker, event.getEntity());
        if (hit == null || !hit.shouldFlag()) return;

        flag(attacker, cfgD("probe.flag-weight", 3.0), String.format(
                "命中隐身假人 %d 次/%d 个目标 (%dms)",
                hit.hits(), hit.uniqueTargets(), hit.elapsedMillis()));
        probes.finish(attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (isExempt(attacker)) return;
        PlayerData data = data(attacker);

        if (checkInventoryAttack(event, attacker, data)) return;
        if (checkAttackBurst(event, attacker)) return;
        checkAngle(event, attacker, victim, data);
        checkMultiTarget(event, attacker, victim, data);
        checkAimTracking(attacker, victim);
        checkSnapBack(attacker, victim);
        probes.activate(attacker);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isEnabled()) return;
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        if (Math.abs(AimPatternAnalyzer.wrappedAngleDelta(from.getYaw(), to.getYaw())) < 1.0E-4
                && Math.abs(to.getPitch() - from.getPitch()) < 1.0E-4) return;

        RotationHistory history = rotationHistory.computeIfAbsent(
                event.getPlayer().getUniqueId(), ignored -> new RotationHistory());
        int tick = Bukkit.getCurrentTick();
        addRotationSample(history, new RotationSample(tick, from.getYaw(), from.getPitch()));
        addRotationSample(history, new RotationSample(tick, to.getYaw(), to.getPitch()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        probes.hideFromViewer(event.getPlayer());
        Location location = event.getPlayer().getLocation();
        RotationHistory history = new RotationHistory();
        history.samples.add(new RotationSample(
                Bukkit.getCurrentTick(), location.getYaw(), location.getPitch()));
        rotationHistory.put(event.getPlayer().getUniqueId(), history);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        probes.onQuit(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        rotationHistory.remove(id);
        snapbackBuffers.remove(id);
        inventoryBuffers.remove(id);
        attackBursts.remove(id);
        trackingSessions.remove(id);
        trackingBuffers.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        probes.finish(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        rotationHistory.remove(id);
        snapbackBuffers.remove(id);
        inventoryBuffers.remove(id);
        attackBursts.remove(id);
        trackingSessions.remove(id);
        trackingBuffers.remove(id);
    }

    private boolean checkInventoryAttack(EntityDamageByEntityEvent event,
                                         Player attacker, PlayerData data) {
        UUID id = attacker.getUniqueId();
        if (!cfgB("inventory.enabled", true)
                || (data.isBedrock() && cfgB("inventory.exclude-bedrock", true))
                || !data.isContainerOpen()
                || System.currentTimeMillis() - data.getContainerOpenAt()
                < Math.max(0, cfgI("inventory.grace-ms", 250))) {
            adjustBuffer(inventoryBuffers, id, -cfgD("inventory.buffer-decay", 0.5));
            return false;
        }

        if (cfgB("inventory.cancel-hits", true)) event.setCancelled(true);
        double buffered = adjustBuffer(inventoryBuffers, id,
                cfgD("inventory.buffer-increment", 1.0));
        if (buffered >= cfgD("inventory.buffer-to-flag", 2.0)) {
            inventoryBuffers.remove(id);
            flag(attacker, cfgD("inventory.flag-weight", 2.5),
                    "容器界面打开时连续攻击实体");
        }
        return true;
    }

    private boolean checkAttackBurst(EntityDamageByEntityEvent event, Player attacker) {
        if (!cfgB("burst.enabled", true)) return false;
        if (data(attacker).isBedrock() && cfgB("burst.exclude-bedrock", true)) return false;
        UUID id = attacker.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        AttackBurstState state = attackBursts.computeIfAbsent(id, ignored -> new AttackBurstState());
        if (state.tick != tick) {
            state.tick = tick;
            state.count = 1;
            state.flagged = false;
            return false;
        }

        state.count++;
        int maximum = Math.max(1, cfgI("burst.max-attacks-per-tick", 2));
        if (state.count <= maximum) return false;

        if (cfgB("burst.cancel-excess-hits", true)) event.setCancelled(true);
        if (!state.flagged) {
            state.flagged = true;
            flag(attacker, cfgD("burst.flag-weight", 2.5),
                    String.format("单 tick 发出 %d 次近战攻击", state.count));
        }
        return true;
    }

    private void checkSnapBack(Player attacker, LivingEntity victim) {
        if (!cfgB("snapback.enabled", true)) return;
        PlayerData data = data(attacker);
        if (data.isBedrock() && cfgB("snapback.exclude-bedrock", true)) return;

        Location eye = attacker.getEyeLocation();
        Vector target = victim.getBoundingBox().getCenter().subtract(eye.toVector());
        if (target.length() < cfgD("snapback.min-distance", 1.5)) return;
        double targetAngle = Math.toDegrees(eye.getDirection().angle(target));
        if (Double.isNaN(targetAngle)
                || targetAngle > cfgD("snapback.max-target-angle", 8.0)) return;

        int attackTick = Bukkit.getCurrentTick();
        float attackYaw = eye.getYaw();
        float attackPitch = eye.getPitch();
        RotationSample before = findPreAttackRotation(
                rotationHistory.get(attacker.getUniqueId()), attackTick,
                attackYaw, attackPitch,
                Math.max(1, cfgI("snapback.acquisition-window-ticks", 3)));
        if (before == null || AimPatternAnalyzer.rotationDistance(
                before.yaw(), before.pitch(), attackYaw, attackPitch)
                < cfgD("snapback.min-snap-angle", 25.0)) {
            adjustBuffer(snapbackBuffers, attacker.getUniqueId(),
                    -cfgD("snapback.buffer-decay", 0.35));
            return;
        }

        UUID id = attacker.getUniqueId();
        int delay = Math.max(1, cfgI("snapback.return-window-ticks", 3));
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> evaluateSnapBack(id, before, attackYaw, attackPitch), delay);
    }

    private void checkAimTracking(Player attacker, LivingEntity victim) {
        if (!cfgB("tracking.enabled", true) || !(victim instanceof Player)) return;
        PlayerData data = data(attacker);
        if (data.isBedrock() && cfgB("tracking.exclude-bedrock", true)) return;

        Location eye = attacker.getEyeLocation();
        Vector toCenter = victim.getBoundingBox().getCenter().subtract(eye.toVector());
        if (toCenter.length() < cfgD("tracking.min-distance", 1.6)) return;

        double horizontal = Math.hypot(toCenter.getX(), toCenter.getZ());
        double desiredYaw = Math.toDegrees(Math.atan2(-toCenter.getX(), toCenter.getZ()));
        double desiredPitch = Math.toDegrees(-Math.atan2(toCenter.getY(), horizontal));
        UUID attackerId = attacker.getUniqueId();
        TrackingSession session = trackingSessions.computeIfAbsent(
                attackerId, ignored -> new TrackingSession());
        if (!victim.getUniqueId().equals(session.targetId)) {
            session.reset(victim.getUniqueId(), desiredYaw, desiredPitch);
            return;
        }

        double targetAngleChange = AimPatternAnalyzer.rotationDistance(
                session.lastDesiredYaw, session.lastDesiredPitch, desiredYaw, desiredPitch);
        session.lastDesiredYaw = desiredYaw;
        session.lastDesiredPitch = desiredPitch;
        if (targetAngleChange < cfgD("tracking.min-target-angle-change", 0.6)) return;

        double error = Math.toDegrees(eye.getDirection().angle(toCenter));
        if (Double.isNaN(error)) return;
        long now = System.currentTimeMillis();
        session.samples.addLast(new TrackingSample(now, error));
        long windowMs = Math.max(1000L, cfgI("tracking.window-ms", 5000));
        while (!session.samples.isEmpty()
                && now - session.samples.peekFirst().at() > windowMs) {
            session.samples.removeFirst();
        }

        int sampleSize = Math.max(5, cfgI("tracking.sample-size", 8));
        if (session.samples.size() < sampleSize) return;
        List<Double> errors = session.samples.stream().map(TrackingSample::error).toList();
        session.samples.clear();
        AimTrackingAnalyzer.TrackingStats stats = AimTrackingAnalyzer.analyze(errors);
        boolean suspicious = stats != null
                && stats.mean() <= cfgD("tracking.max-mean-error", 0.8)
                && stats.stddev() <= cfgD("tracking.max-stddev", 0.22)
                && stats.range() <= cfgD("tracking.max-range", 0.75);
        if (!suspicious) {
            adjustBuffer(trackingBuffers, attackerId,
                    -cfgD("tracking.buffer-decay", 1.0));
            return;
        }

        double buffered = adjustBuffer(trackingBuffers, attackerId,
                cfgD("tracking.buffer-increment", 1.0));
        if (buffered >= cfgD("tracking.buffer-to-flag", 2.0)) {
            trackingBuffers.remove(attackerId);
            flag(attacker, cfgD("tracking.flag-weight", 2.0), String.format(
                    "持续完美追踪 mean=%.2f° std=%.2f° range=%.2f°",
                    stats.mean(), stats.stddev(), stats.range()));
        }
    }

    private void evaluateSnapBack(UUID id, RotationSample before,
                                  float attackYaw, float attackPitch) {
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || !player.isOnline() || isExempt(player)) return;
        if (data(player).teleportedWithin(500)) return;
        Location after = player.getLocation();
        boolean snapBack = AimPatternAnalyzer.isSnapBack(
                before.yaw(), before.pitch(), attackYaw, attackPitch,
                after.getYaw(), after.getPitch(),
                cfgD("snapback.min-snap-angle", 25.0),
                cfgD("snapback.max-return-angle", 4.0),
                cfgD("snapback.min-return-ratio", 0.70));
        if (!snapBack) {
            adjustBuffer(snapbackBuffers, id, -cfgD("snapback.buffer-decay", 0.35));
            return;
        }

        double snap = AimPatternAnalyzer.rotationDistance(
                before.yaw(), before.pitch(), attackYaw, attackPitch);
        double buffered = adjustBuffer(snapbackBuffers, id,
                cfgD("snapback.buffer-increment", 1.0));
        if (buffered >= cfgD("snapback.buffer-to-flag", 3.0)) {
            snapbackBuffers.remove(id);
            flag(player, cfgD("snapback.flag-weight", 2.0),
                    String.format("攻击吸附后回正 %.1f°", snap));
        }
    }

    private static RotationSample findPreAttackRotation(
            RotationHistory history, int attackTick, float attackYaw,
            float attackPitch, int windowTicks) {
        if (history == null) return null;
        RotationSample best = null;
        double largestDistance = 0.0;
        for (RotationSample sample : history.samples) {
            int age = attackTick - sample.tick();
            if (age < 0 || age > windowTicks) continue;
            double distance = AimPatternAnalyzer.rotationDistance(
                    sample.yaw(), sample.pitch(), attackYaw, attackPitch);
            if (distance > largestDistance) {
                largestDistance = distance;
                best = sample;
            }
        }
        return best;
    }

    private static void addRotationSample(RotationHistory history, RotationSample sample) {
        RotationSample last = history.samples.peekLast();
        if (last != null
                && Math.abs(AimPatternAnalyzer.wrappedAngleDelta(last.yaw(), sample.yaw())) < 1.0E-4
                && Math.abs(last.pitch() - sample.pitch()) < 1.0E-4) return;
        history.samples.addLast(sample);
        while (history.samples.size() > 12) history.samples.removeFirst();
    }

    private static double adjustBuffer(Map<UUID, Double> buffers, UUID id, double delta) {
        double value = Math.max(0.0, buffers.getOrDefault(id, 0.0) + delta);
        if (value == 0.0) buffers.remove(id); else buffers.put(id, value);
        return value;
    }

    private void checkAngle(EntityDamageByEntityEvent event, Player attacker,
                            LivingEntity victim, PlayerData data) {
        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();
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
        int tick = Bukkit.getCurrentTick();
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

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private record RotationSample(int tick, float yaw, float pitch) { }

    private record TrackingSample(long at, double error) { }

    private static final class RotationHistory {
        private final Deque<RotationSample> samples = new ArrayDeque<>();
    }

    private static final class AttackBurstState {
        private int tick = Integer.MIN_VALUE;
        private int count;
        private boolean flagged;
    }

    private static final class TrackingSession {
        private UUID targetId;
        private double lastDesiredYaw;
        private double lastDesiredPitch;
        private final Deque<TrackingSample> samples = new ArrayDeque<>();

        private void reset(UUID targetId, double desiredYaw, double desiredPitch) {
            this.targetId = targetId;
            this.lastDesiredYaw = desiredYaw;
            this.lastDesiredPitch = desiredPitch;
            this.samples.clear();
        }
    }
}
