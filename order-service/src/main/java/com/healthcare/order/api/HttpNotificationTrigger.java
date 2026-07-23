package com.healthcare.order.api;

import com.healthcare.order.NotificationOutbox;
import com.healthcare.order.Order;
import com.healthcare.order.OrderService;
import com.healthcare.order.OrderStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Concrete {@link OrderService.NotificationTrigger} that bridges an order status change from the
 * Java order-service to the Node {@code notification-service} over HTTP.
 *
 * <p>This is the producer side of the cross-runtime notification integration required by the
 * feature (the order-service &rarr; notification-service status-change seam). It depends only on
 * the JDK's built-in {@link HttpClient} — no third-party HTTP library — so it works in the fully
 * offline build environment.</p>
 *
 * <h2>Wire contract (must match the Node receiver)</h2>
 * <p>On each status change it {@code POST}s a JSON event to the configured receiver URL (the Node
 * {@code POST /notifications} endpoint):</p>
 * <pre>
 * {
 *   "eventId":  "&lt;orderId&gt;|&lt;from&gt;-&gt;&lt;to&gt;",
 *   "orderId":  "&lt;orderId&gt;",
 *   "oldStatus":"CREATED",
 *   "newStatus":"CONFIRMED",
 *   "order":   {"id":"&lt;orderId&gt;","status":"CONFIRMED"},
 *   "at":       "&lt;ISO-8601 instant&gt;"
 * }
 * </pre>
 * <p>The {@code eventId} is the same stable correlation id the {@link NotificationOutbox} uses, so
 * the Node side can deduplicate re-deliveries idempotently.</p>
 *
 * <h2>Failure semantics (works with the outbox)</h2>
 * <p>A non-2xx response or any I/O/timeout/interruption is surfaced as an unchecked
 * {@link NotificationDeliveryException}. {@code OrderService} records the event as {@code PENDING}
 * in its outbox <b>before</b> invoking this trigger and only marks it {@code DELIVERED} when this
 * method returns normally; a thrown exception therefore leaves the event pending for retry and
 * never fails or rolls back the (already-committed) status transition.</p>
 *
 * <p>Instances are immutable and thread-safe (the {@link HttpClient} is thread-safe and reused).</p>
 */
public class HttpNotificationTrigger implements OrderService.NotificationTrigger {

    /** Default per-request connect/response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** The Node receiver endpoint, for example {@code http://127.0.0.1:3001/notifications}. */
    private final URI receiverUri;

    /** Reused JDK HTTP client; thread-safe. */
    private final HttpClient httpClient;

    /** Per-request timeout. */
    private final Duration timeout;

    /**
     * Creates a trigger targeting the given Node receiver URL with the default timeout and a
     * freshly built {@link HttpClient}.
     *
     * @param receiverUrl the absolute URL of the Node {@code POST /notifications} endpoint; must
     *                    not be {@code null} or blank
     * @throws NullPointerException     if {@code receiverUrl} is {@code null}
     * @throws IllegalArgumentException if {@code receiverUrl} is blank or not a valid URI
     */
    public HttpNotificationTrigger(String receiverUrl) {
        this(receiverUrl, HttpClient.newHttpClient(), DEFAULT_TIMEOUT);
    }

    /**
     * Full constructor allowing an injected {@link HttpClient} and timeout (used by tests).
     *
     * @param receiverUrl the absolute URL of the Node receiver endpoint; must not be {@code null}
     *                    or blank
     * @param httpClient  the HTTP client to use; must not be {@code null}
     * @param timeout     the per-request timeout; must not be {@code null}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code receiverUrl} is blank or not a valid URI
     */
    public HttpNotificationTrigger(String receiverUrl, HttpClient httpClient, Duration timeout) {
        Objects.requireNonNull(receiverUrl, "receiverUrl");
        if (receiverUrl.isBlank()) {
            throw new IllegalArgumentException("receiverUrl must not be blank");
        }
        URI parsed;
        try {
            parsed = URI.create(receiverUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("receiverUrl is not a valid URI: " + receiverUrl, e);
        }
        // CONFIG-01: the receiver URL must be an ABSOLUTE http(s) URL that carries a host.
        // URI.create happily accepts a bare relative reference such as
        // "relative-notification-path" (no scheme, no authority); left unchecked, that only blows
        // up much later inside onStatusChange() when HttpRequest.newBuilder rejects the
        // non-absolute URI — by which point the status transition has already committed and the
        // event is stranded PENDING with the service otherwise reporting healthy. Validating the
        // authority here makes a misconfiguration fail fast at construction, and therefore at
        // application startup (before the HTTP server ever reports "listening").
        requireAbsoluteHttpUri(parsed, receiverUrl);
        this.receiverUri = parsed;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Validates that the receiver URI is an absolute {@code http}/{@code https} URL with a host
     * (finding CONFIG-01). Rejects relative references (no scheme), non-HTTP schemes, and
     * authority-less URIs so a misconfigured {@code NOTIFICATION_URL} fails fast at startup rather
     * than after the first (already-committed) status transition.
     *
     * @param uri      the parsed receiver URI
     * @param original the original URL string, echoed in the error for operator clarity
     * @throws IllegalArgumentException if {@code uri} is not an absolute http(s) URL with a host
     */
    private static void requireAbsoluteHttpUri(URI uri, String original) {
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new IllegalArgumentException(
                    "receiverUrl must be an absolute URL with an http/https scheme, e.g. "
                            + "http://127.0.0.1:3001/notifications; got: " + original);
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "receiverUrl scheme must be http or https; got: " + original);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "receiverUrl must include a host, e.g. http://127.0.0.1:3001/notifications; "
                            + "got: " + original);
        }
    }

    /**
     * Builds the event payload and POSTs it to the Node receiver.
     *
     * @param order the order whose status changed (already carrying {@code to})
     * @param from  the previous status
     * @param to    the new status just applied
     * @throws NotificationDeliveryException if the receiver responds non-2xx, or the request
     *                                       fails with an I/O error, timeout, or interruption
     */
    @Override
    public void onStatusChange(Order order, OrderStatus from, OrderStatus to) {
        String body = Json.stringify(buildEvent(order, from, to));
        HttpRequest request = HttpRequest.newBuilder(receiverUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new NotificationDeliveryException(
                        "notification receiver returned HTTP " + status + " for event "
                                + NotificationOutbox.eventId(order.getId(), from, to));
            }
        } catch (IOException e) {
            throw new NotificationDeliveryException(
                    "failed to POST notification event to " + receiverUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationDeliveryException(
                    "interrupted while POSTing notification event to " + receiverUri, e);
        }
    }

    /**
     * Builds the ordered event map serialized to the receiver. Package-private so tests can
     * assert the exact wire shape without performing a network call.
     *
     * @param order the order whose status changed
     * @param from  the previous status
     * @param to    the new status
     * @return an insertion-ordered map matching the documented event contract
     */
    Map<String, Object> buildEvent(Order order, OrderStatus from, OrderStatus to) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");

        Map<String, Object> orderView = new LinkedHashMap<>();
        orderView.put("id", order.getId());
        orderView.put("status", to.name());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", NotificationOutbox.eventId(order.getId(), from, to));
        event.put("orderId", order.getId());
        event.put("oldStatus", from.name());
        event.put("newStatus", to.name());
        event.put("order", orderView);
        event.put("at", Instant.now().toString());
        return event;
    }

    /**
     * Unchecked exception signaling that a status-change notification could not be delivered.
     * {@code OrderService} treats it as a recoverable delivery failure (the event stays pending
     * in the outbox), never as a transition failure.
     */
    public static class NotificationDeliveryException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates a delivery exception with a message.
         *
         * @param message a description of the delivery failure
         */
        public NotificationDeliveryException(String message) {
            super(message);
        }

        /**
         * Creates a delivery exception with a message and underlying cause.
         *
         * @param message a description of the delivery failure
         * @param cause   the underlying I/O or interruption cause
         */
        public NotificationDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
