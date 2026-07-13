package cn.haitang.anticheat.packet;

import java.util.ArrayDeque;
import java.util.Deque;

/** Pure packet-clock model, isolated from Bukkit and Netty for trace tests. */
final class PacketTimerClock {

    record Evidence(int packets, double balanceMillis, double ratePerSecond) { }

    private static final long EXPECTED_MOVE_NANOS = 50_000_000L;
    private static final long SIGNAL_BALANCE_NANOS = 250_000_000L;
    private final Deque<Long> movementTimes = new ArrayDeque<>();
    private long lastMoveNanos;
    private long balanceNanos;
    private long lastSignalNanos;

    Evidence accept(long now) {
        movementTimes.addLast(now);
        while (!movementTimes.isEmpty() && now - movementTimes.peekFirst() > 3_000_000_000L) {
            movementTimes.removeFirst();
        }
        if (lastMoveNanos == 0L || now - lastMoveNanos > 1_000_000_000L) {
            balanceNanos = 0L;
            lastMoveNanos = now;
            return null;
        }
        long elapsed = Math.max(0L, now - lastMoveNanos);
        lastMoveNanos = now;
        balanceNanos = Math.max(0L, balanceNanos + EXPECTED_MOVE_NANOS - elapsed);
        if (movementTimes.size() < 40 || balanceNanos < SIGNAL_BALANCE_NANOS
                || now - lastSignalNanos < 1_000_000_000L) return null;

        lastSignalNanos = now;
        balanceNanos = Math.max(0L, balanceNanos - 100_000_000L);
        double span = Math.max(0.001,
                (movementTimes.peekLast() - movementTimes.peekFirst()) / 1_000_000_000.0);
        return new Evidence(movementTimes.size(), balanceNanos / 1_000_000.0,
                (movementTimes.size() - 1) / span);
    }

    void reset() {
        lastMoveNanos = 0L;
        balanceNanos = 0L;
        lastSignalNanos = 0L;
        movementTimes.clear();
    }
}
