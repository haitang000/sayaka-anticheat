package cn.haitang.anticheat.packet;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.combat.CombatAttackContext;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Main-thread 40 tick position history used for latency-aware combat checks. */
public final class EntityPositionHistory {

    private record Snapshot(int tick, UUID worldId, BoundingBox box) { }

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Deque<Snapshot>> histories = new HashMap<>();
    private final BukkitTask sampleTask;

    public EntityPositionHistory(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.sampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 1L, 1L);
    }

    public BoundingBox boxAt(Player victim, CombatAttackContext.Attack attack) {
        Deque<Snapshot> history = histories.get(victim.getUniqueId());
        if (history == null || history.isEmpty()) return victim.getBoundingBox();
        int wantedTick = attack.confirmedServerTick();
        if (wantedTick < attack.serverTick() - 40 || wantedTick > attack.serverTick()) {
            long rtt = plugin.getPacketTimeline().roundTripMillis(attack.attackerId());
            int rewindTicks = Math.max(0, Math.min(4, (int) Math.round(rtt / 100.0)));
            wantedTick = attack.serverTick() - rewindTicks;
        }
        UUID worldId = victim.getWorld().getUID();
        Snapshot candidate = null;
        for (Snapshot snapshot : history) {
            if (!snapshot.worldId().equals(worldId)) continue;
            if (snapshot.tick() <= wantedTick) candidate = snapshot;
            else break;
        }
        return candidate == null ? victim.getBoundingBox() : candidate.box().clone();
    }

    public void remove(UUID playerId) {
        histories.remove(playerId);
    }

    public void shutdown() {
        sampleTask.cancel();
        histories.clear();
    }

    private void sample() {
        int tick = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            BoundingBox box = player.getBoundingBox();
            BoundingBox copy = new BoundingBox(box.getMinX(), box.getMinY(), box.getMinZ(),
                    box.getMaxX(), box.getMaxY(), box.getMaxZ());
            Deque<Snapshot> history = histories.computeIfAbsent(player.getUniqueId(),
                    ignored -> new ArrayDeque<>());
            history.addLast(new Snapshot(tick, world.getUID(), copy));
            while (!history.isEmpty() && tick - history.peekFirst().tick() > 40) {
                history.removeFirst();
            }
        }
    }
}
