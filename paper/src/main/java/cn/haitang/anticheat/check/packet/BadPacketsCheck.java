package cn.haitang.anticheat.check.packet;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByteArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTLongArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 非法数据包检测（数据包级）。
 *
 * 拦截原版客户端不可能产生的数据：
 * - 位置包：NaN / Infinity / 绝对值超出世界边界数量级的坐标，
 *   多用于崩服或触发异常区块加载；
 * - 攻击包：目标为自己实体 id 的自击包（原版射线不可能选中自己），
 *   多用于触发插件异常或伤害结算漏洞；
 * - 物品包：创造动作/放置包里的物品数量超限、NBT 深度或体积超限
 *   （NBT 炸弹：深度或体积达到崩溃数量级的嵌套结构）。
 * 均在进入服务端处理之前直接丢弃——确定性证据，无需缓冲。
 *
 * 入口由 {@link cn.haitang.anticheat.packet.PacketBridge} 与
 * {@link cn.haitang.anticheat.packet.PacketTimeline} 在 Netty 线程调用：
 * 取消非法包当场完成，违规上报调度回主线程。
 */
public class BadPacketsCheck extends Check {

    /** 原版世界边界 ±29,999,984，超出该数量级的坐标只可能是恶意构造 */
    private static final double MAX_ABS_COORDINATE = 3.0E7;

    /** 配置缓存：Netty 线程读取，reload 时主线程刷新 */
    private volatile boolean packetEnabled;
    private volatile double flagWeight;
    private volatile boolean itemEnabled;
    private volatile int maxNbtDepth;
    private volatile int maxNbtSize;
    private volatile int maxStackSize;
    private volatile double itemFlagWeight;

    public BadPacketsCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.BAD_PACKETS);
        reloadConfiguration();
        if (plugin.getPacketTimeline() != null) {
            plugin.getPacketTimeline().setSelfAttackHandler(this::onSelfAttack);
        }
    }

    @Override
    public void reloadConfiguration() {
        String base = "checks." + type().configKey() + ".";
        packetEnabled = plugin.getConfig().getBoolean(base + "enabled", true);
        flagWeight = plugin.getConfig().getDouble(base + "flag-weight", 5.0);
        itemEnabled = plugin.getConfig().getBoolean(base + "check-items", true);
        maxNbtDepth = Math.max(1, plugin.getConfig().getInt(base + "max-nbt-depth", 512));
        maxNbtSize = Math.max(1024, plugin.getConfig().getInt(base + "max-nbt-size", 262144));
        maxStackSize = Math.max(1, plugin.getConfig().getInt(base + "max-stack-size", 64));
        itemFlagWeight = plugin.getConfig().getDouble(base + "item-flag-weight", 5.0);
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

    /**
     * Netty 线程入口：物品包（创造物品 / 放置物品）携带非法物品数据时当场丢弃。
     *
     * @param source 数据来源描述，出现在证据摘要中
     */
    public void onItemPacket(PacketReceiveEvent event, Player player, ItemStack item, String source) {
        if (!packetEnabled || !itemEnabled) return;
        if (item == null || item.isEmpty()) return;
        String violation = inspectItem(item, maxStackSize, maxNbtDepth, maxNbtSize);
        if (violation == null) return;

        // 确定性非法：合法客户端（含基岩互通）发不出超限数量/深度/体积的物品数据
        event.setCancelled(true);
        String detail = source + ": " + violation;
        double weight = itemFlagWeight;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isExempt(player)) return;
            flag(player, weight, detail);
        });
    }

    /** Netty 线程入口：返回 true 让 PacketTimeline 丢弃自击包，上报走主线程 */
    public boolean onSelfAttack(Player player, int entityId) {
        if (!packetEnabled) return false;
        String detail = String.format("攻击自己的实体 id=%d", entityId);
        double weight = flagWeight;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isExempt(player)) return;
            flag(player, weight, detail);
        });
        return true;
    }

    /** 合法客户端不可能产生的坐标：非有限值，或绝对值超出世界边界数量级 */
    static boolean isInvalidCoordinate(double value) {
        return !Double.isFinite(value) || Math.abs(value) > MAX_ABS_COORDINATE;
    }

    /**
     * 校验物品数据是否在原版可能范围内；返回违规描述，合法返回 {@code null}。
     * 纯结构判断，不访问 Bukkit 状态，可在 Netty 线程执行。
     */
    static String inspectItem(ItemStack item, int maxStackSize, int maxNbtDepth, int maxNbtSize) {
        return inspectItem(item.getAmount(), item.getNBT(),
                maxStackSize, maxNbtDepth, maxNbtSize);
    }

    /** 纯判定（供单元测试直接调用）：数量与 NBT 结构超出原版范围即违规。 */
    static String inspectItem(int amount, NBTCompound nbt,
                              int maxStackSize, int maxNbtDepth, int maxNbtSize) {
        if (amount > maxStackSize) {
            return String.format("数量 %d 超过上限 %d", amount, maxStackSize);
        }
        if (nbt == null || nbt.isEmpty()) return null;
        int depth = estimateNbtDepth(nbt);
        if (depth > maxNbtDepth) {
            return String.format("NBT 深度 %d 超过上限 %d", depth, maxNbtDepth);
        }
        int size = estimateNbtSize(nbt);
        if (size > maxNbtSize) {
            return String.format("NBT 体积 %d 超过上限 %d", size, maxNbtSize);
        }
        return null;
    }

    /** NBT 树的最大嵌套深度（根为 1）。 */
    static int estimateNbtDepth(NBT nbt) {
        int deepest = 1;
        if (nbt instanceof NBTCompound compound) {
            for (NBT tag : compound.getTags().values()) {
                deepest = Math.max(deepest, 1 + estimateNbtDepth(tag));
            }
        } else if (nbt instanceof NBTList<?> list) {
            for (NBT tag : list.getTags()) {
                deepest = Math.max(deepest, 1 + estimateNbtDepth(tag));
            }
        }
        return deepest;
    }

    /**
     * NBT 树体积的粗略估算（序列化文本字节量级），仅用于拦截体积爆炸级数据。
     * 原版物品（含 100 页成书）通常远低于 256KB，体积炸弹常在 MB 级。
     */
    static int estimateNbtSize(NBT nbt) {
        if (nbt instanceof NBTCompound compound) {
            int size = 2;
            for (var entry : compound.getTags().entrySet()) {
                size += entry.getKey().length() + 4 + estimateNbtSize(entry.getValue());
            }
            return size;
        }
        if (nbt instanceof NBTList<?> list) {
            int size = 3;
            for (NBT tag : list.getTags()) {
                size += estimateNbtSize(tag) + 1;
            }
            return size;
        }
        if (nbt instanceof NBTString string) return string.getValue().length() + 2;
        if (nbt instanceof NBTNumber) return 8;
        if (nbt instanceof NBTByteArray array) return array.getValue().length + 1;
        if (nbt instanceof NBTIntArray array) return array.getValue().length * 4 + 1;
        if (nbt instanceof NBTLongArray array) return array.getValue().length * 8 + 1;
        return 4;
    }
}
