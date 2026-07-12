package cn.haitang.anticheat.data;

import cn.haitang.anticheat.check.CheckType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerDataTest {

    @Test
    void totalVlIncludesEveryCheck() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");

        data.addVl(CheckType.SPEED, 3.5);
        data.addVl(CheckType.REACH, 4.0);
        data.addVl(CheckType.AUTO_CLICKER, 2.5);

        assertEquals(10.0, data.getTotalVl(), 0.0001);
        assertEquals(3.5, data.getVl(CheckType.SPEED), 0.0001);
    }

    @Test
    void decayOnlyResetsGlobalWarningAfterTotalFallsBelowRewarnLevel() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addVl(CheckType.SPEED, 2.0);
        data.addVl(CheckType.REACH, 2.0);
        data.setPunishmentWarnStage(2);

        data.decay(0.5, Map.of(), 0L, 2.5);
        assertEquals(3.0, data.getTotalVl(), 0.0001);
        assertEquals(2, data.getPunishmentWarnStage());

        data.decay(0.3, Map.of(), 0L, 2.5);
        assertEquals(2.4, data.getTotalVl(), 0.0001);
        assertEquals(0, data.getPunishmentWarnStage());
    }

    @Test
    void perTypeRateOverridesTheGlobalDecayRate() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addVl(CheckType.KILL_AURA, 2.0);
        data.addVl(CheckType.SPEED, 2.0);

        data.decay(0.4, Map.of(CheckType.KILL_AURA, 0.1), 0L, 0.0);

        assertEquals(1.9, data.getVl(CheckType.KILL_AURA), 0.0001);
        assertEquals(1.6, data.getVl(CheckType.SPEED), 0.0001);
    }

    @Test
    void recentlyFlaggedChecksAreHeldBackFromDecay() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addVl(CheckType.KILL_AURA, 2.0);

        data.decay(0.4, Map.of(), 60_000L, 0.0);
        assertEquals(2.0, data.getVl(CheckType.KILL_AURA), 0.0001);

        data.decay(0.4, Map.of(), 0L, 0.0);
        assertEquals(1.6, data.getVl(CheckType.KILL_AURA), 0.0001);
    }

    @Test
    void resetAllVlAlsoResetsGlobalWarningStage() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addVl(CheckType.FLIGHT, 12.0);
        data.setPunishmentWarnStage(2);

        data.resetAllVl();

        assertEquals(0.0, data.getTotalVl(), 0.0001);
        assertEquals(0, data.getPunishmentWarnStage());
    }
}
