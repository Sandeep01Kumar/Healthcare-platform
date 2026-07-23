package com.healthcare.order.api;

import com.healthcare.order.Coupon;
import com.healthcare.order.CouponValidator;
import com.healthcare.order.OrderService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * <p>Run it after building both Java modules. Because this repository is a single flat checkout
 * with no aggregator (reactor) POM, build each module by pointing Maven at that module's own POM
 * with {@code -f} (equivalently, {@code cd} into the module directory and run {@code mvn} there).
 * From the repository root, for example:</p>
 * <pre>
 *   mvn -o -f order-service/pricing-engine/pom.xml install
 *   mvn -o -f order-service/pom.xml compile
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

    /**
     * Default base delay (milliseconds) before the first automatic pending-notification retry
     * sweep, and the starting point the exponential backoff resets to. Overridable via
     * {@code NOTIFICATION_RETRY_BASE_MS}.
     */
    private static final long DEFAULT_RETRY_BASE_DELAY_MS = 1_000L;

    /**
     * Default cap (milliseconds) on the exponential-backoff retry delay, so a persistently
     * unreachable receiver is retried at a bounded, non-hammering cadence. Overridable via
     * {@code NOTIFICATION_RETRY_MAX_MS}.
     */
    private static final long DEFAULT_RETRY_MAX_DELAY_MS = 30_000L;

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

        // CONFIG-01: construct (and thereby validate) the notification transport BEFORE binding the
        // HTTP socket. HttpNotificationTrigger now rejects a relative/scheme-less/host-less
        // NOTIFICATION_URL at construction, so a misconfiguration fails fast here — the server
        // never starts listening and never reports "ready" with a transport that can never deliver.
        OrderService.NotificationTrigger trigger;
        try {
            trigger = new HttpNotificationTrigger(notificationUrl);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, () -> "FATAL: invalid NOTIFICATION_URL '" + notificationUrl
                    + "': " + e.getMessage() + " — refusing to start.");
            System.exit(2);
            return; // unreachable after System.exit; satisfies definite assignment of 'trigger'
        }

        OrderApiServer apiServer = new OrderApiServer(service, trigger, port).start();
        LOGGER.log(Level.INFO, () -> "notification events will be POSTed to " + notificationUrl);

        // INT-01: start the automatic outbox retry loop so any status-change event left PENDING by
        // a transient transport outage is re-delivered once the receiver recovers — with no manual
        // intervention. Delivery health is observable via the outbox pending/delivered counts.
        long retryBaseMs = readLongEnv("NOTIFICATION_RETRY_BASE_MS", DEFAULT_RETRY_BASE_DELAY_MS);
        long retryMaxMs = readLongEnv("NOTIFICATION_RETRY_MAX_MS", DEFAULT_RETRY_MAX_DELAY_MS);
        ScheduledExecutorService retryScheduler =
                startNotificationRetryLoop(service, retryBaseMs, retryMaxMs);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            retryScheduler.shutdownNow();
            apiServer.stop(1);
        }, "order-api-shutdown"));
    }

    /**
     * Starts a single daemon thread that periodically re-attempts delivery of any status-change
     * events still {@link com.healthcare.order.NotificationOutbox.State#PENDING} in the service's
     * outbox (finding INT-01).
     *
     * <p>The loop is self-rescheduling with <b>bounded exponential backoff</b>: it sweeps after
     * {@code baseDelayMs}, and while pending events remain undeliverable it doubles the delay up to
     * {@code maxDelayMs} (so a persistently down receiver is retried at a bounded, non-hammering
     * cadence); as soon as the outbox drains it resets to {@code baseDelayMs}. Because the thread
     * is a daemon it never blocks JVM shutdown, and the returned executor is stopped by the
     * application's shutdown hook.</p>
     *
     * <p>Note: the outbox is in-memory (persistence is out of scope per the feature plan), so this
     * recovers the <em>receiver-outage</em> scenario — a committed event reaching a receiver that
     * was temporarily down — within a single process lifetime; it does not survive an
     * order-service process restart.</p>
     *
     * @param service     the order service whose outbox is swept
     * @param baseDelayMs base/reset delay in milliseconds (must be positive)
     * @param maxDelayMs  maximum backoff delay in milliseconds (clamped to at least {@code baseDelayMs})
     * @return the scheduler running the loop; shut down via {@link ScheduledExecutorService#shutdownNow()}
     */
    static ScheduledExecutorService startNotificationRetryLoop(
            OrderService service, long baseDelayMs, long maxDelayMs) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "order-notification-retry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.schedule(new NotificationRetryLoop(service, scheduler, baseDelayMs, maxDelayMs),
                baseDelayMs, TimeUnit.MILLISECONDS);
        return scheduler;
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

    /**
     * Reads a positive {@code long} from the named environment variable, falling back to
     * {@code fallback} when the variable is unset, blank, non-positive, or unparseable.
     *
     * @param name     the environment variable name
     * @param fallback the value to use when the variable is absent or invalid
     * @return the parsed positive value, or {@code fallback}
     */
    private static long readLongEnv(String name, long fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING,
                    () -> "invalid " + name + " '" + raw + "'; falling back to " + fallback);
            return fallback;
        }
    }

    /**
     * Self-rescheduling retry task with bounded exponential backoff (finding INT-01).
     *
     * <p>Runs on the single-thread scheduler created by
     * {@link #startNotificationRetryLoop(OrderService, long, long)}; because every invocation is
     * on that one thread, its {@code delayMs} state needs no synchronization. Each run sweeps the
     * outbox only when something is pending, adjusts the next delay (reset on drain, double up to
     * the cap while still failing), and reschedules itself.</p>
     */
    private static final class NotificationRetryLoop implements Runnable {

        private final OrderService service;
        private final ScheduledExecutorService scheduler;
        private final long baseDelayMs;
        private final long maxDelayMs;
        private long delayMs;

        NotificationRetryLoop(OrderService service, ScheduledExecutorService scheduler,
                long baseDelayMs, long maxDelayMs) {
            this.service = service;
            this.scheduler = scheduler;
            this.baseDelayMs = baseDelayMs;
            this.maxDelayMs = Math.max(baseDelayMs, maxDelayMs);
            this.delayMs = baseDelayMs;
        }

        @Override
        public void run() {
            try {
                if (service.getOutbox().pendingCount() > 0) {
                    int delivered = service.retryPendingNotifications();
                    if (service.getOutbox().pendingCount() == 0) {
                        if (delivered > 0) {
                            LOGGER.log(Level.INFO, () -> "auto-retry delivered " + delivered
                                    + " pending notification(s); outbox drained");
                        }
                        delayMs = baseDelayMs;                        // recovered -> reset backoff
                    } else {
                        delayMs = Math.min(delayMs * 2, maxDelayMs);  // still failing -> back off
                    }
                } else {
                    delayMs = baseDelayMs;                            // nothing pending -> steady
                }
            } catch (RuntimeException e) {
                // A sweep error must never kill the loop; log sanitized (OBS-01 style) and back off.
                LOGGER.log(Level.WARNING, () -> "notification retry sweep error [type="
                        + e.getClass().getSimpleName() + "]");
                LOGGER.log(Level.FINE, e, () -> "stack trace for notification retry sweep error");
                delayMs = Math.min(delayMs * 2, maxDelayMs);
            } finally {
                if (!scheduler.isShutdown()) {
                    scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS);
                }
            }
        }
    }
}
