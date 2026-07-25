package cn.haitang.anticheat.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketSequencerTest {

    @Test
    void clientPacketsAdvanceTheCounter() {
        PacketSequencer sequencer = new PacketSequencer();

        assertEquals(1L, sequencer.next());
        assertEquals(2L, sequencer.next());
        assertEquals(3L, sequencer.next());
    }

    @Test
    void serverPacketsMarkThePositionWithoutAdvancing() {
        PacketSequencer sequencer = new PacketSequencer();
        sequencer.next();

        assertEquals(1L, sequencer.current());
        assertEquals(1L, sequencer.current());
        assertEquals(2L, sequencer.next());
    }

    @Test
    void knockbackBetweenSwingAndAttackDoesNotWidenTheirGap() {
        // 近战对拼：挥臂 → 收到多次击退（服务端下发）→ 攻击。
        // 击退曾经也 ++ 计数，把 SWING 与 ATTACK 的序号差从 1 撑到 4，
        // 越过 swingMatches 的窗口 3，使合法玩家被 NoSwing 判违规。
        PacketSequencer sequencer = new PacketSequencer();

        long swingSequence = sequencer.next();
        for (int i = 0; i < 3; i++) {
            assertEquals(swingSequence, sequencer.current());
        }
        long attackSequence = sequencer.next();

        assertEquals(1L, attackSequence - swingSequence);

        long attackNanos = 1_000_000_000L;
        org.junit.jupiter.api.Assertions.assertTrue(PacketTimeline.swingMatches(
                attackSequence, attackNanos, swingSequence,
                swingSequence, attackNanos - 20_000_000L));
    }
}
