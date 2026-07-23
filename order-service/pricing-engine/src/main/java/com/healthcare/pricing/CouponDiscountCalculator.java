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
 * otherwise <i>increase</i> the total). The base {@code price} is likewise floored at zero
 * <i>before</i> any coupon runs, so the guarantee also holds on the paths where no coupon is
 * ever applied — an empty or {@code null} list, or a list whose entries are all skipped as
 * malformed. Consequently the returned total is <b>always {@code >= 0}</b>, even for an
 * over-100% percentage stack, a fixed amount larger than the price, or a negative input
 * price.</p>
 *
 * <h2>Monetary safety</h2>
 * <p>All arithmetic uses {@link BigDecimal} with {@link RoundingMode#HALF_UP} at a fixed
 * monetary scale of <b>2 decimal places</b>; {@code double}/{@code float} are never used, so
 * results are free of binary floating-point rounding error. The running subtotal is
 * <b>re-normalized to scale 2 after every coupon step</b> — not only at the end — so a
 * {@code FIXED} value carrying more than two decimals, or any scale accumulated by a prior
 * subtraction, can never leak sub-cent precision into a subsequent {@code PERCENTAGE} coupon's
 * base. (The same-module {@link Coupon} additionally normalizes a {@code FIXED} value to scale
 * 2 at construction, so this per-step normalization is a defensive second layer that keeps the
 * engine correct for any {@code Coupon} it is handed.)</p>
 *
 * <h2>Duplicate and oversized coupons</h2>
 * <p>The engine applies <b>every</b> coupon in the supplied list, in order, including
 * duplicates — deduplication is the <i>caller's</i> responsibility (the order-service
 * orchestration layer collapses coupons by canonical code before handing a list here). A
 * {@code FIXED} value larger than the running subtotal, or a {@code PERCENTAGE} stack that
 * would exceed 100%, is not rejected: the per-step zero-floor simply clamps the subtotal to
 * {@code 0.00}. There is no upper cap on list length in the engine; the caller bounds the batch
 * size before invoking it.</p>
 *
 * <h2>Null / empty handling</h2>
 * <p>A {@code null} {@code price} is treated as {@code 0.00}, and a negative {@code price} is
 * floored to {@code 0.00}. A {@code null} or empty {@code coupons} list yields the
 * (normalized, zero-floored) price with no discount applied — the no-coupon baseline. Within
 * the list, any {@code null} coupon — or a coupon with a {@code null} {@link Coupon#getType()}
 * or {@code null} {@link Coupon#getValue()} — is <b>skipped</b> (treated as a no-op) so a
 * single malformed entry cannot corrupt the running subtotal.</p>
 *
 * <p>This class is stateless and therefore immutable and thread-safe; a single instance may
 * be shared freely across threads. It lives in the {@code com.healthcare.pricing} package and
 * depends on nothing outside the Java standard library and the same-package {@link Coupon}
 * value object.</p>
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
     * Creates a stateless coupon-discount calculator. The instance holds no mutable state, so it
     * is immutable and thread-safe and a single instance may be shared freely across threads.
     */
    public CouponDiscountCalculator() {
        // No initialization required: all configuration is expressed as immutable constants.
    }

    /**
     * Applies the given coupons to the price and returns the discounted total.
     *
     * <p>Coupons stack deterministically in the list's iteration order against a running
     * subtotal, each {@code PERCENTAGE} or {@code FIXED} coupon reducing the subtotal left by
     * the previous one. The base price is floored at {@code 0.00} before any coupon runs and
     * the subtotal is floored at {@code 0.00} after every coupon, so the returned total is
     * always non-negative on every path. See the class documentation for the full stacking
     * policy and monetary-safety guarantees.</p>
     *
     * @param price   the base price before discounts; a {@code null} or negative price is
     *                treated as {@code 0.00}
     * @param coupons the coupons to apply, in the order they should stack; a {@code null} or
     *                empty list applies no discount and returns the zero-floored price, and any
     *                {@code null} coupon or coupon with a {@code null} type/value is skipped
     * @return the discounted total, normalized to {@code BigDecimal} scale 2 with
     *         {@link RoundingMode#HALF_UP}; never negative
     */
    public BigDecimal applyCoupons(BigDecimal price, List<Coupon> coupons) {
        // Normalize the price to the monetary scale and floor it at zero UP FRONT. Flooring the
        // base price here — not only inside the per-coupon loop below — is what makes the
        // "never negative" guarantee hold on EVERY path: the no-coupon early return, a list of
        // only skipped (malformed) coupons that never reach the in-loop floor, and the normal
        // discounting path alike. For a non-negative price this is a no-op; it only ever clamps
        // an atypical negative input up to 0.00.
        BigDecimal running = (price == null ? BigDecimal.ZERO : price)
                .setScale(MONEY_SCALE, ROUNDING)
                .max(ZERO_MONEY);

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

            // Re-normalize to the monetary scale after EVERY step. A FIXED subtraction can
            // leave the subtotal at a scale wider than 2 if a value ever carried extra
            // decimals; normalizing here (rather than only in the final return) guarantees the
            // base handed to the next coupon's PERCENTAGE math is a clean 2-decimal amount, so
            // no sub-cent precision can compound across steps.
            running = running.setScale(MONEY_SCALE, ROUNDING);

            // Zero-floor per step: a discount can never drive the subtotal below zero.
            if (running.compareTo(ZERO_MONEY) < 0) {
                running = ZERO_MONEY;
            }
        }

        return running.setScale(MONEY_SCALE, ROUNDING);
    }

}
