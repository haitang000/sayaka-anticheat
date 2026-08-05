package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardPrimitivesTest {

    @Test
    void rateLimiterAllowsUpToLimitPerWindowPerKey() {
        AtomicLong now = new AtomicLong(0);
        DashboardServer.RateLimiter limiter = new DashboardServer.RateLimiter(2, 60_000L, now::get);

        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
        assertTrue(limiter.allow("b"));
    }

    @Test
    void rateLimiterRefusesNewKeysOnceTheMapIsFullAndNothingExpired() {
        AtomicLong now = new AtomicLong(0);
        DashboardServer.RateLimiter limiter = new DashboardServer.RateLimiter(5, 60_000L, now::get);
        // 撑满全部槽位且所有窗口仍活跃：第 MAX_KEYS+1 个不同 key 必须被拒绝，而不是继续插入
        for (int i = 0; i < 10_000; i++) {
            assertTrue(limiter.allow("key-" + i));
        }
        assertFalse(limiter.allow("key-overflow"));
    }

    @Test
    void rateLimiterReclaimsExpiredWindowsUnderPressure() {
        AtomicLong now = new AtomicLong(0);
        DashboardServer.RateLimiter limiter = new DashboardServer.RateLimiter(5, 60_000L, now::get);
        for (int i = 0; i < 10_000; i++) {
            limiter.allow("old-" + i);
        }
        // 窗口过期后容量被回收，新 key 不再被上限拒绝
        now.addAndGet(61_000L);
        assertTrue(limiter.allow("fresh"));
    }

    @Test
    void cooldownTableBlocksRepeatsWithinTheWindow() {
        AtomicLong now = new AtomicLong(0);
        DashboardServer.CooldownTable cooldowns = new DashboardServer.CooldownTable(60_000L, now::get);

        assertTrue(cooldowns.allow("ip|punishment-1"));
        assertFalse(cooldowns.allow("ip|punishment-1"));
        assertTrue(cooldowns.allow("ip|punishment-2"));
    }

    @Test
    void cooldownTableAllowsTheKeyAgainAfterTheWindow() {
        AtomicLong now = new AtomicLong(0);
        DashboardServer.CooldownTable cooldowns = new DashboardServer.CooldownTable(60_000L, now::get);

        assertTrue(cooldowns.allow("ip|punishment-1"));
        now.addAndGet(60_000L);
        assertTrue(cooldowns.allow("ip|punishment-1"));
    }

    @Test
    void cooldownTableRefusesNewKeysOnceFullAndNothingExpired() {
        AtomicLong now = new AtomicLong(0);
        DashboardServer.CooldownTable cooldowns = new DashboardServer.CooldownTable(60_000L, now::get);
        for (int i = 0; i < 1024; i++) {
            assertTrue(cooldowns.allow("ip|punishment-" + i));
        }
        assertFalse(cooldowns.allow("ip|punishment-overflow"));
    }
}