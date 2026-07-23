package com.healthcare.order.api;

import com.healthcare.order.CouponValidator;
import com.healthcare.order.Order;
import com.healthcare.order.OrderService;
import com.healthcare.order.OrderStatus;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for the application entry-point wiring, focused on the automatic notification retry loop
 * (finding INT-01): a status-change event left {@code PENDING} by a transient transport failure is
 * re-delivered automatically once the transport recovers, with no manual intervention, and the
 * outbox delivery-health counts reflect the outcome.
 */
public class OrderServiceApplicationTest {

    /** A trigger that throws for its first {@code failFirst} invocations, then succeeds. */
    private static final class FlakyTrigger implements OrderService.NotificationTrigger {
        final AtomicInteger attempts = new AtomicInteger();
        private final int failFirst;

        FlakyTrigger(int failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public void onStatusChange(Order order, OrderStatus from, OrderStatus to) {
            if (attempts.incrementAndGet() <= failFirst) {
                throw new IllegalStateException("simulated transient delivery failure");
            }
        }
    }

    @Test
    void pendingNotificationIsAutoRetriedUntilDelivered() throws Exception {
        OrderService service = new OrderService(new CouponValidator());
        FlakyTrigger trigger = new FlakyTrigger(1); // first delivery fails; a retry succeeds
        service.setNotificationTrigger(trigger);

        Order order = service.createOrder(new BigDecimal("10.00"), List.of());
        // The first transition attempts delivery synchronously; it fails and, per the outbox
        // contract, the transition still commits while the event is left PENDING for retry.
        service.updateStatus(order.getId(), OrderStatus.CONFIRMED);
        assertEquals(1L, service.getOutbox().pendingCount(),
                "a failed initial delivery must leave the event PENDING");
        assertEquals(0L, service.getOutbox().deliveredCount());

        // Start the auto-retry loop with fast delays for a deterministic, quick test.
        ScheduledExecutorService scheduler =
                OrderServiceApplication.startNotificationRetryLoop(service, 20L, 100L);
        try {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (service.getOutbox().pendingCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals(0L, service.getOutbox().pendingCount(),
                    "the auto-retry loop must eventually deliver the pending event");
            assertEquals(1L, service.getOutbox().deliveredCount(),
                    "the delivered event must be reflected in the delivery-health count");
            assertTrue(trigger.attempts.get() >= 2,
                    "delivery must have been retried at least once beyond the initial failure");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void idleRetryLoopDeliversNothingAndStopsCleanly() throws Exception {
        OrderService service = new OrderService(new CouponValidator());
        service.setNotificationTrigger(new FlakyTrigger(0)); // would always succeed if invoked

        // No transition performed -> nothing is pending, so the loop must be a quiet no-op.
        ScheduledExecutorService scheduler =
                OrderServiceApplication.startNotificationRetryLoop(service, 20L, 100L);
        try {
            Thread.sleep(120L);
            assertEquals(0L, service.getOutbox().pendingCount());
            assertEquals(0L, service.getOutbox().deliveredCount());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
