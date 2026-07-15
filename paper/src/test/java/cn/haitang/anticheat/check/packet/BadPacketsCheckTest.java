package cn.haitang.anticheat.check.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BadPacketsCheckTest {

    @Test
    void vanillaReachableCoordinatesAreLegal() {
        assertFalse(BadPacketsCheck.isInvalidCoordinate(0.0));
        assertFalse(BadPacketsCheck.isInvalidCoordinate(100.5));
        assertFalse(BadPacketsCheck.isInvalidCoordinate(-320.0));
        // 原版世界边界 ±29,999,984
        assertFalse(BadPacketsCheck.isInvalidCoordinate(29_999_984.0));
        assertFalse(BadPacketsCheck.isInvalidCoordinate(-29_999_984.0));
    }

    @Test
    void nonFiniteCoordinatesAreInvalid() {
        assertTrue(BadPacketsCheck.isInvalidCoordinate(Double.NaN));
        assertTrue(BadPacketsCheck.isInvalidCoordinate(Double.POSITIVE_INFINITY));
        assertTrue(BadPacketsCheck.isInvalidCoordinate(Double.NEGATIVE_INFINITY));
    }

    @Test
    void coordinatesBeyondWorldBorderMagnitudeAreInvalid() {
        assertTrue(BadPacketsCheck.isInvalidCoordinate(3.1E7));
        assertTrue(BadPacketsCheck.isInvalidCoordinate(-3.1E7));
        // 典型崩服包：极端数量级坐标触发异常区块计算
        assertTrue(BadPacketsCheck.isInvalidCoordinate(1.0E300));
    }
}
