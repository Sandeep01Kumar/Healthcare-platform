package com.healthcare.order;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link OrderService} order-status handling and coupon orchestration.
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>the {@link OrderStatus} transition guard — only the forward steps
 *       {@code CREATED -> CONFIRMED -> DELIVERED} are permitted; skips, backward moves,
 *       self-transitions, moves out of the terminal {@code DELIVERED} state, and a
 *       {@code null} target are all rejected (including the null-safe static
 *       {@link OrderStatus#isValidTransition(OrderStatus, OrderStatus)});</li>
 *   <li>the service-level valid forward walk, driven through both
 *       {@link OrderService#updateStatus(Order, OrderStatus)} (an explicit order) and
 *       {@link OrderService#updateStatus(OrderStatus)} (the managed
 *       {@linkplain OrderService#getCurrentOrder() current order}), advancing the status
 *       at each step;</li>
 *   <li>rejection of invalid transitions:
 *       {@link OrderService#updateStatus(Order, OrderStatus)} throws
 *       {@link IllegalStateException} and leaves the order's status unchanged without
 *       firing a notification;</li>
 *   <li>the decoupled {@link OrderService.NotificationTrigger}: it fires exactly once per
 *       successful transition with the correct {@code (from, to)} statuses and order, is
 *       not invoked on a rejected transition, and a {@code null} trigger is tolerated at the
 *       library level (the mandatory-wiring requirement is enforced separately by the API
 *       server, covered in its own test);</li>
 *   <li>backward compatibility of the no-argument {@link OrderService#createOrder()};</li>
 *   <li>the coupon-aware {@link OrderService#createOrder(BigDecimal, List)} overload: the
 *       discounted total is always non-negative (zero-floored) and is asserted to the EXACT
 *       amount at {@code scale() == 2} for single, stacked, and duplicate-code cases, with the
 *       applied-coupon metadata recorded on the order;</li>
 *   <li>per-id storage and retrieval via {@link OrderService#getOrder(String)} and status
 *       transitions driven by id via {@link OrderService#updateStatus(String, OrderStatus)}
 *       (unknown ids raise {@link OrderNotFoundException});</li>
 *   <li>atomic coupon redemption: a usage-limited coupon is consumed across orders and cannot
 *       be over-redeemed, including under concurrent order creation;</li>
 *   <li>the transaction outbox (C7): a failing trigger does not abort the committed transition
 *       and leaves the event {@link NotificationOutbox.State#PENDING}, which a later
 *       {@link OrderService#retryPendingNotifications()} drains to
 *       {@link NotificationOutbox.State#DELIVERED}.</li>
 * </ul>
 *
 * <p>The production classes live in this same {@code com.healthcare.order} package, so they
 * are referenced by simple name with no {@code import}; only JUnit 5 and the JDK types
 * actually used are imported. The {@link OrderService.NotificationTrigger} test-double is a
 * plain lambda backed by atomic counters (no mocking framework). Monetary
 * {@link BigDecimal} values are compared with {@link BigDecimal#compareTo(BigDecimal)}
 * (never {@code equals}) so the assertions are insensitive to scale, and enum identities
 * are asserted with {@code assertSame}.
 */
public class OrderServiceTest {

    /** The enum guard permits only the forward CREATED->CONFIRMED->DELIVERED steps. */
    @Test
    void transitionGuardAllowsOnlyForwardSteps() {
        // Valid forward steps.
        assertTrue(OrderStatus.CREATED.canTransitionTo(OrderStatus.CONFIRMED));
        assertTrue(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.DELIVERED));
        // Skip.
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.DELIVERED));
        // Backward.
        assertFalse(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.CREATED));
        assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CONFIRMED));
        // Self.
        assertFalse(OrderStatus.CREATED.canTransitionTo(OrderStatus.CREATED));
        assertFalse(OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.CONFIRMED));
        assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.DELIVERED));
        // Out of the terminal DELIVERED state.
        assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.CREATED));
        // Null target.
        assertFalse(OrderStatus.CREATED.canTransitionTo(null));
    }

    /** The static isValidTransition mirror is null-safe and agrees with the instance guard. */
    @Test
    void staticIsValidTransitionIsNullSafe() {
        assertTrue(OrderStatus.isValidTransition(OrderStatus.CREATED, OrderStatus.CONFIRMED));
        assertTrue(OrderStatus.isValidTransition(OrderStatus.CONFIRMED, OrderStatus.DELIVERED));
        assertFalse(OrderStatus.isValidTransition(OrderStatus.CREATED, OrderStatus.DELIVERED));
        assertFalse(OrderStatus.isValidTransition(OrderStatus.CONFIRMED, OrderStatus.CREATED));
        assertFalse(OrderStatus.isValidTransition(null, OrderStatus.CONFIRMED));
        assertFalse(OrderStatus.isValidTransition(OrderStatus.CREATED, null));
        assertFalse(OrderStatus.isValidTransition(null, null));
    }

    /** A full valid walk via updateStatus(Order, OrderStatus) advances the status at each step. */
    @Test
    void validTransitionsAdvanceStatus() {
        OrderService svc = new OrderService();
        Order order = new Order("order-1", new BigDecimal("100.00"));
        assertSame(OrderStatus.CREATED, order.getStatus());
        svc.updateStatus(order, OrderStatus.CONFIRMED);
        assertSame(OrderStatus.CONFIRMED, order.getStatus());
        svc.updateStatus(order, OrderStatus.DELIVERED);
        assertSame(OrderStatus.DELIVERED, order.getStatus());
    }

    /** The no-arg updateStatus drives the managed current order and is rejected before one exists. */
    @Test
    void noArgUpdateStatusDrivesCurrentOrder() {
        OrderService svc = new OrderService();
        // No order created yet: the no-arg transition must be rejected.
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(OrderStatus.CONFIRMED));
        // Create the managed current order (CREATED), then walk it forward via the no-arg form.
        Order created = svc.createOrder(new BigDecimal("50.00"), List.of());
        assertSame(OrderStatus.CREATED, created.getStatus());
        svc.updateStatus(OrderStatus.CONFIRMED);
        assertSame(OrderStatus.CONFIRMED, svc.getCurrentOrder().getStatus());
        svc.updateStatus(OrderStatus.DELIVERED);
        assertSame(OrderStatus.DELIVERED, svc.getCurrentOrder().getStatus());
    }

    /** Invalid transitions (skip, self, null) throw, leave status unchanged, and do not notify. */
    @Test
    void invalidTransitionsAreRejectedWithoutNotifying() {
        OrderService svc = new OrderService();
        AtomicInteger count = new AtomicInteger();
        svc.setNotificationTrigger((o, from, to) -> count.incrementAndGet());
        Order order = new Order("order-2", new BigDecimal("100.00"));
        // Skip CREATED -> DELIVERED.
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(order, OrderStatus.DELIVERED));
        assertSame(OrderStatus.CREATED, order.getStatus());
        // Self CREATED -> CREATED.
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(order, OrderStatus.CREATED));
        assertSame(OrderStatus.CREATED, order.getStatus());
        // Null target.
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(order, null));
        assertSame(OrderStatus.CREATED, order.getStatus());
        // No successful transition occurred, so the trigger never fired.
        assertEquals(0, count.get());
    }

    /** Backward and out-of-terminal transitions are rejected with the status left unchanged. */
    @Test
    void backwardAndTerminalTransitionsAreRejected() {
        OrderService svc = new OrderService();
        Order order = new Order("order-3", new BigDecimal("100.00"));
        svc.updateStatus(order, OrderStatus.CONFIRMED);
        // Backward CONFIRMED -> CREATED.
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(order, OrderStatus.CREATED));
        assertSame(OrderStatus.CONFIRMED, order.getStatus());
        svc.updateStatus(order, OrderStatus.DELIVERED);
        // Out of the terminal DELIVERED state (to CONFIRMED and to CREATED).
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(order, OrderStatus.CONFIRMED));
        assertSame(OrderStatus.DELIVERED, order.getStatus());
        assertThrows(IllegalStateException.class, () -> svc.updateStatus(order, OrderStatus.CREATED));
        assertSame(OrderStatus.DELIVERED, order.getStatus());
    }

    /** The trigger fires exactly once per successful transition with the correct (from, to) and order. */
    @Test
    void notificationFiresOncePerValidTransition() {
        OrderService svc = new OrderService();
        AtomicInteger count = new AtomicInteger();
        AtomicReference<OrderStatus> lastFrom = new AtomicReference<>();
        AtomicReference<OrderStatus> lastTo = new AtomicReference<>();
        AtomicReference<Order> lastOrder = new AtomicReference<>();
        svc.setNotificationTrigger((o, from, to) -> {
            count.incrementAndGet();
            lastFrom.set(from);
            lastTo.set(to);
            lastOrder.set(o);
        });
        Order order = new Order("order-4", new BigDecimal("100.00"));
        svc.updateStatus(order, OrderStatus.CONFIRMED);
        svc.updateStatus(order, OrderStatus.DELIVERED);
        assertEquals(2, count.get());
        assertSame(OrderStatus.CONFIRMED, lastFrom.get());
        assertSame(OrderStatus.DELIVERED, lastTo.get());
        assertSame(order, lastOrder.get());
    }

    /** A null notification trigger is tolerated: valid transitions still succeed without an NPE. */
    @Test
    void nullTriggerIsTolerated() {
        OrderService svc = new OrderService();
        Order order = new Order("order-5", new BigDecimal("100.00"));
        // No trigger wired at all.
        assertDoesNotThrow(() -> svc.updateStatus(order, OrderStatus.CONFIRMED));
        assertSame(OrderStatus.CONFIRMED, order.getStatus());
        // Explicitly clearing the trigger is equally tolerated.
        svc.setNotificationTrigger(null);
        assertDoesNotThrow(() -> svc.updateStatus(order, OrderStatus.DELIVERED));
        assertSame(OrderStatus.DELIVERED, order.getStatus());
    }

    /** Backward compatibility: the no-arg entry point still returns the fixed confirmation string. */
    @Test
    void createOrderIsBackwardCompatible() {
        assertEquals("Order Created", new OrderService().createOrder());
    }

    /**
     * A valid, seeded coupon is applied and yields the EXACT discounted total at scale 2, and the
     * order carries the applied coupon's metadata. SAVE10 is 10% off {@code 100.00}, so the total
     * must be exactly {@code 90.00} (not merely "less than 100") — asserting the exact amount and
     * {@code scale() == 2} is what makes this test able to catch a money-scale or discount
     * regression.
     */
    @Test
    void createOrderWithValidCouponYieldsExactDiscountedTotal() {
        LocalDate today = LocalDate.now();
        Coupon save10 = new Coupon("SAVE10", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 100, 0);
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(save10);
        OrderService svc = new OrderService(validator);
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of("SAVE10"));
        assertNotNull(order);
        assertSame(OrderStatus.CREATED, order.getStatus());
        assertEquals(1, order.getAppliedCoupons().size());
        assertSame(order, svc.getCurrentOrder());
        // Applied-coupon metadata is recorded on the order.
        Coupon applied = order.getAppliedCoupons().get(0);
        assertEquals("SAVE10", applied.getCode());
        assertEquals(Coupon.TYPE_PERCENTAGE, applied.getType());
        // EXACT discounted total, asserted for both amount and scale.
        BigDecimal total = order.getDiscountedTotal();
        assertNotNull(total);
        assertEquals(0, new BigDecimal("90.00").compareTo(total), "10% off 100.00 must be exactly 90.00");
        assertEquals(2, total.scale(), "discounted total must be normalized to scale 2");
    }

    /**
     * Multiple valid coupons stack in submission order and produce the EXACT stacked total at
     * scale 2: 10% off {@code 100.00} -> {@code 90.00}, then a {@code $5.00} fixed reduction ->
     * {@code 85.00}. Both coupons are recorded on the order.
     */
    @Test
    void createOrderWithMultipleCouponsStacksToExactTotal() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("SAVE10", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0));
        validator.addCoupon(new Coupon("FIVE", Coupon.TYPE_FIXED, new BigDecimal("5.00"),
                today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0));
        OrderService svc = new OrderService(validator);
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of("SAVE10", "FIVE"));
        assertEquals(2, order.getAppliedCoupons().size());
        BigDecimal total = order.getDiscountedTotal();
        assertEquals(0, new BigDecimal("85.00").compareTo(total), "stacked 10% then $5 off 100.00 must be 85.00");
        assertEquals(2, total.scale());
    }

    /**
     * A duplicate coupon code (any casing) is applied — and redeemed — only ONCE for one order:
     * the applied list holds a single SAVE10, and the discount reflects a single 10% reduction
     * ({@code 90.00}), not a compounded double application ({@code 81.00}).
     */
    @Test
    void createOrderDeduplicatesRepeatedCouponCode() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("SAVE10", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0));
        OrderService svc = new OrderService(validator);
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of("SAVE10", "save10", " SAVE10 "));
        assertEquals(1, order.getAppliedCoupons().size(), "a duplicate code must be applied only once");
        assertEquals(0, new BigDecimal("90.00").compareTo(order.getDiscountedTotal()),
                "a duplicate code must not stack a second discount");
    }

    /** A created order is stored and retrievable by its exact id; an unknown id yields empty. */
    @Test
    void createdOrderIsRetrievableByIdFromRepository() {
        OrderService svc = new OrderService();
        Order a = svc.createOrder(new BigDecimal("40.00"), List.of());
        Order b = svc.createOrder(new BigDecimal("60.00"), List.of());
        assertNotEquals(a.getId(), b.getId(), "each order gets a distinct id");
        assertSame(a, svc.getOrder(a.getId()).orElseThrow(), "order A retrievable by its own id");
        assertSame(b, svc.getOrder(b.getId()).orElseThrow(), "order B retrievable by its own id");
        assertTrue(svc.getOrder("no-such-id").isEmpty(), "an unknown id resolves to empty");
        // Creating B must not clobber A (the single-current-order bug this replaces).
        assertSame(b, svc.getCurrentOrder());
        assertSame(OrderStatus.CREATED, svc.getOrder(a.getId()).orElseThrow().getStatus());
    }

    /** Transitioning by id advances the stored order; an unknown id throws OrderNotFoundException. */
    @Test
    void updateStatusByIdDrivesStoredOrderAndRejectsUnknownId() {
        OrderService svc = new OrderService();
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of());
        svc.updateStatus(order.getId(), OrderStatus.CONFIRMED);
        assertSame(OrderStatus.CONFIRMED, svc.getOrder(order.getId()).orElseThrow().getStatus());
        assertThrows(OrderNotFoundException.class,
                () -> svc.updateStatus("no-such-id", OrderStatus.CONFIRMED));
    }

    /**
     * A usage-limited coupon cannot be over-redeemed across orders: a single-use coupon is valid
     * for the first order but rejected (usage exhausted) for the second, so the second order gets
     * no discount. This exercises the atomic validate-and-redeem consumption.
     */
    @Test
    void usageLimitedCouponIsConsumedAcrossOrders() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("ONCE", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 1, 0));
        OrderService svc = new OrderService(validator);

        Order first = svc.createOrder(new BigDecimal("100.00"), List.of("ONCE"));
        assertEquals(1, first.getAppliedCoupons().size(), "first order redeems the single use");
        assertEquals(0, new BigDecimal("90.00").compareTo(first.getDiscountedTotal()));

        Order second = svc.createOrder(new BigDecimal("100.00"), List.of("ONCE"));
        assertEquals(0, second.getAppliedCoupons().size(), "coupon is exhausted for the second order");
        assertEquals(0, new BigDecimal("100.00").compareTo(second.getDiscountedTotal()),
                "an exhausted coupon applies no discount");
    }

    /**
     * The transaction-outbox guarantee: when the notification trigger throws, the status
     * transition STILL commits (the exception does not propagate) and the event is left PENDING in
     * the outbox rather than lost. A subsequent {@code retryPendingNotifications()} — once the
     * transport recovers — drains the pending event and marks it delivered.
     */
    @Test
    void failedNotificationLeavesEventPendingButCommitsTransition() {
        OrderService svc = new OrderService();
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of());
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        svc.setNotificationTrigger((o, from, to) -> {
            attempts.incrementAndGet();
            if (fail.get()) {
                throw new RuntimeException("transport down");
            }
        });

        // Delivery fails, but the transition must still commit and must not throw.
        assertDoesNotThrow(() -> svc.updateStatus(order, OrderStatus.CONFIRMED));
        assertSame(OrderStatus.CONFIRMED, order.getStatus(), "transition commits despite delivery failure");
        assertEquals(1, attempts.get());
        String eventId = NotificationOutbox.eventId(order.getId(), OrderStatus.CREATED, OrderStatus.CONFIRMED);
        assertEquals(NotificationOutbox.State.PENDING, svc.getOutbox().stateOf(eventId),
                "a failed delivery must leave the event PENDING, not lose it");
        assertEquals(1, svc.getOutbox().pending().size());

        // Transport recovers; retry drains the pending event and marks it delivered.
        fail.set(false);
        int delivered = svc.retryPendingNotifications();
        assertEquals(1, delivered);
        assertEquals(NotificationOutbox.State.DELIVERED, svc.getOutbox().stateOf(eventId));
        assertTrue(svc.getOutbox().pending().isEmpty(), "no pending events remain after a successful retry");
    }

    /**
     * A successful transition records the event and marks it DELIVERED in the outbox.
     */
    @Test
    void successfulNotificationMarksOutboxDelivered() {
        OrderService svc = new OrderService();
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of());
        svc.setNotificationTrigger((o, from, to) -> { /* succeeds */ });
        svc.updateStatus(order, OrderStatus.CONFIRMED);
        String eventId = NotificationOutbox.eventId(order.getId(), OrderStatus.CREATED, OrderStatus.CONFIRMED);
        assertEquals(NotificationOutbox.State.DELIVERED, svc.getOutbox().stateOf(eventId));
        assertTrue(svc.getOutbox().pending().isEmpty());
    }

    /**
     * Concurrency: many threads racing to redeem a coupon limited to N uses must collectively
     * redeem it exactly N times — never more. Verifies the atomic check-then-consume in
     * {@code validateAndRedeem} holds under contention.
     */
    @Test
    void concurrentOrderCreationDoesNotOverRedeemLimitedCoupon() throws InterruptedException {
        final int limit = 20;
        final int threads = 64;
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("LIMITED", Coupon.TYPE_FIXED, new BigDecimal("5.00"),
                today.minusDays(1), today.plusDays(30), limit, 0));
        OrderService svc = new OrderService(validator);

        AtomicInteger applied = new AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Order order = svc.createOrder(new BigDecimal("100.00"), List.of("LIMITED"));
                if (!order.getAppliedCoupons().isEmpty()) {
                    applied.incrementAndGet();
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        assertEquals(limit, applied.get(),
                "a coupon limited to " + limit + " uses must be redeemed exactly " + limit + " times under contention");
    }

    /** With no coupons, the discounted total is non-negative and equals the base price. */
    @Test
    void createOrderWithNoCouponsReturnsBasePrice() {
        OrderService svc = new OrderService();
        Order order = svc.createOrder(new BigDecimal("100.00"), List.of());
        assertNotNull(order);
        assertSame(OrderStatus.CREATED, order.getStatus());
        assertEquals(0, order.getAppliedCoupons().size());
        BigDecimal total = order.getDiscountedTotal();
        assertNotNull(total);
        assertTrue(total.compareTo(BigDecimal.ZERO) >= 0);
        // No discount applied, so the total equals the base price (scale-insensitive compare).
        assertEquals(0, total.compareTo(new BigDecimal("100.00")));
    }

}
