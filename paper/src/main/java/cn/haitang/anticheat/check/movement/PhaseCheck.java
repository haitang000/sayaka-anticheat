package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.AntiCheatPlugin;
import cn.haitang.anticheat.check.Check;
import cn.haitang.anticheat.check.CheckType;
import cn.haitang.anticheat.check.MovementTracker;
import cn.haitang.anticheat.data.PlayerData;
import cn.haitang.anticheat.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.BoundingBox;

/**
 * 穿墙移动检测（Phase / NoClip）。
 *
 * <p>原版碰撞会在玩家碰撞箱撞到实体方块时截停位移；穿墙类客户端绕过本地碰撞，
 * 使玩家中轴扫过墙体内部。沿 from→to 直线对玩家竖直中轴采样，起点在空旷处而
 * 途中/终点落入实体方块碰撞体即为穿墙（{@link PhaseGeometry}）。</p>
 *
 * <p>只采样中轴而非整只碰撞箱：贴墙行走时中轴距墙面至少半个箱宽，天然排除
 * "沿墙移动"误判；容差 epsilon 进一步要求采样点确实嵌入方块内部而非贴面。
 * 载具/滑翔/飞行/攀爬/蛛网/击退/传送宽限期一律豁免，秉持"宁可漏判"策略。</p>
 */
public class PhaseCheck extends Check {

    /** 采样点判定为"嵌入内部"所需的边界内缩，避免贴面触发。 */
    private static final double INSIDE_EPSILON = 0.01;

    public PhaseCheck(AntiCheatPlugin plugin) {
        super(plugin, CheckType.PHASE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || MovementTracker.isTeleport(event) || isExempt(player)) return;
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) return;

        PlayerData data = data(player);
        if (isMovementExempt(player, data)) {
            data.resetBuffer(type());
            return;
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // 忽略静止抖动；超大位移交由传送/速度检测处理，避免跨大量区块采样
        double minHorizontal = cfgD("min-horizontal-per-move", 0.08);
        if (horizontal < minHorizontal || horizontal > cfgD("max-horizontal-per-move", 10.0)) {
            data.buffer(type(), -cfgD("buffer-decay", 0.5));
            return;
        }

        // 依据当前姿态高度采样脚/腰/头三点，兼容潜行、游泳与滑行的矮碰撞箱
        double height = Math.max(0.6, player.getHeight());
        double[] spineHeights = {0.1, height * 0.5, Math.max(0.2, height - 0.1)};
        int samples = clamp((int) Math.ceil(horizontal / 0.2),
                cfgI("min-samples", 4), cfgI("max-samples", 40));

        boolean phased = PhaseGeometry.phasedThroughSolid(
                from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ(),
                spineHeights, samples,
                (x, y, z) -> isSolidAt(world, x, y, z));

        if (!phased) {
            data.buffer(type(), -cfgD("buffer-decay", 0.5));
            return;
        }

        double buffered = data.buffer(type(), cfgD("buffer-increment", 2.0));
        if (buffered >= cfgD("buffer-to-flag", 3.0)) {
            data.resetBuffer(type());
            flag(player, 2.5, String.format("穿越实体方块 水平位移 %.2f 格", horizontal));
            if (cfgB("setback", true) && shouldMitigate(player)) {
                setback(event, data);
            }
        }
    }

    /** 采样点 (x,y,z) 是否落在实体方块的碰撞体内部（内缩 epsilon）。 */
    private boolean isSolidAt(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (by < world.getMinHeight() || by >= world.getMaxHeight()) return false;
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;

        Block block = world.getBlockAt(bx, by, bz);
        Material type = block.getType();
        if (type.isAir() || isPassableMaterial(type)) return false;

        double rx = x - bx;
        double ry = y - by;
        double rz = z - bz;
        for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
            if (rx > local.getMinX() + INSIDE_EPSILON && rx < local.getMaxX() - INSIDE_EPSILON
                    && ry > local.getMinY() + INSIDE_EPSILON && ry < local.getMaxY() - INSIDE_EPSILON
                    && rz > local.getMinZ() + INSIDE_EPSILON && rz < local.getMaxZ() - INSIDE_EPSILON) {
                return true;
            }
        }
        return false;
    }

    /** 可穿行/可攀爬的软方块：玩家本就可以合法处于其内部，排除误判。 */
    private static boolean isPassableMaterial(Material type) {
        return Tag.CLIMBABLE.isTagged(type)
                || type == Material.POWDER_SNOW
                || type == Material.COBWEB;
    }

    private boolean isMovementExempt(Player player, PlayerData data) {
        if (player.isInsideVehicle() || player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding() || data.glidedWithin(2500)) return true;
        if (player.isRiptiding() || data.riptideWithin(2000)) return true;
        if (data.teleportedWithin(3000) || data.velocityWithin(2000)) return true;
        if (data.climbedWithin(1500) || MoveUtil.isClimbing(player)) return true;
        if (data.isInWeb() || MoveUtil.isInWeb(player)) return true;
        // 水下移动的服务端不同步较大，穿墙证据不可靠，从严豁免
        if (player.isInWater() || data.liquidWithin(1500)) return true;
        return data.levitationWithin(2500);
    }

    private void setback(PlayerMoveEvent event, PlayerData data) {
        Location target = data.getLastValidLocation();
        Location from = event.getFrom();
        if (target == null || target.getWorld() == null
                || !target.getWorld().equals(from.getWorld())
                || target.distanceSquared(from) > 64) {
            target = from;
        }
        data.touchSetback();
        event.setTo(target);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
