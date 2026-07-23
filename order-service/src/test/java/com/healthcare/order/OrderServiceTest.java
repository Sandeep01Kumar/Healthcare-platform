package com.healthcare.order;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 *       not invoked on a rejected transition, and a {@code null} trigger is tolerated;</li>
 *   <li>backward compatibility of the no-argument {@link OrderService#createOrder()}; and</li>
 *   <li>the coupon-aware {@link OrderService#createOrder(BigDecimal, List)} overload, whose
 *       discounted total is always non-negative (zero-floored).</li>
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

    /** A valid, seeded coupon is applied and yields a non-negative, reduced discounted total. */
    @Test
    void createOrderWithValidCouponYieldsNonNegativeReducedTotal() {
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
        BigDecimal total = order.getDiscountedTotal();
        assertNotNull(total);
        // Non-negative (zero-floor) and strictly reduced by the applied percentage discount.
        assertTrue(total.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(total.compareTo(new BigDecimal("100.00")) < 0);
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
