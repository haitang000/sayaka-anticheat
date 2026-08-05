package cn.haitang.anticheat.check.world;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * 世界交互检测的纯几何与范围工具（BlockReach / EntityReach / InteractAim 共用）。
 */
final class InteractionMath {

    static final double EPSILON = 1.0E-6;

    private InteractionMath() { }

    /**
     * 眼睛到碰撞箱表面最近点的距离。
     * 退化（零体积）碰撞箱按自身位置外扩 0.25 格，避免空气类目标算出远距离。
     */
    static double eyeToBoxDistance(Location eye, BoundingBox box) {
        if (box.getVolume() <= EPSILON) {
            double cx = box.getCenterX();
            double cy = box.getCenterY();
            double cz = box.getCenterZ();
            box = new BoundingBox(cx - 0.25, cy - 0.25, cz - 0.25,
                    cx + 0.25, cy + 0.25, cz + 0.25);
        }
        double cx = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double cy = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double cz = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 视角方向与"眼睛→目标碰撞箱最近点"方向的夹角（度）；眼睛在箱内返回 NaN。 */
    static double eyeToBoxAngleDegrees(Location eye, BoundingBox box) {
        double cx = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double cy = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double cz = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        Vector toTarget = new Vector(cx - eye.getX(), cy - eye.getY(), cz - eye.getZ());
        if (toTarget.lengthSquared() < EPSILON) return Double.NaN;
        return Math.toDegrees(eye.getDirection().angle(toTarget));
    }

    /**
     * 方块的可点击碰撞形状：多个碰撞盒取并集（楼梯/栅栏等由多盒组成）。
     * 无碰撞或零体积（红石线等空气类）时按所在格完整单元计算，距离只会偏宽松。
     */
    static BoundingBox clickableShape(int x, int y, int z,
                                      java.util.Collection<BoundingBox> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return new BoundingBox(x, y, z, x + 1.0, y + 1.0, z + 1.0);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (BoundingBox shape : shapes) {
            minX = Math.min(minX, shape.getMinX());
            minY = Math.min(minY, shape.getMinY());
            minZ = Math.min(minZ, shape.getMinZ());
            maxX = Math.max(maxX, shape.getMaxX());
            maxY = Math.max(maxY, shape.getMaxY());
            maxZ = Math.max(maxZ, shape.getMaxZ());
        }
        BoundingBox union = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        return union.getVolume() <= EPSILON
                ? new BoundingBox(x, y, z, x + 1.0, y + 1.0, z + 1.0)
                : union;
    }

    /** 1.20.5+ 的方块交互范围属性；旧版本不存在，用反射探测一次后缓存。 */
    private static volatile org.bukkit.attribute.Attribute blockRangeAttribute;

    static double blockInteractionRange(Player player, double fallback) {
        return interactionRange(player, fallback, "player.block_interaction_range",
                blockRangeAttribute);
    }

    /** 1.20.5+ 的实体交互范围属性；旧版本不存在，用反射探测一次后缓存。 */
    private static volatile org.bukkit.attribute.Attribute entityRangeAttribute;

    static double entityInteractionRange(Player player, double fallback) {
        return interactionRange(player, fallback, "player.entity_interaction_range",
                entityRangeAttribute);
    }

    /** 反射探测一次后缓存；旧版本 API 无此属性时保持 null 并回落配置值。 */
    private static double interactionRange(Player player, double fallback,
                                           String key,
                                           org.bukkit.attribute.Attribute cached) {
        try {
            org.bukkit.attribute.Attribute attribute = cached;
            if (attribute == null) {
                Object registry = org.bukkit.Registry.class.getField("ATTRIBUTE").get(null);
                Object value = registry.getClass().getMethod("get", NamespacedKey.class)
                        .invoke(registry, NamespacedKey.minecraft(key));
                if (value instanceof org.bukkit.attribute.Attribute typed) {
                    attribute = typed;
                    if ("player.block_interaction_range".equals(key)) {
                        blockRangeAttribute = typed;
                    } else {
                        entityRangeAttribute = typed;
                    }
                }
            }
            if (attribute != null) {
                var instance = player.getAttribute(attribute);
                if (instance != null && instance.getValue() > 0) return instance.getValue();
            }
        } catch (ReflectiveOperationException ignored) {
            // Attribute was introduced after the minimum supported server version.
        }
        return fallback;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
