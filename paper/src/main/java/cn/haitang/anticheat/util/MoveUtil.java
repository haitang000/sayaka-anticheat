package cn.haitang.anticheat.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

/** 移动物理相关的工具方法。判定一律取宽松策略：宁可漏判，不可误判。 */
public final class MoveUtil {

    private MoveUtil() {}

    private static final double BB_HALF_WIDTH = 0.3;
    private static final double CONTACT_EPSILON = 1.0e-4;

    /**
     * 跳跃提升效果。1.20.4 及以前字段名为 JUMP，1.20.5+ 改名 JUMP_BOOST，
     * 直接引用字段会在另一侧版本抛 NoSuchFieldError，故按命名空间键解析。
     */
    public static PotionEffectType jumpBoostType() {
        return resolveJumpBoost();
    }

    @SuppressWarnings("deprecation")
    private static PotionEffectType resolveJumpBoost() {
        PotionEffectType byKey = PotionEffectType.getByKey(NamespacedKey.minecraft("jump_boost"));
        return byKey != null ? byKey : PotionEffectType.JUMP;
    }

    /**
     * 检查某位置下方 depth 范围内是否存在任何可站立的碰撞体。
     * 扫描玩家碰撞箱覆盖到的所有方块列，兼容半砖/地毯/栅栏/雪层等非完整方块。
     */
    public static boolean hasCollisionBelow(Location loc, double depth) {
        World world = loc.getWorld();
        if (world == null) return true;
        double normalizedDepth = Math.max(0.0, depth);
        BoundingBox probe = new BoundingBox(
                loc.getX() - BB_HALF_WIDTH + CONTACT_EPSILON,
                loc.getY() - normalizedDepth - CONTACT_EPSILON,
                loc.getZ() - BB_HALF_WIDTH + CONTACT_EPSILON,
                loc.getX() + BB_HALF_WIDTH - CONTACT_EPSILON,
                loc.getY() + CONTACT_EPSILON,
                loc.getZ() + BB_HALF_WIDTH - CONTACT_EPSILON);
        double minY = probe.getMinY();
        int bx0 = (int) Math.floor(loc.getX() - BB_HALF_WIDTH);
        int bx1 = (int) Math.floor(loc.getX() + BB_HALF_WIDTH);
        int bz0 = (int) Math.floor(loc.getZ() - BB_HALF_WIDTH);
        int bz1 = (int) Math.floor(loc.getZ() + BB_HALF_WIDTH);
        int by0 = (int) Math.floor(minY) - 1;
        int by1 = (int) Math.floor(probe.getMaxY());
        if (by0 < world.getMinHeight()) return true; // 世界底部按有支撑处理
        for (int x = bx0; x <= bx1; x++) {
            for (int z = bz0; z <= bz1; z++) {
                for (int y = by0; y <= by1; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) continue;
                    for (BoundingBox local : block.getCollisionShape().getBoundingBoxes()) {
                        if (overlapsTranslated(probe, local, x, y, z)) return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean overlapsTranslated(BoundingBox probe, BoundingBox local,
                                      int blockX, int blockY, int blockZ) {
        BoundingBox worldBox = local.clone().shift(blockX, blockY, blockZ);
        return worldBox.overlaps(probe);
    }

    /** 是否站在船 / 矿车 / 潜影贝等实体"平台"上（或紧邻） */
    public static boolean standingOnEntity(Player player) {
        for (Entity e : player.getNearbyEntities(1.2, 2.0, 1.2)) {
            if (e instanceof Boat || e instanceof Minecart || e instanceof Shulker) {
                BoundingBox box = e.getBoundingBox();
                if (box.getMaxY() <= player.getLocation().getY() + 0.5) return true;
            }
        }
        return false;
    }

    /** 脚下或身位是否为可攀爬方块（梯子/藤蔓/脚手架/细雪） */
    public static boolean isClimbing(Player player) {
        Location loc = player.getLocation();
        Material feet = loc.getBlock().getType();
        Material eye = player.getEyeLocation().getBlock().getType();
        return isClimbable(feet) || isClimbable(eye);
    }

    private static boolean isClimbable(Material mat) {
        return Tag.CLIMBABLE.isTagged(mat) || mat == Material.POWDER_SNOW;
    }

    /** 身处蜘蛛网中（下落极慢，会触发悬浮误判，需豁免） */
    public static boolean isInWeb(Player player) {
        return player.getLocation().getBlock().getType() == Material.COBWEB
                || player.getEyeLocation().getBlock().getType() == Material.COBWEB;
    }

    /** 是否贴着蜂蜜方块（缓慢滑落，需豁免悬浮判定） */
    public static boolean isNearHoney(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        return isNearHoney(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static boolean isNearHoney(World world, int bx, int by, int bz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (world.getBlockAt(bx + dx, by + dy, bz + dz).getType() == Material.HONEY_BLOCK) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 是否接触液体 / 气泡柱 */
    public static boolean touchingLiquid(Player player) {
        if (player.isInWater()) return true;
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return false;
        Material feet = loc.getBlock().getType();
        Material eye = player.getEyeLocation().getBlock().getType();
        Material below = world.getBlockAt(loc.getBlockX(),
                (int) Math.floor(loc.getY() - 0.1), loc.getBlockZ()).getType();
        return isLiquidLike(feet) || isLiquidLike(eye) || isLiquidLike(below);
    }

    private static boolean isLiquidLike(Material material) {
        return material == Material.WATER
                || material == Material.LAVA
                || material == Material.BUBBLE_COLUMN;
    }

    public static int effectLevel(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }
}
