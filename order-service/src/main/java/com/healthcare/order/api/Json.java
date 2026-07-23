package com.healthcare.order.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer for the order-service HTTP layer.
 *
 * <p>The build environment is fully offline with no Jackson/Gson on the classpath, and the
 * order-service deliberately pulls in no HTTP/JSON frameworks. This class provides just enough
 * JSON to implement the small, well-defined wire contract between {@code customer-ui} and
 * {@code order-service}: request bodies are flat objects and responses are shallow objects,
 * arrays, strings, numbers, booleans, and {@code null}.</p>
 *
 * <h2>Writing ({@link #stringify(Object)})</h2>
 * <p>Serializes the following Java types:</p>
 * <ul>
 *   <li>{@code null} &rarr; {@code null}</li>
 *   <li>{@link String} &rarr; a quoted, escaped JSON string</li>
 *   <li>{@link Boolean} &rarr; {@code true}/{@code false}</li>
 *   <li>{@link BigDecimal} &rarr; an unquoted JSON number via {@link BigDecimal#toPlainString()}
 *       (no exponent, monetary scale preserved)</li>
 *   <li>other {@link Number}s &rarr; their {@code toString()} as an unquoted number</li>
 *   <li>{@link Map} &rarr; a JSON object (keys stringified, insertion order preserved by the map)</li>
 *   <li>{@link List} &rarr; a JSON array</li>
 * </ul>
 * <p>Any other type is rejected with an {@link IllegalArgumentException} rather than silently
 * mis-serialized.</p>
 *
 * <h2>Reading ({@link #parse(String)})</h2>
 * <p>A small recursive-descent parser that returns {@link Map}{@code <String,Object>} for objects,
 * {@link List}{@code <Object>} for arrays, {@link String}, {@link BigDecimal} for every number
 * (so monetary precision is never lost to {@code double}), {@link Boolean}, or {@code null}. It
 * rejects malformed input, control characters in strings, and trailing content after the top-level
 * value with a {@link JsonException}. It is intentionally strict and small; it is not a
 * general-purpose JSON library.</p>
 *
 * <p>The class is stateless (all methods are static) and therefore thread-safe.</p>
 */
public final class Json {

    private Json() {
        // Utility class: no instances.
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Serializes a supported Java value to a compact JSON string.
     *
     * @param value the value to serialize (see class docs for supported types); may be
     *              {@code null}
     * @return the JSON text
     * @throws IllegalArgumentException if {@code value} (or a nested element) is of an
     *                                  unsupported type
     */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
        } else if (value instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            throw new IllegalArgumentException(
                    "Json.stringify: unsupported type " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Object key = entry.getKey();
            writeString(sb, key == null ? "null" : key.toString());
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object element : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, element);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    /**
     * Parses a JSON document into Java objects (see class docs for the type mapping).
     *
     * @param text the JSON text to parse; must not be {@code null}
     * @return the parsed value: {@link Map}, {@link List}, {@link String}, {@link BigDecimal},
     *         {@link Boolean}, or {@code null}
     * @throws JsonException        if the text is malformed or has trailing content
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new NullPointerException("text must not be null");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("Unexpected trailing content at index " + parser.pos);
        }
        return value;
    }

    /**
     * Parses a JSON document and asserts the top-level value is an object.
     *
     * @param text the JSON text to parse
     * @return the parsed object as a {@link Map}
     * @throws JsonException if the text is malformed or the top-level value is not an object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object at the top level");
        }
        return (Map<String, Object>) value;
    }

    /** Strict recursive-descent parser over a fixed input string. */
    private static final class Parser {

        /**
         * Maximum object/array nesting depth accepted by the parser. The order-service wire
         * contract exchanges only shallow objects and arrays (the deepest legitimate shape is a
         * few levels), so this cap never rejects a real document; it exists solely to reject
         * pathologically-nested input that would otherwise recurse until the thread stack
         * overflows. See {@link #readValue()}.
         */
        private static final int MAX_DEPTH = 64;

        private final String src;
        private int pos;
        /** Current object/array nesting depth; bounded by {@link #MAX_DEPTH}. */
        private int depth;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of input");
            }
            // Guard against uncontrolled recursion on deeply-nested input (CWE-674). Without this,
            // a tiny body such as 16 000 nested '[' characters drives this method (mutually
            // recursive with readObject/readArray) until the thread stack overflows. A
            // StackOverflowError is a java.lang.Error, so it bypasses the handlers' RuntimeException
            // catch blocks, terminating the worker with an empty reply (a non-contract response)
            // plus thread churn and stack-trace log amplification. Throwing a JsonException before
            // recursing turns the vector into the standard 400 error envelope on every endpoint.
            if (++depth > MAX_DEPTH) {
                throw new JsonException("maximum nesting depth exceeded (" + MAX_DEPTH + ")");
            }
            try {
                char c = src.charAt(pos);
                return switch (c) {
                    case '{' -> readObject();
                    case '[' -> readArray();
                    case '"' -> readString();
                    case 't', 'f' -> readBoolean();
                    case 'n' -> readNull();
                    default -> {
                        if (c == '-' || (c >= '0' && c <= '9')) {
                            yield readNumber();
                        }
                        throw new JsonException("Unexpected character '" + c + "' at index " + pos);
                    }
                };
            } finally {
                depth--;
            }
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException("Expected string key at index " + pos);
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or '}' at index " + (pos - 1));
                }
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new java.util.ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or ']' at index " + (pos - 1));
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("Unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new JsonException("Unterminated escape");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> sb.append(readUnicodeEscape());
                        default -> throw new JsonException("Invalid escape '\\" + esc + "'");
                    }
                } else if (c < 0x20) {
                    throw new JsonException("Unescaped control character in string at index " + (pos - 1));
                } else {
                    sb.append(c);
                }
            }
        }

        private char readUnicodeEscape() {
            if (pos + 4 > src.length()) {
                throw new JsonException("Incomplete unicode escape");
            }
            String hex = src.substring(pos, pos + 4);
            try {
                int code = Integer.parseInt(hex, 16);
                pos += 4;
                return (char) code;
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid unicode escape '\\u" + hex + "'");
            }
        }

        private BigDecimal readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd() && isNumberChar(src.charAt(pos))) {
                pos++;
            }
            String number = src.substring(start, pos);
            try {
                return new BigDecimal(number);
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number '" + number + "'");
            }
        }

        private boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private Boolean readBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("Invalid literal at index " + pos);
        }

        private Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("Invalid literal at index " + pos);
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of input");
            }
            return src.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of input");
            }
            return src.charAt(pos++);
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new JsonException("Expected '" + expected + "' but found '" + c
                        + "' at index " + (pos - 1));
            }
        }
    }
}
