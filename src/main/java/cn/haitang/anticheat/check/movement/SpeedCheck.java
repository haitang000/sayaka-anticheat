package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Deque;

/**
 * 水平速度检测。
 *
 * 用 1.5 秒滚动窗口计算持续平均速度（m/s），与动态上限比较：
 *   上限 = max-bps × 速度效果加成 × 冰面/灵魂疾行容差
 * 疾跑跳跃≈7.13 m/s，默认上限 9.2，加速类客户端通常 12+，留有充分安全余量。
 * 击退、传送、鞘翅、激流、液体等场景一律宽限，杜绝误判。
 */
public class SpeedCheck extends Check {

    public SpeedCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.SPEED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || isExempt(player)) return;
        PlayerData data = data(player);

        // ---- 宽限场景 ----
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return;
        if (player.isGliding() || data.glidedWithin(3000)) return;   // 鞘翅本体及落地惯性
        if (player.isRiptiding() || data.riptideWithin(2000)) return;
        if (data.liquidWithin(1500)) return;                          // 海豚恩惠/激流等水中变速
        if (data.teleportedWithin(2000)) return;
        if (data.velocityWithin(2500)) return;                        // TNT/活塞等服务端赋速
        if (data.bouncedWithin(3000)) return;

        double bps = windowBps(data.getSpeedWindow());
        if (bps < 0) return;

        // ---- 动态上限 ----
        double cap = cfgD("max-bps", 9.2);
        int speedLevel = MoveUtil.effectLevel(player, PotionEffectType.SPEED);
        cap *= 1 + cfgD("speed-effect-bonus", 0.25) * speedLevel;
        cap *= pluginSpeedRatio(player, speedLevel);
        if (data.iceWithin(2500)) cap *= cfgD("ice-multiplier", 2.2);
        if (data.soulSandWithin(2000)) cap *= cfgD("soul-speed-multiplier", 1.6);

        if (bps > cap) {
            double over = bps / cap;
            double buffered = data.buffer(type(), over);
            if (buffered >= cfgD("buffer-to-flag", 3.0)) {
                data.resetBuffer(type());
                flag(player, Math.min(over, 3.0),
                        String.format("%.1f m/s > %.1f m/s", bps, cap));
                if (cfgB("setback", true) && shouldMitigate(player)) {
                    setback(event, data);
                }
            }
        } else {
            data.buffer(type(), -0.75);
        }
    }

    /** 滚动窗口的平均水平速度（m/s）；样本不足或时长过短返回 -1 表示本次不判。 */
    static double windowBps(Deque<PlayerData.MoveSample> window) {
        if (window.size() < 4) return -1;
        long duration = window.peekLast().at() - window.peekFirst().at();
        if (duration < 700) return -1;
        double total = 0;
        for (PlayerData.MoveSample sample : window) total += sample.dist();
        return total / duration * 1000.0;
    }

    /**
     * MMO/技能类插件常通过移速属性或 walkSpeed 提供合法加速，按比例放大上限。
     * 属性值里混着原版的疾跑（×1.3）与速度药水修正，先除掉以免与上面的
     * 药水加成重复计算；只放大不缩小（缓慢效果不收紧上限，宁可漏判）。
     */
    private double pluginSpeedRatio(Player player, int speedLevel) {
        double ratio = 1.0;
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (attr != null) {
            double value = attr.getValue() / 0.1; // 原版基准 0.1
            if (player.isSprinting()) value /= 1.3;
            if (speedLevel > 0) value /= 1 + 0.2 * speedLevel;
            ratio = Math.max(ratio, value);
        }
        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed > 0.2f) {
            ratio *= walkSpeed / 0.2f; // setWalkSpeed 与属性独立生效，相乘
        }
        return ratio;
    }

    private void setback(PlayerMoveEvent event, PlayerData data) {
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        // 合法回弹点太远或跨世界时退回本次移动起点
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())
                || target.distanceSquared(from) > 64) {
            target = from;
        }
        data.touchSetback();
        data.getSpeedWindow().clear();
        event.setTo(target);
    }
}
