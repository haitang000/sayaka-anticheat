package cn.haitang.anticheat.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 序列化 / 解析工具，避免为 Web 面板引入额外依赖。
 *
 * <p>序列化支持 {@link Map}、{@link List}、{@link String}、{@link Number}、
 * {@link Boolean} 与 {@code null}；解析实现完整的递归下降，返回
 * Map / List / String / Double / Boolean / null。仅用于面板 API，性能不敏感。
 */
public final class Json {

    private Json() {}

    // ---- 序列化 ----

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Boolean bool) {
            out.append(bool.booleanValue() ? "true" : "false");
        } else if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (value instanceof Double || value instanceof Float) {
                if (d == Math.rint(d) && !Double.isInfinite(d)) {
                    out.append(Long.toString((long) d));
                } else {
                    out.append(number);
                }
            } else {
                out.append(number);
            }
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, element);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder out, String string) {
        out.append('"');
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- 解析 ----

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("JSON 结尾存在多余字符");
        }
        return value;
    }

    /** 便捷方法：解析后确保结果为对象，否则抛出 {@link IllegalArgumentException}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("期望 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        Object readValue() {
            if (atEnd()) throw new IllegalArgumentException("JSON 意外结束");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw new IllegalArgumentException("对象键必须是字符串");
                String key = readString();
                skipWhitespace();
                if (peek() != ':') throw new IllegalArgumentException("对象键后缺少冒号");
                pos++;
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') { pos++; continue; }
                if (next == '}') { pos++; break; }
                throw new IllegalArgumentException("对象中缺少逗号或右括号");
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') { pos++; continue; }
                if (next == ']') { pos++; break; }
                throw new IllegalArgumentException("数组中缺少逗号或右括号");
            }
            return list;
        }

        private String readString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (atEnd()) throw new IllegalArgumentException("字符串未闭合");
                char c = text.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (atEnd()) throw new IllegalArgumentException("转义未完成");
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("Unicode 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("非法转义: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("非法的布尔字面量");
        }

        private Object readNull() {
            if (text.startsWith("null", pos)) { pos += 4; return null; }
            throw new IllegalArgumentException("非法字面量");
        }

        private Double readNumber() {
            int start = pos;
            while (pos < text.length()
                    && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (pos == start) throw new IllegalArgumentException("非法的 JSON 值");
            try {
                return Double.parseDouble(text.substring(start, pos));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("非法数字: " + text.substring(start, pos));
            }
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(pos);
        }
    }
}
