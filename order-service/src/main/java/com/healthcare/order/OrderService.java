package com.healthcare.order;

import com.healthcare.pricing.DiscountCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Order lifecycle service — the order-service orchestrator for the Coupon and Order
 * Tracking feature.
 *
 * <p>This service owns four cooperating responsibilities:</p>
 * <ul>
 *   <li><b>Order creation.</b> The original {@link #createOrder()} entry point is retained
 *       unchanged for backward compatibility, alongside the coupon-aware
 *       {@link #createOrder(BigDecimal, List)} overload that builds a fully-priced
 *       {@link Order}.</li>
 *   <li><b>Guarded status transitions.</b> {@link #updateStatus(OrderStatus)} advances an
 *       order strictly through {@link OrderStatus#CREATED} &rarr;
 *       {@link OrderStatus#CONFIRMED} &rarr; {@link OrderStatus#DELIVERED}, rejecting every
 *       other movement via {@link OrderStatus#canTransitionTo(OrderStatus)}.</li>
 *   <li><b>Authoritative coupon orchestration.</b> Submitted coupon codes are validated
 *       server-side through {@link CouponValidator} (the client is never trusted), and only
 *       the coupons that pass are handed to the pricing-engine {@link DiscountCalculator} to
 *       compute a stacked, zero-floored discounted total.</li>
 *   <li><b>Decoupled status-change notification.</b> Every successful transition fires an
 *       optional {@link NotificationTrigger}, so the order module can signal a status change
 *       without any compile-time dependency on a notification transport.</li>
 * </ul>
 *
 * <p><b>Cross-module coupon mapping.</b> Coupons cross a module boundary in this class:
 * validation uses the order-side {@link Coupon} ({@code com.healthcare.order.Coupon}),
 * whereas the pricing engine consumes the distinct discount-side
 * {@code com.healthcare.pricing.Coupon}. Because the two share a simple name, the
 * pricing-side type is referenced here by its fully-qualified name (it cannot be imported
 * into this package), and {@link #toPricingCoupon(Coupon)} performs the explicit
 * {@code String}&nbsp;&harr;&nbsp;{@code Type} mapping when forwarding validated coupons to
 * the pricing engine.</p>
 *
 * <p><b>Storage.</b> Every created order is stored in an {@link OrderRepository} keyed by its
 * own id, so any order can be retrieved by the exact id a client supplies
 * ({@link #getOrder(String)}) and transitioned by id ({@link #updateStatus(String, OrderStatus)}) —
 * this is what backs the HTTP {@code GET /orders/{id}} and {@code POST /orders/{id}/status}
 * routes. The convenience {@linkplain #getCurrentOrder() current order} (the most recently
 * created one) is retained for the no-argument {@link #updateStatus(OrderStatus)} overload and for
 * backward compatibility. All state is held in memory (no persistence, ORM, or framework).</p>
 *
 * <p><b>Atomic transitions &amp; reliable notification.</b> {@link #updateStatus(Order, OrderStatus)}
 * runs under the order's own monitor and <b>re-reads the current status inside the lock</b> before
 * checking the guard, closing the check-then-act (TOCTOU) race that would otherwise let two threads
 * both advance the same order. Every successful transition is recorded in a
 * {@link NotificationOutbox} as {@code PENDING} <b>before</b> delivery is attempted, and only marked
 * {@code DELIVERED} once the {@link NotificationTrigger} returns; a delivery failure leaves the
 * event pending for {@link #retryPendingNotifications()} and never fails or rolls back the
 * (already-committed) transition. The transition and the notification therefore cannot diverge, and
 * no status-change event is ever silently lost.</p>
 *
 * <p><b>Coupon redemption.</b> {@link #createOrder(BigDecimal, List)} deduplicates submitted codes
 * by canonical form (so a duplicate cannot stack twice) and redeems each accepted coupon atomically
 * through {@link CouponValidator#validateAndRedeem(String)}; if creation fails after some coupons
 * were redeemed, every redemption is rolled back so usage accounting cannot leak.</p>
 */
public class OrderService {

    /** Logger for recoverable notification-delivery failures (the transition still commits). */
    private static final Logger LOGGER = Logger.getLogger(OrderService.class.getName());

    /**
     * Authoritative, server-side coupon validator. Seeded with the known coupons; an empty
     * validator simply treats every submitted code as unknown, so no discount is applied.
     * Never {@code null}.
     */
    private final CouponValidator couponValidator;

    /**
     * Pricing-engine discount calculator used to compute the stacked, zero-floored total
     * from the validated coupons. Stateless and reused across calls; never {@code null}.
     */
    private final DiscountCalculator discountCalculator;

    /**
     * Thread-safe, in-memory store of every created order, keyed by id. Enables retrieval and
     * status transitions by the exact id a client supplies. Never {@code null}.
     */
    private final OrderRepository repository;

    /**
     * Transactional outbox recording every status-change event before delivery, so a
     * notification-transport failure can never lose an event. Never {@code null}.
     */
    private final NotificationOutbox outbox;

    /**
     * The order currently managed by this service, or {@code null} until the first order has
     * been created. {@link #updateStatus(OrderStatus)} advances this order. It is the most
     * recently created order; per-id access is via {@link #getOrder(String)}.
     */
    private volatile Order currentOrder;

    /**
     * Optional, decoupled hook invoked on every successful status transition. When
     * {@code null} the service still functions fully; the notification is simply skipped.
     */
    private NotificationTrigger notificationTrigger;

    /**
     * Creates a service backed by an empty {@link CouponValidator}. With no coupons
     * registered, every submitted code validates as unknown and is ignored, so
     * {@link #createOrder(BigDecimal, List)} returns the price unchanged. Use
     * {@link #OrderService(CouponValidator)} to supply a validator seeded with the known
     * coupons.
     */
    public OrderService() {
        this(new CouponValidator());
    }

    /**
     * Creates a service backed by the supplied, pre-seeded coupon validator.
     *
     * @param couponValidator the authoritative validator holding the known coupons; must not
     *                        be {@code null}
     * @throws NullPointerException if {@code couponValidator} is {@code null}
     */
    public OrderService(CouponValidator couponValidator) {
        this.couponValidator = Objects.requireNonNull(couponValidator, "couponValidator");
        this.discountCalculator = new DiscountCalculator();
        this.repository = new OrderRepository();
        this.outbox = new NotificationOutbox();
    }

    /**
     * Creates an order.
     *
     * <p>Retained UNCHANGED for backward compatibility: this original entry point takes no
     * arguments and returns the fixed confirmation string exactly as before, so existing
     * callers remain valid. The richer, coupon-aware creation path is
     * {@link #createOrder(BigDecimal, List)}.</p>
     *
     * @return the confirmation message {@code "Order Created"}
     */
    public String createOrder() {
        return "Order Created";
    }

    /**
     * Creates a fully-priced {@link Order} from a base price and zero or more submitted
     * coupon codes, applying authoritative server-side validation and the pricing-engine
     * discount.
     *
     * <p>Each submitted code is validated through {@link CouponValidator#validate(String)};
     * only codes whose result {@linkplain CouponValidator.ValidationResult#isValid() is
     * valid} are applied — invalid or unknown codes are ignored (the client is never trusted
     * to pre-validate). Each accepted order-side coupon is recorded on the order and mapped
     * to the pricing-engine coupon type, then the collected coupons are handed to
     * {@link DiscountCalculator#calculate(BigDecimal, List)}, which stacks them
     * deterministically in submission order and floors the total at zero. The new order
     * starts in {@link OrderStatus#CREATED} and becomes this service's
     * {@linkplain #getCurrentOrder() current order}.</p>
     *
     * <p><b>Deduplication &amp; redemption.</b> Submitted codes are first collapsed to their
     * canonical form (trimmed, upper-cased), preserving first-seen order, so the same coupon
     * cannot be applied — or redeemed — twice for one order. Each unique code is then validated
     * <b>and redeemed atomically</b> via {@link CouponValidator#validateAndRedeem(String)};
     * only accepted coupons are applied and forwarded to the pricing engine. If any step throws
     * after some coupons were already redeemed, every redemption made for this order is rolled
     * back via {@link CouponValidator#releaseRedemption(String)} so usage accounting cannot leak.
     * The finished order is stored in the {@link OrderRepository} so it can be retrieved by id.</p>
     *
     * @param price       the base (pre-discount) price; must not be {@code null}
     * @param couponCodes the submitted coupon codes to validate and apply, in the order they
     *                    should stack; may be {@code null} or empty (treated as "no
     *                    coupons"), and individual invalid/unknown codes are skipped
     * @return the created {@link Order}, in {@link OrderStatus#CREATED}, carrying the applied
     *         coupons and the computed discounted total; never {@code null}
     * @throws NullPointerException     if {@code price} is {@code null}
     * @throws IllegalArgumentException if more than {@link CouponValidator#MAX_BATCH_SIZE} codes
     *                                  are submitted
     */
    public Order createOrder(BigDecimal price, List<String> couponCodes) {
        Objects.requireNonNull(price, "price");
        List<String> codes = couponCodes == null ? List.of() : couponCodes;
        if (codes.size() > CouponValidator.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "coupon batch size must not exceed " + CouponValidator.MAX_BATCH_SIZE
                            + ": " + codes.size());
        }

        Order order = new Order(UUID.randomUUID().toString(), price);

        // Collapse to unique canonical codes, preserving submission order, so a duplicate code
        // is never applied or redeemed twice for the same order.
        LinkedHashSet<String> uniqueCodes = new LinkedHashSet<>();
        for (String code : codes) {
            String canonical = Coupon.canonicalizeCode(code);
            if (canonical != null && !canonical.isEmpty()) {
                uniqueCodes.add(canonical);
            }
        }

        List<com.healthcare.pricing.Coupon> pricingCoupons = new ArrayList<>();
        List<String> redeemedCodes = new ArrayList<>();
        try {
            // Authoritative, server-side validate-AND-redeem per unique code. Only accepted
            // coupons are applied; invalid/unknown codes are skipped (and consume nothing).
            for (String canonical : uniqueCodes) {
                CouponValidator.ValidationResult result = couponValidator.validateAndRedeem(canonical);
                if (result.isValid()) {
                    Coupon validated = result.getCoupon();
                    order.addCoupon(validated);
                    pricingCoupons.add(toPricingCoupon(validated));
                    redeemedCodes.add(validated.getCode());
                }
            }
            // Delegate the stacked, zero-floored discount arithmetic to the pricing engine.
            order.setDiscountedTotal(discountCalculator.calculate(price, pricingCoupons));
        } catch (RuntimeException ex) {
            // Roll back every redemption already consumed for this failed order so a partial
            // failure cannot permanently consume coupon usage.
            for (String code : redeemedCodes) {
                couponValidator.releaseRedemption(code);
            }
            throw ex;
        }

        repository.save(order);
        this.currentOrder = order;
        return order;
    }

    /**
     * Advances this service's {@linkplain #getCurrentOrder() current order} to the given
     * status, enforcing the lifecycle guard and firing the notification trigger on success.
     *
     * @param next the target status; must be the immediate forward successor of the current
     *             order's status
     * @throws IllegalStateException if no order has been created yet, or if the transition
     *                               from the current status to {@code next} is not permitted
     * @see #updateStatus(Order, OrderStatus)
     */
    public void updateStatus(OrderStatus next) {
        if (currentOrder == null) {
            throw new IllegalStateException(
                    "No current order to update; create an order first.");
        }
        updateStatus(currentOrder, next);
    }

    /**
     * Advances the supplied order to the given status if and only if the transition is
     * permitted, then fires the notification trigger.
     *
     * <p>The whole check-and-apply runs under the order's own monitor and <b>re-reads the
     * current status inside the lock</b>, so two threads cannot both pass the guard and double-
     * advance the order (the check-then-act race is closed). The transition is validated against
     * {@link OrderStatus#canTransitionTo(OrderStatus)}, which permits only the forward steps
     * {@code CREATED -> CONFIRMED} and {@code CONFIRMED -> DELIVERED}. On an invalid transition —
     * a skip, a backward move, a self-transition, a move out of the terminal state, or a
     * {@code null} target — the method throws and neither mutates the order, records an event,
     * nor fires a notification.</p>
     *
     * <p>On a valid transition the event is first recorded in the {@link NotificationOutbox} as
     * {@code PENDING}, then the order's status is updated and persisted (the transition commits
     * here), and finally delivery is attempted: if a {@link NotificationTrigger} is wired its
     * {@link NotificationTrigger#onStatusChange(Order, OrderStatus, OrderStatus)} is invoked once
     * and, on success, the event is marked {@code DELIVERED}. A delivery failure is logged and the
     * event is left {@code PENDING} for {@link #retryPendingNotifications()}; it does <b>not</b>
     * propagate, because the status transition has already succeeded and must not be rolled back.</p>
     *
     * @param order the order to transition; must not be {@code null}
     * @param next  the target status; must be the immediate forward successor of the order's
     *              current status
     * @throws NullPointerException  if {@code order} is {@code null}
     * @throws IllegalStateException if the transition from the order's current status to
     *                               {@code next} is not permitted
     */
    public void updateStatus(Order order, OrderStatus next) {
        Objects.requireNonNull(order, "order");
        synchronized (order) {
            // Re-read the current status INSIDE the lock (TOCTOU fix): the decision to transition
            // and the mutation itself are now one atomic step for this order.
            OrderStatus current = order.getStatus();
            if (!current.canTransitionTo(next)) {
                throw new IllegalStateException(
                        "Illegal order status transition: " + current + " -> " + next);
            }
            // Record the event BEFORE applying/delivering so a delivery failure cannot lose it.
            NotificationOutbox.Entry event = outbox.record(order, current, next);
            // Apply and persist: the transition COMMITS here, independent of delivery outcome.
            order.setStatus(next);
            repository.save(order);
            // Attempt delivery; success marks DELIVERED, failure leaves the event PENDING.
            deliver(event);
        }
    }

    /**
     * Attempts to deliver a recorded status-change event through the wired
     * {@link NotificationTrigger}. On success the outbox entry is marked
     * {@link NotificationOutbox.State#DELIVERED}; on a delivery failure the entry is left
     * {@link NotificationOutbox.State#PENDING} (logged, not rethrown) so the already-committed
     * transition is preserved and the event can be retried. When no trigger is wired the event
     * simply remains pending until one is set and {@link #retryPendingNotifications()} runs.
     *
     * @param event the recorded outbox entry to deliver
     */
    private void deliver(NotificationOutbox.Entry event) {
        NotificationTrigger trigger = this.notificationTrigger;
        if (trigger == null) {
            return;
        }
        try {
            trigger.onStatusChange(event.getOrder(), event.getFrom(), event.getTo());
            outbox.markDelivered(event.getEventId());
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, ex,
                    () -> "notification delivery failed for event " + event.getEventId()
                            + "; left PENDING for retry");
        }
    }

    /**
     * Advances the stored order with the given id to the target status, enforcing the same guard
     * and notification semantics as {@link #updateStatus(Order, OrderStatus)}. Backs the HTTP
     * {@code POST /orders/{id}/status} route.
     *
     * @param orderId the id of the order to transition; must resolve to a stored order
     * @param next    the target status
     * @return the transitioned order
     * @throws OrderNotFoundException if no order with {@code orderId} exists
     * @throws IllegalStateException  if the transition is not permitted
     */
    public Order updateStatus(String orderId, OrderStatus next) {
        Order order = repository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        updateStatus(order, next);
        return order;
    }

    /**
     * Retrieves a stored order by the exact id supplied. Backs the HTTP {@code GET /orders/{id}}
     * route.
     *
     * @param orderId the order id to look up; {@code null}-safe
     * @return the order wrapped in an {@link Optional}, or empty if no such order exists
     */
    public Optional<Order> getOrder(String orderId) {
        return repository.findById(orderId);
    }

    /**
     * Performs authoritative, <b>read-only</b> validation of a single coupon code (no redemption
     * is consumed). Backs the HTTP {@code POST /coupons/validate} route, which reports whether a
     * code would be accepted without committing usage; redemption happens only when an order is
     * created. Routing coupon validation through the service keeps the HTTP layer decoupled from
     * the {@link CouponValidator} internals.
     *
     * @param code the coupon code to validate
     * @return the normalized {@link CouponValidator.ValidationResult}; never {@code null}
     */
    public CouponValidator.ValidationResult validateCoupon(String code) {
        return couponValidator.validate(code);
    }

    /**
     * Re-attempts delivery of every status-change event still {@code PENDING} in the outbox
     * (for example after an earlier transport outage), marking each {@code DELIVERED} on success.
     * Safe to call repeatedly; a no-op when no trigger is wired or nothing is pending.
     *
     * @return the number of events successfully delivered by this call
     */
    public int retryPendingNotifications() {
        NotificationTrigger trigger = this.notificationTrigger;
        if (trigger == null) {
            return 0;
        }
        int delivered = 0;
        for (NotificationOutbox.Entry event : outbox.pending()) {
            try {
                trigger.onStatusChange(event.getOrder(), event.getFrom(), event.getTo());
                outbox.markDelivered(event.getEventId());
                delivered++;
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, ex,
                        () -> "retry of notification event " + event.getEventId() + " failed");
            }
        }
        return delivered;
    }

    /**
     * Returns the repository backing this service (in-memory order store). Intended for the HTTP
     * layer and tests.
     *
     * @return the order repository; never {@code null}
     */
    public OrderRepository getRepository() {
        return repository;
    }

    /**
     * Returns the notification outbox backing this service. Intended for diagnostics and tests
     * (for example asserting an event was recorded/delivered).
     *
     * @return the notification outbox; never {@code null}
     */
    public NotificationOutbox getOutbox() {
        return outbox;
    }

    /**
     * Wires (or clears) the decoupled status-change notification hook.
     *
     * <p>The {@code notification-service} module supplies the implementation from outside;
     * order-service holds no compile-time dependency on any notification transport. Passing
     * {@code null} disables notifications — the service still operates fully.</p>
     *
     * @param trigger the trigger invoked on each successful transition, or {@code null} to
     *                disable notifications
     */
    public void setNotificationTrigger(NotificationTrigger trigger) {
        this.notificationTrigger = trigger;
    }

    /**
     * Returns the order currently managed by this service.
     *
     * <p>This is the order that {@link #createOrder(BigDecimal, List)} last created and that
     * the no-argument {@link #updateStatus(OrderStatus)} transitions; it also lets callers
     * surface the current lifecycle status.</p>
     *
     * @return the current order, or {@code null} if no order has been created yet
     */
    public Order getCurrentOrder() {
        return currentOrder;
    }

    /**
     * Maps a validated order-side {@link Coupon} to the distinct pricing-engine coupon type
     * consumed by {@link DiscountCalculator}.
     *
     * <p>The order-side {@code String} discount type
     * ({@link Coupon#TYPE_PERCENTAGE}/{@link Coupon#TYPE_FIXED}) is translated to the
     * pricing-engine {@code Type} enum, while the code and value carry over unchanged. The
     * mapping is safe because both coupon models enforce the same value invariants (a
     * {@code PERCENTAGE} value in {@code [0, 100]} and a non-negative {@code FIXED} value),
     * so the pricing-engine coupon constructor never rejects a validated coupon.</p>
     *
     * @param validated a coupon that has already passed authoritative validation; must not
     *                  be {@code null}
     * @return the equivalent {@code com.healthcare.pricing.Coupon} for discount computation
     */
    private static com.healthcare.pricing.Coupon toPricingCoupon(Coupon validated) {
        com.healthcare.pricing.Coupon.Type type =
                Coupon.TYPE_PERCENTAGE.equals(validated.getType())
                        ? com.healthcare.pricing.Coupon.Type.PERCENTAGE
                        : com.healthcare.pricing.Coupon.Type.FIXED;
        return new com.healthcare.pricing.Coupon(
                validated.getCode(), type, validated.getValue());
    }

    /**
     * Decoupled callback fired on every successful order status transition.
     *
     * <p>This functional interface is the ONLY seam between order-service and any
     * notification mechanism: {@code OrderService} depends on this interface, never on a
     * concrete transport. The {@code notification-service} module (or a test) supplies an
     * implementation via {@link OrderService#setNotificationTrigger(NotificationTrigger)}.
     * Implementations should be idempotent, since a status change may be re-delivered.</p>
     */
    @FunctionalInterface
    public interface NotificationTrigger {

        /**
         * Invoked once immediately after an order's status has advanced.
         *
         * @param order the order whose status changed (already carrying {@code to})
         * @param from  the previous status
         * @param to    the new status just applied
         */
        void onStatusChange(Order order, OrderStatus from, OrderStatus to);
    }

}
