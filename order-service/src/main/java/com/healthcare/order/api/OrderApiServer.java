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
                CouponValidator.ValidationResult result = service.validateCoupon((String) codeValue);
                sendJson(exchange, 200, couponVerdict(result));
            } catch (PayloadTooLargeException e) {
                sendError(exchange, 413, "PAYLOAD_TOO_LARGE", e.getMessage());
            } catch (JsonException | IllegalArgumentException e) {
                sendError(exchange, 400, "VALIDATION", e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, e, () -> "unexpected error in POST /coupons/validate");
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
                String remainder = path.length() > "/orders".length()
                        ? path.substring("/orders".length())
                        : "";
                // Trim a single leading slash and split.
                if (remainder.startsWith("/")) {
                    remainder = remainder.substring(1);
                }
                String[] segments = remainder.isEmpty() ? new String[0] : remainder.split("/");

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
            } catch (OrderNotFoundException e) {
                sendError(exchange, 404, "NOT_FOUND", e.getMessage());
            } catch (IllegalStateException e) {
                sendError(exchange, 409, "ILLEGAL_TRANSITION", e.getMessage());
            } catch (JsonException | IllegalArgumentException e) {
                sendError(exchange, 400, "VALIDATION", e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, e, () -> "unexpected error in /orders handler");
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

    // ---------------------------------------------------------------------------------------
    // Request parsing helpers
    // ---------------------------------------------------------------------------------------

    private static BigDecimal readPrice(Object priceValue) {
        if (priceValue == null) {
            throw new IllegalArgumentException("'price' is required");
        }
        if (priceValue instanceof BigDecimal bd) {
            return bd;
        }
        if (priceValue instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'price' is not a valid number: " + s);
            }
        }
        throw new IllegalArgumentException("'price' must be a number or numeric string");
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
            codes.add((String) element);
        }
        return codes;
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

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        byte[] raw = readBody(exchange);
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            throw new JsonException("request body must be a JSON object");
        }
        return Json.parseObject(text);
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
}
