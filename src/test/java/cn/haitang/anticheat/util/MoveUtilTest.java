package cn.haitang.anticheat.util;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveUtilTest {

    private static final BoundingBox FOOT_PROBE =
            new BoundingBox(0.2, 0.95, 0.2, 0.8, 1.01, 0.8);

    @Test
    void blockDirectlyBelowSupportsTheProbe() {
        assertTrue(MoveUtil.overlapsTranslated(FOOT_PROBE,
                new BoundingBox(0, 0, 0, 1, 1, 1), 0, 0, 0));
    }

    @Test
    void adjacentWallDoesNotCountAsGround() {
        assertFalse(MoveUtil.overlapsTranslated(FOOT_PROBE,
                new BoundingBox(0, 0, 0, 1, 1, 1), 1, 0, 0));
    }

    @Test
    void fullAirGapDoesNotCountAsGround() {
        assertFalse(MoveUtil.overlapsTranslated(FOOT_PROBE,
                new BoundingBox(0, 0, 0, 1, 1, 1), 0, -1, 0));
    }

    @Test
    void partialCollisionShapeSupportsOnlyWhereItActuallyOverlaps() {
        BoundingBox narrowPost = new BoundingBox(0.375, 0, 0.375, 0.625, 1.5, 0.625);
        assertTrue(MoveUtil.overlapsTranslated(FOOT_PROBE, narrowPost, 0, 0, 0));
        BoundingBox edgeProbe = new BoundingBox(0.7, 0.95, 0.7, 0.9, 1.01, 0.9);
        assertFalse(MoveUtil.overlapsTranslated(edgeProbe, narrowPost, 0, 0, 0));
    }

    @Test
    void slabAndSnowSupportOnlyAtTheirActualSurfaceHeight() {
        BoundingBox slab = new BoundingBox(0, 0, 0, 1, 0.5, 1);
        BoundingBox snowLayer = new BoundingBox(0, 0, 0, 1, 0.125, 1);
        BoundingBox slabFeet = new BoundingBox(0.2, 0.49, 0.2, 0.8, 0.51, 0.8);
        BoundingBox snowFeet = new BoundingBox(0.2, 0.12, 0.2, 0.8, 0.14, 0.8);

        assertTrue(MoveUtil.overlapsTranslated(slabFeet, slab, 0, 0, 0));
        assertTrue(MoveUtil.overlapsTranslated(snowFeet, snowLayer, 0, 0, 0));
        assertFalse(MoveUtil.overlapsTranslated(FOOT_PROBE, slab, 0, 0, 0));
    }

    @Test
    void fencePostSupportsCenterButNotPlayerBesideIt() {
        BoundingBox fencePost = new BoundingBox(0.375, 0, 0.375, 0.625, 1.5, 0.625);
        BoundingBox centeredFeet = new BoundingBox(0.35, 1.49, 0.35, 0.65, 1.51, 0.65);
        BoundingBox besideFeet = new BoundingBox(0.7, 1.49, 0.2, 0.95, 1.51, 0.8);

        assertTrue(MoveUtil.overlapsTranslated(centeredFeet, fencePost, 0, 0, 0));
        assertFalse(MoveUtil.overlapsTranslated(besideFeet, fencePost, 0, 0, 0));
    }
}
