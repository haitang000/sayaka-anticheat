package cn.haitang.anticheat.check.player;

import cn.haitang.anticheat.data.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryMoveCheckTest {

    @Test
    void skipsDetectionAtConfiguredPingThreshold() {
        assertFalse(InventoryMoveCheck.shouldSkipForPing(199, 200));
        assertTrue(InventoryMoveCheck.shouldSkipForPing(200, 200));
        assertTrue(InventoryMoveCheck.shouldSkipForPing(350, 200));
    }

    @Test
    void nonPositiveThresholdDisablesPingExemption() {
        assertFalse(InventoryMoveCheck.shouldSkipForPing(350, 0));
        assertFalse(InventoryMoveCheck.shouldSkipForPing(350, -1));
    }

    @Test
    void closingContainerClearsHighPingSampleCutoff() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "player");
        data.setContainerOpen(true);
        assertEquals(data.getContainerOpenAt(), data.getInventoryMoveIgnoreBefore());
        data.setInventoryMoveIgnoreBefore(1234L);

        data.setContainerOpen(false);

        assertFalse(data.isContainerOpen());
        assertEquals(0L, data.getInventoryMoveIgnoreBefore());
    }
}
