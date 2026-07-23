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
 * <p><b>Assertion policy.</b> Monetary results are {@link BigDecimal}. Numeric equality is
 * checked with {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}) so it is not
 * confused by scale, but the discounted total is <i>also</i> asserted to carry the canonical
 * monetary <b>scale of exactly 2</b> via {@link #assertMoney(String, BigDecimal)}. Asserting
 * scale is what makes these tests able to catch monetary-scale regressions (a total returned as
 * {@code 94.990} instead of {@code 94.99}, or sub-cent precision leaking through a {@code FIXED}
 * coupon) that a scale-insensitive {@code compareTo}-only check would silently pass. The legacy
 * {@code double} path is asserted with a small delta.</p>
 */
public class DiscountCalculatorTest {

    /**
     * Asserts that {@code actual} equals the {@code expected} money value <b>both</b> in numeric
     * amount (via {@link BigDecimal#compareTo(BigDecimal)}) <b>and</b> in scale — every
     * discounted total must be normalized to exactly 2 decimal places.
     *
     * @param expected the expected monetary amount as a canonical two-decimal string (e.g. {@code "90.00"})
     * @param actual   the {@link BigDecimal} returned by the calculator under test
     */
    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "monetary result must not be null");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected amount " + expected + " but was " + actual);
        assertEquals(2, actual.scale(),
                () -> "discounted total must be normalized to scale 2 but was " + actual
                        + " (scale " + actual.scale() + ")");
    }

    /**
     * Case 1a — no-coupon baseline (BigDecimal path): an empty or {@code null} coupon list
     * returns the price unchanged, normalized to scale 2.
     */
    @Test
    void noCouponBaselineReturnsPrice() {
        DiscountCalculator calc = new DiscountCalculator();
        assertMoney("100.00", calc.calculate(new BigDecimal("100.00"), List.of()));
        assertMoney("100.00", calc.calculate(new BigDecimal("100.00"), null));
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
        assertMoney("90.00", result);
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
        assertMoney("85.00", result);
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
        assertMoney("85.00",
                new DiscountCalculator().calculate(new BigDecimal("100.00"), coupons));
        assertMoney("85.00",
                new CouponDiscountCalculator().applyCoupons(new BigDecimal("100.00"), coupons));
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
        assertMoney("0.00", result);
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
        assertMoney("0.00", result);
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
        assertMoney("0.00", new DiscountCalculator().calculate(negative, null));
    }

    /**
     * Case 5 — monetary scale enforcement (M1). A {@code FIXED} coupon supplied with sub-cent
     * precision must be normalized to 2 decimal places at construction ({@code 5.005 -> 5.01}
     * HALF_UP), so the discounted total is exact and at scale 2 ({@code 100.00 - 5.01 = 94.99})
     * rather than carrying a leaked third decimal. {@link #assertMoney} verifies both the amount
     * and {@code scale() == 2}.
     */
    @Test
    void fixedCouponSubCentValueNormalizedToScaleTwo() {
        Coupon subCent = new Coupon("F5005", Coupon.Type.FIXED, new BigDecimal("5.005"));
        // The coupon itself must have normalized its value up front.
        assertMoney("5.01", subCent.getValue());
        BigDecimal result = new DiscountCalculator()
                .calculate(new BigDecimal("100.00"), List.of(subCent));
        assertMoney("94.99", result);
    }

    /**
     * Case 5b — a coarse-scale ({@code scale 0}) {@code FIXED} value is canonicalized to scale 2
     * so the total is a clean two-decimal amount ({@code 100.00 - 5.00 = 95.00}).
     */
    @Test
    void fixedCouponCoarseValueNormalizedToScaleTwo() {
        Coupon coarse = new Coupon("F5", Coupon.Type.FIXED, new BigDecimal("5"));
        assertMoney("5.00", coarse.getValue());
        assertMoney("95.00", new DiscountCalculator()
                .calculate(new BigDecimal("100.00"), List.of(coarse)));
    }

    /**
     * Case 5c — a {@code PERCENTAGE} value applied to a subtotal that would otherwise produce a
     * sub-cent discount is rounded HALF_UP to scale 2, and the total stays at scale 2. Against
     * {@code 100.05}, a 10% discount is {@code 10.005 -> 10.01}, leaving {@code 90.04}.
     */
    @Test
    void percentageDiscountRoundedToScaleTwo() {
        BigDecimal result = new DiscountCalculator().calculate(
                new BigDecimal("100.05"),
                List.of(new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10"))));
        assertMoney("90.04", result);
    }

    /**
     * Case 6 — reverse-order stacking. The <i>same</i> two coupons in the opposite order yield a
     * different total, demonstrating that stacking is deterministic and order-dependent, and both
     * orderings return a scale-2 total. {@code [10%, then $5]}: {@code 100.00 -> 90.00 -> 85.00};
     * {@code [$5, then 10%]}: {@code 100.00 -> 95.00 -> 85.50}.
     */
    @Test
    void reverseOrderStackingDiffersAndIsScaleTwo() {
        Coupon tenPct = new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10"));
        Coupon fiveFixed = new Coupon("F5", Coupon.Type.FIXED, new BigDecimal("5.00"));
        DiscountCalculator calc = new DiscountCalculator();

        assertMoney("85.00", calc.calculate(new BigDecimal("100.00"), List.of(tenPct, fiveFixed)));
        assertMoney("85.50", calc.calculate(new BigDecimal("100.00"), List.of(fiveFixed, tenPct)));
    }

    /**
     * Case 7 — duplicate coupons stack. The engine applies every entry in the list, including a
     * repeated coupon; deduplication is the caller's responsibility, not the engine's. Two 10%
     * coupons compound against the running subtotal: {@code 100.00 -> 90.00 -> 81.00}.
     */
    @Test
    void duplicateCouponsStackAgainstRunningSubtotal() {
        Coupon tenPct = new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10"));
        BigDecimal result = new DiscountCalculator()
                .calculate(new BigDecimal("100.00"), List.of(tenPct, tenPct));
        assertMoney("81.00", result);
    }

    /**
     * Case 8 — a large {@code FIXED} value combined with an earlier percentage floors at zero and
     * still returns a scale-2 total. {@code 100.00 -> (10% off) 90.00 -> ($500 off) 0.00}.
     */
    @Test
    void largeFixedValueAfterPercentageFlooredAtScaleTwoZero() {
        List<Coupon> coupons = List.of(
                new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10")),
                new Coupon("F500", Coupon.Type.FIXED, new BigDecimal("500.00")));
        BigDecimal result = new DiscountCalculator().calculate(new BigDecimal("100.00"), coupons);
        assertMoney("0.00", result);
    }

    /**
     * Case 9 — a malformed (skipped) coupon interleaved with a real one must not corrupt the
     * subtotal or its scale: a {@code null} entry is a no-op, so only the 10% coupon applies,
     * leaving {@code 90.00} at scale 2.
     */
    @Test
    void malformedCouponSkippedPreservesScaleTwo() {
        List<Coupon> coupons = Arrays.asList(
                null,
                new Coupon("P10", Coupon.Type.PERCENTAGE, new BigDecimal("10")));
        BigDecimal result = new CouponDiscountCalculator()
                .applyCoupons(new BigDecimal("100.00"), coupons);
        assertMoney("90.00", result);
    }
}
