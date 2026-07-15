package cn.haitang.anticheat.check.combat;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 连点器检测，四条判定线：
 * 1. CPS 超标：滚动 1 秒窗口内点击数持续超过上限，极端 CPS 直接上报
 * 2. 机械化节奏：点击间隔的标准差低到人手不可能达到（人手抖动≈10-30ms，
 *    机器点击可以稳定在 1ms 内）
 * 3. 低离散重复节奏：抓带少量随机抖动的宏。只有在低标准差、低变异系数、
 *    且大部分间隔落入同一时间桶时才累积 buffer。
 * 4. 短周期节奏：识别在多个固定间隔间循环、但整体标准差较高的随机化宏。
 * 点击来源 = 直接近战攻击 + 左键挥空；挖掘产生的持续挥臂不计入，避免误判。
 */
public class AutoClickerCheck extends Check {

    public AutoClickerCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.AUTO_CLICKER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (event.getDamager() instanceof Player attacker) {
            if (plugin.getCombatAttackContext().attack(event).isEmpty()) return;
            registerClick(attacker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR) {
            registerClick(event.getPlayer());
        }
    }

    private void registerClick(Player player) {
        if (isExempt(player)) return;
        PlayerData data = data(player);
        long now = System.currentTimeMillis();

        Deque<Long> clicks = data.getClicks();
        Long lastClick = clicks.peekLast();
        if (lastClick != null && now - lastClick <= cfgI("dedupe-ms", 8)) {
            return;
        }
        clicks.addLast(now);
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 6000) clicks.removeFirst();

        checkCps(player, data, now, clicks);
        checkRobotic(player, data, now, clicks);
    }

    private void checkCps(Player player, PlayerData data, long now, Deque<Long> clicks) {
        int cps = 0;
        for (Long t : clicks) {
            if (now - t <= 1000) cps++;
        }

        int hardMaxCps = cfgI("hard-max-cps", 28);
        if (cps >= hardMaxCps) {
            if (now - data.getLastCpsFlagAt() >= 1000) {
                data.setLastCpsFlagAt(now);
                data.setCpsSustainStart(now);
                flag(player, 2.0, cps + " CPS（极端频率）");
            }
            return;
        }

        int maxCps = cfgI("max-cps", 21);
        if (cps >= maxCps) {
            if (data.getCpsSustainStart() == 0) {
                data.setCpsSustainStart(now);
            } else if (now - data.getCpsSustainStart() >= cfgI("sustain-ms", 1000)) {
                data.setCpsSustainStart(now); // 持续超标则每个周期重复计违规
                data.setLastCpsFlagAt(now);
                flag(player, 1.5, cps + " CPS");
            }
        } else if (cps < maxCps - 4) {
            data.setCpsSustainStart(0);
        }
    }

    private void checkRobotic(Player player, PlayerData data, long now, Deque<Long> clicks) {
        if (clicks.size() < 30) return;
        if (now - data.getLastRoboticEvalAt() < cfgI("analysis-interval-ms", 2000)) return;
        data.setLastRoboticEvalAt(now);

        List<Long> times = new ArrayList<>(clicks);
        double bucketMs = cfgD("pattern-bucket-ms", 5.0);
        double cycleToleranceMs = cfgD("cycle-tolerance-ms", 2.0);
        int maxCycleLength = cfgI("cycle-max-length", 6);
        UUID playerId = player.getUniqueId();
        plugin.getAnalysisExecutor().submit(
                () -> ClickPatternAnalyzer.analyze(
                        times, bucketMs, cycleToleranceMs, maxCycleLength),
                stats -> applyRoboticResult(playerId, data, now, stats));
    }

    private void applyRoboticResult(UUID playerId, PlayerData expectedData, long evaluationAt,
                                    ClickPatternAnalyzer.TimingStats stats) {
        if (stats == null) return;
        Player player = plugin.getServer().getPlayer(playerId);
        PlayerData currentData = plugin.getDataManager().getIfPresent(playerId);
        if (player == null || !player.isOnline() || currentData != expectedData
                || currentData.getLastRoboticEvalAt() != evaluationAt) {
            return;
        }
        Deque<Long> clicks = currentData.getClicks();

        if (stats.stddev() < cfgD("robotic-stddev-ms", 1.2)) {
            clicks.clear();
            currentData.resetBuffer(type());
            flag(player, 1.0, String.format("机械节奏 σ=%.2fms @%.0fms", stats.stddev(), stats.mean()));
            return;
        }

        checkRepeatedPattern(player, currentData, clicks, stats);
    }

    private void checkRepeatedPattern(Player player, PlayerData data, Deque<Long> clicks,
                                      ClickPatternAnalyzer.TimingStats stats) {
        double mean = stats.mean();
        if (mean < cfgD("pattern-min-mean-ms", 40.0)
                || mean > cfgD("pattern-max-mean-ms", 125.0)) {
            data.buffer(type(), -0.35);
            return;
        }

        double maxStddev = cfgD("pattern-stddev-ms", 4.5);
        double maxCv = cfgD("pattern-max-cv", 0.055);
        double minDominantRatio = cfgD("pattern-dominant-ratio", 0.78);
        boolean lowDispersionPattern = stats.stddev() <= maxStddev
                && stats.coefficientOfVariation() <= maxCv
                && stats.dominantBucketRatio() >= minDominantRatio;

        double minCycleSimilarity = cfgD("cycle-min-similarity", 0.90);
        boolean repeatedCycle = stats.cycleLength() >= 2
                && stats.cycleSimilarity() >= minCycleSimilarity;
        if (lowDispersionPattern || repeatedCycle) {
            double distributionScore = lowDispersionPattern ? 1.0
                    + Math.max(0.0, stats.dominantBucketRatio() - minDominantRatio) * 2.0
                    + Math.max(0.0, (maxStddev - stats.stddev()) / maxStddev) : 0.0;
            double cycleScore = repeatedCycle ? 1.0
                    + (stats.cycleSimilarity() - minCycleSimilarity)
                    / Math.max(0.01, 1.0 - minCycleSimilarity) : 0.0;
            double stabilityScore = Math.max(distributionScore, cycleScore);
            double buffered = data.buffer(type(), stabilityScore);
            if (buffered >= cfgD("buffer-to-flag", 2.5)) {
                clicks.clear();
                data.resetBuffer(type());
                flag(player, 1.25, String.format(
                        "重复节奏 σ=%.2fms cv=%.3f bucket=%.0f%% cycle=%d/%.0f%% @%.0fms",
                        stats.stddev(), stats.coefficientOfVariation(),
                        stats.dominantBucketRatio() * 100.0, stats.cycleLength(),
                        stats.cycleSimilarity() * 100.0, stats.mean()));
            }
        } else {
            data.buffer(type(), -0.35);
        }
    }

}
