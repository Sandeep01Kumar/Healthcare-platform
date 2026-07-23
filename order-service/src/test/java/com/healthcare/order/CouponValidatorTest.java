package com.healthcare.order;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Beyond those read-only cases the suite pins the run-time redemption and bounds
 * contract that backs order creation:</p>
 * <ul>
 *   <li><b>validity-window boundaries</b> are inclusive on both ends — a coupon is valid
 *       exactly on its {@code validFrom} and {@code validUntil} dates, "not yet active" the
 *       day before, and "expired" the day after (via {@link CouponValidator#validate(String,
 *       LocalDate)});</li>
 *   <li>the read-only {@link CouponValidator#validate(String)} <b>consumes nothing</b> — it may
 *       be called repeatedly without exhausting a usage-limited coupon;</li>
 *   <li>{@link CouponValidator#validateAndRedeem(String)} <b>atomically consumes</b> exactly one
 *       redemption per success, so a coupon limited to N uses is accepted exactly N times across
 *       orders and rejected with {@link CouponValidator#REASON_USAGE_LIMIT_EXCEEDED} thereafter;</li>
 *   <li>{@link CouponValidator#releaseRedemption(String)} <b>rolls back</b> one consumed
 *       redemption, is a safe no-op for a never-redeemed code, and never drives the ledger below
 *       zero;</li>
 *   <li>redemption is <b>correct under concurrency</b> — many threads racing for the last units of
 *       a limited coupon collectively redeem it exactly N times; and</li>
 *   <li>batch validation enforces the {@link CouponValidator#MAX_BATCH_SIZE} resource bound
 *       (CWE-400): a batch of exactly the bound is accepted, one over throws
 *       {@link IllegalArgumentException}.</li>
 * </ul>
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
     * Regression test for finding BE-01: a {@code null} coupon code must not crash the public
     * validation APIs. The registry is a {@code ConcurrentHashMap}, whose {@code get(null)} throws
     * {@link NullPointerException}; the validator must intercept the {@code null} before the lookup
     * and return the documented {@link CouponValidator#REASON_UNKNOWN_CODE} invalid result instead.
     * All three public entry points — read-only {@code validate}, redeeming {@code validateAndRedeem},
     * and batch {@code validate(List)} — must be total (never throw) for a {@code null} code.
     */
    @Test
    void nullCodeYieldsUnknownCodeInsteadOfThrowing() {
        CouponValidator validator = seededValidator();

        CouponValidator.ValidationResult single = assertDoesNotThrow(
                () -> validator.validate((String) null),
                "validate(null) must not throw");
        assertNormalized(single);
        assertFalse(single.isValid(), "a null code is not valid");
        assertEquals(CouponValidator.REASON_UNKNOWN_CODE, single.getReason());
        assertNull(single.getCoupon(), "a null code resolves to no coupon");

        CouponValidator.ValidationResult redeemed = assertDoesNotThrow(
                () -> validator.validateAndRedeem(null),
                "validateAndRedeem(null) must not throw");
        assertFalse(redeemed.isValid(), "a null code is not valid to redeem");
        assertEquals(CouponValidator.REASON_UNKNOWN_CODE, redeemed.getReason());

        // Batch validation with a null element must yield one result per input, in order, with the
        // null element mapping to the unknown-code result rather than aborting the whole batch.
        List<String> withNull = new ArrayList<>();
        withNull.add("SAVE10");
        withNull.add(null);
        List<CouponValidator.ValidationResult> results = assertDoesNotThrow(
                () -> validator.validate(withNull),
                "batch validate with a null element must not throw");
        assertEquals(2, results.size(), "one result per input code, including the null element");
        assertTrue(results.get(0).isValid(), "the SAVE10 element still validates");
        assertFalse(results.get(1).isValid(), "the null element is invalid");
        assertEquals(CouponValidator.REASON_UNKNOWN_CODE, results.get(1).getReason());
    }

    /**
     * A blank (whitespace-only) code canonicalizes to the empty string, which can never match a
     * registered coupon; it must resolve to {@link CouponValidator#REASON_UNKNOWN_CODE} without
     * throwing (companion to the {@code null} guard in finding BE-01).
     */
    @Test
    void blankCodeYieldsUnknownCode() {
        CouponValidator validator = seededValidator();
        CouponValidator.ValidationResult result =
                assertDoesNotThrow(() -> validator.validate("   "), "a blank code must not throw");
        assertNormalized(result);
        assertFalse(result.isValid(), "a blank code is not valid");
        assertEquals(CouponValidator.REASON_UNKNOWN_CODE, result.getReason());
    }

    /**
     * Regression test for finding BE-02: a {@link Coupon#TYPE_FIXED} coupon's monetary value is
     * normalized to the two-decimal monetary scale (HALF_UP) at construction, so the value the
     * validation result exposes is exactly the canonical amount the pricing engine subtracts.
     * A sub-cent input such as {@code 5.005} is stored — and reported — as {@code 5.01}, never as
     * the raw {@code 5.005} that previously diverged from the applied discount.
     */
    @Test
    void fixedCouponValueIsNormalizedToTwoDecimals() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("SUBCENT", Coupon.TYPE_FIXED, new BigDecimal("5.005"),
                today.minusDays(1), today.plusDays(30), Coupon.UNLIMITED_USAGE, 0));

        CouponValidator.ValidationResult result = validator.validate("SUBCENT");
        assertNormalized(result);
        assertTrue(result.isValid(), "SUBCENT should be accepted");
        assertEquals(0, new BigDecimal("5.01").compareTo(result.getValue()),
                "a FIXED value of 5.005 must normalize to 5.01");
        assertEquals(2, result.getValue().scale(),
                "a FIXED coupon value must be exposed at the canonical monetary scale of 2");
    }

    /**
     * Regression test for the coupon-code normalization contract (QA finding F1).
     *
     * <p>A {@link Coupon} canonicalizes its {@link Coupon#getCode() code} on construction
     * (trimmed and upper-cased with {@code Locale.ROOT}), and the validator's registry is
     * keyed by that canonical form. Realistic free-text input from the customer-ui coupon
     * field is commonly lower-cased, mixed-cased, or padded with surrounding whitespace, so
     * every such variant of a seeded code MUST resolve to the same coupon. Before the fix,
     * {@link CouponValidator#validate(String)} looked the code up with the <i>raw</i> input
     * while the registry was keyed by the <i>canonical</i> code, so {@code validate("save10")}
     * and {@code validate(" SAVE10 ")} returned {@link CouponValidator#REASON_UNKNOWN_CODE}
     * even though {@code SAVE10} was registered — an asymmetric, incomplete implementation of
     * the documented canonicalization contract. This test pins the corrected, case- and
     * whitespace-insensitive behavior across a spread of casing/whitespace variants and
     * confirms each resolves to the canonical {@code SAVE10} coupon with its discount
     * metadata intact.</p>
     */
    @Test
    void couponLookupIsCaseAndWhitespaceInsensitive() {
        CouponValidator validator = seededValidator();
        String[] variants = {"save10", "Save10", "SAVE10", "sAvE10", " save10 ", "  SAVE10  ", "\tSave10\n"};
        for (String variant : variants) {
            CouponValidator.ValidationResult result = validator.validate(variant);
            assertNormalized(result);
            assertTrue(result.isValid(),
                    "lookup must be case/whitespace-insensitive; '" + variant + "' should resolve SAVE10");
            assertEquals(CouponValidator.REASON_OK, result.getReason(),
                    "'" + variant + "' should validate with reason OK");
            assertNotNull(result.getCoupon(),
                    "'" + variant + "' must carry the resolved coupon");
            assertEquals("SAVE10", result.getCoupon().getCode(),
                    "'" + variant + "' must resolve to the canonical SAVE10 coupon");
            assertEquals(Coupon.TYPE_PERCENTAGE, result.getType(),
                    "'" + variant + "' must carry SAVE10's percentage discount type");
            assertEquals(0, new BigDecimal("10").compareTo(result.getValue()),
                    "'" + variant + "' must carry SAVE10's discount value of 10");
        }
    }

    /**
     * The validity window is inclusive on BOTH ends: a coupon is valid exactly on its
     * {@code validFrom} and {@code validUntil} dates, rejected as "not yet active" the day
     * before {@code validFrom}, and rejected as "expired" the day after {@code validUntil}.
     */
    @Test
    void validityWindowBoundariesAreInclusive() {
        LocalDate from = LocalDate.of(2025, 6, 1);
        LocalDate until = LocalDate.of(2025, 6, 30);
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("WINDOW", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                from, until, Coupon.UNLIMITED_USAGE, 0));

        assertTrue(validator.validate("WINDOW", from).isValid(),
                "the exact validFrom date is inside the window (inclusive start)");
        assertTrue(validator.validate("WINDOW", until).isValid(),
                "the exact validUntil date is inside the window (inclusive end)");

        CouponValidator.ValidationResult dayBefore = validator.validate("WINDOW", from.minusDays(1));
        assertFalse(dayBefore.isValid());
        assertEquals(CouponValidator.REASON_NOT_YET_ACTIVE, dayBefore.getReason(),
                "the day before validFrom is not yet active");

        CouponValidator.ValidationResult dayAfter = validator.validate("WINDOW", until.plusDays(1));
        assertFalse(dayAfter.isValid());
        assertEquals(CouponValidator.REASON_EXPIRED, dayAfter.getReason(),
                "the day after validUntil is expired");
    }

    /**
     * The read-only {@link CouponValidator#validate(String)} consumes no redemption: repeated
     * calls against a single-use coupon all report valid, because only
     * {@link CouponValidator#validateAndRedeem(String)} mutates usage.
     */
    @Test
    void readOnlyValidateNeverConsumesUsage() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("ONCE", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 1, 0));
        for (int i = 0; i < 5; i++) {
            assertTrue(validator.validate("ONCE").isValid(),
                    "read-only validate must not exhaust the coupon (call " + i + ")");
        }
    }

    /**
     * {@link CouponValidator#validateAndRedeem(String)} atomically consumes one unit per success:
     * a coupon limited to two uses is accepted exactly twice and rejected with the usage-limit
     * reason on the third attempt.
     */
    @Test
    void validateAndRedeemConsumesUpToTheUsageLimit() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("TWICE", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 2, 0));

        assertTrue(validator.validateAndRedeem("TWICE").isValid(), "first redemption succeeds");
        assertTrue(validator.validateAndRedeem("TWICE").isValid(), "second redemption succeeds");

        CouponValidator.ValidationResult third = validator.validateAndRedeem("TWICE");
        assertFalse(third.isValid(), "the third redemption is refused");
        assertEquals(CouponValidator.REASON_USAGE_LIMIT_EXCEEDED, third.getReason());

        // The read-only view now agrees the coupon is exhausted.
        assertFalse(validator.validate("TWICE").isValid());
        assertEquals(CouponValidator.REASON_USAGE_LIMIT_EXCEEDED, validator.validate("TWICE").getReason());
    }

    /**
     * {@link CouponValidator#releaseRedemption(String)} rolls a consumed redemption back so the
     * freed unit can be redeemed again; it is a safe no-op for a code that was never redeemed and
     * never drives the ledger below zero.
     */
    @Test
    void releaseRedemptionRollsBackAndNeverGoesNegative() {
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("ONCE", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), 1, 0));

        // Releasing before any redemption is a harmless no-op and must not create a negative slot.
        validator.releaseRedemption("ONCE");
        validator.releaseRedemption("never-seen");

        assertTrue(validator.validateAndRedeem("ONCE").isValid(), "the single use is available");
        assertFalse(validator.validateAndRedeem("ONCE").isValid(), "now exhausted");

        // Roll the single redemption back; the freed unit becomes available exactly once more.
        validator.releaseRedemption("ONCE");
        assertTrue(validator.validateAndRedeem("ONCE").isValid(), "released unit is redeemable again");
        assertFalse(validator.validateAndRedeem("ONCE").isValid(), "and exhausted once more");

        // An over-release cannot manufacture extra redemptions.
        validator.releaseRedemption("ONCE");
        validator.releaseRedemption("ONCE");
        validator.releaseRedemption("ONCE");
        // Only one unit ever existed, so at most one redemption can follow the single legitimate release.
        assertTrue(validator.validateAndRedeem("ONCE").isValid());
        assertFalse(validator.validateAndRedeem("ONCE").isValid(),
                "an over-release must not drive the ledger below zero to grant phantom uses");
    }

    /**
     * Concurrency: many threads racing to redeem a coupon limited to N uses collectively succeed
     * exactly N times — never more — verifying the atomic check-then-consume under contention.
     */
    @Test
    void concurrentValidateAndRedeemHonorsLimitExactly() throws InterruptedException {
        final int limit = 25;
        final int threads = 128;
        LocalDate today = LocalDate.now();
        CouponValidator validator = new CouponValidator();
        validator.addCoupon(new Coupon("RACE", Coupon.TYPE_PERCENTAGE, new BigDecimal("10"),
                today.minusDays(1), today.plusDays(30), limit, 0));

        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (validator.validateAndRedeem("RACE").isValid()) {
                    accepted.incrementAndGet();
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        assertEquals(limit, accepted.get(),
                "a coupon limited to " + limit + " uses must be redeemed exactly " + limit + " times");
    }

    /**
     * Batch validation enforces the {@link CouponValidator#MAX_BATCH_SIZE} resource bound: a batch
     * of exactly the bound is processed, and one element over the bound is rejected up front with
     * {@link IllegalArgumentException} (bounding server-side fan-out, CWE-400).
     */
    @Test
    void batchValidationEnforcesMaxBatchSize() {
        CouponValidator validator = seededValidator();

        List<String> atLimit = Collections.nCopies(CouponValidator.MAX_BATCH_SIZE, "SAVE10");
        List<CouponValidator.ValidationResult> results = validator.validate(atLimit);
        assertEquals(CouponValidator.MAX_BATCH_SIZE, results.size(),
                "a batch of exactly MAX_BATCH_SIZE is processed");
        for (CouponValidator.ValidationResult r : results) {
            assertTrue(r.isValid(), "each SAVE10 in the batch validates");
        }

        List<String> overLimit = Collections.nCopies(CouponValidator.MAX_BATCH_SIZE + 1, "SAVE10");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(overLimit),
                "a batch larger than MAX_BATCH_SIZE must be rejected up front");
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
