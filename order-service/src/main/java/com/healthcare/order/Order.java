package com.healthcare.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory order domain model for the order-service.
 *
 * <p>An {@code Order} carries the four pieces of state the order lifecycle needs: a stable
 * {@link #getId() id} identity, the current {@link #getStatus() status} (which advances
 * through {@link OrderStatus#CREATED} &rarr; {@link OrderStatus#CONFIRMED} &rarr;
 * {@link OrderStatus#DELIVERED}), the original {@link #getPrice() price} together with the
 * {@link #getDiscountedTotal() discounted total} once the pricing engine has applied any
 * coupons, and the {@link #getAppliedCoupons() applied coupons} that were validated for
 * this order.</p>
 *
 * <p>This class is a plain state holder: it deliberately contains no transition-guard
 * logic. The legal status transitions are defined by
 * {@link OrderStatus#canTransitionTo(OrderStatus)} and enforced by
 * {@code OrderService.updateStatus}; {@link #setStatus(OrderStatus)} here simply records
 * whatever status the service has already accepted. Monetary amounts use
 * {@link BigDecimal} for consistency with the pricing engine's BigDecimal-based discount
 * math rather than error-prone binary floating point.</p>
 *
 * <p>The applied-coupon list is never {@code null}: it starts empty, grows only through
 * {@link #addCoupon(Coupon)}, and is exposed through {@link #getAppliedCoupons()} as an
 * unmodifiable snapshot so callers cannot mutate the order's internal state. The
 * {@link Coupon} elements are the order-service validation-side coupons; callers are
 * expected to have validated them before applying them to the order.</p>
 *
 * <p>Plain in-memory Java object: no persistence, ORM, or framework annotations, and no
 * {@code CANCELLED} handling (both out of scope for this feature).</p>
 */
public class Order {

    /** Stable business identity of the order; never {@code null}. */
    private final String id;

    /**
     * Current lifecycle status. Initialized to {@link OrderStatus#CREATED} and advanced
     * only via {@link #setStatus(OrderStatus)} after {@code OrderService} has validated the
     * transition against {@link OrderStatus#canTransitionTo(OrderStatus)}.
     */
    private OrderStatus status;

    /** Original (pre-discount) order price; held as {@link BigDecimal} for precision. */
    private final BigDecimal price;

    /**
     * Total after coupon discounts have been applied by the pricing engine, or
     * {@code null} while no discount has been computed yet.
     */
    private BigDecimal discountedTotal;

    /**
     * Validated coupons applied to this order, in application order. Never {@code null};
     * mutated only through {@link #addCoupon(Coupon)}.
     */
    private final List<Coupon> appliedCoupons;

    /**
     * Creates a new order in the {@link OrderStatus#CREATED} state with an empty
     * applied-coupon list and no discounted total computed yet.
     *
     * @param id    the stable order identity; must not be {@code null}
     * @param price the original (pre-discount) price; must not be {@code null}
     * @throws NullPointerException if {@code id} or {@code price} is {@code null}
     */
    public Order(String id, BigDecimal price) {
        this.id = Objects.requireNonNull(id, "id");
        this.price = Objects.requireNonNull(price, "price");
        this.status = OrderStatus.CREATED;
        this.discountedTotal = null;
        this.appliedCoupons = new ArrayList<>();
    }

    /**
     * Returns the stable business identity of this order.
     *
     * @return the order id; never {@code null}
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the current lifecycle status of this order.
     *
     * @return the current {@link OrderStatus}; never {@code null}
     */
    public OrderStatus getStatus() {
        return status;
    }

    /**
     * Records the current lifecycle status of this order.
     *
     * <p>This mutator performs <em>no</em> transition validation: the legal-transition
     * guard lives in {@link OrderStatus#canTransitionTo(OrderStatus)} and is enforced by
     * {@code OrderService.updateStatus}, which calls this method only after a transition
     * has been accepted. It exists so the service can persist the new state onto the
     * model.</p>
     *
     * @param status the new status to record; must not be {@code null}
     * @throws NullPointerException if {@code status} is {@code null}
     */
    public void setStatus(OrderStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Returns the original (pre-discount) price of this order.
     *
     * @return the price as a {@link BigDecimal}; never {@code null}
     */
    public BigDecimal getPrice() {
        return price;
    }

    /**
     * Returns the total after coupon discounts have been applied by the pricing engine.
     *
     * @return the discounted total, or {@code null} if no discount has been computed yet
     */
    public BigDecimal getDiscountedTotal() {
        return discountedTotal;
    }

    /**
     * Records the total after the pricing engine has applied this order's coupons.
     *
     * @param discountedTotal the discounted total; may be {@code null} to clear a
     *                        previously computed value
     */
    public void setDiscountedTotal(BigDecimal discountedTotal) {
        this.discountedTotal = discountedTotal;
    }

    /**
     * Returns an unmodifiable snapshot of the coupons applied to this order.
     *
     * <p>The returned list is a defensive copy: mutating it is impossible (it is
     * unmodifiable), and a subsequent {@link #addCoupon(Coupon)} call is not reflected in
     * an already-returned list. This keeps the order's internal state fully encapsulated.</p>
     *
     * @return an unmodifiable {@link List} of the applied {@link Coupon}s; never
     *         {@code null} (empty when no coupons have been applied)
     */
    public List<Coupon> getAppliedCoupons() {
        return List.copyOf(appliedCoupons);
    }

    /**
     * Appends a validated coupon to this order's applied-coupon list.
     *
     * <p>Callers are expected to have validated the coupon (via {@code CouponValidator})
     * before applying it; this method only records the association and does not itself
     * re-validate the coupon.</p>
     *
     * @param coupon the validated coupon to apply; must not be {@code null}
     * @throws NullPointerException if {@code coupon} is {@code null}
     */
    public void addCoupon(Coupon coupon) {
        appliedCoupons.add(Objects.requireNonNull(coupon, "coupon"));
    }

    /**
     * Value equality based on the order {@link #getId() id}, which is the business identity
     * of an order.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is an {@code Order} with an equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Order)) {
            return false;
        }
        return Objects.equals(id, ((Order) o).id);
    }

    /**
     * Returns a hash code derived from the order {@link #getId() id}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Returns a concise, human-readable description of this order for logging and
     * debugging. The format is not part of the API contract and may change.
     *
     * @return a string representation of this order
     */
    @Override
    public String toString() {
        return "Order{id=" + id + ", status=" + status + ", price=" + price
                + ", discountedTotal=" + discountedTotal
                + ", appliedCoupons=" + appliedCoupons + "}";
    }

}
