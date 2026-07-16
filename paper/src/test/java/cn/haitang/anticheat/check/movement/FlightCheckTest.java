package cn.haitang.anticheat.check.movement;

import cn.haitang.anticheat.data.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightCheckTest {

    @Test
    void vanillaJumpAndJumpBoostFollowGravityRecurrence() {
        double vanillaNext = FlightCheck.predictedNextDeltaY(0.42);
        double boostedNext = FlightCheck.predictedNextDeltaY(0.62);

        assertEquals(0.3332, vanillaNext, 0.000001);
        assertEquals(0.5292, boostedNext, 0.000001);
        assertEquals(0.0, FlightCheck.gravityExcess(0.42, vanillaNext), 0.000001);
        assertEquals(0.0, FlightCheck.gravityExcess(0.62, boostedNext), 0.000001);
    }

    @Test
    void normalFallingDoesNotCreatePositiveGravityResidual() {
        double current = FlightCheck.predictedNextDeltaY(-0.18);

        assertEquals(-0.2548, current, 0.000001);
        assertEquals(0.0, FlightCheck.gravityExcess(-0.18, current), 0.000001);
    }

    @Test
    void upwardFlightHoverAndLowGravityExceedDefaultTolerance() {
        assertTrue(FlightCheck.gravityExcess(0.10, 0.10) > 0.06);
        assertTrue(FlightCheck.gravityExcess(0.0, 0.0) > 0.06);
        assertTrue(FlightCheck.gravityExcess(-0.10, -0.10) > 0.06);
    }

    @Test
    void flightEvidenceBuffersRemainIndependent() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");

        data.buffer("flight.ascent", 2.0);
        data.buffer("flight.hover", 1.0);
        data.buffer("flight.gravity", 3.0);

        assertEquals(1.5, data.buffer("flight.ascent", -0.5), 0.0001);
        assertEquals(0.5, data.buffer("flight.hover", -0.5), 0.0001);
        assertEquals(2.5, data.buffer("flight.gravity", -0.5), 0.0001);
    }
}
