package cn.haitang.anticheat.check.chat;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AntiAdsDetector {

    private static final Pattern DISCORD_INVITE = Pattern.compile(
            "(?<![a-z0-9.-])((?:discord(?:app)?\\.com/invite|discord\\.gg)/?[a-z0-9-]+)");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9.])((?:[0-9]{1,3}\\.){3}[0-9]{1,3})(?::([0-9]{2,5}))?(?![0-9.])");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?<![a-z0-9_-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,24}))"
                    + "(?::[0-9]{2,5})?(?:/[^\\s]*)?");

    record Match(String kind, String value) {
    }

    Match find(String rawMessage, List<String> allowedHosts, Set<String> blockedTlds) {
        String message = ChatTextNormalizer.forAds(rawMessage);

        Matcher invite = DISCORD_INVITE.matcher(message);
        while (invite.find()) {
            String value = invite.group(1);
            String host = value.substring(0, value.indexOf('/'));
            if (!isAllowed(host, allowedHosts)) return new Match("Discord 邀请", value);
        }

        Matcher ip = IPV4.matcher(message);
        while (ip.find()) {
            String host = ip.group(1);
            if (isValidIpv4(host) && !isAllowed(host, allowedHosts)) {
                String value = ip.group(2) == null ? host : host + ":" + ip.group(2);
                return new Match("服务器 IP", value);
            }
        }

        Matcher domain = DOMAIN.matcher(message);
        while (domain.find()) {
            String host = domain.group(1);
            String tld = domain.group(2).toLowerCase(Locale.ROOT);
            if (blockedTlds.contains(tld) && !isAllowed(host, allowedHosts)) {
                return new Match("域名", host);
            }
        }
        return null;
    }

    static Set<String> normalizeTlds(List<String> configured) {
        return configured.stream()
                .map(value -> value.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .filter(value -> value.matches("[a-z]{2,24}"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean isAllowed(String candidate, List<String> allowedHosts) {
        String host = candidate.toLowerCase(Locale.ROOT);
        for (String configured : allowedHosts) {
            String allowed = normalizeAllowedHost(configured);
            if (!allowed.isEmpty() && (host.equals(allowed) || host.endsWith("." + allowed))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeAllowedHost(String configured) {
        String value = ChatTextNormalizer.forAds(configured)
                .replaceFirst("^(?:https?://)", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.lastIndexOf(':');
        if (colon > 0) value = value.substring(0, colon);
        return value.trim();
    }

    private boolean isValidIpv4(String value) {
        String[] octets = value.split("\\.");
        if (octets.length != 4) return false;
        for (String octet : octets) {
            int number = Integer.parseInt(octet);
            if (number > 255) return false;
        }
        return true;
    }
}
