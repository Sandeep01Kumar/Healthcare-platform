package com.healthcare.order.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link Json}, the dependency-free JSON codec the order-service uses on the wire
 * (the module deliberately pulls in no Jackson/Gson/Spring). These tests pin the exact
 * serialization and parsing behavior the HTTP layer and the Java&#8594;Node notification bridge
 * rely on, so a regression in the hand-rolled codec is caught here rather than as a mysterious
 * wire-format mismatch downstream.
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li><b>stringify</b> of every supported type — {@code null}, {@link String} (with the full
 *       escape set, including {@code \\u00xx} for other control characters), {@link Boolean},
 *       {@link BigDecimal} (emitted as an unquoted JSON number via {@code toPlainString}, so money
 *       precision and scale survive), other {@link Number}s, insertion-ordered {@link Map}s, and
 *       {@link List}s — plus rejection of an unsupported type with {@link IllegalArgumentException};</li>
 *   <li><b>parse</b> of objects, arrays, strings, numbers (always {@link BigDecimal}), booleans and
 *       {@code null}, with malformed input and trailing content raising {@link JsonException} and a
 *       {@code null} document raising {@link NullPointerException};</li>
 *   <li><b>parseObject</b> requiring a top-level object; and</li>
 *   <li>a <b>round-trip</b> of a nested structure that mirrors the order-view wire DTO, proving the
 *       codec is self-consistent and money values keep their exact scale.</li>
 * </ul>
 */
public class JsonTest {

    // ------------------------------------------------------------------ stringify

    @Test
    void stringifyScalars() {
        assertEquals("null", Json.stringify(null));
        assertEquals("true", Json.stringify(Boolean.TRUE));
        assertEquals("false", Json.stringify(Boolean.FALSE));
        assertEquals("42", Json.stringify(42));
        assertEquals("\"hello\"", Json.stringify("hello"));
    }

    @Test
    void stringifyBigDecimalIsAnUnquotedNumberPreservingScale() {
        // Money must serialize as a JSON number that keeps its scale (e.g. "90.00", not "90").
        assertEquals("90.00", Json.stringify(new BigDecimal("90.00")));
        assertEquals("100.00", Json.stringify(new BigDecimal("100.00")));
        assertEquals("0.00", Json.stringify(new BigDecimal("0.00")));
    }

    @Test
    void stringifyEscapesStringSpecialAndControlCharacters() {
        assertEquals("\"a\\\"b\"", Json.stringify("a\"b"));       // embedded quote
        assertEquals("\"a\\\\b\"", Json.stringify("a\\b"));        // backslash
        assertEquals("\"line1\\nline2\"", Json.stringify("line1\nline2"));
        assertEquals("\"tab\\there\"", Json.stringify("tab\there"));
        assertEquals("\"cr\\r\"", Json.stringify("cr\r"));
        // Other control characters below 0x20 are emitted as lowercase \\u00xx escapes.
        assertEquals("\"x\\u0001y\"", Json.stringify("x\u0001y"));
    }

    @Test
    void stringifyObjectPreservesInsertionOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", true);
        m.put("reason", "OK");
        assertEquals("{\"valid\":true,\"reason\":\"OK\"}", Json.stringify(m));
    }

    @Test
    void stringifyArray() {
        assertEquals("[1,2,3]", Json.stringify(List.of(1, 2, 3)));
        assertEquals("[\"a\",\"b\"]", Json.stringify(List.of("a", "b")));
    }

    @Test
    void stringifyRejectsUnsupportedType() {
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(new Object()));
    }

    // ------------------------------------------------------------------ parse

    @Test
    void parseObjectYieldsMap() {
        Object parsed = Json.parse("{\"a\":1,\"b\":\"two\"}");
        assertInstanceOf(Map.class, parsed);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        assertEquals(0, new BigDecimal("1").compareTo((BigDecimal) m.get("a")));
        assertEquals("two", m.get("b"));
    }

    @Test
    void parseArrayYieldsList() {
        Object parsed = Json.parse("[1,\"two\",true,null]");
        assertInstanceOf(List.class, parsed);
        List<?> list = (List<?>) parsed;
        assertEquals(4, list.size());
        assertInstanceOf(BigDecimal.class, list.get(0));
        assertEquals("two", list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
        assertNull(list.get(3));
    }

    @Test
    void parseNumbersAreBigDecimalAndKeepPrecision() {
        Object parsed = Json.parse("{\"price\":100.00,\"rate\":0.1}");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        assertInstanceOf(BigDecimal.class, m.get("price"));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) m.get("price")));
        assertEquals(0, new BigDecimal("0.1").compareTo((BigDecimal) m.get("rate")));
    }

    @Test
    void parseScalarDocuments() {
        assertEquals("hi", Json.parse("\"hi\""));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
        assertEquals(0, new BigDecimal("7").compareTo((BigDecimal) Json.parse("7")));
    }

    @Test
    void parseMalformedInputThrowsJsonException() {
        assertThrows(JsonException.class, () -> Json.parse("{"));             // unterminated object
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));          // unterminated array
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));      // missing value
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));// unterminated string
        assertThrows(JsonException.class, () -> Json.parse("nul"));           // bad literal
    }

    @Test
    void parseRejectsTrailingContent() {
        assertThrows(JsonException.class, () -> Json.parse("{} garbage"));
        assertThrows(JsonException.class, () -> Json.parse("1 2"));
    }

    @Test
    void parseNullTextThrowsNpe() {
        assertThrows(NullPointerException.class, () -> Json.parse(null));
    }

    @Test
    void parseObjectRequiresTopLevelObject() {
        Map<String, Object> obj = Json.parseObject("{\"k\":\"v\"}");
        assertEquals("v", obj.get("k"));
        assertThrows(JsonException.class, () -> Json.parseObject("[1,2]"));
        assertThrows(JsonException.class, () -> Json.parseObject("\"scalar\""));
    }

    // ------------------------------------------------------------------ round-trip

    @Test
    void roundTripNestedStructurePreservesValuesAndMoneyScale() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", "order-1");
        order.put("status", "CREATED");
        order.put("price", new BigDecimal("100.00"));
        order.put("discountedTotal", new BigDecimal("90.00"));
        Map<String, Object> coupon = new LinkedHashMap<>();
        coupon.put("code", "SAVE10");
        coupon.put("type", "PERCENTAGE");
        coupon.put("value", "10");
        order.put("appliedCoupons", List.of(coupon));

        String json = Json.stringify(order);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Json.parse(json);

        assertEquals("order-1", back.get("id"));
        assertEquals("CREATED", back.get("status"));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) back.get("price")));
        assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) back.get("discountedTotal")));
        // The money fields parsed back as BigDecimal keep scale 2.
        assertEquals(2, ((BigDecimal) back.get("price")).scale());
        assertEquals(2, ((BigDecimal) back.get("discountedTotal")).scale());
        List<?> coupons = (List<?>) back.get("appliedCoupons");
        assertEquals(1, coupons.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> c0 = (Map<String, Object>) coupons.get(0);
        assertEquals("SAVE10", c0.get("code"));
        assertEquals("PERCENTAGE", c0.get("type"));
        assertEquals("10", c0.get("value"));
    }

    // ------------------------------------------------------------------ nesting-depth guard (SEC-2)

    /**
     * Security regression (finding SEC-2, CWE-674): a pathologically deep document must raise the
     * codec's own {@link JsonException} — which the HTTP layer maps to a clean {@code 400} — rather
     * than recursing until the thread stack overflows. A {@link StackOverflowError} is a
     * {@link Error} (not a {@link RuntimeException}), so it would escape both the codec and the
     * handlers' catch blocks; if this test ever caught one, {@code assertThrows(JsonException.class,
     * ...)} would fail because the error would not match and would propagate out.
     */
    @Test
    void parseDeeplyNestedArrayThrowsJsonExceptionNotStackOverflow() {
        String deep = "[".repeat(16_000);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    /** Companion to the array case: deeply nested objects are guarded identically. */
    @Test
    void parseDeeplyNestedObjectThrowsJsonException() {
        String deep = "{\"a\":".repeat(16_000);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    /**
     * Regression guard for finding SEC-2: the depth cap must not break legitimately nested input.
     * A properly-balanced structure well within the limit parses successfully, and the shared depth
     * counter is correctly unwound between top-level parses (a second deep-but-legal parse still
     * succeeds).
     */
    @Test
    void parseLegitimatelyNestedStructureStillParses() {
        int depth = 40; // comfortably under the internal cap
        String balanced = "[".repeat(depth) + "]".repeat(depth);
        Object first = Json.parse(balanced);
        assertTrue(first instanceof List<?>, "balanced nested arrays parse to nested lists");

        // Depth is per-parse state that must reset; a second call of the same depth still works.
        Object second = Json.parse(balanced);
        assertTrue(second instanceof List<?>, "the depth counter must reset between parses");
    }
}
