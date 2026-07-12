package cn.haitang.anticheat.check.chat;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class ChatTextNormalizer {

    private static final Pattern COLOR_CODE = Pattern.compile("(?i)[&§][0-9A-FK-ORX]");
    private static final Pattern DOT_WORD = Pattern.compile(
            "(?i)(?:[\\[({<]\\s*)?(?:dot|点)(?:\\s*[\\])}>])?");
    private static final Pattern DOT_SPACING = Pattern.compile("\\s*[.。．·]\\s*");
    private static final Pattern URL_SPACING = Pattern.compile("\\s*([:/])\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ChatTextNormalizer() {
    }

    static String forSpam(String message) {
        String normalized = normalizeUnicode(message).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    static String forAds(String message) {
        String normalized = COLOR_CODE.matcher(normalizeUnicode(message)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        normalized = DOT_WORD.matcher(normalized).replaceAll(".");
        normalized = DOT_SPACING.matcher(normalized).replaceAll(".");
        normalized = URL_SPACING.matcher(normalized).replaceAll("$1");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    private static String normalizeUnicode(String message) {
        if (message == null || message.isEmpty()) return "";
        return Normalizer.normalize(message, Normalizer.Form.NFKC)
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "");
    }
}
