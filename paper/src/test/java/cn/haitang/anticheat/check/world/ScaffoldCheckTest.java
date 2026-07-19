package cn.haitang.anticheat.check.world;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldCheckTest {

    /** 桥式搭路：眼睛悬在方块 (10,64,10) 东面外侧，点击其东面（法线 +X，平面 x=11） */
    @Test
    void bridgingEyeOutsideFaceIsVisible() {
        double signed = ScaffoldCheck.eyeToFacePlane(11.3, 65.6, 10.5, 10, 64, 10, BlockFace.EAST);
        assertEquals(0.3, signed, 1.0e-9);
    }

    /** 站在方块正上方却声称点击其东面：眼睛在面平面背侧 */
    @Test
    void placingAgainstFaceBehindEyeIsNegative() {
        double signed = ScaffoldCheck.eyeToFacePlane(10.5, 65.6, 10.5, 10, 64, 10, BlockFace.EAST);
        assertEquals(-0.5, signed, 1.0e-9);
    }

    /** 负方向面：点击西面（法线 -X，平面 x=10），眼睛须在 x<10 一侧 */
    @Test
    void negativeAxisFaceUsesNearPlane() {
        assertEquals(0.4,
                ScaffoldCheck.eyeToFacePlane(9.6, 65.0, 10.5, 10, 64, 10, BlockFace.WEST), 1.0e-9);
        assertEquals(-0.7,
                ScaffoldCheck.eyeToFacePlane(10.7, 65.0, 10.5, 10, 64, 10, BlockFace.WEST), 1.0e-9);
    }

    /** 顶面只能从上方点到：站在方块旁（眼睛低于顶面）点击其顶面为背侧 */
    @Test
    void topFaceRequiresEyeAbovePlane() {
        assertTrue(ScaffoldCheck.eyeToFacePlane(10.5, 66.6, 10.5, 10, 64, 10, BlockFace.UP) > 0);
        assertTrue(ScaffoldCheck.eyeToFacePlane(10.5, 64.6, 10.5, 10, 64, 10, BlockFace.UP) < 0);
    }

    /** 头顶封板：点击天花板底面（法线 -Y，平面 y=66），眼睛在下方为可见 */
    @Test
    void ceilingBottomFaceVisibleFromBelow() {
        assertTrue(ScaffoldCheck.eyeToFacePlane(10.5, 65.6, 10.5, 10, 66, 10, BlockFace.DOWN) > 0);
    }
}
