package com.healthcare.order.api;

import com.healthcare.order.Coupon;
import com.healthcare.order.CouponValidator;
import com.healthcare.order.Order;
import com.healthcare.order.OrderService;
import com.healthcare.order.OrderStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP integration tests for {@link OrderApiServer}: a real server is bound on an ephemeral
 * loopback port and driven with a real {@link HttpClient}, exercising the full wire contract that
 * the customer-ui client depends on (coupon-verdict and order-view DTOs, the error envelope, CORS,
 * body limits, method/route handling) end to end. Because the module ships no HTTP/JSON framework,
 * these tests are the proof that the hand-rolled {@link OrderApiServer} and {@link Json} correctly
 * implement the agreed contract.
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li><b>Mandatory production wiring</b> — the constructor rejects a {@code null} notification
 *       trigger with {@link NullPointerException} (an API server must always have a transport
 *       wired), the library-level {@code null}-tolerance being a separate, deliberate choice tested
 *       on {@code OrderService} itself;</li>
 *   <li><b>POST /coupons/validate</b> — a valid code yields {@code 200} with the nested
 *       {@code discount:{type,value}} block; an unknown code yields {@code 200} with
 *       {@code valid:false} and no {@code discount}; and validation is read-only (it does not
 *       consume a usage-limited coupon);</li>
 *   <li><b>POST /orders</b> — {@code 201} with the order view, the submitted coupon applied and the
 *       discounted total computed; redemption happens here, so a single-use coupon is consumed;</li>
 *   <li><b>GET /orders/{id}</b> — {@code 200} for a known id, {@code 404} with the error envelope
 *       for an unknown id;</li>
 *   <li><b>POST /orders/{id}/status</b> — {@code 200} on a legal transition (and the notification
 *       trigger fires exactly once), {@code 409} on an illegal one;</li>
 *   <li><b>CORS/OPTIONS</b> — a preflight yields {@code 204} and every response carries
 *       {@code Access-Control-Allow-Origin};</li>
 *   <li><b>hardening</b> — a body over {@link OrderApiServer#MAX_BODY_BYTES} yields {@code 413},
 *       malformed JSON yields {@code 400}, a wrong method yields {@code 405}, and an unknown deep
 *       route yields {@code 404}.</li>
 * </ul>
 */
public class OrderApiServerTest {

    /** A notification trigger that records how many times it fired and the last transition. */
    private static final class CapturingTrigger implements OrderService.NotificationTrigger {
        final AtomicInteger count = new AtomicInteger();
        volatile OrderStatus lastFrom;
        volatile OrderStatus lastTo;
        volatile String lastOrderId;

        @Override
        public void onStatusChange(Order order, OrderStatus from, OrderStatus to) {
            count.incrementAndGet();
            lastFrom = from;
            lastTo = to;
            lastOrderId = order.getId();
        }
    }

    private OrderApiServer server;
    private CapturingTrigger trigger;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("SAVE10", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0));
        validator.addCoupon(new Coupon("WELCOME5", Coupon.TYPE_FIXED, new BigDecimal("5.00"),
                today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0));
        validator.addCoupon(new Coupon("ONCE", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 1, 0));

        OrderService service = new OrderService(validator);
        trigger = new CapturingTrigger();
        server = new OrderApiServer(service, trigger, 0).start();
        client = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void constructorRejectsNullTrigger() {
        OrderService service = new OrderService();
        assertThrows(NullPointerException.class,
                () -> new OrderApiServer(service, null, 0),
                "a production API server must be constructed with a non-null notification trigger");
    }

    @Test
    void constructorRejectsNullService() {
        assertThrows(NullPointerException.class,
                () -> new OrderApiServer(null, new CapturingTrigger(), 0));
    }

    // ------------------------------------------------------------------ coupon validation

    @Test
    void validateValidCouponReturnsVerdictWithNestedDiscount() throws Exception {
        HttpResponse<String> resp = post("/coupons/validate", "{\"code\":\"SAVE10\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "every response must carry the CORS allow-origin header");

        Map<String, Object> verdict = asObject(resp.body());
        assertEquals(Boolean.TRUE, verdict.get("valid"));
        assertEquals("OK", verdict.get("reason"));
        assertTrue(verdict.containsKey("discount"), "a valid verdict must carry a nested discount");
        @SuppressWarnings("unchecked")
        Map<String, Object> discount = (Map<String, Object>) verdict.get("discount");
        assertEquals("PERCENTAGE", discount.get("type"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) discount.get("value")));
    }

    @Test
    void validateUnknownCouponReturnsInvalidVerdictWithoutDiscount() throws Exception {
        HttpResponse<String> resp = post("/coupons/validate", "{\"code\":\"NOPE\"}");
        assertEquals(200, resp.statusCode());
        Map<String, Object> verdict = asObject(resp.body());
        assertEquals(Boolean.FALSE, verdict.get("valid"));
        assertNotNull(verdict.get("reason"));
        assertFalse(verdict.containsKey("discount"), "an invalid verdict must omit the discount block");
    }

    @Test
    void validateEndpointIsReadOnlyAndDoesNotConsumeUsage() throws Exception {
        // Validating a single-use coupon repeatedly must not exhaust it (validation != redemption).
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> resp = post("/coupons/validate", "{\"code\":\"ONCE\"}");
            assertEquals(200, resp.statusCode());
            assertEquals(Boolean.TRUE, asObject(resp.body()).get("valid"),
                    "read-only validation must remain valid on call " + i);
        }
    }

    @Test
    void validateBlankCodeIsRejected() throws Exception {
        HttpResponse<String> resp = post("/coupons/validate", "{\"code\":\"   \"}");
        assertEquals(400, resp.statusCode());
        assertEquals("VALIDATION", errorCode(resp.body()));
    }

    // ------------------------------------------------------------------ create / get / status

    @Test
    void createOrderAppliesCouponAndReturnsOrderView() throws Exception {
        HttpResponse<String> resp = post("/orders", "{\"price\":\"100.00\",\"couponCodes\":[\"SAVE10\"]}");
        assertEquals(201, resp.statusCode());
        Map<String, Object> order = asObject(resp.body());
        assertFalse(((String) order.get("id")).isBlank());
        assertEquals("CREATED", order.get("status"));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) order.get("price")));
        assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) order.get("discountedTotal")),
                "10% off 100.00 must be 90.00 on the wire");
        List<?> applied = (List<?>) order.get("appliedCoupons");
        assertEquals(1, applied.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> c0 = (Map<String, Object>) applied.get(0);
        assertEquals("SAVE10", c0.get("code"));
        assertEquals("PERCENTAGE", c0.get("type"));
        assertEquals("10", c0.get("value"), "applied-coupon value is a plain string on the wire");
    }

    @Test
    void createOrderRedeemsSingleUseCouponSoSecondOrderMissesIt() throws Exception {
        Map<String, Object> first = asObject(post("/orders",
                "{\"price\":\"100.00\",\"couponCodes\":[\"ONCE\"]}").body());
        assertEquals(1, ((List<?>) first.get("appliedCoupons")).size(), "first order redeems ONCE");
        assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) first.get("discountedTotal")));

        Map<String, Object> second = asObject(post("/orders",
                "{\"price\":\"100.00\",\"couponCodes\":[\"ONCE\"]}").body());
        assertEquals(0, ((List<?>) second.get("appliedCoupons")).size(), "ONCE is exhausted");
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) second.get("discountedTotal")));
    }

    @Test
    void getOrderReturnsStoredOrderAndUnknownIdYields404() throws Exception {
        String id = (String) asObject(post("/orders", "{\"price\":\"50.00\",\"couponCodes\":[]}").body()).get("id");

        HttpResponse<String> got = get("/orders/" + id);
        assertEquals(200, got.statusCode());
        assertEquals(id, asObject(got.body()).get("id"));

        HttpResponse<String> missing = get("/orders/no-such-id");
        assertEquals(404, missing.statusCode());
        assertEquals("NOT_FOUND", errorCode(missing.body()));
    }

    @Test
    void statusUpdateAdvancesOrderAndFiresNotificationExactlyOnce() throws Exception {
        String id = (String) asObject(post("/orders", "{\"price\":\"100.00\",\"couponCodes\":[]}").body()).get("id");

        HttpResponse<String> resp = post("/orders/" + id + "/status", "{\"status\":\"CONFIRMED\"}");
        assertEquals(200, resp.statusCode());
        assertEquals("CONFIRMED", asObject(resp.body()).get("status"));

        // The notification transport fired exactly once for CREATED -> CONFIRMED.
        assertEquals(1, trigger.count.get());
        assertSame(OrderStatus.CREATED, trigger.lastFrom);
        assertSame(OrderStatus.CONFIRMED, trigger.lastTo);
        assertEquals(id, trigger.lastOrderId);
    }

    @Test
    void illegalTransitionYields409AndDoesNotNotify() throws Exception {
        String id = (String) asObject(post("/orders", "{\"price\":\"100.00\",\"couponCodes\":[]}").body()).get("id");
        // CREATED -> DELIVERED skips CONFIRMED and must be rejected.
        HttpResponse<String> resp = post("/orders/" + id + "/status", "{\"status\":\"DELIVERED\"}");
        assertEquals(409, resp.statusCode());
        assertEquals("ILLEGAL_TRANSITION", errorCode(resp.body()));
        assertEquals(0, trigger.count.get(), "a rejected transition must not fire a notification");
    }

    // ------------------------------------------------------------------ CORS / hardening

    @Test
    void optionsPreflightReturns204WithCorsHeaders() throws Exception {
        HttpResponse<String> resp = options("/coupons/validate");
        assertEquals(204, resp.statusCode());
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Methods").isPresent());
    }

    /**
     * Security headers (finding SEC-3/SEC-4): every response must carry
     * {@code X-Content-Type-Options: nosniff} and {@code Cache-Control: no-store}. They are set in
     * {@code addCors()}, which runs first in every handler, so they appear on both success and
     * error responses.
     */
    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        String id = (String) asObject(post("/orders", "{\"price\":\"10.00\",\"couponCodes\":[]}").body()).get("id");
        HttpResponse<String> resp = get("/orders/" + id);
        assertEquals(200, resp.statusCode());
        assertEquals("nosniff",
                resp.headers().firstValue("X-Content-Type-Options").orElse(null),
                "dynamic responses must disable MIME sniffing");
        assertEquals("no-store",
                resp.headers().firstValue("Cache-Control").orElse(null),
                "dynamic per-order responses must not be cached");

        // Also present on an error response (404).
        HttpResponse<String> missing = get("/orders/does-not-exist");
        assertEquals(404, missing.statusCode());
        assertEquals("nosniff", missing.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("no-store", missing.headers().firstValue("Cache-Control").orElse(null));
    }

    /**
     * DoS guard (finding SEC-1): a tiny body carrying an astronomical scientific-notation price
     * must be rejected with a clean {@code 400}, never expanded (which previously produced a
     * multi-megabyte response / timeout / OOM-class memory growth).
     */
    @Test
    void astronomicalPriceYields400() throws Exception {
        HttpResponse<String> resp = post("/orders", "{\"price\":\"1e10000000\",\"couponCodes\":[]}");
        assertEquals(400, resp.statusCode(), "absurd-magnitude price must be rejected, not expanded");
        assertEquals("VALIDATION", errorCode(resp.body()));
        // The response is the small error envelope, not a materialized giant number.
        assertTrue(resp.body().length() < 1024, "error response must be small, not an expansion");
    }

    /** DoS guard (finding SEC-1): a huge negative exponent (enormous positive scale) is rejected. */
    @Test
    void astronomicalFractionalPriceYields400() throws Exception {
        HttpResponse<String> resp = post("/orders", "{\"price\":\"1e-10000000\",\"couponCodes\":[]}");
        assertEquals(400, resp.statusCode());
        assertEquals("VALIDATION", errorCode(resp.body()));
    }

    /** Validation (finding SEC-1): a negative order price is rejected with a clean {@code 400}. */
    @Test
    void negativePriceYields400() throws Exception {
        HttpResponse<String> resp = post("/orders", "{\"price\":\"-5.00\",\"couponCodes\":[]}");
        assertEquals(400, resp.statusCode());
        assertEquals("VALIDATION", errorCode(resp.body()));
    }

    /** Validation (finding SEC-1): an over-precise price (too many significant digits) is rejected. */
    @Test
    void excessivePrecisionPriceYields400() throws Exception {
        HttpResponse<String> resp = post("/orders",
                "{\"price\":\"123456789012345678901234567890\",\"couponCodes\":[]}");
        assertEquals(400, resp.statusCode());
        assertEquals("VALIDATION", errorCode(resp.body()));
    }

    /** Validation (finding SEC-1): a price above the maximum allowed amount is rejected. */
    @Test
    void overMaxPriceYields400() throws Exception {
        HttpResponse<String> resp = post("/orders",
                "{\"price\":\"1000000000001\",\"couponCodes\":[]}");
        assertEquals(400, resp.statusCode());
        assertEquals("VALIDATION", errorCode(resp.body()));
    }

    /**
     * Regression (finding SEC-1): the price bounds must NOT reject legitimate large orders. A price
     * within {@code MAX_PRICE} with a few decimals is accepted and priced normally.
     */
    @Test
    void largeButValidPriceIsAccepted() throws Exception {
        HttpResponse<String> resp = post("/orders", "{\"price\":\"1000000.50\",\"couponCodes\":[]}");
        assertEquals(201, resp.statusCode(), "a realistic large price must still be accepted");
        Map<String, Object> order = asObject(resp.body());
        assertEquals(0, new BigDecimal("1000000.50").compareTo((BigDecimal) order.get("price")));
    }

    @Test
    void oversizedBodyYields413() throws Exception {
        String big = "{\"code\":\"" + "A".repeat(OrderApiServer.MAX_BODY_BYTES + 100) + "\"}";
        HttpResponse<String> resp = post("/coupons/validate", big);
        assertEquals(413, resp.statusCode());
        assertEquals("PAYLOAD_TOO_LARGE", errorCode(resp.body()));
    }

    @Test
    void malformedJsonYields400() throws Exception {
        HttpResponse<String> resp = post("/orders", "{ not json ");
        assertEquals(400, resp.statusCode());
        assertEquals("VALIDATION", errorCode(resp.body()));
    }

    @Test
    void wrongMethodYields405() throws Exception {
        HttpResponse<String> resp = get("/coupons/validate");
        assertEquals(405, resp.statusCode());
        assertEquals("METHOD_NOT_ALLOWED", errorCode(resp.body()));
    }

    @Test
    void unknownDeepRouteYields404() throws Exception {
        String id = (String) asObject(post("/orders", "{\"price\":\"10.00\",\"couponCodes\":[]}").body()).get("id");
        HttpResponse<String> resp = post("/orders/" + id + "/bogus", "{}");
        assertEquals(404, resp.statusCode());
        assertEquals("NOT_FOUND", errorCode(resp.body()));
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> options(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(String json) {
        return (Map<String, Object>) Json.parse(json);
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(String json) {
        Map<String, Object> body = (Map<String, Object>) Json.parse(json);
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        return (String) error.get("code");
    }
}
