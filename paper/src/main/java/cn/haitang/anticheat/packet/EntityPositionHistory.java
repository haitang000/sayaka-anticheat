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

    record Snapshot(int tick, UUID worldId, BoundingBox box) { }

    private final AntiCheatPlugin plugin;
    private final Map<UUID, Deque<Snapshot>> histories = new HashMap<>();
    private final BukkitTask sampleTask;

    public EntityPositionHistory(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.sampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 1L, 1L);
    }

    public BoundingBox boxAt(Player victim, CombatAttackContext.Attack attack) {
        return boxWithin(victim, attack, 0);
    }

    /**
     * 攻击者客户端在攻击瞬间可能渲染到的所有目标位置的包络箱：以延迟补偿后的
     * 服务端 tick 为终点，对客户端实体插值窗口内的历史快照取并集。射线类判定
     * 应针对这个并集，单一 tick 的精确箱体会误杀瞄准插值位置的正常攻击。
     */
    public BoundingBox interpolatedBoxAt(Player victim, CombatAttackContext.Attack attack,
                                         int interpolationTicks) {
        return boxWithin(victim, attack, interpolationTicks);
    }

    private BoundingBox boxWithin(Player victim, CombatAttackContext.Attack attack,
                                  int windowTicks) {
        Deque<Snapshot> history = histories.get(victim.getUniqueId());
        if (history == null || history.isEmpty()) return victim.getBoundingBox();
        BoundingBox box = unionWindow(history, victim.getWorld().getUID(),
                wantedTick(attack), windowTicks);
        return box == null ? victim.getBoundingBox() : box;
    }

    private int wantedTick(CombatAttackContext.Attack attack) {
        int wantedTick = attack.confirmedServerTick();
        if (wantedTick < attack.serverTick() - 40 || wantedTick > attack.serverTick()) {
            long rtt = plugin.getPacketTimeline().roundTripMillis(attack.attackerId());
            int rewindTicks = Math.max(0, Math.min(4, (int) Math.round(rtt / 100.0)));
            wantedTick = attack.serverTick() - rewindTicks;
        }
        return wantedTick;
    }

    /**
     * Union of snapshots with tick in {@code [wantedTick - windowTicks, wantedTick]} for the
     * given world; falls back to the newest older snapshot, or null when nothing matches.
     * Snapshots must be ordered oldest to newest.
     */
    static BoundingBox unionWindow(Iterable<Snapshot> history, UUID worldId,
                                   int wantedTick, int windowTicks) {
        int windowStart = wantedTick - Math.max(0, windowTicks);
        BoundingBox union = null;
        BoundingBox fallback = null;
        for (Snapshot snapshot : history) {
            if (!snapshot.worldId().equals(worldId)) continue;
            if (snapshot.tick() > wantedTick) break;
            if (snapshot.tick() < windowStart) {
                fallback = snapshot.box();
                continue;
            }
            union = union == null ? snapshot.box().clone() : union.union(snapshot.box());
        }
        if (union != null) return union;
        return fallback == null ? null : fallback.clone();
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
