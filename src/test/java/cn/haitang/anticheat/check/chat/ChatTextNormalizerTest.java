package cn.haitang.anticheat.check.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatTextNormalizerTest {

    // ---- forSpam：刷屏对比用的激进归一化 ----

    @Test
    void spamNormalizationKeepsOnlyLowercaseLettersAndDigits() {
        assertEquals("helloworld123", ChatTextNormalizer.forSpam("Hello, World! 123"));
        assertEquals("买金币", ChatTextNormalizer.forSpam("买 金 币 ！！"));
    }

    @Test
    void spamNormalizationFoldsFullWidthCharacters() {
        assertEquals("helloworld", ChatTextNormalizer.forSpam("Ｈｅｌｌｏ　Ｗｏｒｌｄ！"));
        assertEquals("123", ChatTextNormalizer.forSpam("１２３"));
    }

    @Test
    void spamNormalizationStripsZeroWidthCharacters() {
        // 字符串内嵌零宽字符：U+200B / U+200C / U+200D / U+FEFF
        String zeroWidthSpam = "he​l‌l‍o﻿";
        assertEquals("hello", ChatTextNormalizer.forSpam(zeroWidthSpam));
    }

    @Test
    void spamNormalizationHandlesNullAndEmptyInput() {
        assertEquals("", ChatTextNormalizer.forSpam(null));
        assertEquals("", ChatTextNormalizer.forSpam(""));
        assertEquals("", ChatTextNormalizer.forSpam("!?。 、"));
    }

    // ---- forAds：广告识别前的地址还原 ----

    @Test
    void adsNormalizationStripsColorCodesInBothStyles() {
        assertEquals("play.example.com",
                ChatTextNormalizer.forAds("&c&lplay&r.example.com"));
        assertEquals("play.example.com",
                ChatTextNormalizer.forAds("§cplay§r.example.com"));
    }

    @Test
    void adsNormalizationRewritesDotWordsToRealDots() {
        assertEquals("play.example.com",
                ChatTextNormalizer.forAds("play[dot]example[dot]com"));
        assertEquals("play.example.com",
                ChatTextNormalizer.forAds("play (dot) example (dot) com"));
        assertEquals("play.example.com",
                ChatTextNormalizer.forAds("play 点 example 点 com"));
    }

    @Test
    void adsNormalizationCollapsesSpacedOutDotsAndUrlSeparators() {
        assertEquals("play.example.com",
                ChatTextNormalizer.forAds("play . example 。 com"));
        assertEquals("http://example.com",
                ChatTextNormalizer.forAds("http : / / example . com"));
    }

    @Test
    void adsNormalizationFoldsFullWidthHostNames() {
        assertEquals("evil.net", ChatTextNormalizer.forAds("ｅｖｉｌ．ｎｅｔ"));
    }

    @Test
    void adsNormalizationLowercasesAndCollapsesWhitespace() {
        assertEquals("visit example.com now",
                ChatTextNormalizer.forAds("  Visit   EXAMPLE.COM \t now  "));
    }
}
