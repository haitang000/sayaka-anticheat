package cn.haitang.anticheat.check.combat;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachCheckTest {

    private static final BoundingBox TARGET = new BoundingBox(2, 0, -0.3, 2.6, 1.8, 0.3);

    @Test
    void reachDistanceDoesNotDependOnPacketRotation() {
        Location eye = new Location(null, 0, 1.62, 0, -90.0f, 0.0f);
        assertEquals(2.0, ReachCheck.eyeToBoxDistance(eye, TARGET), 1.0e-9);

        eye.setYaw(90.0f);
        assertEquals(2.0, ReachCheck.eyeToBoxDistance(eye, TARGET), 1.0e-9);
    }

    @Test
    void measuresDiagonalEdgeHitWithoutDependingOnRotation() {
        assertEquals(Math.sqrt(4.09), ReachCheck.eyeToBoxDistance(
                new Location(null, 0, 1.62, 0.6), TARGET), 1.0e-9);
    }

    @Test
    void originInsideTargetHasZeroDistance() {
        assertEquals(0.0, ReachCheck.eyeToBoxDistance(
                new Location(null, 2.2, 1.0, 0), TARGET), 1.0e-9);
    }
}
