package cn.haitang.anticheat.data;

import cn.haitang.anticheat.check.CheckType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import org.bukkit.util.Vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataTest {

    @Test
    void warningHistoryKeepsTheMostRecentTwentyEntries() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "tester");

        for (int stage = 1; stage <= 25; stage++) {
            data.addWarning(new PlayerData.WarningRecord(stage, CheckType.SPEED, stage, stage));
        }

        assertEquals(20, data.getRecentWarnings().size());
        assertEquals(6, data.getRecentWarnings().getFirst().stage());
        assertEquals(25, data.getRecentWarnings().getLast().stage());
    }

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
    void decayResetsWarningOnlyForTheCheckThatFellBelowTheThreshold() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addVl(CheckType.SPEED, 3.0);
        data.addVl(CheckType.REACH, 1.0);
        data.setPunishmentWarnStage(CheckType.SPEED, 2);
        data.setPunishmentWarnStage(CheckType.REACH, 2);

        data.decay(0.5, Map.of(), 0L, 2.5);
        assertEquals(3.0, data.getTotalVl(), 0.0001);
        assertEquals(2, data.getPunishmentWarnStage(CheckType.SPEED));
        assertEquals(0, data.getPunishmentWarnStage(CheckType.REACH));

        data.decay(0.3, Map.of(), 0L, 2.5);
        assertEquals(2.4, data.getTotalVl(), 0.0001);
        assertEquals(0, data.getPunishmentWarnStage(CheckType.SPEED));
        assertEquals(0, data.getPunishmentWarnStage(CheckType.REACH));
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
        data.setPunishmentWarnStage(CheckType.FLIGHT, 2);

        data.resetAllVl();

        assertEquals(0.0, data.getTotalVl(), 0.0001);
        assertEquals(0, data.getPunishmentWarnStage(CheckType.FLIGHT));
    }

    @Test
    void impulseAllowanceIsDirectionalAndConsumable() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.startImpulse(new Vector(1.0, 0.0, 0.0), 1_000L);

        data.consumeImpulse(new Vector(0.0, 0.0, 1.0), 1_300L);
        assertTrue(data.hasActiveImpulse(1_300L), "orthogonal movement must not consume knockback");

        data.consumeImpulse(new Vector(0.9, 0.0, 0.0), 1_300L);
        assertFalse(data.hasActiveImpulse(1_300L), "response along the impulse should consume it");
    }

    @Test
    void knockbackDuringJumpRemainsExemptUntilLanding() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.startImpulse(new Vector(0.4, 0.4, 0.0), 1_000L);

        data.consumeImpulse(new Vector(0.5, 0.5, 0.0), 1_300L);
        assertFalse(data.hasActiveImpulse(1_300L), "the short knockback response is consumed");
        assertTrue(data.velocityWithin(50L, 1_300L),
                "Flight must stay exempt while the knocked-back player is airborne");

        assertFalse(data.updateServerLaunch(false, 1_350L));
        assertTrue(data.hasActiveServerLaunch(1_350L));
        assertTrue(data.updateServerLaunch(true, 2_000L), "landing should finish the launch early");
        assertFalse(data.hasActiveServerLaunch(2_000L));
    }

    @Test
    void strongerVerticalLaunchGetsLongerTrajectoryAllowance() {
        long shortLaunch = PlayerData.estimateServerLaunchMs(new Vector(0.0, 0.5, 0.0));
        long tallLaunch = PlayerData.estimateServerLaunchMs(new Vector(0.0, 2.5, 0.0));

        assertTrue(shortLaunch >= 1_000L);
        assertTrue(tallLaunch > shortLaunch);
        assertTrue(tallLaunch <= 15_000L);
    }

    @Test
    void rotationHistoryAnswersViewAtOrBeforeTick() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.addRotation(100, 10.0f, 5.0f);
        data.addRotation(102, 40.0f, 8.0f);
        data.addRotation(102, 45.0f, 9.0f); // 同 tick 覆盖

        assertEquals(10.0f, data.rotationAtOrBefore(101).yaw());
        assertEquals(45.0f, data.rotationAtOrBefore(102).yaw());
        assertEquals(45.0f, data.rotationAtOrBefore(200).yaw());
        assertTrue(data.rotationAtOrBefore(99) == null);

        data.addRotation(130, 50.0f, 0.0f); // 距 100 超过 16 tick，最老样本被裁剪
        assertTrue(data.rotationAtOrBefore(101) == null);
    }

    @Test
    void namedBuffersAreIsolatedFromCheckBuffers() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");

        data.buffer(CheckType.KILL_AURA, 2.0);
        assertEquals(1.0, data.buffer("kill-aura.snapback", 1.0), 0.0001);
        assertEquals(2.5, data.buffer(CheckType.KILL_AURA, 0.5), 0.0001);

        data.resetBuffer("kill-aura.snapback");
        assertEquals(1.0, data.buffer("kill-aura.snapback", 1.0), 0.0001);
        assertEquals(0.0, data.buffer("kill-aura.snapback", -5.0), 0.0001);
    }

    @Test
    void burstCountingRestartsEveryTickAndFlagsOnce() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");

        assertEquals(1, data.countAttackInTick(50));
        assertEquals(2, data.countAttackInTick(50));
        assertEquals(3, data.countAttackInTick(50));
        assertEquals(1, data.countAttackInTick(51));

        assertTrue(data.markBurstFlagged(51));
        assertFalse(data.markBurstFlagged(51));
        assertTrue(data.markBurstFlagged(52));
    }
}
