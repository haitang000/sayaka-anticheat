package cn.haitang.anticheat.velocity;

import java.util.List;
import java.util.UUID;

/** Live proxy state and actions the dashboard exposes without touching Velocity APIs directly. */
interface NetworkControl {

    /** Total players connected to the proxy. */
    int onlineCount();

    /** Snapshot of every connected player, ordered by name. */
    List<OnlinePlayer> onlinePlayers();

    /** Disconnects a player with the given reason; returns false when the player is not online. */
    boolean kick(UUID playerId, String reason);

    /** Broadcasts a message to every connected player; returns how many players received it. */
    default int broadcast(String message) {
        return 0;
    }

    /** Snapshot of every registered backend server. */
    List<ServerNode> servers();

    record OnlinePlayer(UUID id, String name, String server, long pingMillis) {}

    record ServerNode(String name, int playerCount, boolean reachable, long pingMillis) {}

    /** A control that reports an empty network; used by tests and before the proxy is wired in. */
    static NetworkControl empty() {
        return new NetworkControl() {
            @Override public int onlineCount() { return 0; }
            @Override public List<OnlinePlayer> onlinePlayers() { return List.of(); }
            @Override public boolean kick(UUID playerId, String reason) { return false; }
            @Override public List<ServerNode> servers() { return List.of(); }
        };
    }
}
