package cn.haitang.anticheat.check.packet;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 非法数据包检测（数据包级）。
 *
 * 拦截原版客户端不可能产生的位置数据：NaN / Infinity / 绝对值超出世界边界
 * 数量级的坐标。此类包多用于崩服或触发异常区块加载，在进入服务端移动
 * 处理之前直接丢弃——确定性证据，无需缓冲。
 *
 * 入口由 {@link cn.haitang.anticheat.packet.PacketBridge} 在 Netty 线程调用：
 * 取消非法包当场完成，违规上报调度回主线程。
 */
public class BadPacketsCheck extends Check {

    /** 原版世界边界 ±29,999,984，超出该数量级的坐标只可能是恶意构造 */
    private static final double MAX_ABS_COORDINATE = 3.0E7;

    /** 配置缓存：Netty 线程读取，reload 时主线程刷新 */
    private volatile boolean packetEnabled;
    private volatile double flagWeight;

    public BadPacketsCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.BAD_PACKETS);
        reloadConfiguration();
    }

    @Override
    public void reloadConfiguration() {
        String base = "checks." + type().configKey() + ".";
        packetEnabled = plugin.getConfig().getBoolean(base + "enabled", true);
        flagWeight = plugin.getConfig().getDouble(base + "flag-weight", 5.0);
    }

    /** Netty 线程入口：非法坐标包当场丢弃，上报走主线程豁免与 VL 流程 */
    public void onPacketPosition(PacketReceiveEvent event, Player player, double x, double y, double z) {
        if (!packetEnabled) return;
        if (!isInvalidCoordinate(x) && !isInvalidCoordinate(y) && !isInvalidCoordinate(z)) return;

        // 无条件丢弃：任何合法客户端（含基岩互通）都不可能发出这样的坐标
        event.setCancelled(true);
        String detail = String.format("坐标 (%.4g, %.4g, %.4g)", x, y, z);
        double weight = flagWeight;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isExempt(player)) return;
            flag(player, weight, detail);
        });
    }

    /** 合法客户端不可能产生的坐标：非有限值，或绝对值超出世界边界数量级 */
    static boolean isInvalidCoordinate(double value) {
        return !Double.isFinite(value) || Math.abs(value) > MAX_ABS_COORDINATE;
    }
}
