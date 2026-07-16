package cn.haitang.anticheat.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseGeometryTest {

    /** 玩家三点中轴高度：脚 / 腰 / 头（1.8 站立姿态）。 */
    private static final double[] SPINE = {0.1, 0.9, 1.7};

    /** x ∈ [5,6) 处一整面墙（任意 y、任意 z 都是实体）。 */
    private static final PhaseGeometry.SolidSampler WALL_AT_X5 =
            (x, y, z) -> x >= 5.0 && x < 6.0;

    @Test
    void flagsWalkingStraightThroughAWall() {
        // 从 x=4.5 空旷处直线穿到 x=6.5，中轴必然扫过 [5,6) 墙体
        boolean phased = PhaseGeometry.phasedThroughSolid(
                4.5, 64.0, 0.5,
                6.5, 64.0, 0.5,
                SPINE, 20, WALL_AT_X5);
        assertTrue(phased);
    }

    @Test
    void ignoresMovingParallelAlongsideAWall() {
        // 沿墙面外侧 (x=4.7) 平行移动，中轴始终在墙外，不应判定
        boolean phased = PhaseGeometry.phasedThroughSolid(
                4.7, 64.0, 0.5,
                4.7, 64.0, 8.0,
                SPINE, 20, WALL_AT_X5);
        assertFalse(phased);
    }

    @Test
    void ignoresMovementThatStartsAlreadyInsideSolid() {
        // 起点已嵌在墙内（卡墙/回退不同步），不作为穿墙证据
        boolean phased = PhaseGeometry.phasedThroughSolid(
                5.5, 64.0, 0.5,
                7.0, 64.0, 0.5,
                SPINE, 20, WALL_AT_X5);
        assertFalse(phased);
    }

    @Test
    void detectsThinWallWithSufficientSampling() {
        // 只有 x ∈ [5, 5.1) 的薄墙，需要足够采样密度才不会漏过
        PhaseGeometry.SolidSampler thin = (x, y, z) -> x >= 5.0 && x < 5.1;
        boolean phased = PhaseGeometry.phasedThroughSolid(
                4.8, 64.0, 0.5,
                5.4, 64.0, 0.5,
                SPINE, 40, thin);
        assertTrue(phased);
    }

    @Test
    void respectsPerHeightSampling() {
        // 只有腰部高度 (y≈64.9) 有一块凸出的实体，脚/头两点采样不到，腰点应命中
        PhaseGeometry.SolidSampler waistBlock =
                (x, y, z) -> x >= 5.0 && x < 6.0 && y >= 64.5 && y < 65.5;
        boolean phased = PhaseGeometry.phasedThroughSolid(
                4.5, 64.0, 0.5,
                6.5, 64.0, 0.5,
                SPINE, 20, waistBlock);
        assertTrue(phased);
    }

    @Test
    void ignoresWhenSpineNeverEntersSolid() {
        // 头顶 (y≥65.5) 有悬空方块，站立中轴最高只到 65.7... 用只在 y≥66 的方块确保不触发
        PhaseGeometry.SolidSampler ceiling =
                (x, y, z) -> y >= 66.0;
        boolean phased = PhaseGeometry.phasedThroughSolid(
                4.5, 64.0, 0.5,
                6.5, 64.0, 0.5,
                SPINE, 20, ceiling);
        assertFalse(phased);
    }
}
