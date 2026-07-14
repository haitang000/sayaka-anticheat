package cn.haitang.anticheat.check;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * 在移动检测执行前（LOWEST）维护共享移动状态：滞空计数、悬浮计数、
 * 速度采样窗口、地表材质宽限（冰/灵魂沙/弹跳方块/液体）等；
 * 在所有检测执行后（MONITOR）更新"最后合法位置"，供回弹传送使用。
 */
public class MovementTracker implements Listener {

    /** 判定"有支撑"时向下扫描的深度 */
    private static final double GROUND_DEPTH = 0.08;

    private final AntiCheatPlugin plugin;

    public MovementTracker(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMoveEarly(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        PlayerData data = plugin.getDataManager().get(player);

        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            data.resetMovement(to);
            return;
        }
        if (player.isInsideVehicle()) {
            // 载具移动物理完全不同，清空状态避免下船瞬间误判
            data.resetMovement(to);
            return;
        }

        long now = System.currentTimeMillis();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        data.setLastDeltaXZ(distXZ);
        data.setLastDeltaY(dy);
        data.consumeImpulse(new org.bukkit.util.Vector(dx, dy, dz));

        // 仅转动视角不算位移：不刷新 lastMoveAt，否则悬浮者环顾四周即可绕过静止悬浮扫描
        boolean rotationOnly = distXZ < 1e-7 && Math.abs(dy) < 1e-7;
        if (rotationOnly) return;
        data.setLastMovementDelta(new org.bukkit.util.Vector(dx, dy, dz));

        // ---- 滞空 / 悬浮计数 ----
        boolean collision = MoveUtil.hasCollisionBelow(to, GROUND_DEPTH);
        boolean serverLaunchEnded = data.updateServerLaunch(collision, now);
        if (serverLaunchEnded) {
            // A timed-out launch may still be airborne. Restart Flight from the current position
            // instead of comparing it with the original pressure plate height.
            data.setAirTicks(0);
            data.setHoverTicks(0);
            data.setAirStartY(to.getY());
        }
        data.setCollisionBelow(collision);
        if (collision) {
            data.setSupportedTicks(data.getSupportedTicks() + 1);
            data.setAirTicks(0);
            data.setHoverTicks(0);
            data.setAirStartY(to.getY());
        } else {
            data.setSupportedTicks(0);
            data.setAirTicks(data.getAirTicks() + 1);
            double hoverMaxDy = plugin.config().getDouble("checks.flight.hover-max-dy", 0.06);
            if (Math.abs(dy) <= hoverMaxDy) {
                data.setHoverTicks(data.getHoverTicks() + 1);
            } else {
                data.setHoverTicks(0);
            }
        }

        // ---- 地表材质宽限采样 ----
        Material below = to.clone().subtract(0, 0.3, 0).getBlock().getType();
        String belowName = below.name();
        if (belowName.endsWith("ICE")) data.touchIce();
        if (below == Material.SOUL_SAND || below == Material.SOUL_SOIL) data.touchSoulSand();
        if (below == Material.SLIME_BLOCK || Tag.BEDS.isTagged(below)) data.touchBounce();

        if (MoveUtil.touchingLiquid(player)) data.touchLiquid();
        if (MoveUtil.isClimbing(player)) data.touchClimb();
        if (player.isGliding()) data.touchGlide();
        if (player.isRiptiding()) data.touchRiptide();
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) data.touchLevitation();
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) data.touchSlowFall();
        data.setInWeb(MoveUtil.isInWeb(player));
        data.setNearHoney(MoveUtil.isNearHoney(to));

        // ---- 速度采样窗口（1.5 秒滚动） ----
        var window = data.getSpeedWindow();
        if (data.hasActiveServerLaunch() || serverLaunchEnded) {
            // Do not let legal launch displacement poison the first Speed window after landing.
            window.clear();
        } else {
            window.addLast(new PlayerData.MoveSample(now, distXZ));
            while (!window.isEmpty() && now - window.peekFirst().at() > 1500) {
                window.removeFirst();
            }
        }

        data.setLastMoveAt(now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveLate(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.setLastLocation(to.clone());

        // 只有"实际站在支撑物上、且近 1 秒未被回弹"的位置才算合法回弹点
        if (data.isCollisionBelow() && data.getAirTicks() == 0 && data.getSupportedTicks() >= 2
                && System.currentTimeMillis() - data.getLastSetbackAt() > 1000) {
            data.setLastValidLocation(to.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMoveCancelled(PlayerMoveEvent event) {
        if (!event.isCancelled()) return;
        PlayerData data = plugin.getDataManager().get(event.getPlayer());
        data.resetMovement(event.getFrom().clone());
    }
}
