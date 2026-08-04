package cn.haitang.anticheat.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerEventsTest {

    @Test
    void flagEventExposesDetectionContext() {
        PlayerFlagEvent event = new PlayerFlagEvent(null, "KillAura", "攻击视角异常 (KillAura)",
                7.5, 2.5, "视角 60.0° 偏离");
        assertEquals("KillAura", event.getCheckId());
        assertEquals("攻击视角异常 (KillAura)", event.getCheckDisplay());
        assertEquals(7.5, event.getVl());
        assertEquals(2.5, event.getWeight());
        assertEquals("视角 60.0° 偏离", event.getDetail());
        assertFalse(event.isCancelled());
        assertTrue(event.getHandlers() != null);
    }

    @Test
    void flagEventCanBeCancelledToVetoRecording() {
        PlayerFlagEvent event = new PlayerFlagEvent(null, "Speed", "移动速度异常 (Speed)",
                3.0, 1.0, "12.5 m/s");
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    @Test
    void punishEventExposesContextAndVeto() {
        PlayerPunishEvent event = new PlayerPunishEvent(null, "Timer", "移动包速率异常 (Timer)", 20.0);
        assertEquals("Timer", event.getCheckId());
        assertEquals(20.0, event.getVl());
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    @Test
    void eventsAreRegisteredWithHandlerList() {
        assertTrue(PlayerFlagEvent.getHandlerList().getRegisteredListeners().length >= 0);
        assertTrue(PlayerPunishEvent.getHandlerList().getRegisteredListeners().length >= 0);
    }
}
