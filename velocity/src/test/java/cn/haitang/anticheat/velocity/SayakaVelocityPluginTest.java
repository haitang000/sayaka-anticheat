package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SayakaVelocityPluginTest {

    private static final long NOW = 1_000_000L;

    @Test
    void swapWindowBlocksConnectionsWhileReloading() {
        assertTrue(SayakaVelocityPlugin.swapBlockActive(true, NOW, NOW));
    }

    @Test
    void swapWindowExpiresAfterTheFailClosedWindow() {
        long deadline = NOW + SayakaVelocityPlugin.SWAP_FAIL_CLOSED_MILLIS;
        // 恰好等于时限：已结束，放行
        assertFalse(SayakaVelocityPlugin.swapBlockActive(true, NOW, deadline));
        assertFalse(SayakaVelocityPlugin.swapBlockActive(true, NOW, deadline + 1));
    }

    @Test
    void idleProxyIsNeverBlocked() {
        assertFalse(SayakaVelocityPlugin.swapBlockActive(false, NOW, NOW));
        assertFalse(SayakaVelocityPlugin.swapBlockActive(false, 0, NOW));
    }
}