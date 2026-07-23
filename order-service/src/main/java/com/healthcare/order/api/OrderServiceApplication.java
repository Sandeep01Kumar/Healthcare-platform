package com.healthcare.order.api;

import com.healthcare.order.Coupon;
import com.healthcare.order.CouponValidator;
import com.healthcare.order.OrderService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runnable entry point that boots the order-service HTTP API with a live Java&rarr;Node
 * notification bridge wired in.
 *
 * <p>It assembles the production object graph:</p>
 * <ol>
 *   <li>a {@link CouponValidator} seeded with a few demo coupons so the API is usable out of the
 *       box;</li>
 *   <li>an {@link OrderService} over that validator;</li>
 *   <li>an {@link HttpNotificationTrigger} pointed at the Node {@code notification-service}
 *       receiver; and</li>
 *   <li>an {@link OrderApiServer} that wires the trigger into the service (mandatory) and serves
 *       the HTTP routes.</li>
 * </ol>
 *
 * <h2>Configuration (environment variables)</h2>
 * <ul>
 *   <li>{@code ORDER_SERVICE_PORT} — TCP port to bind (default {@code 8080}).</li>
 *   <li>{@code NOTIFICATION_URL} — the Node receiver URL (default
 *       {@code http://127.0.0.1:3001/notifications}).</li>
 * </ul>
 *
 * <p>Run it after building both Java modules, for example:</p>
 * <pre>
 *   mvn -o -pl order-service/pricing-engine install
 *   mvn -o -pl order-service compile
 *   java -cp "order-service/target/classes:order-service/pricing-engine/target/classes" \
 *        com.healthcare.order.api.OrderServiceApplication
 * </pre>
 */
public final class OrderServiceApplication {

    private static final Logger LOGGER = Logger.getLogger(OrderServiceApplication.class.getName());

    /** Default bind port when {@code ORDER_SERVICE_PORT} is unset. */
    private static final int DEFAULT_PORT = 8080;

    /** Default Node receiver URL when {@code NOTIFICATION_URL} is unset. */
    private static final String DEFAULT_NOTIFICATION_URL = "http://127.0.0.1:3001/notifications";

    private OrderServiceApplication() {
        // Entry-point holder: no instances.
    }

    /**
     * Boots the API server and installs a shutdown hook to stop it cleanly.
     *
     * @param args ignored; configuration is read from the environment
     * @throws IOException if the HTTP socket cannot be bound
     */
    public static void main(String[] args) throws IOException {
        int port = readPort();
        String notificationUrl = envOrDefault("NOTIFICATION_URL", DEFAULT_NOTIFICATION_URL);

        OrderService service = new OrderService(seededValidator());
        OrderService.NotificationTrigger trigger = new HttpNotificationTrigger(notificationUrl);

        OrderApiServer apiServer = new OrderApiServer(service, trigger, port).start();
        LOGGER.log(Level.INFO, () -> "notification events will be POSTed to " + notificationUrl);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> apiServer.stop(1), "order-api-shutdown"));
    }

    /**
     * Builds a validator seeded with a small set of demo coupons active for the next 30 days.
     *
     * @return the seeded validator
     */
    static CouponValidator seededValidator() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupons(List.of(
                new Coupon("SAVE10", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                        today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0),
                new Coupon("WELCOME5", Coupon.TYPE_FIXED, new BigDecimal("5.00"),
                        today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0),
                new Coupon("HALFOFF", Coupon.TYPE_PERCENTAGE, new BigDecimal("50"),
                        today.minusDays(1), today.plusDays(30), 100, 0)));
        return validator;
    }

    private static int readPort() {
        String raw = System.getenv("ORDER_SERVICE_PORT");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING,
                    () -> "invalid ORDER_SERVICE_PORT '" + raw + "'; falling back to " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
