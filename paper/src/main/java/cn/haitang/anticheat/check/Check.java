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
    /** "checks.<key>." 前缀只拼一次，热路径配置读取不再逐次拼接 */
    private final String configPrefix;
    private final String enabledKey;

    protected Check(AntiCheatPlugin plugin, CheckType type) {
        this.plugin = plugin;
        this.type = type;
        this.configPrefix = "checks." + type.configKey() + ".";
        this.enabledKey = configPrefix + "enabled";
    }

    public CheckType type() {
        return type;
    }

    public boolean isEnabled() {
        return plugin.config().getBoolean(enabledKey, true);
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
        // 第三方通过 SayakaApi.registerExemptionChecker 注册的自定义豁免
        for (java.util.function.Predicate<Player> checker
                : plugin.getExemptionCheckers()) {
            if (checker.test(player)) return true;
        }

        long graceMs = plugin.config().getInt("settings.join-grace-seconds", 5) * 1000L;
        return System.currentTimeMillis() - data.getJoinAt() < graceMs;
    }

    /**
     * 服务端是否卡到不能再信任墙钟时间差。
     *
     * <p>大量检测拿 {@code System.currentTimeMillis()} 的差值去比对由数据包驱动的事件
     * （拉弓、用物品、挖掘、放置、容器操作、图腾换手）。服务端一旦停顿
     * （GC、同步区块加载、{@code /reload}），积压的客户端包会在同一毫秒内被一次性排空，
     * 起止事件的时间差趋近于 0，合法玩家就会被判成"快得不可能"。
     *
     * <p>阈值优先取本检测的 {@code min-tps}，未配置时回落到 {@code settings.min-tps}。
     * 配 0 或负数表示关闭该门控。
     */
    protected boolean serverLagging() {
        double minTps = cfgD("min-tps", plugin.config().getDouble("settings.min-tps", 18.0));
        if (minTps <= 0) return false;
        double[] tps = plugin.getServer().getTPS();
        return tps.length > 0 && tps[0] < minTps;
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
        return plugin.config().getDouble(configPrefix + path, def);
    }

    protected int cfgI(String path, int def) {
        return plugin.config().getInt(configPrefix + path, def);
    }

    protected boolean cfgB(String path, boolean def) {
        return plugin.config().getBoolean(configPrefix + path, def);
    }
}
