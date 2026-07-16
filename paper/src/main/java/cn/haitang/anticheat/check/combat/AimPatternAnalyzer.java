package cn.haitang.anticheat.check.combat;

/** Pure rotation-pattern calculations. This class must not access Bukkit state. */
final class AimPatternAnalyzer {

    private AimPatternAnalyzer() { }

    static double rotationDistance(double firstYaw, double firstPitch,
                                   double secondYaw, double secondPitch) {
        double yawDelta = wrappedAngleDelta(firstYaw, secondYaw);
        double pitchDelta = secondPitch - firstPitch;
        return Math.hypot(yawDelta, pitchDelta);
    }

    static boolean isSnapBack(double beforeYaw, double beforePitch,
                              double attackYaw, double attackPitch,
                              double afterYaw, double afterPitch,
                              double minSnapAngle, double maxReturnAngle,
                              double minReturnRatio) {
        double snap = rotationDistance(beforeYaw, beforePitch, attackYaw, attackPitch);
        if (snap < minSnapAngle) return false;

        double returnedToBefore = rotationDistance(beforeYaw, beforePitch, afterYaw, afterPitch);
        double movedFromAttack = rotationDistance(attackYaw, attackPitch, afterYaw, afterPitch);
        return returnedToBefore <= maxReturnAngle
                && movedFromAttack >= snap * minReturnRatio;
    }

    static double wrappedAngleDelta(double from, double to) {
        double delta = (to - from) % 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return delta;
    }

    /** 视角 (yaw, pitch) 对应的单位方向向量，与 Bukkit Location#getDirection 一致。 */
    static double[] directionFromRotation(double yaw, double pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double xz = Math.cos(pitchRad);
        return new double[] {-xz * Math.sin(yawRad), -Math.sin(pitchRad), xz * Math.cos(yawRad)};
    }

    /** 视角方向与目标向量的夹角（度）；目标向量长度为零时返回 NaN。 */
    static double viewAngleToTarget(double yaw, double pitch, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-9) return Double.NaN;
        double[] view = directionFromRotation(yaw, pitch);
        double dot = (view[0] * dx + view[1] * dy + view[2] * dz) / length;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    /** 攻击者到目标的水平方位角（度），用于判断目标相对方位是否在变化。 */
    static double horizontalBearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** 半径为 halfExtent 的目标在 distance 处的视角半径（度）。 */
    static double apparentHalfAngle(double distance, double halfExtent) {
        return Math.toDegrees(Math.atan2(halfExtent, Math.max(0.1, distance)));
    }
}
