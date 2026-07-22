package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Deque;
import java.util.List;

/**
 * 鞘翅飞行异常检测（ElytraFly：挂机以恒速平飞/爬升，无视滑翔物理）。
 *
 * 能量守恒判定：滑翔总能量 E = 高度 + 速度²/(2g)。升力只改变速度方向，
 * 阻力只消耗能量，无助推的合法滑翔 E 单调递减——俯冲是高度换速度，
 * 拉起是速度换高度（且换算率受 g 限制，40 m/s 拉起 20 格后 ΔE 仍为负）。
 * 烟花助推/激流/服务端赋速/弹跳/液体/悬浮等一切外来能量源都在宽限内
 * 清空采样窗口，窗口内能量净增只能来自客户端凭空注入速度或高度。
 *
 * Speed 与 Flight 对滑翔状态整体豁免，本检测是鞘翅期间唯一的物理约束。
 */
public class ElytraCheck extends Check {

    /** 原版重力加速度 ≈ 0.08 格/tick² = 32 格/秒²；升力不产生能量，此换算率是合法上界 */
    static final double GRAVITY_BPS2 = 32.0;

    public ElytraCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.ELYTRA);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoost(PlayerElytraBoostEvent event) {
        data(event.getPlayer()).touchElytraBoost();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || MovementTracker.isTeleport(event)) return;
        PlayerData data = data(player);
        Deque<PlayerData.GlideSample> samples = data.getGlideSamples();

        if (isExempt(player) || !player.isGliding()) {
            samples.clear();
            data.resetBuffer(type());
            return;
        }
        // 外来能量源与异常介质：本窗口不可判，重新累积
        if (data.teleportedWithin(2000) || data.velocityWithin(2000)
                || data.riptideWithin(3000) || data.bouncedWithin(3000)
                || data.levitationWithin(2000) || data.liquidWithin(1500)
                || data.elytraBoostWithin(cfgI("boost-grace-ms", 4000))
                || player.isInsideVehicle()) {
            samples.clear();
            return;
        }

        long now = System.currentTimeMillis();
        PlayerData.GlideSample last = samples.peekLast();
        // 采样断流（掉包/卡顿/世界切换）后能量不可比
        if (last != null && now - last.at() > cfgI("sample-gap-reset-ms", 300)) {
            samples.clear();
        }
        samples.addLast(new PlayerData.GlideSample(now, to.getX(), to.getY(), to.getZ()));

        long windowMs = Math.max(500, cfgI("window-ms", 2000));
        PlayerData.GlideSample first = samples.peekFirst();
        int minSamples = Math.max(6, cfgI("min-samples", 8));
        if (first == null || samples.size() < minSamples
                || now - first.at() < Math.max(500, cfgI("min-window-ms", 1500))) {
            while (!samples.isEmpty() && now - samples.peekFirst().at() > windowMs) {
                samples.removeFirst();
            }
            return;
        }

        Double gain = energyGain(List.copyOf(samples));
        samples.clear();
        if (gain == null) return;

        double maxGain = cfgD("max-energy-gain", 2.5);
        if (gain > maxGain) {
            double buffered = data.buffer(type(), 1.0);
            if (buffered >= cfgD("buffer-to-flag", 2.0)) {
                data.resetBuffer(type());
                flag(player, 2.0, String.format("滑翔能量凭空 +%.1f 格（上限 %.1f）", gain, maxGain));
                if (cfgB("setback", true) && allowsMitigation(player)) {
                    setback(event, data);
                }
            }
        } else {
            data.buffer(type(), -0.5);
        }
    }

    /**
     * 窗口首尾的滑翔总能量差（格）。速度各用相邻三个采样跨度测量以压低
     * 时间戳抖动；样本不足或时间跨度异常时返回 {@code null} 不判。
     */
    static Double energyGain(List<PlayerData.GlideSample> samples) {
        if (samples.size() < 6) return null;
        double startSpeed = segmentSpeed(samples.get(0), samples.get(2));
        int n = samples.size();
        double endSpeed = segmentSpeed(samples.get(n - 3), samples.get(n - 1));
        if (Double.isNaN(startSpeed) || Double.isNaN(endSpeed)) return null;

        double startEnergy = samples.get(2).y() + startSpeed * startSpeed / (2 * GRAVITY_BPS2);
        double endEnergy = samples.get(n - 1).y() + endSpeed * endSpeed / (2 * GRAVITY_BPS2);
        return endEnergy - startEnergy;
    }

    /** 两个采样间的平均速度（格/秒）；时间跨度非正或过短返回 NaN */
    private static double segmentSpeed(PlayerData.GlideSample from, PlayerData.GlideSample to) {
        long dtMs = to.at() - from.at();
        if (dtMs < 20) return Double.NaN;
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) / dtMs * 1000.0;
    }

    private void setback(PlayerMoveEvent event, PlayerData data) {
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())) {
            target = from;
        }
        data.touchSetback();
        data.resetBuffer(type());
        data.getGlideSamples().clear();
        data.getSpeedWindow().clear();
        data.resetAirborneState(target.getY());
        event.setTo(target.clone());
    }
}
