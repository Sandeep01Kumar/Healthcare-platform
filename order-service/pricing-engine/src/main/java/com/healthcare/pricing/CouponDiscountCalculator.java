package com.healthcare.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Multi-coupon stacking discount engine — the core of the pricing-engine coupon feature.
 *
 * <p>Given a base {@code price} and an ordered collection of {@link Coupon}s, this engine
 * computes the <b>discounted total</b> (the amount the customer pays after all coupons are
 * applied), never the discount amount. It is the single delegate behind the coupon-aware
 * overload {@code DiscountCalculator.calculate(BigDecimal, List&lt;Coupon&gt;)}: that method
 * forwards to {@link #applyCoupons(BigDecimal, List)} so all stacking logic lives here.</p>
 *
 * <h2>Deterministic stacking policy</h2>
 * <p>Coupons are applied <b>sequentially, in the exact iteration order of the supplied
 * {@link List}</b>, against a single <i>running subtotal</i> that starts at {@code price}.
 * Because a {@code List} defines a stable order, the same input list always yields the same
 * total — the result is fully deterministic. <b>The caller controls the order</b> in which
 * coupons stack; the engine neither sorts nor reorders them. Each coupon is applied to the
 * subtotal <i>left by the previous coupon</i> (compounding), not to the original price.</p>
 *
 * <p>How each coupon mutates the running subtotal depends on its {@link Coupon.Type}:</p>
 * <ul>
 *   <li>{@link Coupon.Type#PERCENTAGE} — the discount is
 *       {@code subtotal * value / 100}, computed as
 *       {@code subtotal.multiply(value).divide(100, 2, HALF_UP)}, and subtracted from the
 *       subtotal. {@code value} is expressed in percentage points (for example
 *       {@code new BigDecimal("10")} means 10% off the current subtotal).</li>
 *   <li>{@link Coupon.Type#FIXED} — {@code value} is an absolute monetary amount subtracted
 *       directly from the subtotal (for example {@code new BigDecimal("5.00")} means 5
 *       currency units off).</li>
 * </ul>
 *
 * <h2>Zero-floor guarantee</h2>
 * <p>After <i>each</i> coupon is applied, the running subtotal is floored at zero: if a
 * subtraction drives it below zero it is reset to {@code 0.00}. Flooring per step (rather
 * than only at the end) means a coupon can never push the subtotal negative and thereby
 * distort a subsequent {@code PERCENTAGE} coupon (a percentage of a negative number would
 * otherwise <i>increase</i> the total). Consequently the returned total is <b>always
 * {@code >= 0}</b>, even for an over-100% percentage stack or a fixed amount larger than the
 * price.</p>
 *
 * <h2>Monetary safety</h2>
 * <p>All arithmetic uses {@link BigDecimal} with {@link RoundingMode#HALF_UP} at a fixed
 * monetary scale of <b>2 decimal places</b>; {@code double}/{@code float} are never used, so
 * results are free of binary floating-point rounding error.</p>
 *
 * <h2>Null / empty handling</h2>
 * <p>A {@code null} {@code price} is treated as {@code 0.00}. A {@code null} or empty
 * {@code coupons} list yields the price unchanged (the no-coupon baseline). Within the list,
 * any {@code null} coupon — or a coupon with a {@code null} {@link Coupon#getType()} or
 * {@code null} {@link Coupon#getValue()} — is <b>skipped</b> (treated as a no-op) so a single
 * malformed entry cannot corrupt the running subtotal.</p>
 *
 * <p>This class is stateless and therefore immutable and thread-safe; a single instance may
 * be shared freely across threads. It lives in the default (unnamed) package by module
 * convention and depends on nothing outside the Java standard library and the same-module
 * {@link Coupon} value object.</p>
 */
public class CouponDiscountCalculator {

    /** Monetary scale for all currency values: 2 decimal places. */
    private static final int MONEY_SCALE = 2;

    /** Rounding mode applied to every monetary operation. */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Divisor for percentage coupons, whose {@code value} is expressed in points of 100. */
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /** Zero at the monetary scale, reused as the per-step floor and the empty result. */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);

    /**
     * Applies the given coupons to the price and returns the discounted total.
     *
     * <p>Coupons stack deterministically in the list's iteration order against a running
     * subtotal, each {@code PERCENTAGE} or {@code FIXED} coupon reducing the subtotal left by
     * the previous one. The subtotal is floored at {@code 0.00} after every coupon, so the
     * returned total is always non-negative. See the class documentation for the full
     * stacking policy and monetary-safety guarantees.</p>
     *
     * @param price   the base price before discounts; a {@code null} price is treated as
     *                {@code 0.00}
     * @param coupons the coupons to apply, in the order they should stack; a {@code null} or
     *                empty list returns the price unchanged (no-coupon baseline), and any
     *                {@code null} coupon or coupon with a {@code null} type/value is skipped
     * @return the discounted total, normalized to {@code BigDecimal} scale 2 with
     *         {@link RoundingMode#HALF_UP}; never negative
     */
    public BigDecimal applyCoupons(BigDecimal price, List<Coupon> coupons) {
        BigDecimal running = (price == null ? BigDecimal.ZERO : price).setScale(MONEY_SCALE, ROUNDING);

        if (coupons == null || coupons.isEmpty()) {
            return running;
        }

        for (Coupon coupon : coupons) {
            if (coupon == null || coupon.getType() == null || coupon.getValue() == null) {
                // Skip malformed entries so one bad coupon cannot corrupt the subtotal.
                continue;
            }

            if (coupon.getType() == Coupon.Type.PERCENTAGE) {
                BigDecimal discount = running.multiply(coupon.getValue())
                        .divide(ONE_HUNDRED, MONEY_SCALE, ROUNDING);
                running = running.subtract(discount);
            } else if (coupon.getType() == Coupon.Type.FIXED) {
                running = running.subtract(coupon.getValue());
            }

            // Zero-floor per step: a discount can never drive the subtotal below zero.
            if (running.compareTo(BigDecimal.ZERO) < 0) {
                running = ZERO_MONEY;
            }
        }

        return running.setScale(MONEY_SCALE, ROUNDING);
    }

}
