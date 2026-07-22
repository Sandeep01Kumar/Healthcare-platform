package com.healthcare.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authoritative, server-side coupon validation API for the order-service.
 *
 * <p>This class is the single source of truth for whether a coupon code may be
 * applied to an order. Per the feature rules, the {@code customer-ui} (or any
 * other client) must <b>never</b> be trusted to decide coupon validity: the UI
 * may echo a code back to the user, but the definitive yes/no decision — and the
 * discount metadata that drives the pricing engine — is produced here, on the
 * server, by {@link #validate(String)}.</p>
 *
 * <p><b>Checks performed, in order.</b> For a given code
 * {@link #validate(String, LocalDate) validate} applies these authoritative
 * checks and stops at the first failure:</p>
 * <ol>
 *   <li><b>Existence</b> — the code must resolve to a known {@link Coupon} in the
 *       in-memory registry. An unresolved (or {@code null}) code yields
 *       {@link #REASON_UNKNOWN_CODE}.</li>
 *   <li><b>Validity window</b> — the supplied date must fall within the coupon's
 *       inclusive {@code [validFrom, validUntil]} window (see
 *       {@link Coupon#isWithinValidityWindow(LocalDate)}). A date before the
 *       window yields {@link #REASON_NOT_YET_ACTIVE}; a date after it yields
 *       {@link #REASON_EXPIRED}.</li>
 *   <li><b>Usage limit</b> — the coupon must not have reached its redemption cap
 *       (see {@link Coupon#isUsageLimitExceeded()}); an exhausted coupon yields
 *       {@link #REASON_USAGE_LIMIT_EXCEEDED}.</li>
 * </ol>
 * <p>When every check passes, the returned {@link ValidationResult} is
 * {@linkplain ValidationResult#isValid() valid} with reason {@link #REASON_OK}
 * and carries the resolved coupon's discount metadata
 * ({@link ValidationResult#getType() type} and
 * {@link ValidationResult#getValue() value}) for the pricing engine to consume.</p>
 *
 * <p><b>Registry.</b> Known coupons are held in an in-memory registry keyed by
 * each coupon's own {@link Coupon#getCode() code}; there is no database or other
 * persistence (out of scope for this feature). Coupons may be seeded through the
 * constructors or added later with {@link #addCoupon(Coupon)} /
 * {@link #addCoupons(Collection)}, which makes deterministic test scenarios
 * (valid, not-yet-active, expired, usage-limit exceeded, unknown) easy to build.</p>
 *
 * <p><b>Time.</b> {@link #validate(String)} evaluates the validity window against
 * {@link LocalDate#now()} in the system default time zone. The
 * {@link #validate(String, LocalDate)} overload accepts an explicit "as of" date
 * so callers (and tests) can evaluate coupons deterministically.</p>
 *
 * <p><b>Rate limiting.</b> Production coupon endpoints should additionally
 * rate-limit validation calls to deter code enumeration/brute force. That
 * concern belongs to the (not-yet-present) HTTP layer and is intentionally
 * <i>not</i> implemented here; no networking or throttling framework is
 * introduced by this class.</p>
 *
 * <p><b>Threading.</b> The registry is a plain map; concurrent seeding and
 * validation are not synchronized. The intended usage is to seed the known
 * coupons up front and then validate, which is safe for the single-threaded and
 * read-mostly access this feature requires.</p>
 */
public class CouponValidator {

    /** Reason attached to a valid result: all checks passed. */
    public static final String REASON_OK = "OK";

    /** Reason for an invalid result whose code does not resolve to a known coupon. */
    public static final String REASON_UNKNOWN_CODE = "unknown code";

    /** Reason for an invalid result evaluated before the coupon's validity window opens. */
    public static final String REASON_NOT_YET_ACTIVE = "not yet active";

    /** Reason for an invalid result evaluated after the coupon's validity window closes. */
    public static final String REASON_EXPIRED = "expired";

    /** Reason for an invalid result whose coupon has reached its redemption limit. */
    public static final String REASON_USAGE_LIMIT_EXCEEDED = "usage limit exceeded";

    /**
     * In-memory registry of known coupons, keyed by {@link Coupon#getCode()}. A
     * {@link LinkedHashMap} is used so iteration (e.g. for multi-code validation
     * ordering during debugging) is deterministic in insertion order.
     */
    private final Map<String, Coupon> registry;

    /**
     * Creates a validator with an empty registry. Seed coupons afterwards with
     * {@link #addCoupon(Coupon)} or {@link #addCoupons(Collection)}.
     */
    public CouponValidator() {
        this.registry = new LinkedHashMap<>();
    }

    /**
     * Creates a validator seeded from the values of the supplied map. Each coupon
     * is registered under its own {@link Coupon#getCode() code}; the map's keys are
     * not used for lookup, so validation stays consistent with each coupon's actual
     * code. A {@code null} map produces an empty registry.
     *
     * @param coupons a map whose values are the coupons to register; may be
     *                {@code null} or empty
     * @throws NullPointerException if any value in the map, or its code, is
     *                              {@code null}
     */
    public CouponValidator(Map<String, Coupon> coupons) {
        this();
        if (coupons != null) {
            seedAll(coupons.values());
        }
    }

    /**
     * Creates a validator seeded from the supplied collection. Each coupon is
     * registered under its own {@link Coupon#getCode() code}. A {@code null}
     * collection produces an empty registry.
     *
     * @param coupons the coupons to register; may be {@code null} or empty
     * @throws NullPointerException if any coupon, or its code, is {@code null}
     */
    public CouponValidator(Collection<Coupon> coupons) {
        this();
        seedAll(coupons);
    }

    /**
     * Registers (or replaces) a single coupon in the in-memory registry, keyed by
     * its {@link Coupon#getCode() code}. Re-adding a coupon with an existing code
     * overwrites the previous entry.
     *
     * @param coupon the coupon to register; must not be {@code null}
     * @throws NullPointerException if {@code coupon} or its
     *                              {@link Coupon#getCode() code} is {@code null}
     */
    public void addCoupon(Coupon coupon) {
        register(coupon);
    }

    /**
     * Registers all coupons in the supplied collection via
     * {@link #addCoupon(Coupon)}. A {@code null} collection is a no-op.
     *
     * @param coupons the coupons to register; may be {@code null}
     * @throws NullPointerException if any coupon, or its code, is {@code null}
     */
    public void addCoupons(Collection<Coupon> coupons) {
        seedAll(coupons);
    }

    /**
     * Registers a single coupon into the {@link #registry}, keyed by its
     * {@link Coupon#getCode() code}. Private and therefore non-overridable, so it is
     * safe to invoke from the constructors without a {@code this}-escape.
     *
     * @param coupon the coupon to register; must not be {@code null}
     * @throws NullPointerException if {@code coupon} or its code is {@code null}
     */
    private void register(Coupon coupon) {
        Objects.requireNonNull(coupon, "coupon");
        Objects.requireNonNull(coupon.getCode(), "coupon.code");
        registry.put(coupon.getCode(), coupon);
    }

    /**
     * Registers every coupon in the supplied collection into the {@link #registry}.
     * A {@code null} collection is a no-op. Private and therefore non-overridable, so
     * it is safe to invoke from the constructors without a {@code this}-escape.
     *
     * @param coupons the coupons to register; may be {@code null}
     * @throws NullPointerException if any coupon, or its code, is {@code null}
     */
    private void seedAll(Collection<Coupon> coupons) {
        if (coupons == null) {
            return;
        }
        for (Coupon coupon : coupons) {
            register(coupon);
        }
    }

    /**
     * Validates a single coupon code as of the current date
     * ({@link LocalDate#now()} in the system default time zone).
     *
     * <p>This is the primary entry point used by {@code OrderService} during order
     * creation. It performs the authoritative existence, validity-window, and
     * usage-limit checks described on the {@link CouponValidator class Javadoc}.</p>
     *
     * @param code the coupon code to validate; a {@code null} or unrecognized code
     *             yields an invalid result with reason {@link #REASON_UNKNOWN_CODE}
     * @return a normalized {@link ValidationResult} carrying validity, a reason, and
     *         (for a valid or known-but-rejected code) the resolved coupon's
     *         discount metadata; never {@code null}
     */
    public ValidationResult validate(String code) {
        return validate(code, LocalDate.now());
    }

    /**
     * Validates a single coupon code as of an explicit date. This overload lets
     * callers (and tests) evaluate the validity window deterministically, without
     * depending on the wall clock.
     *
     * <p>The checks are applied in order and short-circuit at the first failure:</p>
     * <ol>
     *   <li>the code must resolve to a known coupon, else
     *       {@link #REASON_UNKNOWN_CODE};</li>
     *   <li>{@code asOf} must fall within the coupon's validity window, else
     *       {@link #REASON_NOT_YET_ACTIVE} (before the window) or
     *       {@link #REASON_EXPIRED} (after it);</li>
     *   <li>the coupon must not have exhausted its usage limit, else
     *       {@link #REASON_USAGE_LIMIT_EXCEEDED}.</li>
     * </ol>
     *
     * @param code the coupon code to validate; a {@code null} or unrecognized code
     *             yields {@link #REASON_UNKNOWN_CODE}
     * @param asOf the date at which to evaluate the validity window; must not be
     *             {@code null}
     * @return a normalized {@link ValidationResult}; never {@code null}
     * @throws NullPointerException if {@code asOf} is {@code null}
     */
    public ValidationResult validate(String code, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");

        // 1. Existence: the code must resolve to a known coupon.
        Coupon coupon = (code == null) ? null : registry.get(code);
        if (coupon == null) {
            return ValidationResult.invalid(REASON_UNKNOWN_CODE);
        }

        // 2. Validity window: distinguish not-yet-active from expired for a clear reason.
        if (!coupon.isWithinValidityWindow(asOf)) {
            LocalDate validFrom = coupon.getValidFrom();
            if (validFrom != null && asOf.isBefore(validFrom)) {
                return ValidationResult.invalid(REASON_NOT_YET_ACTIVE, coupon);
            }
            return ValidationResult.invalid(REASON_EXPIRED, coupon);
        }

        // 3. Usage limit: reject an exhausted coupon.
        if (coupon.isUsageLimitExceeded()) {
            return ValidationResult.invalid(REASON_USAGE_LIMIT_EXCEEDED, coupon);
        }

        // 4. All checks passed: valid, carrying the discount metadata.
        return ValidationResult.valid(coupon);
    }

    /**
     * Validates several coupon codes at once, returning one {@link ValidationResult}
     * per input code in the same order. All codes are evaluated against a single
     * "now" snapshot so a batch is internally consistent. This is a convenience over
     * {@link #validate(String)} for callers (such as multi-coupon order creation)
     * that stack several codes; the single-code {@link #validate(String)} remains the
     * primary API.
     *
     * @param codes the coupon codes to validate; must not be {@code null} (individual
     *              entries may be {@code null}, each yielding
     *              {@link #REASON_UNKNOWN_CODE})
     * @return a list of results, one per input code, in input order; never
     *         {@code null}
     * @throws NullPointerException if {@code codes} is {@code null}
     */
    public List<ValidationResult> validate(List<String> codes) {
        Objects.requireNonNull(codes, "codes");
        LocalDate asOf = LocalDate.now();
        List<ValidationResult> results = new ArrayList<>(codes.size());
        for (String code : codes) {
            results.add(validate(code, asOf));
        }
        return results;
    }

    /**
     * Normalized, immutable outcome of validating a single coupon code.
     *
     * <p>A result always carries a {@linkplain #isValid() validity} flag and a
     * human-readable {@linkplain #getReason() reason}. For a valid result the reason
     * is {@link CouponValidator#REASON_OK} and the resolved {@linkplain #getCoupon()
     * coupon} — together with its convenience {@linkplain #getType() type} and
     * {@linkplain #getValue() value} discount metadata — is present for the pricing
     * engine to consume.</p>
     *
     * <p>For an invalid result the reason explains the failure. The coupon may still
     * be present when the code resolved but was rejected (for example an
     * {@link CouponValidator#REASON_EXPIRED expired} or
     * {@link CouponValidator#REASON_USAGE_LIMIT_EXCEEDED usage-exhausted} coupon), or
     * {@code null} when the code was {@link CouponValidator#REASON_UNKNOWN_CODE
     * unknown}. Callers must therefore check {@link #isValid()} before relying on the
     * discount metadata.</p>
     */
    public static final class ValidationResult {

        /** Whether the coupon passed every authoritative check. */
        private final boolean valid;

        /** Human-readable reason; {@link CouponValidator#REASON_OK} when valid. */
        private final String reason;

        /** The resolved coupon, or {@code null} when the code was unknown. */
        private final Coupon coupon;

        /**
         * Creates a result. Private: results are produced only by
         * {@link CouponValidator} through the {@link #valid(Coupon)} and
         * {@link #invalid(String)} / {@link #invalid(String, Coupon)} factories.
         *
         * @param valid  whether the coupon is valid
         * @param reason the human-readable reason
         * @param coupon the resolved coupon, or {@code null}
         */
        private ValidationResult(boolean valid, String reason, Coupon coupon) {
            this.valid = valid;
            this.reason = reason;
            this.coupon = coupon;
        }

        /**
         * Builds a valid result for the given resolved coupon with reason
         * {@link CouponValidator#REASON_OK}.
         *
         * @param coupon the validated coupon; must not be {@code null}
         * @return a valid result carrying the coupon's discount metadata
         */
        private static ValidationResult valid(Coupon coupon) {
            return new ValidationResult(true, REASON_OK, coupon);
        }

        /**
         * Builds an invalid result with the given reason and no coupon (used when the
         * code did not resolve).
         *
         * @param reason the reason the code is invalid
         * @return an invalid result with a {@code null} coupon
         */
        private static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason, null);
        }

        /**
         * Builds an invalid result with the given reason for a code that resolved to a
         * known coupon but failed a later check (window or usage limit).
         *
         * @param reason the reason the coupon is invalid
         * @param coupon the resolved-but-rejected coupon
         * @return an invalid result carrying the resolved coupon
         */
        private static ValidationResult invalid(String reason, Coupon coupon) {
            return new ValidationResult(false, reason, coupon);
        }

        /**
         * Returns whether the coupon passed every authoritative check.
         *
         * @return {@code true} if the coupon is valid and may be applied
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the human-readable reason for this outcome.
         *
         * @return {@link CouponValidator#REASON_OK} when valid, otherwise one of the
         *         {@code REASON_*} explanations
         */
        public String getReason() {
            return reason;
        }

        /**
         * Returns the resolved coupon associated with this outcome, if any.
         *
         * @return the resolved coupon, or {@code null} when the code was unknown
         */
        public Coupon getCoupon() {
            return coupon;
        }

        /**
         * Convenience accessor for the resolved coupon's discount type, i.e. the
         * discount metadata forwarded to the pricing engine.
         *
         * @return the coupon's {@link Coupon#getType() type}, or {@code null} when no
         *         coupon is present
         */
        public String getType() {
            return coupon == null ? null : coupon.getType();
        }

        /**
         * Convenience accessor for the resolved coupon's discount magnitude, i.e. the
         * discount metadata forwarded to the pricing engine.
         *
         * @return the coupon's {@link Coupon#getValue() value}, or {@code null} when no
         *         coupon is present
         */
        public BigDecimal getValue() {
            return coupon == null ? null : coupon.getValue();
        }

        /**
         * Returns a concise, human-readable description for logging and test output.
         * The format is not part of the API contract and may change.
         *
         * @return a string representation of this result
         */
        @Override
        public String toString() {
            return "ValidationResult{valid=" + valid + ", reason=" + reason
                    + ", coupon=" + coupon + "}";
        }

    }

}
