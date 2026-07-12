package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Deque;

/**
 * 移动包速率检测（Timer 加速器：客户端加快游戏时钟，所有包按倍速发出）。
 *
 * 正常客户端每秒发送 20 个移动包。统计 3 秒滚动窗口内的移动事件数，
 * 持续超过上限即违规。服务端卡顿恢复时会把积压的包集中处理，
 * 这批包的到达时间几乎相同——用最小间隔去重把尖峰压平（宽松方向），
 * 而真实 Timer 的包间隔均匀，不受去重影响。
 */
public class TimerCheck extends Check {

    /** 滚动窗口长度（毫秒） */
    private static final long WINDOW_MS = 3000;

    public TimerCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.TIMER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
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
