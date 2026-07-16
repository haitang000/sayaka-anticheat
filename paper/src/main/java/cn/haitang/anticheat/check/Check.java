package cn.haitang.anticheat.check;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

/**
 * 检测基类。子类通过 {@link #flag} 上报违规，通过 {@link #shouldMitigate} 决定是否拦截。
 * 阈值统一从 config.yml 的 checks.<configKey> 段读取。
 */
public abstract class Check implements Listener {

    public static final String PERM_BYPASS = "anticheat.bypass";

    protected final AntiCheatPlugin plugin;
    private final CheckType type;

    protected Check(AntiCheatPlugin plugin, CheckType type) {
        this.plugin = plugin;
        this.type = type;
    }

    public CheckType type() {
        return type;
    }

    public boolean isEnabled() {
        return plugin.config().getBoolean("checks." + type.configKey() + ".enabled", true);
    }

    /** 配置热重载钩子；需要缓存配置的检测可覆盖。 */
    public void reloadConfiguration() {
    }

    /** 通用豁免：绕过权限 / OP豁免 / 创造与旁观 / 禁用世界 / 进服宽限 / NPC */
    protected boolean isExempt(Player player) {
        if (!isEnabled()) return true;
        PlayerData data = data(player);
        int tick = Bukkit.getCurrentTick();
        if (data.hasExemptionCache(tick)) {
            return data.getExemptionCacheValue();
        }

        boolean exempt = computeGenericExemption(player, data);
        data.cacheExemption(tick, exempt);
        return exempt;
    }

    private boolean computeGenericExemption(Player player, PlayerData data) {
        if (!player.isOnline() || player.isDead()) return true;
        if (plugin.getStore().isWhitelisted(player.getUniqueId())) return true;
        if (player.hasPermission(PERM_BYPASS)) return true;
        if (plugin.config().getBoolean("settings.exempt-ops", false) && player.isOp()) return true;
        if (data.isBedrock()
                && plugin.config().bedrockProfile()
                == cn.haitang.anticheat.config.ConfigSnapshot.BedrockProfile.EXEMPT) return true;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return true;
        String worldName = player.getWorld().getName();
        for (String disabledWorld : plugin.config().getStringList("settings.disabled-worlds")) {
            if (disabledWorld.equalsIgnoreCase(worldName)) return true;
        }
        if (player.hasMetadata("NPC")) return true;

        long graceMs = plugin.config().getInt("settings.join-grace-seconds", 5) * 1000L;
        return System.currentTimeMillis() - data.getJoinAt() < graceMs;
    }

    protected PlayerData data(Player player) {
        return plugin.getDataManager().get(player);
    }

    protected void flag(Player player, double weight, String detail) {
        plugin.getViolationManager().flag(player, type, weight, detail);
    }

    protected void observe(Player player, String detail) {
        plugin.getViolationManager().observe(player, type, detail);
    }

    /** VL 已达拦截阈值：移动类应回弹、战斗类应取消命中 */
    protected boolean shouldMitigate(Player player) {
        return plugin.getViolationManager().shouldMitigate(player, type);
    }

    /** Whether the effective enforcement mode permits local evidence-based mitigation. */
    protected boolean allowsMitigation(Player player) {
        return plugin.getViolationManager().effectiveEnforcement(player, type).allowsMitigation();
    }

    // ---- 配置便捷读取（checks.<key>.<path>） ----

    protected double cfgD(String path, double def) {
        return plugin.config().getDouble("checks." + type.configKey() + "." + path, def);
    }

    protected int cfgI(String path, int def) {
        return plugin.config().getInt("checks." + type.configKey() + "." + path, def);
    }

    protected boolean cfgB(String path, boolean def) {
        return plugin.config().getBoolean("checks." + type.configKey() + "." + path, def);
    }
}
