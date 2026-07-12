package cn.haitang.anticheat.check.combat;

/** Pure combat geometry helpers. This class must not access Bukkit state. */
final class CombatGeometry {

    private static final double EPSILON = 1.0E-9;

    private CombatGeometry() { }

    /**
     * Returns the distance from a ray origin to its first intersection with an axis-aligned box,
     * or {@link Double#NaN} when the ray misses or the hit lies beyond {@code maxDistance}.
     */
    static double rayBoxIntersectionDistance(
            double originX, double originY, double originZ,
            double directionX, double directionY, double directionZ,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            double maxDistance) {
        double length = Math.sqrt(directionX * directionX
                + directionY * directionY + directionZ * directionZ);
        if (length < EPSILON || maxDistance < 0.0) return Double.NaN;

        double[] range = {0.0, maxDistance};
        if (!clipAxis(originX, directionX / length, minX, maxX, range)
                || !clipAxis(originY, directionY / length, minY, maxY, range)
                || !clipAxis(originZ, directionZ / length, minZ, maxZ, range)) {
            return Double.NaN;
        }
        return range[0];
    }

    static double horizontalProjection(double deltaX, double deltaZ,
                                       double velocityX, double velocityZ) {
        double horizontalVelocity = Math.hypot(velocityX, velocityZ);
        if (horizontalVelocity < EPSILON) return Double.NaN;
        return deltaX * velocityX / horizontalVelocity
                + deltaZ * velocityZ / horizontalVelocity;
    }

    private static boolean clipAxis(double origin, double direction,
                                    double min, double max, double[] range) {
        if (Math.abs(direction) < EPSILON) {
            return origin >= min && origin <= max;
        }

        double near = (min - origin) / direction;
        double far = (max - origin) / direction;
        if (near > far) {
            double swap = near;
            near = far;
            far = swap;
        }
        range[0] = Math.max(range[0], near);
        range[1] = Math.min(range[1], far);
        return range[0] <= range[1];
    }
}
