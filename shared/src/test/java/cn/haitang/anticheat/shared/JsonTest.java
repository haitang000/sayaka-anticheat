package cn.haitang.anticheat.shared;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void writesNestedStructuresWithEscaping() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "a\"b\\c\nd");
        map.put("count", 3);
        map.put("ratio", 1.5);
        map.put("flag", true);
        map.put("missing", null);
        map.put("list", List.of("x", 2));

        assertEquals("{\"name\":\"a\\\"b\\\\c\\nd\",\"count\":3,\"ratio\":1.5,"
                + "\"flag\":true,\"missing\":null,\"list\":[\"x\",2]}", Json.write(map));
    }

    @Test
    void wholeDoublesAreWrittenWithoutTrailingZero() {
        assertEquals("18", Json.write(18.0));
        assertEquals("18.5", Json.write(18.5));
    }

    @Test
    void nonFiniteNumbersBecomeNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void parsesObjectWithMixedValueTypes() {
        Map<String, Object> map = Json.parseObject(
                "{\"id\": \"abc\", \"approved\": true, \"n\": 12, \"note\": null}");
        assertEquals("abc", map.get("id"));
        assertEquals(Boolean.TRUE, map.get("approved"));
        assertEquals(12.0, map.get("n"));
        assertTrue(map.containsKey("note"));
        assertNull(map.get("note"));
    }

    @Test
    void parsesEscapesAndUnicode() {
        Map<String, Object> map = Json.parseObject("{\"s\":\"line1\\nline2\\u0041\\\"q\\\"\"}");
        assertEquals("line1\nline2A\"q\"", map.get("s"));
    }

    @Test
    void roundTripsThroughWriteAndParse() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reason", "含\"引号\"与\\反斜杠\n换行");
        map.put("id", "3f9c1a2e");
        Map<String, Object> restored = Json.parseObject(Json.write(map));
        assertEquals(map.get("reason"), restored.get("reason"));
        assertEquals(map.get("id"), restored.get("id"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1,2]"));
    }

    @Test
    void parsesEmptyContainers() {
        assertTrue(Json.parseObject("{}").isEmpty());
        assertEquals(List.of(), Json.parse("[]"));
        assertFalse(Json.parseObject("{\"a\":[]}").isEmpty());
    }
}
