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
}
