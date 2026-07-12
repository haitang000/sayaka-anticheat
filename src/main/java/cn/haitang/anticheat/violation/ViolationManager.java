package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.Map;

/**
 * 递进式惩罚的调度中心。
 *
 * 违规 → 单项 VL 累加 → 汇总全部 VL 后依阈值触发：
 *   warn-1-vl  首次警告
 *   mitigate-vl 开始拦截（回弹/取消命中，由各检测调用 {@link #shouldMitigate}）
 *   warn-2-vl  最后通牒
 *   kick-vl    踢出（已警告玩家按阶段降低阈值；记 strike，窗口内攒够 → 临时封禁）
 * 各检测项 VL 每秒独立衰减，正常游戏可自行"洗白"；刚触发违规的检测项有短暂
 * 保护期不衰减，且衰减速率可按检测项覆盖（战斗类证据稀疏，应慢于移动类）。
 */
public class ViolationManager {

    private final AntiCheatPlugin plugin;
    private BukkitTask decayTask;

    public ViolationManager(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 各检测唯一的违规上报入口。
     *
     * @param weight 本次违规的 VL 权重
     * @param detail 证据摘要，出现在警报与历史记录中
     */
    public void flag(Player player, CheckType type, double weight, String detail) {
        if (plugin.getStore().isWhitelisted(player.getUniqueId())) return;
        PlayerData data = plugin.getDataManager().get(player);
        if (data.isPunishing()) return;

        double vl = data.addVl(type, weight);
        double totalVl = data.getTotalVl();
        data.addViolation(new PlayerData.ViolationRecord(
                System.currentTimeMillis(), type, vl, detail));

        if (plugin.getConfig().getBoolean("settings.debug")) {
            plugin.getLogger().info(String.format("[DEBUG] %s %s +%.1f => VL %.1f, total %.1f (%s)",
                    player.getName(), type.id(), weight, vl, totalVl, detail));
        }

        plugin.getAlertManager().staffAlert(player, type, vl, detail);

        double kickVl = effectiveKickVl(plugin.getConfig(), data.getPunishmentWarnStage());
        double warn2Vl = plugin.getConfig().getDouble("punishment.warn-2-vl", 12);
        double warn1Vl = plugin.getConfig().getDouble("punishment.warn-1-vl", 5);

        if (totalVl >= kickVl) {
            plugin.getPunishmentExecutor().kickOrBan(player, type, totalVl);
        } else if (totalVl >= warn2Vl && data.getPunishmentWarnStage() < 2) {
            data.setPunishmentWarnStage(2);
            plugin.getAlertManager().warnPlayer(player, 2);
        } else if (totalVl >= warn1Vl && data.getPunishmentWarnStage() < 1) {
            data.setPunishmentWarnStage(1);
            plugin.getAlertManager().warnPlayer(player, 1);
        }
    }

    /** 根据本次上报前已有的警告阶段计算踢出阈值。 */
    static double effectiveKickVl(Configuration config, int warningStage) {
        double base = config.getDouble("punishment.kick-vl", 20.0);
        double multiplier = 1.0;
        if (warningStage >= 2) {
            multiplier = config.getDouble("punishment.warned-kick-multipliers.warn-2", 0.75);
        } else if (warningStage >= 1) {
            multiplier = config.getDouble("punishment.warned-kick-multipliers.warn-1", 0.90);
        }
        return base * Math.max(0.0, Math.min(1.0, multiplier));
    }

    /** 当前检测项 VL 是否已达拦截阈值（避免其他检测导致错误回弹/取消事件）。 */
    public boolean shouldMitigate(Player player, CheckType type) {
        if (plugin.getStore().isWhitelisted(player.getUniqueId())) return false;
        double mitigateVl = plugin.getConfig().getDouble("punishment.mitigate-vl", 8);
        return plugin.getDataManager().get(player).getVl(type) >= mitigateVl;
    }

    /** 启动每秒一次的 VL 衰减任务 */
    public void startDecayTask() {
        double warn1Vl = plugin.getConfig().getDouble("punishment.warn-1-vl", 5);
        decayTask = new BukkitRunnable() {
            @Override
            public void run() {
                Configuration config = plugin.getConfig();
                double perSecond = config.getDouble("decay.vl-per-second", 0.2);
                long holdMs = (long) (config.getDouble("decay.hold-seconds", 6.0) * 1000);
                Map<CheckType, Double> perTypeRate = perCheckRates(config);
                double rewarnBelow = config.getDouble("punishment.warn-1-vl", warn1Vl) / 2.0;
                for (PlayerData data : plugin.getDataManager().all()) {
                    data.decay(perSecond, perTypeRate, holdMs, rewarnBelow);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** 读取 decay.per-check.<configKey> 的衰减速率覆盖；未配置的检测项用全局速率。 */
    static Map<CheckType, Double> perCheckRates(Configuration config) {
        Map<CheckType, Double> rates = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            String path = "decay.per-check." + type.configKey();
            if (config.contains(path)) {
                rates.put(type, Math.max(0.0, config.getDouble(path)));
            }
        }
        return rates;
    }

    public void shutdown() {
        if (decayTask != null) decayTask.cancel();
    }
}
