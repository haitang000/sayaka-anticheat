package cn.haitang.anticheat.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {

    @Test
    void comparesStableAndPrereleaseVersions() {
        SemanticVersion stable = parse("2.1.0");
        assertTrue(stable.compareTo(parse("2.0.9")) > 0);
        assertTrue(stable.compareTo(parse("v2.1.0-rc.1")) > 0);
        assertTrue(parse("2.1.0-rc.10").compareTo(parse("2.1.0-rc.2")) > 0);
    }

    @Test
    void acceptsTagPrefixAndBuildMetadata() {
        assertEquals("2.3.4", parse("v2.3.4+build.7").toString());
        assertTrue(SemanticVersion.parse("2.3").isEmpty());
        assertTrue(SemanticVersion.parse("release-2.3.4").isEmpty());
    }

    @Test
    void comparesFourPartReleaseVersions() {
        SemanticVersion revision = parse("2.1.0.1");

        assertEquals("2.1.0.1", revision.toString());
        assertTrue(revision.compareTo(parse("2.1.0")) > 0);
        assertTrue(revision.compareTo(parse("2.1.1")) < 0);
        assertTrue(revision.compareTo(parse("2.1.0.1-beta.1")) > 0);
    }

    private static SemanticVersion parse(String value) {
        return SemanticVersion.parse(value).orElseThrow();
    }
}
