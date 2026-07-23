package com.healthcare.order.api;

import com.healthcare.order.Order;
import com.healthcare.order.OrderStatus;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link HttpNotificationTrigger}, the producer half of the Java&#8594;Node
 * status-change notification bridge (finding C6). Two concerns are covered:
 *
 * <ul>
 *   <li>the exact <b>event wire shape</b> (contract F) built by the package-private
 *       {@link HttpNotificationTrigger#buildEvent(Order, OrderStatus, OrderStatus)} — asserted
 *       without any network call, then confirmed to survive a {@link Json} round-trip; and</li>
 *   <li>the <b>delivery semantics</b> against a real (stand-in) HTTP receiver: a {@code 2xx}
 *       response is a success, a non-{@code 2xx} response and an unreachable receiver both raise
 *       {@link HttpNotificationTrigger.NotificationDeliveryException} (which the order-service maps
 *       to a recoverable, retryable outbox failure rather than a transition failure).</li>
 * </ul>
 *
 * <p>The stand-in receiver is a minimal JDK {@link HttpServer} bound on an ephemeral loopback port,
 * standing in for the Phase&nbsp;4 Node {@code POST /notifications} endpoint that implements the same
 * contract.</p>
 */
public class HttpNotificationTriggerTest {

    private HttpServer receiver;

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    // ------------------------------------------------------------------ wire shape (no network)

    @Test
    void buildEventProducesTheDocumentedContractShape() {
        Order order = new Order("order-1", new BigDecimal("100.00"));
        HttpNotificationTrigger trigger = new HttpNotificationTrigger("http://127.0.0.1:65535/notifications");

        Map<String, Object> event = trigger.buildEvent(order, OrderStatus.CREATED, OrderStatus.CONFIRMED);

        assertEquals("order-1|CREATED->CONFIRMED", event.get("eventId"));
        assertEquals("order-1", event.get("orderId"));
        assertEquals("CREATED", event.get("oldStatus"));
        assertEquals("CONFIRMED", event.get("newStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedOrder = (Map<String, Object>) event.get("order");
        assertEquals("order-1", nestedOrder.get("id"));
        assertEquals("CONFIRMED", nestedOrder.get("status"), "nested order carries the NEW status");
        assertNotNull(event.get("at"), "the event carries an ISO-8601 timestamp");
        assertInstanceOf(String.class, event.get("at"));
    }

    @Test
    void eventSerializesAndRoundTripsThroughJson() {
        Order order = new Order("order-42", new BigDecimal("100.00"));
        HttpNotificationTrigger trigger = new HttpNotificationTrigger("http://127.0.0.1:65535/notifications");

        String json = Json.stringify(trigger.buildEvent(order, OrderStatus.CONFIRMED, OrderStatus.DELIVERED));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Json.parse(json);
        assertEquals("order-42|CONFIRMED->DELIVERED", back.get("eventId"));
        assertEquals("CONFIRMED", back.get("oldStatus"));
        assertEquals("DELIVERED", back.get("newStatus"));
    }

    // ------------------------------------------------------------------ delivery semantics

    @Test
    void successfulDeliveryPostsEventJsonToReceiver() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        int port = startReceiver(200, received, hits);

        HttpNotificationTrigger trigger = new HttpNotificationTrigger(
                "http://127.0.0.1:" + port + "/notifications",
                HttpClient.newHttpClient(), Duration.ofSeconds(10));
        Order order = new Order("order-7", new BigDecimal("100.00"));

        assertDoesNotThrow(() -> trigger.onStatusChange(order, OrderStatus.CREATED, OrderStatus.CONFIRMED));
        assertEquals(1, hits.get(), "the receiver was called exactly once");

        // The receiver got the documented event JSON.
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) Json.parse(received.get());
        assertEquals("order-7|CREATED->CONFIRMED", event.get("eventId"));
        assertEquals("CREATED", event.get("oldStatus"));
        assertEquals("CONFIRMED", event.get("newStatus"));
    }

    @Test
    void nonTwoXxResponseThrowsDeliveryException() throws Exception {
        int port = startReceiver(500, new AtomicReference<>(), new AtomicInteger());
        HttpNotificationTrigger trigger = new HttpNotificationTrigger(
                "http://127.0.0.1:" + port + "/notifications",
                HttpClient.newHttpClient(), Duration.ofSeconds(10));
        Order order = new Order("order-8", new BigDecimal("100.00"));

        assertThrows(HttpNotificationTrigger.NotificationDeliveryException.class,
                () -> trigger.onStatusChange(order, OrderStatus.CREATED, OrderStatus.CONFIRMED));
    }

    @Test
    void unreachableReceiverThrowsDeliveryException() throws Exception {
        // Bind then immediately release a port so connections are refused deterministically.
        HttpServer temp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int deadPort = temp.getAddress().getPort();
        temp.stop(0);

        HttpNotificationTrigger trigger = new HttpNotificationTrigger(
                "http://127.0.0.1:" + deadPort + "/notifications",
                HttpClient.newHttpClient(), Duration.ofSeconds(5));
        Order order = new Order("order-9", new BigDecimal("100.00"));

        assertThrows(HttpNotificationTrigger.NotificationDeliveryException.class,
                () -> trigger.onStatusChange(order, OrderStatus.CREATED, OrderStatus.CONFIRMED));
    }

    // ------------------------------------------------------------------ stand-in receiver

    /**
     * Starts a minimal loopback HTTP receiver that captures the request body and responds with the
     * given status code, returning the bound ephemeral port.
     */
    private int startReceiver(int status, AtomicReference<String> bodyOut, AtomicInteger hits) throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/notifications", exchange -> {
            hits.incrementAndGet();
            try (InputStream in = exchange.getRequestBody()) {
                bodyOut.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resp = "{\"dispatched\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        receiver.start();
        return receiver.getAddress().getPort();
    }
}
