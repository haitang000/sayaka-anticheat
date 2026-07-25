package cn.haitang.anticheat.violation;

import cn.haitang.anticheat.check.CheckType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentExecutorTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void acceptsNamesThatAreSafeToSpliceIntoAConsoleCommand() {
        assertTrue(PunishmentExecutor.isSafeHookName("Notch"));
        assertTrue(PunishmentExecutor.isSafeHookName("some_player9"));
        assertTrue(PunishmentExecutor.isSafeHookName(".BedrockName"));
    }

    @Test
    void rejectsNamesThatWouldInjectExtraArgumentsOrCommands() {
        // 离线模式 / 代理转发 / Geyser 玩家名可含空格，配了
        // "lp user %player% parent add suspect" 时就能把自己提权。
        assertFalse(PunishmentExecutor.isSafeHookName("x parent set admin x"));
        assertFalse(PunishmentExecutor.isSafeHookName("evil; op evil"));
        assertFalse(PunishmentExecutor.isSafeHookName("bad\nop bad"));
        assertFalse(PunishmentExecutor.isSafeHookName(""));
        assertFalse(PunishmentExecutor.isSafeHookName(null));
        assertFalse(PunishmentExecutor.isSafeHookName("thisnameiswaytoolongtobereal"));
    }

    @Test
    void substitutesEveryPlaceholderIncludingUuid() {
        String command = PunishmentExecutor.buildHookCommand(
                "lp user %player% parent add suspect %check% %hours% %punishment-id% %uuid%",
                "Notch", ID, CheckType.SPEED, 6, "PID-7");

        assertEquals("lp user Notch parent add suspect " + CheckType.SPEED.id()
                + " 6 PID-7 " + ID, command);
    }

    @Test
    void missingPunishmentIdAndUuidCollapseToEmptyStrings() {
        assertEquals("say  kicked ()", PunishmentExecutor.buildHookCommand(
                "say %uuid% kicked (%punishment-id%)", "Notch", null, CheckType.SPEED, 0, ""));
    }
}
