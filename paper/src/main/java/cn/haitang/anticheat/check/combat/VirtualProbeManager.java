package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-viewer packet-only probes. Hits are auxiliary evidence and never add VL. */
final class VirtualProbeManager {

    private record ProbeSignal(UUID ownerId, int hits, int uniqueTargets, long elapsedMillis) { }

    private final AntiCheatPlugin plugin;
    private final AtomicInteger entityIds = new AtomicInteger(2_000_000_000);
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ProbeSignal> signals = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedSignals = new AtomicInteger();
    private BukkitTask updateTask;

    VirtualProbeManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (updateTask != null) return;
        plugin.getPacketTimeline().setRawAttackConsumer(this::onRawAttack);
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 1L, 1L);
    }

    void shutdown() {
        plugin.getPacketTimeline().setRawAttackConsumer(null);
        if (updateTask != null) updateTask.cancel();
        for (UUID ownerId : new ArrayList<>(sessions.keySet())) finish(ownerId);
        cooldownUntil.clear();
        signals.clear();
    }

    void activate(Player owner) {
        if (!enabled() || sessions.containsKey(owner.getUniqueId())) return;
        if (cfgB("exclude-bedrock", true)
                && plugin.getDataManager().get(owner).isBedrock()) return;
        long now = System.currentTimeMillis();
        if (cooldownUntil.getOrDefault(owner.getUniqueId(), 0L) > now) return;
        if (sessions.size() >= Math.max(1, cfgI("max-active-sessions", 20))) return;

        int count = Math.max(2, Math.min(6, cfgI("entity-count", 3)));
        Session session = new Session(owner.getUniqueId(), owner.getWorld().getUID(), now,
                Math.max(1, cfgI("hits-to-flag", 2)),
                Math.max(1, cfgI("distinct-targets-to-flag", 2)));
        for (int index = 0; index < count; index++) {
            int entityId = entityIds.getAndDecrement();
            session.entityIds.add(entityId);
            sendSpawn(owner, entityId, location(owner, index, count, 0L));
        }
        sessions.put(owner.getUniqueId(), session);
        cooldownUntil.put(owner.getUniqueId(),
                now + Math.max(1, cfgI("cooldown-ticks", 200)) * 50L);
    }

    void finish(Player owner) {
        finish(owner.getUniqueId());
    }

    void onQuit(Player player) {
        finish(player.getUniqueId());
        cooldownUntil.remove(player.getUniqueId());
    }

    private boolean onRawAttack(UUID playerId, Integer entityId) {
        Session session = sessions.get(playerId);
        if (session == null || !session.contains(entityId)) return false;
        ProbeSignal signal = session.hit(entityId);
        if (signal == null) return true;
        if (queuedSignals.incrementAndGet() > 128) {
            queuedSignals.decrementAndGet();
            return true;
        }
        signals.offer(signal);
        return true;
    }

    private void update() {
        drainSignals();
        if (!enabled()) {
            for (UUID ownerId : new ArrayList<>(sessions.keySet())) finish(ownerId);
            return;
        }
        long now = System.currentTimeMillis();
        long duration = Math.max(10, cfgI("duration-ticks", 80)) * 50L;
        int interval = Math.max(1, cfgI("update-interval-ticks", 2));
        for (Session session : new ArrayList<>(sessions.values())) {
            Player owner = Bukkit.getPlayer(session.ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()
                    || !owner.getWorld().getUID().equals(session.worldId)
                    || now - session.startedAt >= duration) {
                finish(session.ownerId);
                continue;
            }
            long elapsedTicks = (now - session.startedAt) / 50L;
            if (elapsedTicks % interval != 0) continue;
            for (int i = 0; i < session.entityIds.size(); i++) {
                Location location = location(owner, i, session.entityIds.size(), elapsedTicks);
                send(owner, new WrapperPlayServerEntityTeleport(session.entityIds.get(i),
                        new Vector3d(location.getX(), location.getY(), location.getZ()),
                        location.getYaw(), location.getPitch(), false));
            }
        }
    }

    private void drainSignals() {
        for (int i = 0; i < 64; i++) {
            ProbeSignal signal = signals.poll();
            if (signal == null) break;
            queuedSignals.decrementAndGet();
            Player owner = Bukkit.getPlayer(signal.ownerId());
            if (owner != null && owner.isOnline()) {
                plugin.getViolationManager().observe(owner,
                        cn.haitang.anticheat.check.CheckType.KILL_AURA,
                        String.format("命中虚拟探针 %d 次/%d 目标（%dms，仅辅助证据）",
                                signal.hits(), signal.uniqueTargets(), signal.elapsedMillis()));
            }
            finish(signal.ownerId());
        }
    }

    private void finish(UUID ownerId) {
        Session session = sessions.remove(ownerId);
        Player owner = Bukkit.getPlayer(ownerId);
        if (session != null && owner != null && owner.isOnline()) {
            int[] ids = session.entityIds.stream().mapToInt(Integer::intValue).toArray();
            send(owner, new WrapperPlayServerDestroyEntities(ids));
        }
    }

    private void sendSpawn(Player owner, int entityId, Location location) {
        List<EntityData<?>> metadata = List.of(
                new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));
        send(owner, new WrapperPlayServerSpawnLivingEntity(entityId, UUID.randomUUID(),
                EntityTypes.ZOMBIE, new Vector3d(location.getX(), location.getY(), location.getZ()),
                location.getYaw(), location.getPitch(), location.getYaw(),
                Vector3d.zero(), metadata));
    }

    private void send(Player player, Object packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
    }

    private Location location(Player owner, int index, int count, long elapsedTicks) {
        Location center = owner.getLocation();
        double radius = Math.max(1.8, Math.min(4.0, cfgD("radius", 2.7)));
        double safeAngle = Math.max(45.0, Math.min(85.0, cfgD("front-safe-angle", 70.0)));
        double rearArc = 360.0 - safeAngle * 2.0;
        double relativeYaw = safeAngle + rearArc / count * (index + 0.5);
        relativeYaw += Math.sin(Math.toRadians(elapsedTicks
                * Math.max(0.0, cfgD("orbit-speed-degrees-per-tick", 4.0))))
                * Math.max(0.0, Math.min(20.0, cfgD("orbit-sweep-degrees", 12.0)));
        double yaw = Math.toRadians(center.getYaw() + relativeYaw);
        return center.clone().add(-Math.sin(yaw) * radius, 0.0, Math.cos(yaw) * radius);
    }

    private boolean enabled() {
        return plugin.config().getBoolean("checks.kill-aura.enabled", true)
                && cfgB("enabled", false);
    }

    private int cfgI(String path, int fallback) {
        return plugin.config().getInt("checks.kill-aura.probe." + path, fallback);
    }

    private double cfgD(String path, double fallback) {
        return plugin.config().getDouble("checks.kill-aura.probe." + path, fallback);
    }

    private boolean cfgB(String path, boolean fallback) {
        return plugin.config().getBoolean("checks.kill-aura.probe." + path, fallback);
    }

    private static final class Session {
        private final UUID ownerId;
        private final UUID worldId;
        private final long startedAt;
        private final int hitsRequired;
        private final int targetsRequired;
        private final List<Integer> entityIds = new ArrayList<>();
        private final Set<Integer> hitTargets = new HashSet<>();
        private int hits;
        private boolean signaled;

        private Session(UUID ownerId, UUID worldId, long startedAt,
                        int hitsRequired, int targetsRequired) {
            this.ownerId = ownerId;
            this.worldId = worldId;
            this.startedAt = startedAt;
            this.hitsRequired = hitsRequired;
            this.targetsRequired = targetsRequired;
        }

        private boolean contains(int entityId) {
            return entityIds.contains(entityId);
        }

        private synchronized ProbeSignal hit(int entityId) {
            if (signaled || !entityIds.contains(entityId)) return null;
            hits++;
            hitTargets.add(entityId);
            if (hits < hitsRequired || hitTargets.size() < Math.min(entityIds.size(), targetsRequired)) {
                return null;
            }
            signaled = true;
            return new ProbeSignal(ownerId, hits, hitTargets.size(),
                    System.currentTimeMillis() - startedAt);
        }
    }
}
