package cn.haitang.anticheat.packet;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.combat.CombatAttackContext;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/** Bounded packet history. Netty listeners never access Bukkit state. */
public final class PacketTimeline {

    public enum SampleType {
        MOVE, ROTATE, SWING, ATTACK, DIG, USE_ITEM, PLACE, INVENTORY, TELEPORT, VELOCITY
    }

    public record Sample(long sequence, long nanos, SampleType type) { }
    public record TimerEvidence(int packets, double balanceMillis, double ratePerSecond) { }
    public record BlinkEvidence(int cycles, long pauseMillis, int burstPackets) { }
    private record TimerSignal(UUID playerId, TimerEvidence evidence) { }
    private record BlinkSignal(UUID playerId, BlinkEvidence evidence) { }
    private record AttackPacket(long sequence, long nanos, int entityId,
                                double x, double y, double z, float yaw, float pitch,
                                long precedingSwingSequence, int confirmedServerTick,
                                boolean claimed) {
        AttackPacket claimedCopy() {
            return new AttackPacket(sequence, nanos, entityId, x, y, z, yaw, pitch,
                    precedingSwingSequence, confirmedServerTick, true);
        }
    }
    private record Impulse(long nanos, double x, double y, double z) { }
    private record Transaction(long sentNanos, int serverTick) { }

    private final AntiCheatPlugin plugin;
    private final int historyCapacity;
    private final int signalCapacity;
    private final int completionsPerTick;
    /** Blink 识别参数（Netty 线程读取，启动时读配置一次，修改需重启） */
    private final long blinkMinPauseMs;
    private final long blinkMaxPauseMs;
    private final long blinkBurstWindowMs;
    private final int blinkMinBurstPackets;
    private final int blinkMinLivePongs;
    private final int blinkCyclesToSignal;
    private final long blinkCycleWindowMs;
    private final Map<UUID, Timeline> timelines = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TimerSignal> signals = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlinkSignal> blinkSignals = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedSignals = new AtomicInteger();
    private final AtomicInteger queuedBlinkSignals = new AtomicInteger();
    private final AtomicInteger transactionIds = new AtomicInteger(Integer.MIN_VALUE);
    private final PacketListenerCommon listener;
    private final BukkitTask drainTask;
    private volatile BiConsumer<Player, TimerEvidence> timerConsumer = (player, evidence) -> { };
    private volatile BiConsumer<Player, BlinkEvidence> blinkConsumer = (player, evidence) -> { };
    private volatile BiPredicate<Player, Integer> selfAttackHandler = (player, entityId) -> false;
    private volatile BiPredicate<UUID, Integer> rawAttackConsumer = (playerId, entityId) -> false;

    public PacketTimeline(AntiCheatPlugin plugin) {
        this.plugin = plugin;
        this.historyCapacity = Math.max(128, Math.min(2048,
                plugin.config().getInt("settings.packet-analysis.history-per-player", 256)));
        this.signalCapacity = Math.max(256,
                plugin.config().getInt("settings.packet-analysis.queue-capacity", 4096));
        this.completionsPerTick = Math.max(16,
                plugin.config().getInt("settings.packet-analysis.completions-per-tick", 512));
        this.blinkMinPauseMs = Math.max(200L,
                plugin.config().getInt("checks.timer.blink.min-pause-ms", 500));
        this.blinkMaxPauseMs = Math.max(this.blinkMinPauseMs,
                plugin.config().getInt("checks.timer.blink.max-pause-ms", 5000));
        this.blinkBurstWindowMs = Math.max(50L,
                plugin.config().getInt("checks.timer.blink.burst-window-ms", 150));
        this.blinkMinBurstPackets = Math.max(3,
                plugin.config().getInt("checks.timer.blink.min-burst-packets", 6));
        this.blinkMinLivePongs = Math.max(1,
                plugin.config().getInt("checks.timer.blink.min-live-pongs", 2));
        this.blinkCyclesToSignal = Math.max(2,
                plugin.config().getInt("checks.timer.blink.cycles-to-alert", 4));
        this.blinkCycleWindowMs = Math.max(5000L,
                plugin.config().getInt("checks.timer.blink.cycle-window-ms", 30000));
        this.listener = PacketEvents.getAPI().getEventManager().registerListener(
                new TimelineListener());
        this.drainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainSignals, 1L, 1L);
    }

    public void setTimerConsumer(BiConsumer<Player, TimerEvidence> timerConsumer) {
        this.timerConsumer = timerConsumer == null ? (player, evidence) -> { } : timerConsumer;
    }

    public void setBlinkConsumer(BiConsumer<Player, BlinkEvidence> blinkConsumer) {
        this.blinkConsumer = blinkConsumer == null ? (player, evidence) -> { } : blinkConsumer;
    }

    /** 自击包（攻击自己实体 id）处理器：Netty 线程调用，返回 true 时丢弃该包 */
    public void setSelfAttackHandler(BiPredicate<Player, Integer> selfAttackHandler) {
        this.selfAttackHandler = selfAttackHandler == null
                ? (player, entityId) -> false : selfAttackHandler;
    }

    public void setRawAttackConsumer(BiPredicate<UUID, Integer> rawAttackConsumer) {
        this.rawAttackConsumer = rawAttackConsumer == null
                ? (playerId, entityId) -> false : rawAttackConsumer;
    }

    public CombatAttackContext.PacketAttack findAttack(UUID playerId, int entityId,
                                                        long notBeforeNanos) {
        Timeline timeline = timelines.get(playerId);
        return timeline == null ? null : timeline.claimAttack(entityId, notBeforeNanos);
    }

    public boolean hasSwingNear(UUID playerId, long attackSequence, long attackNanos,
                                long precedingSwingSequence) {
        Timeline timeline = timelines.get(playerId);
        return timeline != null && timeline.hasSwingNear(
                attackSequence, attackNanos, precedingSwingSequence);
    }

    /**
     * 最近 windowNanos 内的战斗挥臂包数（挖掘挥臂已在协议层隔离）。
     * 到达时间在 Netty 线程记录，不受服务端 tick 批处理影响，
     * 是唯一能测出真实高 CPS 的数据源。
     */
    public int clickPacketsWithin(UUID playerId, long windowNanos) {
        Timeline timeline = timelines.get(playerId);
        return timeline == null ? 0 : timeline.clickPacketsWithin(windowNanos, System.nanoTime());
    }

    /** 最近的战斗挥臂包到达时间（纳秒，从旧到新），供点击节奏分析使用。 */
    public long[] recentClickNanos(UUID playerId, int maxCount) {
        Timeline timeline = timelines.get(playerId);
        return timeline == null ? new long[0] : timeline.recentClickNanos(maxCount);
    }

    /** 上报违规后清空点击历史，避免同一批证据在下个分析周期重复计违规。 */
    public void clearClicks(UUID playerId) {
        Timeline timeline = timelines.get(playerId);
        if (timeline != null) timeline.clearClicks();
    }

    public boolean wasImpulseSent(UUID playerId, long armedAtNanos, Vector expected) {
        Timeline timeline = timelines.get(playerId);
        return timeline != null && timeline.wasImpulseSent(armedAtNanos, expected);
    }

    public long roundTripMillis(UUID playerId) {
        Timeline timeline = timelines.get(playerId);
        return timeline == null ? 0L : timeline.roundTripMillis();
    }

    public void resetTimer(UUID playerId) {
        Timeline timeline = timelines.get(playerId);
        if (timeline != null) timeline.resetTimer();
    }

    public List<Sample> samples(UUID playerId) {
        Timeline timeline = timelines.get(playerId);
        return timeline == null ? List.of() : timeline.samples();
    }

    public void remove(UUID playerId) {
        timelines.remove(playerId);
    }

    public void shutdown() {
        drainTask.cancel();
        PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        timelines.clear();
        signals.clear();
        queuedSignals.set(0);
        blinkSignals.clear();
        queuedBlinkSignals.set(0);
    }

    private void drainSignals() {
        sendTransactions();
        double[] tps = plugin.getServer().getTPS();
        boolean lagging = tps.length > 0 && tps[0] < plugin.config()
                .getDouble("checks.timer.min-tps", 18.0);
        for (int i = 0; i < completionsPerTick; i++) {
            TimerSignal signal = signals.poll();
            if (signal == null) break;
            queuedSignals.decrementAndGet();
            if (lagging) {
                resetTimer(signal.playerId());
                continue;
            }
            Player player = Bukkit.getPlayer(signal.playerId());
            if (player != null && player.isOnline()) timerConsumer.accept(player, signal.evidence());
        }
        for (int i = 0; i < completionsPerTick; i++) {
            BlinkSignal signal = blinkSignals.poll();
            if (signal == null) break;
            queuedBlinkSignals.decrementAndGet();
            // 服务端卡顿会打乱包到达节奏，本轮证据不可信
            if (lagging) continue;
            Player player = Bukkit.getPlayer(signal.playerId());
            if (player != null && player.isOnline()) blinkConsumer.accept(player, signal.evidence());
        }
    }

    private void sendTransactions() {
        int tick = Bukkit.getCurrentTick();
        if ((tick & 1) != 0) return;
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            Timeline timeline = timeline(playerId);
            int transactionId = transactionIds.getAndIncrement();
            timeline.transactionSent(transactionId, now, tick);
            try {
                PacketEvents.getAPI().getPlayerManager().sendPacketSilently(
                        player, new WrapperPlayServerPing(transactionId));
            } catch (RuntimeException error) {
                timeline.transactionFailed(transactionId);
            }
        }
    }

    private void enqueueTimer(UUID playerId, TimerEvidence evidence) {
        int size = queuedSignals.incrementAndGet();
        if (size > signalCapacity) {
            queuedSignals.decrementAndGet();
            resetTimer(playerId);
            return;
        }
        signals.offer(new TimerSignal(playerId, evidence));
    }

    private void enqueueBlink(UUID playerId, BlinkEvidence evidence) {
        int size = queuedBlinkSignals.incrementAndGet();
        if (size > signalCapacity) {
            queuedBlinkSignals.decrementAndGet();
            return;
        }
        blinkSignals.offer(new BlinkSignal(playerId, evidence));
    }

    private Timeline timeline(UUID playerId) {
        return timelines.computeIfAbsent(playerId, ignored -> new Timeline());
    }

    private final class TimelineListener extends PacketListenerAbstract {
        private TimelineListener() {
            super(PacketListenerPriority.NORMAL);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            UUID playerId = event.getUser().getUUID();
            if (playerId == null) return;
            PacketTypeCommon type = event.getPacketType();
            long now = System.nanoTime();
            Timeline timeline = timeline(playerId);
            long seq = timeline.nextSequence();

            if (WrapperPlayClientPlayerFlying.isFlying(type)) {
                WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
                TimerEvidence evidence = timeline.movement(seq, now, flying);
                if (evidence != null) enqueueTimer(playerId, evidence);
                BlinkEvidence blink = timeline.pollBlinkEvidence();
                if (blink != null) enqueueBlink(playerId, blink);
                return;
            }
            if (type == PacketType.Play.Client.ANIMATION) {
                new WrapperPlayClientAnimation(event);
                timeline.swing(seq, now);
                return;
            }
            if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
                if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    timeline.miningState(false);
                    if (dropSelfAttack(event, interact.getEntityId())) return;
                    if (rawAttackConsumer.test(playerId, interact.getEntityId())) {
                        event.setCancelled(true);
                        return;
                    }
                    timeline.attack(seq, now, interact.getEntityId());
                }
                return;
            }
            if (type == PacketType.Play.Client.ATTACK) {
                timeline.miningState(false);
                int entityId = new WrapperPlayClientAttack(event).getEntityId();
                if (dropSelfAttack(event, entityId)) return;
                if (rawAttackConsumer.test(playerId, entityId)) {
                    event.setCancelled(true);
                    return;
                }
                timeline.attack(seq, now, entityId);
                return;
            }
            if (type == PacketType.Play.Client.KEEP_ALIVE) {
                timeline.keepAliveResponse(new WrapperPlayClientKeepAlive(event).getId(), now);
                return;
            }
            if (type == PacketType.Play.Client.PONG) {
                timeline.transactionResponse(new WrapperPlayClientPong(event).getId(), now);
                return;
            }
            if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
                timeline.add(seq, now, SampleType.TELEPORT);
                timeline.resetTimer();
                return;
            }
            if (type == PacketType.Play.Client.PLAYER_DIGGING) {
                DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
                if (action == DiggingAction.START_DIGGING) timeline.miningState(true);
                else if (action == DiggingAction.FINISHED_DIGGING
                        || action == DiggingAction.CANCELLED_DIGGING) timeline.miningState(false);
                timeline.add(seq, now, SampleType.DIG);
            } else if (type == PacketType.Play.Client.USE_ITEM) {
                timeline.miningState(false);
                timeline.add(seq, now, SampleType.USE_ITEM);
            } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                timeline.miningState(false);
                timeline.add(seq, now, SampleType.PLACE);
            } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
                timeline.add(seq, now, SampleType.INVENTORY);
            }
        }

        /** 攻击自己的实体 id：原版客户端的射线不可能选中自己，确定性恶意包，当场丢弃 */
        private boolean dropSelfAttack(PacketReceiveEvent event, int entityId) {
            if (entityId != event.getUser().getEntityId()) return false;
            if (!(event.getPlayer() instanceof Player player)) return false;
            if (!selfAttackHandler.test(player, entityId)) return false;
            event.setCancelled(true);
            return true;
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            UUID playerId = event.getUser().getUUID();
            if (playerId == null) return;
            PacketTypeCommon type = event.getPacketType();
            long now = System.nanoTime();
            Timeline timeline = timeline(playerId);
            if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
                WrapperPlayServerEntityVelocity velocity = new WrapperPlayServerEntityVelocity(event);
                if (velocity.getEntityId() == event.getUser().getEntityId()) {
                    timeline.impulse(now, velocity.getVelocity());
                }
            } else if (type == PacketType.Play.Server.EXPLOSION) {
                Vector3d knockback = new WrapperPlayServerExplosion(event).getKnockback();
                if (knockback != null && knockback.lengthSquared() > 0) timeline.impulse(now, knockback);
            } else if (type == PacketType.Play.Server.KEEP_ALIVE) {
                timeline.keepAliveSent(new WrapperPlayServerKeepAlive(event).getId(), now);
            } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                timeline.resetTimer();
            }
        }
    }

    private final class Timeline {
        private static final int CLICK_HISTORY = 48;

        private final Deque<Sample> samples = new ArrayDeque<>();
        private final Deque<AttackPacket> attacks = new ArrayDeque<>();
        private final Deque<Long> swings = new ArrayDeque<>();
        private final Deque<Long> swingTimes = new ArrayDeque<>();
        /** 非挖掘挥臂的到达纳秒；挖掘期间的自动挥臂由 miningActive 隔离 */
        private final Deque<Long> clickNanos = new ArrayDeque<>();
        private boolean miningActive;
        private final Deque<Impulse> impulses = new ArrayDeque<>();
        private final Map<Long, Long> keepAliveSent = new LinkedHashMap<>();
        private final Map<Integer, Transaction> transactions = new LinkedHashMap<>();
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private long lastSwingSequence = -1L;
        private long packetSequence;
        private final PacketTimerClock timerClock = new PacketTimerClock();
        private final BlinkTracker blinkTracker = new BlinkTracker(
                blinkMinPauseMs, blinkMaxPauseMs, blinkBurstWindowMs,
                blinkMinBurstPackets, blinkMinLivePongs, blinkCyclesToSignal, blinkCycleWindowMs);
        private BlinkEvidence pendingBlink;
        private long smoothedRttNanos;
        private long smoothedTransactionRttNanos;
        private int confirmedServerTick = -1;

        synchronized long nextSequence() {
            return ++packetSequence;
        }

        synchronized TimerEvidence movement(long seq, long now,
                                            WrapperPlayClientPlayerFlying packet) {
            if (packet.hasPositionChanged()) {
                x = packet.getLocation().getX();
                y = packet.getLocation().getY();
                z = packet.getLocation().getZ();
            }
            if (packet.hasRotationChanged()) {
                yaw = packet.getLocation().getYaw();
                pitch = packet.getLocation().getPitch();
            }
            addLocked(seq, now, packet.hasRotationChanged() ? SampleType.ROTATE : SampleType.MOVE);
            BlinkTracker.Evidence blink = blinkTracker.movement(now);
            if (blink != null) {
                pendingBlink = new BlinkEvidence(
                        blink.cycles(), blink.pauseMillis(), blink.burstPackets());
            }
            PacketTimerClock.Evidence evidence = timerClock.accept(now);
            return evidence == null ? null : new TimerEvidence(evidence.packets(),
                    evidence.balanceMillis(), evidence.ratePerSecond());
        }

        synchronized BlinkEvidence pollBlinkEvidence() {
            BlinkEvidence evidence = pendingBlink;
            pendingBlink = null;
            return evidence;
        }

        synchronized void swing(long seq, long now) {
            lastSwingSequence = seq;
            swings.addLast(seq);
            swingTimes.addLast(now);
            while (swings.size() > 16) {
                swings.removeFirst();
                swingTimes.removeFirst();
            }
            if (!miningActive) {
                clickNanos.addLast(now);
                while (clickNanos.size() > CLICK_HISTORY) clickNanos.removeFirst();
            }
            addLocked(seq, now, SampleType.SWING);
        }

        /**
         * 挖掘状态：开始挖掘置位；结束/取消挖掘、攻击、用物品、放方块复位。
         * 瞬间破坏只有开始包没有结束包，靠后续战斗动作解除卡死，
         * 卡死期间只会漏采样，不会把挖掘挥臂误当点击。
         */
        synchronized void miningState(boolean active) {
            miningActive = active;
        }

        synchronized int clickPacketsWithin(long windowNanos, long now) {
            int count = 0;
            for (var iterator = clickNanos.descendingIterator(); iterator.hasNext(); ) {
                if (now - iterator.next() > windowNanos) break;
                count++;
            }
            return count;
        }

        synchronized long[] recentClickNanos(int maxCount) {
            int size = Math.min(maxCount, clickNanos.size());
            long[] result = new long[size];
            int index = size;
            for (var iterator = clickNanos.descendingIterator(); iterator.hasNext() && index > 0; ) {
                result[--index] = iterator.next();
            }
            return result;
        }

        synchronized void clearClicks() {
            clickNanos.clear();
        }

        synchronized void attack(long seq, long now, int entityId) {
            attacks.addLast(new AttackPacket(seq, now, entityId, x, y, z, yaw, pitch,
                    lastSwingSequence, confirmedServerTick, false));
            while (attacks.size() > 16) attacks.removeFirst();
            addLocked(seq, now, SampleType.ATTACK);
        }

        synchronized CombatAttackContext.PacketAttack claimAttack(int entityId, long notBeforeNanos) {
            List<AttackPacket> copy = new ArrayList<>(attacks);
            for (int i = copy.size() - 1; i >= 0; i--) {
                AttackPacket attack = copy.get(i);
                if (attack.claimed() || attack.entityId() != entityId
                        || attack.nanos() < notBeforeNanos) continue;
                attacks.remove(attack);
                attacks.addLast(attack.claimedCopy());
                return new CombatAttackContext.PacketAttack(attack.sequence(), attack.nanos(),
                        attack.entityId(), attack.x(), attack.y(), attack.z(), attack.yaw(),
                        attack.pitch(), attack.precedingSwingSequence(),
                        attack.confirmedServerTick());
            }
            return null;
        }

        synchronized boolean hasSwingNear(long attackSequence, long attackNanos,
                                          long precedingSwingSequence) {
            var sequenceIterator = swings.iterator();
            var timeIterator = swingTimes.iterator();
            while (sequenceIterator.hasNext() && timeIterator.hasNext()) {
                long swingSequence = sequenceIterator.next();
                long swingNanos = timeIterator.next();
                if (swingMatches(attackSequence, attackNanos, precedingSwingSequence,
                        swingSequence, swingNanos)) return true;
            }
            return false;
        }

        synchronized void impulse(long now, Vector3d velocity) {
            impulses.addLast(new Impulse(now, velocity.x, velocity.y, velocity.z));
            while (impulses.size() > 8) impulses.removeFirst();
            addLocked(++packetSequence, now, SampleType.VELOCITY);
        }

        synchronized boolean wasImpulseSent(long armedAtNanos, Vector expected) {
            for (Impulse impulse : impulses) {
                if (impulse.nanos() < armedAtNanos - 50_000_000L) continue;
                double dx = impulse.x() - expected.getX();
                double dy = impulse.y() - expected.getY();
                double dz = impulse.z() - expected.getZ();
                if (dx * dx + dy * dy + dz * dz <= 0.01) return true;
            }
            return false;
        }

        synchronized void keepAliveSent(long id, long now) {
            keepAliveSent.put(id, now);
            if (keepAliveSent.size() > 8) {
                Long oldest = keepAliveSent.keySet().iterator().next();
                keepAliveSent.remove(oldest);
            }
        }

        synchronized void keepAliveResponse(long id, long now) {
            Long sent = keepAliveSent.remove(id);
            if (sent == null || now <= sent) return;
            long sample = now - sent;
            smoothedRttNanos = smoothedRttNanos == 0L ? sample
                    : (long) (smoothedRttNanos * 0.8 + sample * 0.2);
        }

        synchronized long roundTripMillis() {
            long rtt = smoothedTransactionRttNanos != 0L
                    ? smoothedTransactionRttNanos : smoothedRttNanos;
            return rtt / 1_000_000L;
        }

        synchronized void transactionSent(int id, long now, int serverTick) {
            transactions.put(id, new Transaction(now, serverTick));
            while (transactions.size() > 8) {
                Integer oldest = transactions.keySet().iterator().next();
                transactions.remove(oldest);
            }
        }

        synchronized void transactionFailed(int id) {
            transactions.remove(id);
        }

        synchronized void transactionResponse(int id, long now) {
            Transaction sent = transactions.remove(id);
            if (sent == null || now <= sent.sentNanos()) return;
            long sample = now - sent.sentNanos();
            smoothedTransactionRttNanos = smoothedTransactionRttNanos == 0L ? sample
                    : (long) (smoothedTransactionRttNanos * 0.8 + sample * 0.2);
            confirmedServerTick = Math.max(confirmedServerTick, sent.serverTick());
            blinkTracker.pong(now);
        }

        synchronized void resetTimer() {
            timerClock.reset();
            blinkTracker.reset();
            pendingBlink = null;
        }

        synchronized void add(long seq, long now, SampleType type) {
            addLocked(seq, now, type);
        }

        synchronized List<Sample> samples() {
            return List.copyOf(samples);
        }

        private void addLocked(long seq, long now, SampleType type) {
            samples.addLast(new Sample(seq, now, type));
            while (samples.size() > historyCapacity) samples.removeFirst();
        }
    }

    static boolean swingMatches(long attackSequence, long attackNanos,
                                long precedingSwingSequence,
                                long swingSequence, long swingNanos) {
        if (Math.abs(swingNanos - attackNanos) > 150_000_000L) return false;
        if (swingSequence == precedingSwingSequence) {
            long gap = attackSequence - swingSequence;
            return gap >= 0L && gap <= 3L;
        }
        long gap = swingSequence - attackSequence;
        return gap >= 0L && gap <= 3L;
    }
}
