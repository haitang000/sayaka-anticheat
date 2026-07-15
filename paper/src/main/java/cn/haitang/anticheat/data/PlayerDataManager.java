package cn.haitang.anticheat.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 在线玩家数据的生命周期管理。 */
public class PlayerDataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData get(Player player) {
        return data.computeIfAbsent(player.getUniqueId(),
                id -> new PlayerData(id, player.getName()));
    }

    public PlayerData getIfPresent(UUID uuid) {
        return data.get(uuid);
    }

    public void remove(UUID uuid) {
        data.remove(uuid);
    }

    public Collection<PlayerData> all() {
        return data.values();
    }
}
