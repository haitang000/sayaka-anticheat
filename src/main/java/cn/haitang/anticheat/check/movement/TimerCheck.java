package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.packet.PacketBridge;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Deque;
import java.util.Queue;

/**
 * 移动包速率检测（Timer 加速器：客户端加快游戏时钟，所有包按倍速发出）。
 *
 * 正常客户端每秒发送 20 个移动包。统计 3 秒滚动窗口内的移动包数，
 * 持续超过上限即违规。
 *
 * 数据包引擎工作时以 Netty 线程记录的真实到达时间为准（每 tick 由主线程
 * 批量评估）：不受服务端 tick 合并影响，静止玩家的 idle 包同样可见，
 * 无需去重即可覆盖任意倍速。引擎不可用时回退到移动事件路径——事件在
 * 服务端卡顿恢复时会把积压的包集中触发，这批事件的时间几乎相同，
 * 用最小间隔去重把尖峰压平（宽松方向），代价是高倍速 Timer 也会被
 * 去重掩盖，只能兜底低倍速。
 */
public class TimerCheck extends Check {

    /** 滚动窗口长度（毫秒） */
    private static final long WINDOW_MS = 3000;

    private BukkitTask packetDrainTask;

    public TimerCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.TIMER);
    }

    /** 启动包级采样任务：每 tick 把 Netty 线程记录的包到达时间喂入滚动窗口 */
    public void start() {
        packetDrainTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::drainPacketArrivals, 1L, 1L);
    }

    public void shutdown() {
        if (packetDrainTask != null) packetDrainTask.cancel();
    }

    private void drainPacketArrivals() {
        PacketBridge bridge = plugin.getPacketBridge();
        if (bridge == null || !bridge.isActive()) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData data = plugin.getDataManager().getIfPresent(player.getUniqueId());
            if (data == null) continue;
            Queue<Long> arrivals = data.getPacketArrivals();
            if (arrivals.isEmpty()) continue;

            // 载具内客户端发包节奏不同；豁免期间队列也必须清空，防止积压
            boolean skip = isExempt(player) || player.isInsideVehicle()
                    || data.teleportedWithin(1000) || isServerLagging(data);
            Long arrival;
            while ((arrival = arrivals.poll()) != null) {
                // 包到达时间由 Netty 线程记录，真实反映客户端发包节奏，无需去重
                if (!skip) evaluate(player, data, arrival, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // 包引擎工作时由包级路径接管
        PacketBridge bridge = plugin.getPacketBridge();
        if (bridge != null && bridge.isActive()) return;

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

        evaluate(player, data, System.currentTimeMillis(), cfgI("min-gap-ms", 25));
    }

    /** 两条路径共用的判定：记录一次移动包，窗口超标则累积缓冲并上报 */
    private void evaluate(Player player, PlayerData data, long now, long minGapMs) {
        var times = data.getMoveTimes();
        int size = recordMove(times, now, minGapMs);
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

    /**
     * 记录一次移动包并修剪滚动窗口，返回窗口内的包数。
     * 与上一包间隔不足 minGapMs 的视为服务端卡顿恢复时集中处理的积压包，
     * 不计入窗口并返回 -1（宽松方向：真实 Timer 的包间隔均匀，不受去重影响）；
     * minGapMs 为 0 时不去重（包级路径的到达时间真实，网络突发簇不改变窗口总量）。
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
