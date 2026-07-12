package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.packet.PacketBridge;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 非法视角检测。
 *
 * 原版客户端俯仰角严格限制在 ±90°，超出该范围（Derp 挂、部分光环挂的
 * 特征包）或出现非有限值，只可能来自作弊客户端——确定性证据，无需缓冲。
 *
 * 数据包引擎工作时走包级路径：非法视角包在进入服务端实体状态前直接丢弃
 * （事件级只能事后回写视角）；引擎不可用时自动回退到移动事件路径。
 */
public class RotationCheck extends Check {

    /** 配置缓存：包级入口在 Netty 线程读取，reload 时主线程刷新 */
    private volatile boolean packetEnabled;

    public RotationCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.ROTATION);
        reloadConfiguration();
    }

    @Override
    public void reloadConfiguration() {
        packetEnabled = plugin.getConfig().getBoolean("checks." + type().configKey() + ".enabled", true);
    }

    /** Netty 线程入口：非法视角包当场丢弃，上报走主线程豁免与 VL 流程 */
    public void onPacketRotation(PacketReceiveEvent event, Player player, float yaw, float pitch) {
        if (!packetEnabled) return;
        boolean nonFinite = !Float.isFinite(pitch) || !Float.isFinite(yaw);
        if (!nonFinite && Math.abs(pitch) <= 90.1f) return;

        // 无条件丢弃：非有限视角进入 NMS 会污染实体朝向状态
        event.setCancelled(true);
        String detail = nonFinite ? "非有限视角数据" : String.format("俯仰角 %.1f°", pitch);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isExempt(player)) return;
            if (data(player).teleportedWithin(1000)) return;
            flag(player, 3.0, detail);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // 包引擎工作时非法视角包已在协议层被丢弃，事件路径仅作回退
        PacketBridge bridge = plugin.getPacketBridge();
        if (bridge != null && bridge.isActive()) return;

        Location to = event.getTo();
        if (to == null) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        // 服务端设置视角（传送）的回包宽限
        if (data(player).teleportedWithin(1000)) return;

        float pitch = to.getPitch();
        float yaw = to.getYaw();
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) {
            flag(player, 3.0, "非有限视角数据");
            event.setTo(event.getFrom());
            return;
        }
        if (Math.abs(pitch) > 90.1f) {
            flag(player, 3.0, String.format("俯仰角 %.1f°", pitch));
            Location fixed = to.clone();
            fixed.setPitch(Math.max(-90f, Math.min(90f, pitch)));
            event.setTo(fixed);
        }
    }
}
