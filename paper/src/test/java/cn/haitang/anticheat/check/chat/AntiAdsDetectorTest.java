package cn.haitang.anticheat.check.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AntiAdsDetectorTest {

    private static final Set<String> TLDS = Set.of("com", "net", "org", "cn", "gg");
    private final AntiAdsDetector detector = new AntiAdsDetector();

    @Test
    void detectsIpv4DomainAndDiscordInvite() {
        assertEquals("服务器 IP", detector.find("来玩 127.0.0.1:25565", List.of(), TLDS).kind());
        assertEquals("域名", detector.find("visit play.example.com now", List.of(), TLDS).kind());
        assertEquals("Discord 邀请", detector.find("discord.gg/AbC123", List.of(), TLDS).kind());
    }

    @Test
    void detectsCommonDotObfuscationAndFullWidthText() {
        AntiAdsDetector.Match bracketed = detector.find("play[dot]bad[dot]com", List.of(), TLDS);
        AntiAdsDetector.Match spaced = detector.find("play 点 bad 点 cn", List.of(), TLDS);
        AntiAdsDetector.Match fullWidth = detector.find("ｅｖｉｌ．ｎｅｔ", List.of(), TLDS);

        assertNotNull(bracketed);
        assertNotNull(spaced);
        assertNotNull(fullWidth);
    }

    @Test
    void honorsExactAndSubdomainAllowlistWithoutHidingOtherAds() {
        assertNull(detector.find("官网 example.com / play.example.com", List.of("example.com"), TLDS));
        assertNotNull(detector.find("官网 example.com，另一个 evil.com", List.of("example.com"), TLDS));
    }

    @Test
    void ignoresUnsupportedTldsAndNormalConversation() {
        assertNull(detector.find("版本是 1.20，不是地址", List.of(), TLDS));
        assertNull(detector.find("请查看 readme.local", List.of(), TLDS));
    }

    @Test
    void reportsIpWithPortAndRejectsImpossibleOctets() {
        AntiAdsDetector.Match match = detector.find("连接 192.168.1.1:25565", List.of(), TLDS);
        assertNotNull(match);
        assertEquals("192.168.1.1:25565", match.value());

        assertNull(detector.find("坐标 999.400.712.803", List.of(), TLDS));
        assertNull(detector.find("数据 256.1.1.1", List.of(), TLDS));
    }

    @Test
    void allowlistEntriesTolerateSchemePortAndCase() {
        List<String> allowed = List.of("https://Example.COM:25565/forum");

        assertNull(detector.find("官网 example.com", allowed, TLDS));
        assertNull(detector.find("论坛 bbs.example.com", allowed, TLDS));
        assertNotNull(detector.find("另一个 evil.com", allowed, TLDS));
    }

    @Test
    void normalizeTldsLowercasesStripsLeadingDotAndDropsInvalidEntries() {
        Set<String> tlds = AntiAdsDetector.normalizeTlds(
                List.of(".COM", "Net", "x", "c0m", "org"));

        assertEquals(Set.of("com", "net", "org"), tlds);
    }
}
