package com.healthcare.order;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Unit tests for {@link CouponValidator}, the authoritative server-side coupon
 * validation API of the order-service.
 *
 * <p>Four scenarios are exercised against a deterministically seeded in-memory
 * registry, one per authoritative check performed by
 * {@link CouponValidator#validate(String)}:</p>
 * <ol>
 *   <li>a <b>valid</b>, in-window, under-limit code is accepted and carries the
 *       coupon's discount metadata;</li>
 *   <li>an <b>expired</b> (out-of-validity-window) code is rejected;</li>
 *   <li>a code whose <b>usage limit</b> is exhausted is rejected;</li>
 *   <li>an <b>unknown</b> (unseeded) code is rejected.</li>
 * </ol>
 *
 * <p>Every case also asserts the {@link CouponValidator.ValidationResult}
 * normalization guarantee (see {@link #assertNormalized}): the result is never
 * {@code null}, its reason is always populated and non-blank, and — for a valid
 * result — the discount metadata (resolved coupon, type and value) is present for
 * the pricing engine to consume, while an unknown code carries no metadata.</p>
 *
 * <p>Because the production classes live in this same {@code com.healthcare.order}
 * package, they are referenced by simple name with no {@code import}; only JUnit 5
 * and the JDK types actually used are imported. Monetary {@link BigDecimal} values
 * are compared with {@link BigDecimal#compareTo(BigDecimal)} so the assertions are
 * insensitive to scale, and expected reasons are referenced through the
 * {@code CouponValidator.REASON_*} constants rather than hard-coded strings.</p>
 */
public class CouponValidatorTest {

    /** A valid, in-window, under-limit code is accepted, carrying discount metadata. */
    @Test
    void validCouponIsAccepted() {
        CouponValidator validator = seededValidator();
        CouponValidator.ValidationResult result = validator.validate("SAVE10");
        assertNormalized(result);
        assertTrue(result.isValid(), "SAVE10 should be accepted");
        assertEquals(CouponValidator.REASON_OK, result.getReason());
        assertNotNull(result.getCoupon(), "a valid result must carry the resolved coupon");
        assertEquals("SAVE10", result.getCoupon().getCode());
        assertEquals(Coupon.TYPE_PERCENTAGE, result.getType());
        assertEquals(0, new BigDecimal("10").compareTo(result.getValue()),
                "discount value should be 10 (percentage)");
    }

    /** An expired / out-of-validity-window code is rejected with the expiry reason. */
    @Test
    void expiredCouponIsRejected() {
        CouponValidator validator = seededValidator();
        CouponValidator.ValidationResult result = validator.validate("EXPIRED");
        assertNormalized(result);
        assertFalse(result.isValid(), "EXPIRED should be rejected");
        assertEquals(CouponValidator.REASON_EXPIRED, result.getReason());
        assertNotNull(result.getCoupon(),
                "a resolved-but-rejected code still carries its coupon");
    }

    /** A code whose usage limit is exhausted is rejected with the usage-limit reason. */
    @Test
    void usageLimitExceededIsRejected() {
        CouponValidator validator = seededValidator();
        CouponValidator.ValidationResult result = validator.validate("MAXEDOUT");
        assertNormalized(result);
        assertFalse(result.isValid(), "MAXEDOUT should be rejected");
        assertEquals(CouponValidator.REASON_USAGE_LIMIT_EXCEEDED, result.getReason());
        assertNotNull(result.getCoupon(),
                "a resolved-but-rejected code still carries its coupon");
    }

    /** An unknown (unseeded) code is rejected with the "unknown code" reason and no metadata. */
    @Test
    void unknownCouponIsRejected() {
        CouponValidator validator = seededValidator();
        CouponValidator.ValidationResult result = validator.validate("NOPE");
        assertNormalized(result);
        assertFalse(result.isValid(), "an unseeded code should be rejected");
        assertEquals(CouponValidator.REASON_UNKNOWN_CODE, result.getReason());
        assertNull(result.getCoupon(), "an unknown code resolves to no coupon");
        assertNull(result.getType(), "an unknown code carries no discount type");
        assertNull(result.getValue(), "an unknown code carries no discount value");
    }

    /**
     * Builds a {@link CouponValidator} seeded with three deterministic coupons that
     * exercise the accept, expired, and usage-limit-exceeded paths. The unknown-code
     * scenario is covered by validating a code that is deliberately NOT seeded here.
     *
     * <p>Dates are anchored to {@link LocalDate#now()} so the windows straddle "today":
     * {@code SAVE10} is active (yesterday..+30d) and under its usage limit;
     * {@code EXPIRED}'s window closed yesterday; {@code MAXEDOUT} is active but has
     * consumed its single permitted redemption.</p>
     *
     * @return a validator pre-populated with the {@code SAVE10}, {@code EXPIRED}, and
     *         {@code MAXEDOUT} coupons
     */
    private CouponValidator seededValidator() {
        LocalDate today = LocalDate.now();
        Coupon valid = new Coupon("SAVE10", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 100, 0);
        Coupon expired = new Coupon("EXPIRED", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(30), today.minusDays(1), 100, 0);
        Coupon maxed = new Coupon("MAXEDOUT", Coupon.TYPE_FIXED, new BigDecimal("5.00"),
                today.minusDays(1), today.plusDays(30), 1, 1);
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(valid);
        validator.addCoupon(expired);
        validator.addCoupon(maxed);
        return validator;
    }

    /**
     * Asserts the {@link CouponValidator.ValidationResult} normalization invariant that
     * holds for every outcome: the result is non-null, its reason is always populated
     * and non-blank, and a valid result additionally carries its discount metadata
     * (resolved coupon, type and value) for the pricing engine.
     *
     * @param result the validation result to check; must not be {@code null}
     */
    private void assertNormalized(CouponValidator.ValidationResult result) {
        assertNotNull(result, "validate(...) must never return null");
        assertNotNull(result.getReason(), "reason must always be populated");
        assertFalse(result.getReason().isBlank(), "reason must not be blank");
        if (result.isValid()) {
            assertNotNull(result.getCoupon(), "a valid result must carry the resolved coupon");
            assertNotNull(result.getType(), "a valid result must carry the discount type");
            assertNotNull(result.getValue(), "a valid result must carry the discount value");
        }
    }
}
