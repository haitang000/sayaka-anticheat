package cn.haitang.anticheat.velocity;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityCoreTest {

    private static java.io.InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /** 内核版本取自所属 jar 的 velocity-plugin.json，热替换后不再等于宿主容器版本 */
    @Test
    void readsVersionFromDescriptor() {
        assertEquals("2.1.0.8", VelocityCore.descriptorVersion(stream(
                "{\"id\":\"sayaka-anticheat\",\"version\":\"2.1.0.8\"}")));
    }

    @Test
    void fallsBackOnMissingOrInvalidDescriptor() {
        assertEquals("0.0.0", VelocityCore.descriptorVersion(null));
        assertEquals("0.0.0", VelocityCore.descriptorVersion(stream("not json")));
        assertEquals("0.0.0", VelocityCore.descriptorVersion(stream("{\"id\":\"x\"}")));
        assertEquals("0.0.0", VelocityCore.descriptorVersion(stream("{\"version\":\"\"}")));
    }
}
