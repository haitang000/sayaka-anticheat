package cn.haitang.anticheat.update;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Release version parser used to decide whether a three- or four-part version is newer. */
final class SemanticVersion implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:\\.(0|[1-9]\\d*))?"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private final int major;
    private final int minor;
    private final int patch;
    private final int revision;
    private final boolean hasRevision;
    private final List<String> prerelease;

    private SemanticVersion(int major, int minor, int patch, int revision,
                            boolean hasRevision, List<String> prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.revision = revision;
        this.hasRevision = hasRevision;
        this.prerelease = prerelease;
    }

    static Optional<SemanticVersion> parse(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = PATTERN.matcher(value.trim());
        if (!matcher.matches()) return Optional.empty();
        try {
            String revision = matcher.group(4);
            String suffix = matcher.group(5);
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    revision == null ? 0 : Integer.parseInt(revision),
                    revision != null,
                    suffix == null ? List.of() : List.of(suffix.split("\\."))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) return result;
        result = Integer.compare(minor, other.minor);
        if (result != 0) return result;
        result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        result = Integer.compare(revision, other.revision);
        if (result != 0) return result;

        if (prerelease.isEmpty()) return other.prerelease.isEmpty() ? 0 : 1;
        if (other.prerelease.isEmpty()) return -1;
        int shared = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < shared; i++) {
            result = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
            if (result != 0) return result;
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            String normalizedLeft = stripLeadingZeroes(left);
            String normalizedRight = stripLeadingZeroes(right);
            int length = Integer.compare(normalizedLeft.length(), normalizedRight.length());
            return length != 0 ? length : normalizedLeft.compareTo(normalizedRight);
        }
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        return left.compareTo(right);
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') index++;
        return value.substring(index);
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch
                + (hasRevision ? "." + revision : "");
        return prerelease.isEmpty() ? base : base + "-" + String.join(".", prerelease);
    }
}
