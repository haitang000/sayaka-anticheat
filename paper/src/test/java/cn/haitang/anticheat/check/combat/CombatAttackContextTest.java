package cn.haitang.anticheat.check.combat;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CombatAttackContextTest {

    @Test
    void pluginDamageWithoutClientAttackIntentIsIgnored() {
        UUID target = UUID.randomUUID();
        var queue = new ArrayDeque<CombatAttackContext.Attack>();

        assertNull(CombatAttackContext.consume(queue, target, 42),
                "Bukkit damage synthesized by another plugin has no client attack intent");
    }

    @Test
    void oneIntentCanBindOnlyOneDamageEvent() {
        UUID attacker = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        var queue = new ArrayDeque<CombatAttackContext.Attack>();
        queue.add(new CombatAttackContext.Attack(1L, attacker, target, 42,
                10L, 100L, true, 1, 2, 3, 4, 5, 9L, 40));

        assertEquals(1L, CombatAttackContext.consume(queue, target, 42).id());
        assertNull(CombatAttackContext.consume(queue, target, 42),
                "same-tick synthetic damage must not reuse the intent");
    }

    @Test
    void targetMismatchDoesNotConsumeAnotherIntent() {
        UUID attacker = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        var queue = new ArrayDeque<CombatAttackContext.Attack>();
        queue.add(new CombatAttackContext.Attack(2L, attacker, target, 50,
                -1L, 100L, false, 0, 0, 0, 0, 0, -1L, -1));

        assertNull(CombatAttackContext.consume(queue, UUID.randomUUID(), 50));
        assertEquals(2L, CombatAttackContext.consume(queue, target, 50).id());
    }

    @Test
    void staleIntentsAreDiscarded() {
        UUID attacker = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        var queue = new ArrayDeque<CombatAttackContext.Attack>();
        queue.add(new CombatAttackContext.Attack(3L, attacker, target, 10,
                -1L, 100L, false, 0, 0, 0, 0, 0, -1L, -1));

        assertNull(CombatAttackContext.consume(queue, target, 12));
        assertEquals(0, queue.size());
    }
}
