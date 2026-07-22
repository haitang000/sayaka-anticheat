package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 液面行走检测（Jesus / WaterWalk）。
 *
 * 原版玩家在无碰撞体的水面或岩浆面上水平移动时会下沉或产生明显竖直变化。
 * 检测要求玩家整个脚部碰撞箱都位于稳定液面上方，且连续产生近乎水平的位移；
 * 岸边、睡莲、水中游泳、气泡柱和单次掠过液面的样本均不会累积证据。
 */
public class LiquidWalkCheck extends Check {

    private static final double FOOTPRINT_RADIUS = 0.28;
    private static final double FEET_SAMPLE_Y = 0.02;
    private static final double BELOW_SAMPLE_Y = 0.08;
    private static final double[][] FOOTPRINT = {
            {0.0, 0.0},
            {-FOOTPRINT_RADIUS, -FOOTPRINT_RADIUS},
            {-FOOTPRINT_RADIUS, FOOTPRINT_RADIUS},
            {FOOTPRINT_RADIUS, -FOOTPRINT_RADIUS},
            {FOOTPRINT_RADIUS, FOOTPRINT_RADIUS}
    };

    public LiquidWalkCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.LIQUID_WALK);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || MovementTracker.isTeleport(event) || isExempt(player)) return;
        if (event.getFrom().getWorld() == null
                || !event.getFrom().getWorld().equals(to.getWorld())) return;

        PlayerData data = data(player);
        if (isMovementExempt(player, data)) {
            data.resetBuffer(type());
            return;
        }

        double dx = to.getX() - event.getFrom().getX();
        double dz = to.getZ() - event.getFrom().getZ();
        double horizontal = Math.hypot(dx, dz);
        double vertical = to.getY() - event.getFrom().getY();
        double minHorizontal = cfgD("min-horizontal-per-move", 0.025);
        double maxVertical = cfgD("max-vertical-per-move", 0.025);

        if (!Double.isFinite(horizontal) || !Double.isFinite(vertical)
                || Math.abs(vertical) > maxVertical) {
            data.resetBuffer(type());
            return;
        }
        if (horizontal < minHorizontal) {
            data.buffer(type(), -0.5);
            return;
        }
        if (player.isSwimming() || player.isInWater() || MoveUtil.standingOnEntity(player)) {
            data.resetBuffer(type());
            return;
        }

        SurfaceState surface = inspectSurface(to);

        boolean suspicious = isSuspiciousSample(
                surface.feetClear(),
                surface.fullLiquidFootprint(),
                surface.collisionBelow(),
                false,
                horizontal,
                vertical,
                minHorizontal,
                maxVertical);

        if (!suspicious) {
            // 离开完整液面即丢弃旧证据，避免岸边走动与稍后的液面样本拼接。
            if (!surface.fullLiquidFootprint() || !surface.feetClear()
                    || surface.collisionBelow()) {
                data.resetBuffer(type());
            } else {
                data.buffer(type(), -0.5);
            }
            return;
        }

        double buffered = data.buffer(type(), 1.0);
        if (buffered < cfgD("buffer-to-flag", 8.0)) return;

        data.resetBuffer(type());
        flag(player, 1.5, String.format("液面平移 %.3f 格 dy=%.3f", horizontal, vertical));
        trySetback(event, data);
    }

    private boolean isMovementExempt(Player player, PlayerData data) {
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding() || data.glidedWithin(2000)) return true;
        if (player.isRiptiding() || data.riptideWithin(2000)) return true;
        if (data.teleportedWithin(2000) || data.velocityWithin(2000)) return true;
        if (data.bouncedWithin(2500) || data.climbedWithin(1000)) return true;
        return false;
    }

    private SurfaceState inspectSurface(Location location) {
        World world = location.getWorld();
        if (world == null) return SurfaceState.NOT_SURFACE;

        for (double[] offset : FOOTPRINT) {
            double x = location.getX() + offset[0];
            double z = location.getZ() + offset[1];
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                return SurfaceState.NOT_SURFACE;
            }

            Material feet = world.getBlockAt(blockX,
                    (int) Math.floor(location.getY() + FEET_SAMPLE_Y), blockZ).getType();
            Material below = world.getBlockAt(blockX,
                    (int) Math.floor(location.getY() - BELOW_SAMPLE_Y), blockZ).getType();
            // 气泡柱会合法推动玩家，水/岩浆以外的水浸方块则按岸边处理。
            if (!feet.isAir() || (below != Material.WATER && below != Material.LAVA)) {
                return SurfaceState.NOT_SURFACE;
            }
        }

        return new SurfaceState(true, true, MoveUtil.hasCollisionBelow(location, 0.12));
    }

    static boolean isSuspiciousSample(boolean feetClear, boolean fullLiquidFootprint,
                                      boolean collisionBelow, boolean swimming,
                                      double horizontal, double vertical,
                                      double minHorizontal, double maxVertical) {
        return feetClear && fullLiquidFootprint && !collisionBelow && !swimming
                && Double.isFinite(horizontal) && Double.isFinite(vertical)
                && horizontal >= minHorizontal && Math.abs(vertical) <= maxVertical;
    }

    private void trySetback(PlayerMoveEvent event, PlayerData data) {
        if (!cfgB("setback", true) || !shouldMitigate(event.getPlayer())) return;
        Location from = event.getFrom();
        Location target = data.getLastValidLocation();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())
                || target.distanceSquared(from) > 256) {
            target = from;
        }
        data.touchSetback();
        event.setTo(target);
    }

    private record SurfaceState(boolean feetClear, boolean fullLiquidFootprint,
                                boolean collisionBelow) {
        private static final SurfaceState NOT_SURFACE = new SurfaceState(false, false, true);
    }
}
