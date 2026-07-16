package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
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

    private static final long NOMINAL_MOVE_INTERVAL_MS = 50L;

    public SpeedCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.SPEED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null) return;
        PlayerData data = data(player);

        if (isExempt(player) || isMovementExempt(player, data)) {
            resetEvidence(data);
            return;
        }
        Location from = event.getFrom();
        if (!MovementTracker.isPositionChange(to.getX() - from.getX(),
                to.getY() - from.getY(), to.getZ() - from.getZ())) return;

        int speedLevel = MoveUtil.effectLevel(player, PotionEffectType.SPEED);
        double multiplier = dynamicCapMultiplier(player, data, speedLevel);
        long burstWindowMs = cfgI("burst-window-ms", 350);
        long sustainedWindowMs = cfgI("sustained-window-ms", 1200);
        SpeedEvidence burst = evidence("短时",
                windowBps(data.getSpeedWindow(), burstWindowMs),
                cfgD("burst-max-bps", 12.0) * multiplier);
        SpeedEvidence sustained = evidence("持续",
                windowBps(data.getSpeedWindow(), sustainedWindowMs),
                cfgD("sustained-max-bps", cfgD("max-bps", 9.2)) * multiplier);
        SpeedEvidence suspicious = stronger(burst, sustained);

        if (suspicious != null) {
            double buffered = data.buffer(type(), suspicious.ratio());
            if (buffered >= cfgD("buffer-to-flag", 3.0)) {
                data.resetBuffer(type());
                flag(player, Math.min(suspicious.ratio(), 3.0),
                        String.format("%s %.1f m/s > %.1f m/s",
                                suspicious.label(), suspicious.bps(), suspicious.cap()));
                if (cfgB("setback", true) && allowsMitigation(player)) {
                    setback(event, data);
                }
            }
        } else {
            data.buffer(type(), -0.75);
        }
    }

    /** Calculates trailing-window speed; the first sample is only the interval anchor. */
    static double windowBps(Deque<PlayerData.MoveSample> samples, long windowMs) {
        if (samples.size() < 4 || windowMs <= 0) return -1;
        PlayerData.MoveSample latest = samples.peekLast();
        if (latest == null) return -1;

        long cutoff = latest.at() - windowMs;
        PlayerData.MoveSample anchor = null;
        int intervals = 0;
        double distance = 0;
        for (PlayerData.MoveSample sample : samples) {
            if (sample.at() < cutoff) continue;
            if (anchor == null) {
                anchor = sample;
                continue;
            }
            distance += sample.dist();
            intervals++;
        }
        if (anchor == null || intervals < 3) return -1;

        long elapsedMs = latest.at() - anchor.at();
        long minimumSpanMs = Math.max(100L, Math.round(windowMs * 0.8));
        if (elapsedMs < minimumSpanMs) return -1;
        long effectiveMs = Math.max(elapsedMs, intervals * NOMINAL_MOVE_INTERVAL_MS);
        return distance / effectiveMs * 1000.0;
    }

    private static SpeedEvidence evidence(String label, double bps, double cap) {
        if (bps < 0 || cap <= 0 || bps <= cap) return null;
        return new SpeedEvidence(label, bps, cap, bps / cap);
    }

    private static SpeedEvidence stronger(SpeedEvidence first, SpeedEvidence second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.ratio() >= second.ratio() ? first : second;
    }

    private record SpeedEvidence(String label, double bps, double cap, double ratio) { }

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

    private double dynamicCapMultiplier(Player player, PlayerData data, int speedLevel) {
        double multiplier = 1 + cfgD("speed-effect-bonus", 0.25) * speedLevel;
        multiplier *= pluginSpeedRatio(player, speedLevel);
        if (data.iceWithin(2500)) multiplier *= cfgD("ice-multiplier", 2.2);
        if (data.soulSandWithin(2000)) multiplier *= cfgD("soul-speed-multiplier", 1.6);
        return multiplier;
    }

    private boolean isMovementExempt(Player player, PlayerData data) {
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding() || data.glidedWithin(3000)) return true;
        if (player.isRiptiding() || data.riptideWithin(2000)) return true;
        if (data.liquidWithin(1500) || data.teleportedWithin(2000)) return true;
        return data.velocityWithin(2500) || data.bouncedWithin(3000);
    }

    private void resetEvidence(PlayerData data) {
        data.resetBuffer(type());
        data.getSpeedWindow().clear();
    }

    private void setback(PlayerMoveEvent event, PlayerData data) {
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())) {
            target = from;
        }
        data.touchSetback();
        resetEvidence(data);
        data.resetAirborneState(target.getY());
        event.setTo(target.clone());
    }
}
