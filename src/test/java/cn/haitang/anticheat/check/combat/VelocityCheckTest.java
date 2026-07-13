package cn.haitang.anticheat.check.combat;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityCheckTest {

    @Test
    void creditsKnockbackThatIsPartlyCancelledByForwardMovement() {
        Vector expected = new Vector(0.4, 0.0, 0.0);
        Vector priorMovement = new Vector(-0.15, 0.0, 0.0);
        Vector netDisplacement = new Vector(-0.4, 0.0, 0.0);

        double response = VelocityCheck.responseProjection(
                netDisplacement, expected, priorMovement, 4);

        assertEquals(0.2, response, 0.0001);
        assertTrue(response >= 0.06, "legitimate knockback must survive opposing movement correction");
    }

    @Test
    void continuedForwardMovementAloneDoesNotCountAsKnockback() {
        Vector expected = new Vector(0.4, 0.0, 0.0);
        Vector priorMovement = new Vector(-0.15, 0.0, 0.0);
        Vector netDisplacement = new Vector(-0.6, 0.0, 0.0);

        double response = VelocityCheck.responseProjection(
                netDisplacement, expected, priorMovement, 4);

        assertEquals(0.0, response, 0.0001);
    }

    @Test
    void orthogonalMovementDoesNotSubstituteForKnockback() {
        Vector expected = new Vector(1.0, 0.0, 0.0);
        Vector priorMovement = new Vector(0.0, 0.0, 0.2);

        double response = VelocityCheck.responseProjection(
                new Vector(0.0, 0.0, 0.8), expected, priorMovement, 4);

        assertEquals(0.0, response, 0.0001);
    }
}
