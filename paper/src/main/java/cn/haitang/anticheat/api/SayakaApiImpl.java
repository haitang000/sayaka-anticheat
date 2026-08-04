package cn.haitang.anticheat.api;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** {@link SayakaApi} 的 paper 端实现，由主类通过 ServicesManager 注册。 */
public final class SayakaApiImpl implements SayakaApi {

    private final AntiCheatPlugin plugin;

    public SayakaApiImpl(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public double getVl(Player player, String checkId) {
        CheckType type = CheckType.byId(checkId);
        if (type == null) return 0;
        PlayerData data = plugin.getDataManager().getIfPresent(player.getUniqueId());
        return data == null ? 0 : data.getVl(type);
    }

    @Override
    public double getTotalVl(Player player) {
        PlayerData data = plugin.getDataManager().getIfPresent(player.getUniqueId());
        return data == null ? 0 : data.getTotalVl();
    }

    @Override
    public boolean isExempt(Player player) {
        return plugin.getStore().isWhitelisted(player.getUniqueId())
                || player.hasPermission(cn.haitang.anticheat.check.Check.PERM_BYPASS);
    }

    @Override
    public boolean isWhitelisted(UUID playerId) {
        return plugin.getStore().isWhitelisted(playerId);
    }

    @Override
    public int getStrikeCount(UUID playerId) {
        int windowHours = plugin.config().getInt("punishment.strikes.window-hours", 24);
        return plugin.getStore().strikeCount(playerId, windowHours);
    }

    @Override
    public List<ViolationInfo> getRecentViolations(Player player) {
        PlayerData data = plugin.getDataManager().getIfPresent(player.getUniqueId());
        if (data == null) return List.of();
        List<ViolationInfo> result = new ArrayList<>();
        for (PlayerData.ViolationRecord record : data.getRecentViolations()) {
            result.add(new ViolationInfo(record.at(), record.type().id(),
                    record.type().display(), record.vl(), record.detail()));
        }
        return result;
    }

    @Override
    public List<String> getCheckIds() {
        List<String> ids = new ArrayList<>();
        for (CheckType type : CheckType.values()) ids.add(type.id());
        return ids;
    }

    @Override
    public void resetPlayer(Player player, boolean resetProfile) {
        PlayerData data = plugin.getDataManager().get(player);
        data.resetAllVl();
        if (resetProfile) plugin.getStore().resetPlayer(player.getUniqueId());
    }

    @Override
    public boolean registerExemptionChecker(Predicate<Player> checker) {
        return plugin.addExemptionChecker(checker);
    }

    @Override
    public boolean unregisterExemptionChecker(Predicate<Player> checker) {
        return plugin.removeExemptionChecker(checker);
    }
}
