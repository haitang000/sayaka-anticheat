package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.packet.PacketTimeline;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.Deque;

/**
 * 移动包速率检测（Timer 加速器：客户端加快游戏时钟，所有包按倍速发出）。
 *
 * PacketEvents 路径按客户端移动包的纳秒时钟维护余额，并在主线程结合 TPS、
 * 传送和载具状态决定是否采信。下方 Bukkit 事件窗口仅保留为无数据包源时的
 * 防御性回退，不参与 2.0 正常运行路径。
 */
public class TimerCheck extends Check {

    /** 滚动窗口长度（毫秒） */
    private static final long WINDOW_MS = 3000;

    public TimerCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.TIMER);
        if (plugin.getPacketTimeline() != null) {
            plugin.getPacketTimeline().setTimerConsumer(this::onPacketEvidence);
            plugin.getPacketTimeline().setBlinkConsumer(this::onBlinkEvidence);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (plugin.getPacketTimeline() != null) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        PlayerData data = data(player);

        // 载具内玩家的移动事件由服务端驱动，频率与客户端发包无关
        if (player.isInsideVehicle()) {
            data.getMoveTimes().clear();
            return;
        }
        if (data.teleportedWithin(1000)) return;
        if (isServerLagging(data)) return;

        long now = System.currentTimeMillis();
        var times = data.getMoveTimes();
        int size = recordMove(times, now, cfgI("min-gap-ms", 25));
        if (size < 0) return;

        int maxRate = cfgI("max-packets-per-second", 24);
        if (size > maxRate * WINDOW_MS / 1000) {
            times.clear();
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, String.format("3秒 %d 个移动包 (≈%.1f/秒)", size, size / 3.0));
            }
        } else if (now - times.peekFirst() >= WINDOW_MS - 100) {
            // 窗口已充满且未超标：缓慢洗掉偶发尖峰攒下的缓冲
            data.buffer(type(), -0.05);
        }
    }

    private void onPacketEvidence(Player player, PacketTimeline.TimerEvidence evidence) {
        PlayerData data = data(player);
        if (isExempt(player) || player.isInsideVehicle() || data.teleportedWithin(1_000)) {
            plugin.getPacketTimeline().resetTimer(player.getUniqueId());
            return;
        }
        if (evidence.ratePerSecond() <= cfgD("max-packets-per-second", 24.0)) {
            data.buffer(type(), -0.25);
            plugin.getPacketTimeline().resetTimer(player.getUniqueId());
            return;
        }
        double buffered = data.buffer(type(), 1.0);
        if (buffered >= cfgD("buffer-to-flag", 2.0)) {
            data.resetBuffer(type());
            flag(player, 2.0, String.format(
                    "数据包时钟 %.1f/秒，余额 %.0fms（%d 包）",
                    evidence.ratePerSecond(), evidence.balanceMillis(), evidence.packets()));
        }
    }

    /**
     * Blink/掉包器（憋移动包后一次性放出）：平均包速率不变，余额时钟看不到。
     * 包级模型只统计"暂停期间事务 Pong 仍正常返回"的憋停-爆发循环——
     * 真实网络抖动会把移动包和 Pong 一起拖住，客户端冻结时 Pong 同样停发。
     * 多次循环才生成一次管理员辅助警报，不计 VL。
     */
    private void onBlinkEvidence(Player player, PacketTimeline.BlinkEvidence evidence) {
        if (!cfgB("blink.enabled", true)) return;
        if (isExempt(player) || player.isInsideVehicle()) return;
        PlayerData data = data(player);
        if (data.teleportedWithin(1_000)) return;
        observe(player, String.format(
                "憋包-爆发 %d 次循环（暂停≈%dms 后爆发 %d 包，Pong 期间正常，仅辅助证据）",
                evidence.cycles(), evidence.pauseMillis(), evidence.burstPackets()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            plugin.getPacketTimeline().resetTimer(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            plugin.getPacketTimeline().resetTimer(player.getUniqueId());
        }
    }

    /**
     * 记录一次移动包并修剪滚动窗口，返回窗口内的包数。
     * 与上一包间隔不足 minGapMs 的视为服务端卡顿恢复时集中处理的积压包，
     * 不计入窗口并返回 -1（宽松方向：真实 Timer 的包间隔均匀，不受去重影响）。
     */
    static int recordMove(Deque<Long> times, long now, long minGapMs) {
        if (!times.isEmpty() && now - times.peekLast() < minGapMs) return -1;
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) times.removeFirst();
        return times.size();
    }

    private boolean isServerLagging(PlayerData data) {
        double minTps = cfgD("min-tps", 18.0);
        if (minTps <= 0) return false;
        double[] tps = plugin.getServer().getTPS();
        double recentTps = tps.length == 0 ? 20.0 : tps[0];
        if (recentTps >= minTps) return false;

        data.getMoveTimes().clear();
        data.buffer(type(), -1.0);
        return true;
    }
}
