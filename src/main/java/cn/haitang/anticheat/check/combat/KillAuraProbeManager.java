package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 管理仅用于 KillAura 取证的短时隐身假人。所有方法都在服务端主线程调用。 */
final class KillAuraProbeManager {

    private static final String META_KEY = "sayaka-killaura-probe";

    record ProbeHit(int hits, int uniqueTargets, long elapsedMillis, boolean shouldFlag) {}

    private final AntiCheatPlugin plugin;
    private final Map<UUID, ProbeSession> sessions = new HashMap<>();
    private final Map<UUID, UUID> ownerByProbe = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private BukkitTask updateTask;

    KillAuraProbeManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (updateTask != null) return;
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::update, 1L, 1L);
    }

    void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (ProbeSession session : new ArrayList<>(sessions.values())) {
            removeEntities(session);
        }
        sessions.clear();
        ownerByProbe.clear();
        cooldownUntil.clear();
    }

    void activate(Player owner) {
        if (!probeEnabled() || sessions.containsKey(owner.getUniqueId())) return;
        if (cfgB("exclude-bedrock", true)
                && plugin.getDataManager().get(owner).isBedrock()) return;

        long now = System.currentTimeMillis();
        if (cooldownUntil.getOrDefault(owner.getUniqueId(), 0L) > now) return;
        if (sessions.size() >= Math.max(1, cfgI("max-active-sessions", 20))) return;

        int count = Math.max(2, Math.min(6, cfgI("entity-count", 3)));
        ProbeSession session = new ProbeSession(owner.getUniqueId(), owner.getWorld().getUID(), now);
        for (int i = 0; i < count; i++) {
            ArmorStand stand = spawnProbe(owner, probeLocation(owner, i, count, 0L));
            session.probes.add(stand);
            ownerByProbe.put(stand.getUniqueId(), owner.getUniqueId());
        }

        sessions.put(owner.getUniqueId(), session);
        cooldownUntil.put(owner.getUniqueId(), now + Math.max(1, cfgI("cooldown-ticks", 200)) * 50L);
    }

    boolean isProbe(Entity entity) {
        return ownerByProbe.containsKey(entity.getUniqueId());
    }

    ProbeHit recordHit(Player attacker, Entity entity) {
        UUID ownerId = ownerByProbe.get(entity.getUniqueId());
        if (ownerId == null || !ownerId.equals(attacker.getUniqueId())) return null;

        ProbeSession session = sessions.get(ownerId);
        if (session == null) return null;

        long now = System.currentTimeMillis();
        long debounceMs = Math.max(0, cfgI("hit-debounce-ms", 120));
        long lastHit = session.lastHitByProbe.getOrDefault(entity.getUniqueId(), 0L);
        if (now - lastHit < debounceMs) return null;

        session.lastHitByProbe.put(entity.getUniqueId(), now);
        session.hits++;
        session.uniqueTargets.add(entity.getUniqueId());

        int hitsToFlag = Math.max(1, cfgI("hits-to-flag", 2));
        int distinctToFlag = Math.max(1, Math.min(session.probes.size(),
                cfgI("distinct-targets-to-flag", 2)));
        boolean shouldFlag = session.hits >= hitsToFlag
                && session.uniqueTargets.size() >= distinctToFlag;
        return new ProbeHit(session.hits, session.uniqueTargets.size(),
                now - session.startedAt, shouldFlag);
    }

    void finish(Player owner) {
        ProbeSession session = sessions.remove(owner.getUniqueId());
        if (session != null) removeEntities(session);
    }

    void onQuit(Player player) {
        finish(player);
        cooldownUntil.remove(player.getUniqueId());
    }

    /** 新加入的玩家不应收到其他玩家探针的实体包。 */
    void hideFromViewer(Player viewer) {
        for (ProbeSession session : sessions.values()) {
            if (session.ownerId.equals(viewer.getUniqueId())) continue;
            for (ArmorStand stand : session.probes) {
                if (stand.isValid()) viewer.hideEntity(plugin, stand);
            }
        }
    }

    private void update() {
        if (!probeEnabled()) {
            clearSessions();
            return;
        }

        long now = System.currentTimeMillis();
        long durationMs = Math.max(10, cfgI("duration-ticks", 80)) * 50L;
        int interval = Math.max(1, cfgI("update-interval-ticks", 2));
        Iterator<Map.Entry<UUID, ProbeSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            ProbeSession session = iterator.next().getValue();
            Player owner = plugin.getServer().getPlayer(session.ownerId);
            if (owner == null || !owner.isOnline() || owner.isDead()
                    || !owner.getWorld().getUID().equals(session.worldId)
                    || now - session.startedAt >= durationMs) {
                removeEntities(session);
                iterator.remove();
                continue;
            }

            long elapsedTicks = (now - session.startedAt) / 50L;
            if (elapsedTicks % interval != 0) continue;
            for (int i = 0; i < session.probes.size(); i++) {
                ArmorStand stand = session.probes.get(i);
                if (stand.isValid()) {
                    stand.teleport(probeLocation(owner, i, session.probes.size(), elapsedTicks));
                }
            }
        }
    }

    private ArmorStand spawnProbe(Player owner, Location location) {
        ArmorStand stand = owner.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setVisible(false);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setCollidable(false);
            entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false);
            entity.setCanPickupItems(false);
            entity.setBasePlate(false);
            entity.setArms(false);
            entity.setMarker(false); // 保留可攻击碰撞箱
            entity.setSmall(false);
            entity.setMetadata(META_KEY,
                    new FixedMetadataValue(plugin, owner.getUniqueId().toString()));
        });

        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(owner.getUniqueId())) {
                viewer.hideEntity(plugin, stand);
            }
        }
        return stand;
    }

    /**
     * 探针在玩家侧后方的安全弧内缓慢往复，避开准星正前方，防止截获正常攻击。
     */
    private Location probeLocation(Player owner, int index, int count, long elapsedTicks) {
        Location center = owner.getLocation();
        double radius = Math.max(1.8, Math.min(4.0, cfgD("radius", 2.7)));
        double safeAngle = Math.max(45.0, Math.min(85.0, cfgD("front-safe-angle", 70.0)));
        double rearArc = 360.0 - safeAngle * 2.0;
        double segment = rearArc / count;
        double relativeYaw = safeAngle + segment * (index + 0.5);

        double speed = Math.max(0.0, cfgD("orbit-speed-degrees-per-tick", 4.0));
        double sweep = Math.max(0.0, Math.min(20.0, cfgD("orbit-sweep-degrees", 12.0)));
        relativeYaw += Math.sin(Math.toRadians(elapsedTicks * speed)) * sweep;

        double yaw = Math.toRadians(center.getYaw() + relativeYaw);
        return center.clone().add(-Math.sin(yaw) * radius, 0.0, Math.cos(yaw) * radius);
    }

    private void clearSessions() {
        for (ProbeSession session : new ArrayList<>(sessions.values())) {
            removeEntities(session);
        }
        sessions.clear();
    }

    private void removeEntities(ProbeSession session) {
        for (ArmorStand stand : session.probes) {
            ownerByProbe.remove(stand.getUniqueId());
            stand.removeMetadata(META_KEY, plugin);
            if (stand.isValid()) stand.remove();
        }
    }

    private boolean probeEnabled() {
        return plugin.getConfig().getBoolean("checks.kill-aura.enabled", true)
                && cfgB("enabled", true);
    }

    private int cfgI(String path, int def) {
        return plugin.getConfig().getInt("checks.kill-aura.probe." + path, def);
    }

    private double cfgD(String path, double def) {
        return plugin.getConfig().getDouble("checks.kill-aura.probe." + path, def);
    }

    private boolean cfgB(String path, boolean def) {
        return plugin.getConfig().getBoolean("checks.kill-aura.probe." + path, def);
    }

    private static final class ProbeSession {
        private final UUID ownerId;
        private final UUID worldId;
        private final long startedAt;
        private final List<ArmorStand> probes = new ArrayList<>();
        private final Set<UUID> uniqueTargets = new HashSet<>();
        private final Map<UUID, Long> lastHitByProbe = new HashMap<>();
        private int hits;

        private ProbeSession(UUID ownerId, UUID worldId, long startedAt) {
            this.ownerId = ownerId;
            this.worldId = worldId;
            this.startedAt = startedAt;
        }
    }
}
