package com.healthcare.order.api;

import com.healthcare.order.Coupon;
import com.healthcare.order.CouponValidator;
import com.healthcare.order.Order;
import com.healthcare.order.OrderNotFoundException;
import com.healthcare.order.OrderService;
import com.healthcare.order.OrderStatus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zero-dependency HTTP API for the order-service, built on the JDK's
 * {@link com.sun.net.httpserver.HttpServer} (module {@code jdk.httpserver}). It exposes exactly
 * the routes the {@code customer-ui} wire contract requires so the browser can validate coupons,
 * create orders, read an order by id, and drive status transitions — no Spring, servlet
 * container, or third-party HTTP framework is used, which keeps the module buildable fully
 * offline.
 *
 * <h2>Routes</h2>
 * <ul>
 *   <li>{@code POST /coupons/validate} — body {@code {"code":"SAVE10"}} &rarr; a coupon verdict
 *       {@code {"valid":true,"reason":"OK","discount":{"type":"PERCENTAGE","value":10}}}
 *       (read-only; consumes no redemption).</li>
 *   <li>{@code POST /orders} — body {@code {"price":"100.00","couponCodes":["SAVE10"]}} &rarr; the
 *       created order view (status {@code CREATED}); redemption happens here.</li>
 *   <li>{@code GET /orders/{id}} — the order view, or {@code 404} if unknown.</li>
 *   <li>{@code POST /orders/{id}/status} — body {@code {"status":"CONFIRMED"}} &rarr; the updated
 *       order view; {@code 409} on an illegal transition, {@code 404} if unknown.</li>
 *   <li>{@code OPTIONS} on any route — CORS preflight, {@code 204} with the CORS headers.</li>
 * </ul>
 *
 * <h2>CORS, errors, and limits</h2>
 * <p>Every response carries permissive CORS headers ({@code Access-Control-Allow-Origin: *},
 * {@code -Methods: GET, POST, OPTIONS}, {@code -Headers: Content-Type}) so the SPA can call the
 * API cross-origin, and {@code OPTIONS} preflights are answered. Any non-2xx outcome is returned
 * as the contract error envelope {@code {"error":{"code":"...","message":"..."}}}. Request bodies
 * are capped at {@value #MAX_BODY_BYTES} bytes; a larger body yields {@code 413}.</p>
 *
 * <h2>Security note</h2>
 * <p>The API is <b>unauthenticated</b>: authentication/authorization is out of scope for this
 * feature. It binds to the loopback interface by default and must not be exposed publicly without
 * an auth layer in front of it.</p>
 *
 * <h2>Mandatory notification wiring</h2>
 * <p>Unlike the {@code OrderService} library (which tolerates a {@code null} trigger for embedding
 * flexibility), this production server <b>requires</b> a non-null {@link OrderService.NotificationTrigger}
 * and wires it into the service on construction, so a deployed API always has a status-change
 * transport configured.</p>
 */
public class OrderApiServer {

    private static final Logger LOGGER = Logger.getLogger(OrderApiServer.class.getName());

    /** Maximum accepted request-body size in bytes; larger bodies are rejected with 413. */
    static final int MAX_BODY_BYTES = 16 * 1024;

    private final OrderService service;
    private final int requestedPort;
    private HttpServer server;

    /**
     * Creates the server, wiring the (required) notification trigger into the service.
     *
     * @param service the order service backing the API; must not be {@code null}
     * @param trigger the status-change notification trigger; must not be {@code null} — a
     *                production API must always have a transport wired
     * @param port    the TCP port to bind on {@code start()}; {@code 0} selects an ephemeral port
     * @throws NullPointerException if {@code service} or {@code trigger} is {@code null}
     */
    public OrderApiServer(OrderService service, OrderService.NotificationTrigger trigger, int port) {
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(trigger, "trigger (a production API must have a notification transport wired)");
        service.setNotificationTrigger(trigger);
        this.requestedPort = port;
    }

    /**
     * Binds the loopback socket, registers the route handlers, and starts serving.
     *
     * @return this server, for chaining
     * @throws IOException if the socket cannot be bound
     */
    public OrderApiServer start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.createContext("/coupons/validate", new CouponValidateHandler());
        server.createContext("/orders", new OrdersHandler());
        // Catch-all fallback (finding API-03): the JDK server matches contexts by longest-prefix,
        // so any request that does not fall under a declared route (e.g. "/unknown", "/coupons",
        // "/") lands here. Without this context the server would emit the JDK's default bare-HTML
        // 404 with NONE of the API's CORS/security headers and not the JSON error envelope. This
        // handler answers every undeclared path with the contract error envelope plus the full
        // header set, and honours CORS preflight.
        server.createContext("/", new NotFoundHandler());
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        LOGGER.log(Level.INFO, () -> "order-service API listening on http://127.0.0.1:" + getPort());
        return this;
    }

    /**
     * Stops the server, allowing in-flight exchanges up to {@code delaySeconds} to finish.
     *
     * @param delaySeconds maximum seconds to wait for active exchanges before forcing shutdown
     */
    public void stop(int delaySeconds) {
        if (server != null) {
            server.stop(delaySeconds);
        }
    }

    /**
     * Returns the actual bound port (useful when an ephemeral port {@code 0} was requested).
     *
     * @return the bound TCP port, or the requested port if not yet started
     */
    public int getPort() {
        return server == null ? requestedPort : server.getAddress().getPort();
    }

    // ---------------------------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------------------------

    /** Handles {@code POST /coupons/validate} (and its CORS preflight). */
    private final class CouponValidateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // NOTE: the exchange is closed in a finally block, NOT via try-with-resources.
            // try-with-resources would close the exchange as an exception unwinds — i.e. BEFORE
            // the catch blocks run — so an error response written from a catch block would go to
            // an already-closed exchange and the client would observe a reset/empty response.
            try {
                addCors(exchange);
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                // Exact-path match (finding API-02): the context is registered at
                // "/coupons/validate" but the JDK server matches by PREFIX, so sibling paths such
                // as "/coupons/validateevil" and "/coupons/validate/extra" are also routed here.
                // Only the exact route is a real endpoint; anything else is a clean 404 with NO
                // side effect. This guard precedes the method check so an unknown route yields 404
                // regardless of method (rather than a misleading 405).
                String path = exchange.getRequestURI().getPath();
                if (!"/coupons/validate".equals(path)) {
                    sendError(exchange, 404, "NOT_FOUND", "no such route: " + path);
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    sendError(exchange, 405, "METHOD_NOT_ALLOWED", "use POST /coupons/validate");
                    return;
                }
                Map<String, Object> body = readJsonObject(exchange);
                Object codeValue = body.get("code");
                if (!(codeValue instanceof String) || ((String) codeValue).isBlank()) {
                    sendError(exchange, 400, "VALIDATION", "'code' must be a non-blank string");
                    return;
                }
                // Enforce the shared coupon-code length bound server-side (finding API-05): the
                // client rejects an over-length code, but the server is authoritative and must
                // reject it too rather than returning a misleading 200 "unknown code" verdict.
                enforceCodeLength((String) codeValue);
                CouponValidator.ValidationResult result = service.validateCoupon((String) codeValue);
                sendJson(exchange, 200, couponVerdict(result));
            } catch (PayloadTooLargeException e) {
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
            } catch (UnsupportedMediaTypeException e) {
                // API-01: a body-bearing request must declare application/json before we parse or
                // act on it. Rejecting up front with 415 prevents a wrong/absent Content-Type
                // request from performing a mutation.
                sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", e.getMessage());
            } catch (JsonException | IllegalArgumentException e) {
                sendError(exchange, 400, "VALIDATION", e.getMessage());
            } catch (RuntimeException e) {
                // OBS-01: log a SANITIZED record (stable correlation id + exception TYPE only) at
                // WARNING — never the throwable itself, whose stack trace would leak internal
                // class/source lines and absolute filesystem paths. The full stack is routed to
                // the FINE debug sink (disabled by default), correlatable via the same errorId.
                String errorId = newCorrelationId();
                LOGGER.log(Level.WARNING, () -> "unexpected error in POST /coupons/validate"
                        + " [errorId=" + errorId + ", type=" + e.getClass().getSimpleName() + "]");
                LOGGER.log(Level.FINE, e, () -> "stack trace for errorId=" + errorId);
                sendError(exchange, 500, "INTERNAL", "internal error");
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * Handles the {@code /orders} family: {@code POST /orders}, {@code GET /orders/{id}}, and
     * {@code POST /orders/{id}/status} (plus CORS preflight).
     */
    private final class OrdersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // NOTE: the exchange is closed in a finally block, NOT via try-with-resources.
            // try-with-resources would close the exchange as an exception unwinds — i.e. BEFORE
            // the catch blocks run — so an error response written from a catch block would go to
            // an already-closed exchange and the client would observe a reset/empty response.
            try {
                addCors(exchange);
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                // Path after the "/orders" context: "" (create/list), "/{id}", or "/{id}/status".
                String path = exchange.getRequestURI().getPath();
                // Exact-prefix match (finding API-02): the context is registered at "/orders" but
                // the JDK server matches by PREFIX, so a sibling path such as "/ordersXYZ" is also
                // routed here and MUST NOT be misread as order id "XYZ". Require the request path
                // to be either exactly the collection root "/orders" or a well-formed sub-path
                // "/orders/...". Anything else is a clean 404 with no side effect.
                if (!"/orders".equals(path) && !path.startsWith("/orders/")) {
                    sendError(exchange, 404, "NOT_FOUND", "no such route: " + path);
                    return;
                }
                String remainder = "/orders".equals(path)
                        ? ""
                        : path.substring("/orders/".length());
                // Split KEEPING trailing empty segments (limit -1) so a stray empty segment from
                // "/orders//" (or a trailing slash like "/orders/{id}/") is surfaced and rejected
                // below rather than being silently normalized away.
                String[] segments = remainder.isEmpty() ? new String[0] : remainder.split("/", -1);
                // Reject any empty path segment (finding API-02): an empty order id such as
                // "/orders//" is not a valid route.
                for (String segment : segments) {
                    if (segment.isEmpty()) {
                        sendError(exchange, 404, "NOT_FOUND", "no such route: " + path);
                        return;
                    }
                }

                if (segments.length == 0) {
                    if ("POST".equalsIgnoreCase(method)) {
                        handleCreateOrder(exchange);
                    } else {
                        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "use POST /orders");
                    }
                    return;
                }

                String orderId = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);

                if (segments.length == 1) {
                    if ("GET".equalsIgnoreCase(method)) {
                        handleGetOrder(exchange, orderId);
                    } else {
                        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "use GET /orders/{id}");
                    }
                    return;
                }

                if (segments.length == 2 && "status".equals(segments[1])) {
                    if ("POST".equalsIgnoreCase(method)) {
                        handleUpdateStatus(exchange, orderId);
                    } else {
                        sendError(exchange, 405, "METHOD_NOT_ALLOWED", "use POST /orders/{id}/status");
                    }
                    return;
                }

                sendError(exchange, 404, "NOT_FOUND", "no such route: " + path);
            } catch (PayloadTooLargeException e) {
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
            } catch (UnsupportedMediaTypeException e) {
                // API-01: a body-bearing request must declare application/json before we parse or
                // act on it (see CouponValidateHandler for the rationale).
                sendError(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", e.getMessage());
            } catch (OrderNotFoundException e) {
                sendError(exchange, 404, "NOT_FOUND", e.getMessage());
            } catch (IllegalStateException e) {
                sendError(exchange, 409, "ILLEGAL_TRANSITION", e.getMessage());
            } catch (JsonException | IllegalArgumentException e) {
                sendError(exchange, 400, "VALIDATION", e.getMessage());
            } catch (RuntimeException e) {
                // OBS-01: sanitized WARNING (correlation id + exception TYPE only) — never the
                // throwable; full stack routed to the FINE debug sink under the same errorId.
                String errorId = newCorrelationId();
                LOGGER.log(Level.WARNING, () -> "unexpected error in /orders handler"
                        + " [errorId=" + errorId + ", type=" + e.getClass().getSimpleName() + "]");
                LOGGER.log(Level.FINE, e, () -> "stack trace for errorId=" + errorId);
                sendError(exchange, 500, "INTERNAL", "internal error");
            } finally {
                exchange.close();
            }
        }

        private void handleCreateOrder(HttpExchange exchange) throws IOException {
            Map<String, Object> body = readJsonObject(exchange);
            BigDecimal price = readPrice(body.get("price"));
            List<String> codes = readCouponCodes(body.get("couponCodes"));
            Order order = service.createOrder(price, codes);
            sendJson(exchange, 201, orderView(order));
        }

        private void handleGetOrder(HttpExchange exchange, String orderId) throws IOException {
            Optional<Order> order = service.getOrder(orderId);
            if (order.isEmpty()) {
                sendError(exchange, 404, "NOT_FOUND", "order not found: " + orderId);
                return;
            }
            sendJson(exchange, 200, orderView(order.get()));
        }

        private void handleUpdateStatus(HttpExchange exchange, String orderId) throws IOException {
            Map<String, Object> body = readJsonObject(exchange);
            Object statusValue = body.get("status");
            if (!(statusValue instanceof String) || ((String) statusValue).isBlank()) {
                sendError(exchange, 400, "VALIDATION", "'status' must be a non-blank string");
                return;
            }
            OrderStatus next = parseStatus((String) statusValue);
            Order updated = service.updateStatus(orderId, next);
            sendJson(exchange, 200, orderView(updated));
        }
    }

    /**
     * Catch-all handler for any path not served by a declared route (finding API-03).
     *
     * <p>Registered at the root context {@code "/"}, it receives every request whose path is not
     * matched by a longer, more specific context ({@code /coupons/validate} or {@code /orders}) —
     * for example {@code /unknown}, {@code /coupons}, or {@code /}. It answers with the contract
     * JSON error envelope and a {@code 404} status and, crucially, sets the same CORS and security
     * headers ({@code X-Content-Type-Options: nosniff}, {@code Cache-Control: no-store}) as every
     * other route via {@link #addCors(HttpExchange)}, so an unknown path yields a well-formed API
     * response rather than the JDK's bare-HTML default {@code 404} with no headers. CORS preflight
     * is answered with {@code 204}.</p>
     */
    private static final class NotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Closed in a finally block (not try-with-resources) for the same reason as the other
            // handlers: an error response written from a catch block must reach an open exchange.
            try {
                addCors(exchange);
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                sendError(exchange, 404, "NOT_FOUND",
                        "no such route: " + exchange.getRequestURI().getPath());
            } catch (RuntimeException e) {
                // OBS-01: sanitized WARNING + FINE-sink stack trace, correlatable by errorId.
                String errorId = newCorrelationId();
                LOGGER.log(Level.WARNING, () -> "unexpected error in not-found handler"
                        + " [errorId=" + errorId + ", type=" + e.getClass().getSimpleName() + "]");
                LOGGER.log(Level.FINE, e, () -> "stack trace for errorId=" + errorId);
                sendError(exchange, 500, "INTERNAL", "internal error");
            } finally {
                exchange.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Request parsing helpers
    // ---------------------------------------------------------------------------------------

    /**
     * The maximum accepted order price. Generous enough for any realistic order (a trillion) while
     * bounding the magnitude an attacker can submit.
     */
    static final BigDecimal MAX_PRICE = new BigDecimal("1000000000000");

    /**
     * The maximum accepted fractional scale for a price. Allows sub-cent precision for legitimate
     * inputs while rejecting the enormous positive scales that scientific-notation inputs like
     * {@code 1e-10000000} would otherwise carry.
     */
    static final int MAX_PRICE_SCALE = 8;

    /**
     * The maximum accepted number of significant digits in a price.
     */
    static final int MAX_PRICE_PRECISION = 20;

    /**
     * Reads and validates the {@code price} field into a bounded {@link BigDecimal}.
     *
     * <p>Both the parsed-number branch (a bare JSON number arriving as a {@link BigDecimal}) and
     * the numeric-string branch flow through {@link #validatePrice(BigDecimal)}, so the bounds are
     * enforced regardless of how the client encodes the value.</p>
     *
     * @param priceValue the raw {@code price} value from the request body
     * @return the validated, bounded price
     * @throws IllegalArgumentException if the value is missing, the wrong type, unparseable, or
     *                                  outside the accepted magnitude/precision/scale/sign bounds
     *                                  (mapped to a {@code 400 VALIDATION} response)
     */
    private static BigDecimal readPrice(Object priceValue) {
        if (priceValue == null) {
            throw new IllegalArgumentException("'price' is required");
        }
        if (priceValue instanceof BigDecimal bd) {
            return validatePrice(bd);
        }
        if (priceValue instanceof String s) {
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'price' is not a valid number: " + s);
            }
            return validatePrice(parsed);
        }
        throw new IllegalArgumentException("'price' must be a number or numeric string");
    }

    /**
     * Enforces sane numeric bounds on a price to prevent both nonsensical values and a
     * denial-of-service via unbounded {@link BigDecimal} expansion.
     *
     * <p>A tiny request body such as {@code {"price":"1e10000000"}} parses cheaply into a
     * {@link BigDecimal} whose <em>scale</em> is {@code -10000000}; later rendering it with
     * {@link BigDecimal#toPlainString()} (in the JSON serializer) or performing arithmetic on it
     * would attempt to materialize a ten-million-digit number, exhausting CPU and heap. The scale
     * check below rejects such inputs immediately using only the stored {@code scale} field — no
     * expansion ever occurs. The remaining checks impose ordinary business bounds.</p>
     *
     * <p>Checks are ordered cheapest-and-most-protective first:</p>
     * <ol>
     *   <li><b>scale</b> — a negative scale means an exponent inflated the integer part (e.g.
     *       {@code 1e10000000}); a scale above {@link #MAX_PRICE_SCALE} means an exponent inflated
     *       the fractional part. Both are rejected before any digit materialization.</li>
     *   <li><b>precision</b> — reject absurd significant-digit counts (e.g. a 10 000-digit
     *       integer literal under the 16 KB body cap).</li>
     *   <li><b>sign</b> — a negative order price is nonsensical.</li>
     *   <li><b>magnitude</b> — reject values above {@link #MAX_PRICE}.</li>
     * </ol>
     *
     * @param price the parsed price
     * @return the same price, unchanged, if it is within bounds
     * @throws IllegalArgumentException if any bound is violated
     */
    private static BigDecimal validatePrice(BigDecimal price) {
        int scale = price.scale();
        if (scale < 0 || scale > MAX_PRICE_SCALE) {
            throw new IllegalArgumentException(
                    "'price' has an unsupported scale; provide a plain decimal with at most "
                            + MAX_PRICE_SCALE + " fractional digits");
        }
        if (price.precision() > MAX_PRICE_PRECISION) {
            throw new IllegalArgumentException(
                    "'price' has too many significant digits (max " + MAX_PRICE_PRECISION + ")");
        }
        if (price.signum() < 0) {
            throw new IllegalArgumentException("'price' must not be negative");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new IllegalArgumentException(
                    "'price' exceeds the maximum allowed amount of " + MAX_PRICE.toPlainString());
        }
        return price;
    }

    private static List<String> readCouponCodes(Object codesValue) {
        if (codesValue == null) {
            return List.of();
        }
        if (!(codesValue instanceof List<?> raw)) {
            throw new IllegalArgumentException("'couponCodes' must be an array of strings");
        }
        List<String> codes = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element == null) {
                continue;
            }
            if (!(element instanceof String)) {
                throw new IllegalArgumentException("'couponCodes' entries must be strings");
            }
            // Enforce the shared coupon-code length bound server-side (finding API-05), so an
            // over-length code submitted at order creation is rejected up front rather than
            // silently ignored as unknown by the validator.
            enforceCodeLength((String) element);
            codes.add((String) element);
        }
        return codes;
    }

    /**
     * Enforces the shared, server-authoritative coupon-code length bound
     * ({@link Coupon#MAX_CODE_LENGTH}) at the HTTP boundary (finding API-05).
     *
     * <p>The customer-ui rejects a code longer than the shared bound before it is ever sent;
     * the server must apply the SAME bound so a client bypassing the UI cannot submit an
     * over-length code and receive a misleading {@code 200 unknown code} verdict (validate) or
     * have it silently dropped (order creation). The length is measured on the CANONICALIZED
     * code (trimmed, upper-cased) so it matches exactly how {@link Coupon} itself bounds a
     * code, and a {@code null}/blank canonical form is left for the caller's own non-blank
     * check to report.</p>
     *
     * @param code the raw coupon code from the request
     * @throws IllegalArgumentException if the canonicalized code exceeds
     *                                  {@link Coupon#MAX_CODE_LENGTH} (mapped to
     *                                  {@code 400 VALIDATION})
     */
    private static void enforceCodeLength(String code) {
        String canonical = Coupon.canonicalizeCode(code);
        if (canonical != null && canonical.length() > Coupon.MAX_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "'code' must not exceed " + Coupon.MAX_CODE_LENGTH + " characters");
        }
    }

    private static OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown order status: " + status);
        }
    }

    // ---------------------------------------------------------------------------------------
    // DTO mapping (wire contract)
    // ---------------------------------------------------------------------------------------

    /**
     * Maps a validator result to the coupon-verdict wire DTO. A valid verdict carries nested
     * {@code discount:{type,value}} metadata; an invalid verdict omits {@code discount}.
     *
     * @param result the validation result
     * @return an insertion-ordered map serializable by {@link Json}
     */
    static Map<String, Object> couponVerdict(CouponValidator.ValidationResult result) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("valid", result.isValid());
        verdict.put("reason", result.getReason());
        if (result.isValid()) {
            Map<String, Object> discount = new LinkedHashMap<>();
            discount.put("type", result.getType());
            discount.put("value", result.getValue());
            verdict.put("discount", discount);
        }
        return verdict;
    }

    /**
     * Maps an order to the order-view wire DTO consumed by the client's {@code assertOrderView}.
     *
     * @param order the order
     * @return an insertion-ordered map serializable by {@link Json}
     */
    static Map<String, Object> orderView(Order order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("status", order.getStatus().name());
        view.put("price", order.getPrice());
        view.put("discountedTotal", order.getDiscountedTotal());
        List<Object> coupons = new ArrayList<>();
        for (Coupon coupon : order.getAppliedCoupons()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", coupon.getCode());
            c.put("type", coupon.getType());
            c.put("value", coupon.getValue().toPlainString());
            coupons.add(c);
        }
        view.put("appliedCoupons", coupons);
        return view;
    }

    // ---------------------------------------------------------------------------------------
    // Transport helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Mints a fresh, opaque correlation id for an unexpected server error (finding OBS-01).
     *
     * <p>The id is logged in the sanitized {@code WARNING} record and attached to the full stack
     * trace at the {@code FINE} sink, so an operator can correlate the two without the API ever
     * emitting the stack (and its class/source-line and absolute-path detail) at a default-visible
     * level.</p>
     *
     * @return a random UUID string
     */
    private static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Sets the permissive CORS headers plus baseline security headers on every response.
     *
     * <p>This method is invoked at the very start of each handler (before any response is
     * committed), so the headers it sets appear on <em>all</em> responses — preflight
     * {@code 204}s, success bodies, and every error envelope.</p>
     *
     * <p>Two defense-in-depth security headers are set here in addition to CORS:</p>
     * <ul>
     *   <li>{@code X-Content-Type-Options: nosniff} — instructs browsers not to MIME-sniff the
     *       response body away from its declared {@code Content-Type} (all responses are
     *       {@code application/json}), closing content-sniffing attack vectors.</li>
     *   <li>{@code Cache-Control: no-store} — every response on this API is dynamic, per-order
     *       state; {@code no-store} prevents shared/browser caches from retaining order data.</li>
     * </ul>
     */
    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        // Security hardening (defense-in-depth): prevent MIME sniffing and caching of dynamic data.
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
    }

    private static Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        // API-01: require the body-bearing request to declare application/json BEFORE the body is
        // read or parsed, so a request with a wrong or absent Content-Type is rejected with 415
        // and can never trigger a mutation.
        requireJsonContentType(exchange);
        byte[] raw = readBody(exchange);
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new JsonException("request body must be a JSON object");
        }
        return Json.parseObject(text);
    }

    /**
     * Enforces that a body-bearing request declares a JSON media type (finding API-01).
     *
     * <p>Invoked at the very start of {@link #readJsonObject(HttpExchange)}, before the body is
     * read or any state is mutated. A missing {@code Content-Type} header, or one whose media
     * type is not {@code application/json}, is rejected with {@code 415 Unsupported Media Type}
     * so a client cannot smuggle a mutation past the API with a wrong or absent content type.
     * Media-type parameters (for example {@code application/json; charset=utf-8}) are tolerated;
     * only the media type itself is compared, case-insensitively.</p>
     *
     * @param exchange the HTTP exchange
     * @throws UnsupportedMediaTypeException if the request does not declare {@code application/json}
     */
    private static void requireJsonContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !isJsonMediaType(contentType)) {
            throw new UnsupportedMediaTypeException("Content-Type must be application/json");
        }
    }

    /**
     * Returns whether the given {@code Content-Type} header value has the media type
     * {@code application/json}, ignoring any parameters (for example {@code ; charset=utf-8}) and
     * comparing case-insensitively.
     *
     * @param contentType the raw {@code Content-Type} header value (never {@code null})
     * @return {@code true} if the declared media type is {@code application/json}
     */
    private static boolean isJsonMediaType(String contentType) {
        String mediaType = contentType;
        int semicolon = mediaType.indexOf(';');
        if (semicolon >= 0) {
            mediaType = mediaType.substring(0, semicolon);
        }
        return "application/json".equalsIgnoreCase(mediaType.trim());
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new PayloadTooLargeException(
                            "request body exceeds " + MAX_BODY_BYTES + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message == null ? "" : message);
        error.put("error", detail);
        sendJson(exchange, status, error);
    }

    /** Signals that a request body exceeded {@link #MAX_BODY_BYTES}; mapped to HTTP 413. */
    private static final class PayloadTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Signals that a body-bearing request did not declare the required {@code application/json}
     * media type; mapped to HTTP {@code 415} (finding API-01).
     */
    private static final class UnsupportedMediaTypeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnsupportedMediaTypeException(String message) {
            super(message);
        }
    }
}
