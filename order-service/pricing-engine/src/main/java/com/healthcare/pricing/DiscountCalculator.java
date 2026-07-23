package com.healthcare.pricing;

import java.math.BigDecimal;
import java.util.List;

/**
 * Computes order discounts for the pricing engine.
 *
 * <p>Two calculation entry points are offered:</p>
 * <ul>
 *   <li>{@link #calculate(double)} — the original, coupon-agnostic entry point that applies
 *       a fixed 10% baseline reduction. It is retained UNCHANGED for backward compatibility;
 *       its {@code double}-in / {@code double}-out signature and its baseline meaning are
 *       preserved so existing callers remain valid.</li>
 *   <li>{@link #calculate(BigDecimal, List)} — the coupon-aware overload that computes a
 *       deterministic, multi-coupon (stacked) discount and returns the discounted total,
 *       floored at zero. This supersedes the fixed reduction for the coupon path.</li>
 * </ul>
 *
 * <p>The coupon-aware overload does not implement stacking itself: it DELEGATES to
 * {@link CouponDiscountCalculator} (the same-module engine that owns all stacking logic) so
 * the two can never diverge. {@code CouponDiscountCalculator} and {@link Coupon} live in this
 * same {@code com.healthcare.pricing} package and are therefore referenced directly, with no
 * {@code import}.</p>
 *
 * <p><b>Monetary safety.</b> All coupon arithmetic uses {@link BigDecimal} with
 * {@link java.math.RoundingMode#HALF_UP} at a fixed monetary scale of two decimal places,
 * avoiding the binary floating-point rounding error inherent to {@code double}-based money.
 * The legacy {@link #calculate(double)} baseline is intentionally left in {@code double} form
 * to preserve its exact historical behavior.</p>
 */
public class DiscountCalculator {

    /**
     * Stateless engine that performs the deterministic, multi-coupon stacking. It is immutable
     * and thread-safe, so a single shared instance is reused for every call.
     */
    private final CouponDiscountCalculator couponDiscountCalculator = new CouponDiscountCalculator();

    /**
     * Creates a discount calculator. The instance holds only its own immutable, thread-safe
     * {@link CouponDiscountCalculator} delegate, so it carries no mutable state and a single
     * instance may be reused for every call.
     */
    public DiscountCalculator() {
        // No initialization required: the stacking engine is created as a final field above.
    }

    /**
     * Applies the baseline fixed 10% discount to the supplied price.
     *
     * <p>This overload is retained UNCHANGED for backward compatibility: it takes a
     * {@code double} price and returns the price after a fixed 10% reduction, with no coupons
     * involved. Existing callers of the original entry point remain valid.</p>
     *
     * @param price the original price
     * @return the price after a fixed 10% reduction ({@code price * 0.9})
     */
    public double calculate(double price) {
        return price * 0.9;
    }

    /**
     * Computes the discounted total after applying the supplied coupons, stacked
     * deterministically.
     *
     * <p>This coupon-aware overload supersedes the fixed baseline reduction for the coupon
     * path. It delegates the entire calculation to
     * {@link CouponDiscountCalculator#applyCoupons(BigDecimal, List)}, which applies the
     * coupons <b>sequentially, in the exact iteration order of {@code coupons}</b>, against a
     * running subtotal that starts at {@code price}. Each {@link Coupon.Type#PERCENTAGE} coupon
     * subtracts {@code subtotal * value / 100} and each {@link Coupon.Type#FIXED} coupon
     * subtracts its absolute {@code value}; because the same list always stacks in the same
     * order, the result is fully deterministic.</p>
     *
     * <p><b>Zero-floor guarantee.</b> The base price is floored at {@code 0.00} before any
     * coupon runs and the running subtotal is floored at {@code 0.00} after every coupon, so the
     * returned total is <b>never negative</b> on any path — even when the coupons would otherwise
     * discount more than the price, and even for a negative input price.</p>
     *
     * <p><b>Null / empty handling.</b> A {@code null} {@code price} is treated as {@code 0.00}
     * and a negative {@code price} is floored to {@code 0.00}; a {@code null} or empty
     * {@code coupons} list applies no discount and returns the zero-floored {@code price} (the
     * no-coupon baseline); and any {@code null} or malformed coupon within the list is skipped.</p>
     *
     * <p><b>Monetary safety.</b> The result is a {@link BigDecimal} normalized to a scale of two
     * decimal places using {@link java.math.RoundingMode#HALF_UP}.</p>
     *
     * @param price   the base price before discounts; a {@code null} or negative price is
     *                treated as {@code 0.00}
     * @param coupons the coupons to apply, in the order they should stack; a {@code null} or
     *                empty list applies no discount and returns the zero-floored {@code price}
     *                (the no-coupon baseline), and any {@code null} or malformed coupon in the
     *                list is skipped
     * @return the discounted total as a {@link BigDecimal} at scale 2 (HALF_UP); never negative
     */
    public BigDecimal calculate(BigDecimal price, List<Coupon> coupons) {
        return couponDiscountCalculator.applyCoupons(price, coupons);
    }

}
