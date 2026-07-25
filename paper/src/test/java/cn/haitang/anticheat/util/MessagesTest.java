package cn.haitang.anticheat.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void substitutesEveryKnownPlaceholder() {
        String out = Messages.apply("%player% 未通过 %check% VL %vl%",
                Map.of("player", "Notch", "check", "Speed", "vl", "7.5"));

        assertEquals("Notch 未通过 Speed VL 7.5", out);
    }

    @Test
    void unknownPlaceholdersAreLeftAloneRatherThanBlanked() {
        assertEquals("hi %nobody%", Messages.apply("hi %nobody%", Map.of("player", "Notch")));
    }

    @Test
    void valuesAreNotRescannedForPlaceholders() {
        // 过去是按 Map 迭代顺序逐个 String.replace，而 Map.of 的顺序按 JVM 启动随机化，
        // 值里的 %player% 可能被后一轮替换掉，导致警报显示成另一个玩家的名字。
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("detail", "域名: %player%.com");
        placeholders.put("player", "Notch");

        assertEquals("Notch / 域名: %player%.com",
                Messages.apply("%player% / %detail%", placeholders));
    }

    @Test
    void colourCodesInsideValuesAreNeutralised() {
        // AntiAds 的 detail 直接取自玩家聊天内容；着色发生在模板上，
        // 值里的 & / § 不能再被翻译成颜色码去污染管理员警报。
        String out = Messages.apply("详情 %detail%",
                Map.of("detail", "域名: &k&4evil.com"));

        assertEquals("详情 域名: ＆k＆4evil.com", out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("&k"));

        String sectionSign = Messages.apply("详情 %detail%",
                Map.of("detail", "域名: §cevil.com"));
        org.junit.jupiter.api.Assertions.assertFalse(sectionSign.contains("§"));
    }

    @Test
    void plainValuesPassThroughUntouched() {
        assertEquals("玩家 Notch", Messages.apply("玩家 %player%", Map.of("player", "Notch")));
        assertEquals("无占位符", Messages.apply("无占位符", Map.of("player", "Notch")));
        assertEquals("原样 %x%", Messages.apply("原样 %x%", Map.of()));
    }

    @Test
    void dollarAndBackslashInValuesDoNotBreakRegexReplacement() {
        assertEquals("值 a$1\\b", Messages.apply("值 %detail%", Map.of("detail", "a$1\\b")));
    }
}
