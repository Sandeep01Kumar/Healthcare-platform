package com.healthcare.order;

import com.healthcare.pricing.DiscountCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 * <p>All state is held in memory (no persistence, ORM, or framework). The service models a
 * single {@linkplain #getCurrentOrder() current order} for the convenience no-argument
 * {@link #updateStatus(OrderStatus)} overload; the {@link #updateStatus(Order, OrderStatus)}
 * overload operates on any supplied order. The service is intended for single-threaded
 * orchestration and is not synchronized.</p>
 */
public class OrderService {

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
     * The order currently managed by this service, or {@code null} until the first order has
     * been created. {@link #updateStatus(OrderStatus)} advances this order.
     */
    private Order currentOrder;

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
     * @param price       the base (pre-discount) price; must not be {@code null}
     * @param couponCodes the submitted coupon codes to validate and apply, in the order they
     *                    should stack; may be {@code null} or empty (treated as "no
     *                    coupons"), and individual invalid/unknown codes are skipped
     * @return the created {@link Order}, in {@link OrderStatus#CREATED}, carrying the applied
     *         coupons and the computed discounted total; never {@code null}
     * @throws NullPointerException if {@code price} is {@code null}
     */
    public Order createOrder(BigDecimal price, List<String> couponCodes) {
        Objects.requireNonNull(price, "price");

        Order order = new Order(UUID.randomUUID().toString(), price);

        // Authoritative, server-side validation: collect only the valid coupons, mapping
        // each accepted order-side coupon to the pricing-engine coupon representation.
        List<com.healthcare.pricing.Coupon> pricingCoupons = new ArrayList<>();
        if (couponCodes != null) {
            for (String code : couponCodes) {
                CouponValidator.ValidationResult result = couponValidator.validate(code);
                if (result.isValid()) {
                    Coupon validated = result.getCoupon();
                    order.addCoupon(validated);
                    pricingCoupons.add(toPricingCoupon(validated));
                }
            }
        }

        // Delegate the stacked, zero-floored discount arithmetic to the pricing engine.
        order.setDiscountedTotal(discountCalculator.calculate(price, pricingCoupons));

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
     * <p>The transition is validated against
     * {@link OrderStatus#canTransitionTo(OrderStatus)}, which permits only the forward steps
     * {@code CREATED -> CONFIRMED} and {@code CONFIRMED -> DELIVERED}. On a valid transition
     * the order's status is updated and, if a {@link NotificationTrigger} is wired, its
     * {@link NotificationTrigger#onStatusChange(Order, OrderStatus, OrderStatus)} is invoked
     * exactly once with the old and new statuses. On an invalid transition — a skip, a
     * backward move, a self-transition, a move out of the terminal state, or a {@code null}
     * target — the method throws and neither mutates the order nor fires a notification.</p>
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
        OrderStatus current = order.getStatus();
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal order status transition: " + current + " -> " + next);
        }
        order.setStatus(next);
        if (notificationTrigger != null) {
            notificationTrigger.onStatusChange(order, current, next);
        }
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
