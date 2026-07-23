package com.healthcare.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p><b>Code normalization (case/whitespace-insensitive lookup).</b> Because a
 * {@link Coupon} canonicalizes its {@link Coupon#getCode() code} on construction
 * (trimmed and upper-cased with {@link java.util.Locale#ROOT}) and the registry is keyed
 * by that canonical form, {@link #validate(String) validate} canonicalizes the submitted
 * code the same way — through {@link Coupon#canonicalizeCode(String)} — before looking it
 * up. Lookup is therefore case- and surrounding-whitespace-insensitive: a coupon
 * registered as {@code SAVE10} resolves for {@code "save10"}, {@code "Save10"}, or
 * {@code " SAVE10 "}. This matches the realistic free-text input a coupon UI field
 * produces and keeps the storage and lookup sides of coupon identity in lock-step.</p>
 *
 * <p><b>Time.</b> {@link #validate(String)} evaluates the validity window against
 * {@link LocalDate#now()} in the system default time zone. The
 * {@link #validate(String, LocalDate)} overload accepts an explicit "as of" date
 * so callers (and tests) can evaluate coupons deterministically.</p>
 *
 * <p><b>Rate limiting.</b> Production coupon endpoints should additionally
 * rate-limit validation calls to deter code enumeration/brute force. That
 * concern belongs to the HTTP layer ({@code com.healthcare.order.api}) and is
 * intentionally <i>not</i> implemented here; no networking or throttling framework
 * is introduced by this class.</p>
 *
 * <p><b>Redemption model (authoritative usage accounting).</b> A coupon's
 * {@link Coupon#getUsageCount() usageCount} is an immutable <i>baseline</i> captured
 * when the coupon was seeded. Redemptions accrued at run time are tracked separately
 * in an in-memory ledger keyed by canonical code, so the immutable {@link Coupon}
 * value object never has to change. The <b>effective usage</b> of a coupon is its
 * baseline {@code usageCount} plus the ledger count, and a coupon is exhausted when
 * that effective usage reaches a positive {@link Coupon#getUsageLimit() usageLimit}.
 * Two distinct operations are offered:</p>
 * <ul>
 *   <li>{@link #validate(String)} / {@link #validate(String, LocalDate)} —
 *       <b>read-only</b>. It reports whether a code <i>would</i> be accepted right now
 *       (checking effective usage) but consumes nothing. This backs the read-only
 *       {@code POST /coupons/validate} endpoint.</li>
 *   <li>{@link #validateAndRedeem(String)} /
 *       {@link #validateAndRedeem(String, LocalDate)} — <b>atomically</b> validates and,
 *       on success, consumes one redemption. This is what order creation calls, so a
 *       usage-limited coupon cannot be over-redeemed even under concurrent order
 *       creation. {@link #releaseRedemption(String)} returns a redemption to the ledger
 *       to roll back a failed order.</li>
 * </ul>
 *
 * <p><b>Batch bound.</b> {@link #validate(List)} rejects a batch larger than
 * {@link #MAX_BATCH_SIZE} up front, bounding server-side fan-out (CWE-400); the bound
 * mirrors the client-side {@code MAX_COUPONS} contract.</p>
 *
 * <p><b>Threading.</b> The registry is a {@link ConcurrentHashMap}, so concurrent
 * seeding and lookup are safe. The redemption ledger is guarded so the
 * check-then-consume in {@link #validateAndRedeem(String, LocalDate)} is atomic: two
 * threads redeeming the last unit of a single-use coupon cannot both succeed. The
 * class is therefore safe for concurrent validation and order creation.</p>
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
     * Maximum number of coupon codes accepted by a single {@link #validate(List)} batch.
     * Bounds server-side fan-out against resource-exhaustion abuse (CWE-400) and mirrors the
     * client-side {@code MAX_COUPONS} bound in {@code customer-ui}'s wire contract.
     */
    public static final int MAX_BATCH_SIZE = 25;

    /**
     * In-memory registry of known coupons, keyed by {@link Coupon#getCode()}. A
     * {@link ConcurrentHashMap} is used so concurrent seeding and lookup are thread-safe;
     * iteration order is not relied upon (multi-code validation iterates the caller's input
     * list, not the registry).
     */
    private final Map<String, Coupon> registry;

    /**
     * Run-time redemption ledger, keyed by canonical coupon code. Each value counts the
     * redemptions consumed <i>since seeding</i>, on top of the coupon's immutable baseline
     * {@link Coupon#getUsageCount() usageCount}. Held separately from the immutable
     * {@link Coupon} so usage accounting can advance without mutating the value object.
     */
    private final Map<String, AtomicInteger> redemptions;

    /**
     * Guards the check-then-consume in {@link #validateAndRedeem(String, LocalDate)} and the
     * decrement in {@link #releaseRedemption(String)}, so effective-usage evaluation and the
     * subsequent ledger update happen as one atomic step even under concurrent order creation.
     */
    private final Object redemptionLock = new Object();

    /**
     * Creates a validator with an empty registry. Seed coupons afterwards with
     * {@link #addCoupon(Coupon)} or {@link #addCoupons(Collection)}.
     */
    public CouponValidator() {
        this.registry = new ConcurrentHashMap<>();
        this.redemptions = new ConcurrentHashMap<>();
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
     * @param code the coupon code to validate; it is canonicalized (trimmed and
     *             upper-cased with {@link java.util.Locale#ROOT}) via
     *             {@link Coupon#canonicalizeCode(String)} before lookup, so matching is
     *             case- and surrounding-whitespace-insensitive; a {@code null} or
     *             unrecognized code yields {@link #REASON_UNKNOWN_CODE}
     * @param asOf the date at which to evaluate the validity window; must not be
     *             {@code null}
     * @return a normalized {@link ValidationResult}; never {@code null}
     * @throws NullPointerException if {@code asOf} is {@code null}
     */
    public ValidationResult validate(String code, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");

        // 1. Existence: the code must resolve to a known coupon. The registry is keyed by
        //    each coupon's canonical code (Coupon.getCode()), so the raw input must be
        //    canonicalized the SAME way (Coupon.canonicalizeCode) before lookup; otherwise a
        //    differently-cased or whitespace-padded code — exactly what a free-text UI field
        //    yields — would never match its registered coupon.
        //
        //    NULL/EMPTY GUARD (finding BE-01): canonicalizeCode is null-safe (null -> null),
        //    but the backing registry is a ConcurrentHashMap, whose get(null) THROWS a
        //    NullPointerException rather than returning null (unlike a plain HashMap). A raw
        //    null code — passed directly to this library API or arriving as a null element of
        //    a batch — must therefore be intercepted BEFORE the map lookup and mapped to the
        //    documented "unknown code" invalid result. A canonically-empty code (e.g. a
        //    whitespace-only input) can never match a registered coupon either, so it is
        //    treated identically. Guarding here keeps the public validation APIs total: they
        //    always return a normalized ValidationResult and never throw for a null/blank code.
        String canonicalCode = Coupon.canonicalizeCode(code);
        if (canonicalCode == null || canonicalCode.isEmpty()) {
            return ValidationResult.invalid(REASON_UNKNOWN_CODE);
        }
        Coupon coupon = registry.get(canonicalCode);
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

        // 3. Usage limit: reject an exhausted coupon, measured by EFFECTIVE usage (the
        //    coupon's immutable baseline usageCount plus any redemptions accrued in the
        //    ledger). This read-only check consumes nothing; it reports whether the code
        //    would be accepted right now.
        if (isExhausted(coupon)) {
            return ValidationResult.invalid(REASON_USAGE_LIMIT_EXCEEDED, coupon);
        }

        // 4. All checks passed: valid, carrying the discount metadata.
        return ValidationResult.valid(coupon);
    }

    /**
     * Atomically validates a single coupon code as of the current date and, when the code is
     * accepted, consumes exactly one redemption from the run-time ledger.
     *
     * <p>This is the entry point {@code OrderService} uses during order creation: it is the
     * <i>only</i> path that mutates usage. Unlike the read-only {@link #validate(String)},
     * a successful result here means one redemption has been recorded, so a usage-limited
     * coupon cannot be over-redeemed. An invalid result consumes nothing.</p>
     *
     * @param code the coupon code to validate and, on success, redeem
     * @return the normalized {@link ValidationResult}; on a {@linkplain ValidationResult#isValid()
     *         valid} result one redemption has been consumed
     */
    public ValidationResult validateAndRedeem(String code) {
        return validateAndRedeem(code, LocalDate.now());
    }

    /**
     * Atomically validates a coupon code as of an explicit date and, on success, consumes one
     * redemption. The effective-usage check and the ledger increment are performed together
     * under a lock, so two threads competing for the last unit of a single-use coupon cannot
     * both succeed.
     *
     * @param code the coupon code to validate and, on success, redeem
     * @param asOf the date at which to evaluate the validity window; must not be {@code null}
     * @return the normalized {@link ValidationResult}; on a valid result one redemption has
     *         been consumed
     * @throws NullPointerException if {@code asOf} is {@code null}
     */
    public ValidationResult validateAndRedeem(String code, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        synchronized (redemptionLock) {
            ValidationResult result = validate(code, asOf);
            if (result.isValid()) {
                // Safe under the lock: no concurrent redeem can slip between the effective-usage
                // check inside validate(...) above and this increment.
                redemptions
                        .computeIfAbsent(result.getCoupon().getCode(), c -> new AtomicInteger())
                        .incrementAndGet();
            }
            return result;
        }
    }

    /**
     * Returns one previously consumed redemption to the ledger, rolling back a
     * {@link #validateAndRedeem(String)} that a later step could not honor (for example when
     * order creation fails after some coupons were already redeemed). Never drives a code's
     * ledger count below zero, and an unknown/never-redeemed code is a no-op.
     *
     * @param code the coupon code whose redemption should be released; canonicalized before
     *             lookup, {@code null}-safe
     */
    public void releaseRedemption(String code) {
        String canonical = Coupon.canonicalizeCode(code);
        if (canonical == null) {
            return;
        }
        synchronized (redemptionLock) {
            AtomicInteger consumed = redemptions.get(canonical);
            if (consumed != null && consumed.get() > 0) {
                consumed.decrementAndGet();
            }
        }
    }

    /**
     * Reports whether a coupon has reached its redemption cap measured by effective usage: the
     * immutable baseline {@link Coupon#getUsageCount() usageCount} plus redemptions recorded in
     * the ledger. An {@link Coupon#UNLIMITED_USAGE unlimited} coupon is never exhausted.
     *
     * @param coupon the resolved coupon; must not be {@code null}
     * @return {@code true} if effective usage has reached a positive usage limit
     */
    private boolean isExhausted(Coupon coupon) {
        int limit = coupon.getUsageLimit();
        if (limit <= Coupon.UNLIMITED_USAGE) {
            return false;
        }
        AtomicInteger consumed = redemptions.get(coupon.getCode());
        int effective = coupon.getUsageCount() + (consumed == null ? 0 : consumed.get());
        return effective >= limit;
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
     *              {@link #REASON_UNKNOWN_CODE}) and must not exceed {@link #MAX_BATCH_SIZE}
     * @return a list of results, one per input code, in input order; never
     *         {@code null}
     * @throws NullPointerException     if {@code codes} is {@code null}
     * @throws IllegalArgumentException if {@code codes} has more than {@link #MAX_BATCH_SIZE}
     *                                  entries
     */
    public List<ValidationResult> validate(List<String> codes) {
        Objects.requireNonNull(codes, "codes");
        if (codes.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "coupon batch size must not exceed " + MAX_BATCH_SIZE + ": " + codes.size());
        }
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
