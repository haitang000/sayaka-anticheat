package cn.haitang.anticheat.check.player;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

/**
 * 使用物品移速检测（NoSlow）。
 *
 * 原版客户端在吃喝、拉弓/弩、举盾时会显著降速。玩家已经持续使用核心
 * 物品一小段时间后，若移动窗口内仍保持过高水平速度则累计 VL。
 * 样本只取地面移动（空中惯性衰减慢，跳跃/坠落中使用物品不参与判定），
 * 证据成立时按移动类通例回弹到最后合法落点。
 */
public class NoSlowCheck extends Check {

    public NoSlowCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.NO_SLOW);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || isExempt(player)) return;
        if (!player.hasActiveItem()) return;

        ItemStack active = player.getActiveItem();
        if (!isTrackedUseItem(active)) return;

        PlayerData data = data(player);
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return;
        if (player.isGliding() || data.glidedWithin(3000)) return;
        if (player.isRiptiding() || data.riptideWithin(2000)) return;
        if (data.teleportedWithin(2000) || data.velocityWithin(2500)) return;
        if (data.liquidWithin(1500) || data.iceWithin(2500) || data.bouncedWithin(2500)) return;
        if (data.climbedWithin(1000) || data.isInWeb() || data.isNearHoney()) return;

        int usedTicks = player.getActiveItemUsedTime();
        if (usedTicks < cfgI("min-use-ticks", 4)) return;

        long now = System.currentTimeMillis();
        long useStart = now - usedTicks * 50L;
        long graceEnd = useStart + cfgI("grace-ms", 300);
        if (now < graceEnd) return;

        double total = 0;
        long first = 0;
        long last = 0;
        for (PlayerData.MoveSample sample : data.getSpeedWindow()) {
            if (sample.at() < graceEnd) continue;
            // 空中样本一律跳过：起跳/坠落前的水平惯性按空气阻力（0.91/tick）衰减，
            // 远慢于地面摩擦，可在起始宽限之后仍高于减速上限。跳跃或坠落中喝药水
            // 因此会被误判，而地面上的 NoSlow（惯性被摩擦迅速吸收）仍照常判定。
            if (!sample.grounded()) continue;
            if (first == 0) first = sample.at();
            last = sample.at();
            total += sample.dist();
        }

        if (first == 0 || last <= first) return;
        long duration = last - first;
        if (duration < cfgI("min-sample-ms", 500)) return;

        double bps = total / duration * 1000.0;
        double cap = movementCap(player);
        if (bps > cap) {
            double over = bps / cap;
            double buffered = data.buffer(type(), over);
            if (buffered >= cfgD("buffer-to-flag", 3.0)) {
                data.resetBuffer(type());
                flag(player, Math.min(over, 2.5),
                        String.format("%s 使用中 %.1f m/s > %.1f m/s",
                                active.getType().name(), bps, cap));
                if (cfgB("setback", true) && allowsMitigation(player)) {
                    setback(event, data);
                }
            }
        } else {
            data.buffer(type(), -0.75);
        }
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
        data.getSpeedWindow().clear();
        data.resetAirborneState(target.getY());
        event.setTo(target.clone());
    }

    private boolean isTrackedUseItem(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Material type = item.getType();
        return type.isEdible()
                || type == Material.POTION
                || type == Material.MILK_BUCKET
                || type == Material.BOW
                || type == Material.CROSSBOW
                || type == Material.SHIELD;
    }

    private double movementCap(Player player) {
        double cap = cfgD("max-using-bps", 3.2);
        int speedLevel = MoveUtil.effectLevel(player, PotionEffectType.SPEED);
        cap *= 1 + cfgD("speed-effect-bonus", 0.25) * speedLevel;
        cap *= pluginSpeedRatio(player, speedLevel);
        return cap;
    }

    private double pluginSpeedRatio(Player player, int speedLevel) {
        double ratio = 1.0;
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (attr != null) {
            double value = attr.getValue() / 0.1;
            if (player.isSprinting()) value /= 1.3;
            if (speedLevel > 0) value /= 1 + 0.2 * speedLevel;
            ratio = Math.max(ratio, value);
        }
        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed > 0.2f) {
            ratio *= walkSpeed / 0.2f;
        }
        return ratio;
    }
}
