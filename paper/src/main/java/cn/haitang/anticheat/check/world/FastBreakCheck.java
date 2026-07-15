package cn.haitang.anticheat.check.world;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;

/**
 * 快速挖掘检测（FastBreak / Nuker）。
 *
 * 客户端开始挖掘（BlockDamageEvent）时用 Paper 的 Block#getBreakSpeed
 * 计算理论耗时（已含工具/效率/急迫/疲劳/水下等全部修正），
 * 破坏完成时对比实际耗时：明显快于理论值即违规。
 * 可瞬间破坏的方块（getBreakSpeed ≥ 1）不参与判定。
 */
public class FastBreakCheck extends Check {

    public FastBreakCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.FAST_BREAK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigStart(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (event.getInstaBreak()) return;

        Block block = event.getBlock();
        float breakSpeed = block.getBreakSpeed(player);
        if (breakSpeed >= 1.0f || breakSpeed <= 0.0f) {
            data(player).clearDig();
            return;
        }
        int expectedTicks = (int) Math.ceil(1.0 / breakSpeed);
        data(player).startDig(key(block), expectedTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigAbort(BlockDamageAbortEvent event) {
        data(event.getPlayer()).clearDig();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = data(player);
        Block block = event.getBlock();

        String digKey = data.getDigKey();
        if (digKey != null && digKey.equals(key(block))) {
            long elapsedMs = System.currentTimeMillis() - data.getDigStartAt();
            long elapsedTicks = elapsedMs / 50;
            int expectedTicks = data.getDigExpectedTicks();
            // 宽限：网络延迟折算 + lenience 比例 + 2 tick 固定余量
            long pingTicks = Math.min(player.getPing(), 300) / 50;
            double minTicks = expectedTicks * cfgD("lenience", 0.6) - 2 - pingTicks;
            data.clearDig();

            if (elapsedTicks < minTicks) {
                flag(player, 2.0, String.format("%d刻完成 %d刻的挖掘",
                        elapsedTicks, expectedTicks));
                if (cfgB("cancel", true) && shouldMitigate(player)) {
                    event.setCancelled(true);
                }
            }
            return;
        }

        // 无起始包直接破坏（Nuker 特征）。连锁挖掘类插件会误报，默认关闭
        if (cfgB("nuker-detect", false)) {
            float breakSpeed = block.getBreakSpeed(player);
            if (breakSpeed < 1.0f && breakSpeed > 0.0f) {
                long now = System.currentTimeMillis();
                var breaks = data.getNoDigBreaks();
                breaks.addLast(now);
                while (!breaks.isEmpty() && now - breaks.peekFirst() > 5000) breaks.removeFirst();
                if (breaks.size() >= 4) {
                    breaks.clear();
                    flag(player, 2.0, "无挖掘过程直接破坏");
                    if (cfgB("cancel", true) && shouldMitigate(player)) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    private static String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
}
