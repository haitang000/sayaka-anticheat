package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Binds one real client attack intent to at most one Bukkit damage event. */
public final class CombatAttackContext implements Listener {

    public record PacketAttack(long sequence, long receivedNanos, int entityId,
                               double x, double y, double z, float yaw, float pitch,
                               long precedingSwingSequence, int confirmedServerTick) { }

    public record Attack(long id, UUID attackerId, UUID targetId, int serverTick,
                         long packetSequence, long receivedNanos, boolean packetBacked,
                         double x, double y, double z, float yaw, float pitch,
                         long precedingSwingSequence, int confirmedServerTick) { }

    @FunctionalInterface
    public interface PacketLookup {
        PacketAttack find(UUID attackerId, int targetEntityId, long notBeforeNanos);
    }

    private static final int MAX_PENDING_PER_PLAYER = 8;

    private final AntiCheatPlugin plugin;
    private final AtomicLong ids = new AtomicLong();
    private final Map<UUID, Deque<Attack>> pending = new HashMap<>();
    private final Map<EntityDamageByEntityEvent, Attack> bound = new IdentityHashMap<>();
    private PacketLookup packetLookup;
    private boolean cleanupScheduled;

    public CombatAttackContext(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    public void setPacketLookup(PacketLookup packetLookup) {
        this.packetLookup = packetLookup;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttackIntent(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack()) return;
        Player player = event.getPlayer();
        int tick = Bukkit.getCurrentTick();
        long now = System.nanoTime();
        PacketAttack packet = packetLookup == null ? null
                : packetLookup.find(player.getUniqueId(), event.getAttacked().getEntityId(),
                now - 250_000_000L);
        if (packetLookup != null && packet == null) return;
        Location location = player.getLocation();
        Attack attack = packet == null
                ? new Attack(ids.incrementAndGet(), player.getUniqueId(),
                event.getAttacked().getUniqueId(), tick, -1L, now, false,
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), -1L, -1)
                : new Attack(ids.incrementAndGet(), player.getUniqueId(),
                event.getAttacked().getUniqueId(), tick, packet.sequence(),
                packet.receivedNanos(), true, packet.x(), packet.y(), packet.z(),
                packet.yaw(), packet.pitch(), packet.precedingSwingSequence(),
                packet.confirmedServerTick());

        Deque<Attack> queue = pending.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        queue.removeIf(candidate -> candidate.serverTick() < tick - 1);
        while (queue.size() >= MAX_PENDING_PER_PLAYER) queue.removeFirst();
        queue.addLast(attack);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        bind(event, attacker.getUniqueId(), event.getEntity().getUniqueId(), Bukkit.getCurrentTick());
    }

    public Optional<Attack> attack(EntityDamageByEntityEvent event) {
        return Optional.ofNullable(bound.get(event));
    }

    static Attack consume(Deque<Attack> queue, UUID targetId, int tick) {
        if (queue == null) return null;
        while (!queue.isEmpty() && queue.peekFirst().serverTick() < tick - 1) queue.removeFirst();
        for (var iterator = queue.iterator(); iterator.hasNext(); ) {
            Attack candidate = iterator.next();
            if (candidate.serverTick() <= tick && candidate.targetId().equals(targetId)) {
                iterator.remove();
                return candidate;
            }
        }
        return null;
    }

    private void bind(EntityDamageByEntityEvent event, UUID attackerId, UUID targetId, int tick) {
        Attack attack = consume(pending.get(attackerId), targetId, tick);
        if (attack == null) return;
        bound.put(event, attack);
        if (!cleanupScheduled) {
            cleanupScheduled = true;
            Bukkit.getScheduler().runTask(plugin, () -> {
                bound.clear();
                cleanupScheduled = false;
            });
        }
    }

    public void remove(UUID playerId) {
        pending.remove(playerId);
    }
}
