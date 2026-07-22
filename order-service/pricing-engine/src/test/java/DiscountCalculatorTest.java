import com.healthcare.pricing.Coupon;
import com.healthcare.pricing.CouponDiscountCalculator;
import com.healthcare.pricing.DiscountCalculator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link DiscountCalculator} and the coupon stacking performed by
 * {@link CouponDiscountCalculator}. Covers the four deterministic discount cases mandated
 * by the pricing-engine coupon feature:
 *
 * <ol>
 *   <li>the no-coupon baseline (including the legacy {@code double} path);</li>
 *   <li>a single coupon (both {@code PERCENTAGE} and {@code FIXED});</li>
 *   <li>multiple coupons stacked in the list's iteration order against a running subtotal; and</li>
 *   <li>the zero-floor guarantee (a discount can never produce a negative total).</li>
 * </ol>
 *
 * <p><b>Placement and references.</b> This suite sits at the root of {@code src/test/java}
 * (the default/unnamed package). The production classes under test live in the named package
 * {@code com.healthcare.pricing} (a package is required so the discount-side
 * {@code com.healthcare.pricing.Coupon} can coexist downstream with the validation-side
 * {@code com.healthcare.order.Coupon}), so they are referenced through explicit imports — a
 * type in the default package may legally import types from a named package.</p>
 *
 * <p><b>Assertion policy.</b> Monetary results are {@link BigDecimal}; every monetary
 * assertion compares with {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}) so a
 * difference in scale (for example {@code 90.00} versus {@code 90.0}) does not cause a false
 * failure. The legacy {@code double} path is asserted with a small delta.</p>
 */
public class DiscountCalculatorTest {

    /**
     * Case 1a — no-coupon baseline (BigDecimal path): an empty or {@code null} coupon list
     * returns the price unchanged, normalized to scale 2.
     */
    @Test
    void noCouponBaselineReturnsPrice() {
        DiscountCalculator calc = new DiscountCalculator();
        assertEquals(0, new BigDecimal("100.00").compareTo(
                        calc.calculate(new BigDecimal("100.00"), List.of())),
                "an empty coupon list must return the price unchanged");
        assertEquals(0, new BigDecimal("100.00").compareTo(
                        calc.calculate(new BigDecimal("100.00"), null)),
                "a null coupon list must return the price unchanged");
    }

    /**
     * Case 1b — legacy backward-compatible {@code double} entry point still applies the fixed
     * 10% baseline reduction ({@code 100.0 -> 90.0}).
     */
    @Test
    void legacyDoublePathAppliesTenPercent() {
        double result = new DiscountCalculator().calculate(100.0);
        assertEquals(90.0, result, 0.0001);
    }

    /**
     * Case 2a — a single {@code PERCENTAGE} coupon: 10% off {@code 100.00} yields {@code 90.00}.
     */
    @Test
    void singlePercentageCoupon() {
        List<Coupon> coupons = List.of(
                new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10")));
        BigDecimal result = new DiscountCalculator().calculate(new BigDecimal("100.00"), coupons);
        assertEquals(0, new BigDecimal("90.00").compareTo(result));
    }

    /**
     * Case 2b — a single {@code FIXED} coupon: {@code 15.00} off {@code 100.00} yields
     * {@code 85.00}.
     */
    @Test
    void singleFixedCoupon() {
        List<Coupon> coupons = List.of(
                new Coupon("F15", Coupon.Type.FIXED, new BigDecimal("15.00")));
        BigDecimal result = new DiscountCalculator().calculate(new BigDecimal("100.00"), coupons);
        assertEquals(0, new BigDecimal("85.00").compareTo(result));
    }

    /**
     * Case 3 — multiple coupons stack sequentially in list order against a running subtotal:
     * 10% off {@code 100.00} yields {@code 90.00}, then a {@code 5.00} fixed reduction yields
     * {@code 85.00}. Verified through both the high-level
     * {@link DiscountCalculator#calculate(BigDecimal, List)} and the underlying
     * {@link CouponDiscountCalculator#applyCoupons(BigDecimal, List)}; both must agree.
     */
    @Test
    void multipleCouponsStackInListOrder() {
        List<Coupon> coupons = List.of(
                new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10")),
                new Coupon("F5", Coupon.Type.FIXED, new BigDecimal("5.00")));
        assertEquals(0, new BigDecimal("85.00").compareTo(
                        new DiscountCalculator().calculate(new BigDecimal("100.00"), coupons)),
                "DiscountCalculator must stack coupons sequentially in list order");
        assertEquals(0, new BigDecimal("85.00").compareTo(
                        new CouponDiscountCalculator().applyCoupons(new BigDecimal("100.00"), coupons)),
                "CouponDiscountCalculator must stack coupons sequentially in list order");
    }

    /**
     * Case 4a — zero-floor: a {@code FIXED} coupon larger than the price floors the total at
     * {@code 0.00}; the running subtotal is never allowed to go negative.
     */
    @Test
    void fixedCouponFlooredAtZero() {
        BigDecimal result = new CouponDiscountCalculator().applyCoupons(
                new BigDecimal("50.00"),
                List.of(new Coupon("F200", Coupon.Type.FIXED, new BigDecimal("200.00"))));
        assertTrue(result.compareTo(BigDecimal.ZERO) >= 0, "the discounted total must never be negative");
        assertEquals(0, new BigDecimal("0.00").compareTo(result));
    }

    /**
     * Case 4b — zero-floor via percentage: a full 100% {@code PERCENTAGE} coupon reduces the
     * total to exactly {@code 0.00}. The production {@code Coupon} caps a {@code PERCENTAGE}
     * value at 100, so 100 is the maximum that drives the total to the zero floor through the
     * percentage path.
     */
    @Test
    void fullPercentageCouponFlooredAtZero() {
        BigDecimal result = new DiscountCalculator().calculate(
                new BigDecimal("100.00"),
                List.of(new Coupon("P100", Coupon.Type.PERCENTAGE, new BigDecimal("100"))));
        assertTrue(result.compareTo(BigDecimal.ZERO) >= 0, "the discounted total must never be negative");
        assertEquals(0, new BigDecimal("0.00").compareTo(result));
    }

    /**
     * Case 4c — zero-floor on the no-coupon paths: a negative input {@code price} must still
     * honor the "never negative" guarantee and return {@code 0.00}, even when no coupon ever
     * subtracts from the subtotal. This exercises the base-price floor rather than the
     * per-coupon floor, covering the three paths that skip the in-loop floor: a {@code null}
     * coupon list, an empty list, and a list whose only entry is skipped as malformed. The
     * contrast case (a real coupon) and the high-level {@link DiscountCalculator} facade are
     * asserted too, so the guarantee is verified end-to-end.
     */
    @Test
    void negativeInputPriceFlooredAtZero() {
        CouponDiscountCalculator engine = new CouponDiscountCalculator();
        BigDecimal negative = new BigDecimal("-50.00");

        // No-coupon paths: a null list and an empty list must both floor a negative price to 0.00.
        assertTrue(engine.applyCoupons(negative, null).compareTo(BigDecimal.ZERO) >= 0,
                "a negative price with a null coupon list must never be negative");
        assertEquals(0, new BigDecimal("0.00").compareTo(engine.applyCoupons(negative, null)),
                "negative price + null coupon list must floor to 0.00");
        assertEquals(0, new BigDecimal("0.00").compareTo(engine.applyCoupons(negative, List.of())),
                "negative price + empty coupon list must floor to 0.00");

        // All-malformed list: the per-coupon floor never runs (the sole entry is skipped), so
        // the base-price floor must still guarantee a non-negative result.
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        engine.applyCoupons(negative, Arrays.asList((Coupon) null))),
                "negative price + all-skipped coupon list must floor to 0.00");

        // Contrast: a negative price with a real coupon was already floored; it stays 0.00.
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        engine.applyCoupons(negative,
                                List.of(new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10"))))),
                "negative price + a real coupon must floor to 0.00");

        // The high-level DiscountCalculator facade inherits the same guarantee.
        assertEquals(0, new BigDecimal("0.00").compareTo(
                        new DiscountCalculator().calculate(negative, null)),
                "DiscountCalculator facade must also floor a negative price to 0.00");
    }
}
