package cn.haitang.anticheat.sync;

import cn.haitang.anticheat.data.PersistentStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerCodecTest {

    @Test
    void roundTripsBanEnvelopeWithEvidenceAndPlayerState() {
        UUID playerId = UUID.randomUUID();
        String punishmentId = UUID.randomUUID().toString();
        PersistentStore.PlayerPunishmentState state = new PersistentStore.PlayerPunishmentState(
                playerId, "Cheater", List.of(100L, 200L), 2,
                List.of("2026-07-15 [封禁] Speed"));
        PersistentStore.PunishmentRecord record = new PersistentStore.PunishmentRecord(
                punishmentId, playerId, "Cheater", 1_000L, 3_601_000L,
                "speed", 21.5, 1, 2,
                List.of(new PersistentStore.WarningEvidence(900L, "speed", 2, 12.5)),
                List.of(new PersistentStore.DetectionEvidence(
                        950L, "speed", 21.5, "horizontal=1.2", 42)));
        CrossServerCodec.Envelope envelope = new CrossServerCodec.Envelope(
                "BAN", "server-a", 1_000L, state,
                new CrossServerCodec.SyncedPunishment(record, "§c封禁\n§7ID: " + punishmentId),
                false);

        CrossServerCodec.Envelope restored = CrossServerCodec.decodeEnvelope(
                CrossServerCodec.encodeEnvelope(envelope));

        assertEquals("BAN", restored.type());
        assertEquals(state, restored.state());
        assertEquals(record, restored.punishment().record());
        assertTrue(restored.punishment().screen().contains(punishmentId));
    }

    @Test
    void roundTripsUnbanResetEvent() {
        UUID playerId = UUID.randomUUID();
        PersistentStore.PlayerPunishmentState state = new PersistentStore.PlayerPunishmentState(
                playerId, "Player", List.of(), 0, List.of());
        CrossServerCodec.Envelope envelope = new CrossServerCodec.Envelope(
                "UNBAN", "server-b", 2_000L, state, null, true);

        CrossServerCodec.Envelope restored = CrossServerCodec.decodeEnvelope(
                CrossServerCodec.encodeEnvelope(envelope));

        assertTrue(restored.reset());
        assertEquals(state, restored.state());
        assertEquals(null, restored.punishment());
    }
}
