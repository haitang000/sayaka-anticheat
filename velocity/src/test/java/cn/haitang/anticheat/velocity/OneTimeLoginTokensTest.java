package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneTimeLoginTokensTest {
    @Test
    void issuedTokenCanOnlyBeRedeemedOnce() {
        OneTimeLoginTokens tokens = new OneTimeLoginTokens();
        String token = tokens.issue();

        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertTrue(tokens.redeem(token));
        assertFalse(tokens.redeem(token));
    }

    @Test
    void rejectsExpiredToken() {
        AtomicLong now = new AtomicLong(1_000L);
        OneTimeLoginTokens tokens = new OneTimeLoginTokens(500L, now::get);
        String token = tokens.issue();

        now.set(1_500L);
        assertFalse(tokens.redeem(token));
    }
}
