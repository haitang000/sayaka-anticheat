package cn.haitang.anticheat.packet;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.movement.RotationCheck;
import cn.haitang.anticheat.check.packet.BadPacketsCheck;
import cn.haitang.anticheat.data.PlayerData;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.entity.Player;

/**
 * 数据包引擎桥接层：把 PacketEvents 的入站移动包分发给包级检测。
 *
 * {@link #onPacketReceive} 在 Netty 线程执行，这里只做三件事：
 * 向线程安全队列记录包到达时间（Timer 数据源）、纯数学的非法值判断、
 * 取消非法包。任何 Bukkit 状态读取与违规上报都由各检测调度回主线程，
 * {@link PlayerData} 的其余字段不在这条线程上访问。
 */
public class PacketBridge extends PacketListenerAbstract {

    private final AntiCheatPlugin plugin;
    private final RotationCheck rotationCheck;
    private final BadPacketsCheck badPacketsCheck;

    /** config packet-engine.enabled 的缓存（Netty 线程读取，reload 时刷新） */
    private volatile boolean enabled;
    /** PacketEvents 注入成功后由主类置位 */
    private volatile boolean engineRunning;

    public PacketBridge(AntiCheatPlugin plugin,
                        RotationCheck rotationCheck, BadPacketsCheck badPacketsCheck) {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
        this.rotationCheck = rotationCheck;
        this.badPacketsCheck = badPacketsCheck;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("packet-engine.enabled", true);
    }

    public void setEngineRunning(boolean running) {
        this.engineRunning = running;
    }

    /** 包引擎是否正在工作；false 时 Timer/Rotation 自动回退到 Bukkit 事件级路径 */
    public boolean isActive() {
        return engineRunning && enabled;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        // 尚未建档（join 事件还没处理）的连接直接忽略，不在 Netty 线程创建数据
        PlayerData data = plugin.getDataManager().getIfPresent(player.getUniqueId());
        if (data == null) return;

        data.getPacketArrivals().add(System.currentTimeMillis());

        // 纯 idle 包不含位置与视角，无需解析包体
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING) return;

        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        Location location = wrapper.getLocation();
        if (wrapper.hasRotationChanged()) {
            rotationCheck.onPacketRotation(event, player, location.getYaw(), location.getPitch());
        }
        if (wrapper.hasPositionChanged()) {
            badPacketsCheck.onPacketPosition(event, player,
                    location.getX(), location.getY(), location.getZ());
        }
    }
}
