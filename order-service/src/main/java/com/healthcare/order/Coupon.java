package com.healthcare.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * Validation-side coupon model for the order-service.
 *
 * <p>This immutable value object carries the data the order-service needs to perform
 * authoritative, server-side coupon validation: the coupon {@link #getCode() code}
 * identity, a validity window ({@link #getValidFrom() validFrom} /
 * {@link #getValidUntil() validUntil}), redemption tracking
 * ({@link #getUsageLimit() usageLimit} and {@link #getUsageCount() usageCount}), and the
 * discount metadata ({@link #getType() type} and {@link #getValue() value}) that a
 * normalized validation result forwards to the pricing engine.</p>
 *
 * <p>This class lives in the {@code com.healthcare.order} package and is a DISTINCT type
 * from the discount-side {@code com.healthcare.pricing.Coupon} in the pricing-engine
 * module. The two share a conceptual contract — both identify a coupon by its
 * {@code code} and describe the discount with a {@code type}/{@code value} pair — but
 * they are separate classes in separate Maven modules/jars. The discount {@code type}
 * is represented here as a {@link String} ({@link #TYPE_PERCENTAGE} / {@link #TYPE_FIXED}),
 * whereas the pricing-engine class uses a type-safe {@code Coupon.Type} enum; the (later)
 * orchestration layer performs the explicit {@code String}&nbsp;&harr;&nbsp;{@code enum}
 * mapping when handing validated coupons to the pricing engine. Because the two classes
 * are namespaced, they coexist without a binary-name collision on the order-service
 * classpath.</p>
 *
 * <p>The {@code value} is a {@link BigDecimal} for monetary and percentage precision,
 * consistent with the pricing engine's BigDecimal-based math. This class holds data plus
 * self-contained validity/usage predicates only; the actual discount arithmetic lives in
 * the pricing engine and the broader validation policy lives in {@code CouponValidator},
 * so no logic is duplicated. All invariants are enforced in the constructors, so an
 * invalid or contradictory coupon definition cannot be constructed and the code-based
 * {@link #equals(Object) equality} is always well defined (the code is never null).</p>
 *
 * <p>Plain in-memory Java object: no persistence, ORM, or framework annotations.</p>
 */
public final class Coupon {

    /** Discount {@link #getType() type} denoting a percentage-off coupon. */
    public static final String TYPE_PERCENTAGE = "PERCENTAGE";

    /** Discount {@link #getType() type} denoting a fixed-amount-off coupon. */
    public static final String TYPE_FIXED = "FIXED";

    /**
     * The single sentinel {@link #getUsageLimit() usageLimit} value denoting an
     * unlimited coupon (one that can never be exhausted). Any other permitted limit is a
     * strictly positive maximum number of redemptions; negative limits are rejected.
     */
    public static final int UNLIMITED_USAGE = 0;

    /** Upper bound (inclusive) for a {@link #TYPE_PERCENTAGE} coupon {@link #value}. */
    private static final BigDecimal MAX_PERCENTAGE = new BigDecimal("100");

    /** Coupon code identity (business key), e.g. {@code "SAVE10"}; never null or blank. */
    private final String code;

    /**
     * Discount type: {@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED}. Held as a
     * {@link String} to stay aligned with the pricing-engine {@code Coupon}.
     */
    private final String type;

    /**
     * Discount magnitude. For {@link #TYPE_PERCENTAGE} it is the percent to deduct
     * (e.g. {@code 10} means 10%, constrained to {@code [0, 100]}); for
     * {@link #TYPE_FIXED} it is the non-negative currency amount to deduct. Held as
     * {@link BigDecimal} for precision.
     */
    private final BigDecimal value;

    /** Inclusive start of the validity window; {@code null} means "no lower bound". */
    private final LocalDate validFrom;

    /** Inclusive end of the validity window; {@code null} means "never expires". */
    private final LocalDate validUntil;

    /** Maximum permitted redemptions; {@link #UNLIMITED_USAGE} ({@code 0}) means "unlimited". */
    private final int usageLimit;

    /** Number of redemptions already consumed; never negative. */
    private final int usageCount;

    /**
     * Full constructor. Enforces every invariant so an invalid or contradictory coupon
     * cannot be constructed. The {@code code} is normalized to a canonical form (trimmed
     * and upper-cased with {@link Locale#ROOT}); the {@code type} is likewise normalized
     * and must be one of the supported constants.
     *
     * @param code       the coupon code identity (business key); non-null, non-blank
     * @param type       the discount type ({@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED});
     *                   non-null
     * @param value      the discount magnitude (percentage or fixed amount); non-null,
     *                   {@code >= 0}, and {@code <= 100} when {@code type} is
     *                   {@link #TYPE_PERCENTAGE}
     * @param validFrom  inclusive start of validity, or {@code null} for no lower bound
     * @param validUntil inclusive end of validity, or {@code null} for no expiry; when
     *                   both bounds are present it must not precede {@code validFrom}
     * @param usageLimit maximum permitted redemptions; {@link #UNLIMITED_USAGE} for
     *                   unlimited, otherwise a strictly positive maximum; must not be
     *                   negative
     * @param usageCount redemptions already consumed; must be {@code >= 0} and must not
     *                   exceed {@code usageLimit} when the coupon is limited
     * @throws NullPointerException     if {@code code}, {@code type}, or {@code value} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code code} is blank; {@code type} is not a
     *                                  supported constant; {@code value} is negative or a
     *                                  {@code PERCENTAGE} value exceeds {@code 100}; the
     *                                  validity window is reversed; {@code usageLimit} is
     *                                  negative; {@code usageCount} is negative; or
     *                                  {@code usageCount} exceeds a positive
     *                                  {@code usageLimit}
     */
    public Coupon(String code, String type, BigDecimal value, LocalDate validFrom,
                  LocalDate validUntil, int usageLimit, int usageCount) {
        if (code == null) {
            throw new NullPointerException("code must not be null");
        }
        if (type == null) {
            throw new NullPointerException("type must not be null");
        }
        if (value == null) {
            throw new NullPointerException("value must not be null");
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        if (normalizedCode.isEmpty()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        String normalizedType = type.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_PERCENTAGE.equals(normalizedType) && !TYPE_FIXED.equals(normalizedType)) {
            throw new IllegalArgumentException(
                    "type must be " + TYPE_PERCENTAGE + " or " + TYPE_FIXED + ": " + type);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("value must not be negative: " + value);
        }
        if (TYPE_PERCENTAGE.equals(normalizedType) && value.compareTo(MAX_PERCENTAGE) > 0) {
            throw new IllegalArgumentException(
                    "PERCENTAGE value must not exceed 100: " + value);
        }
        if (validFrom != null && validUntil != null && validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException(
                    "validUntil (" + validUntil + ") must not precede validFrom (" + validFrom + ")");
        }
        if (usageLimit < 0) {
            throw new IllegalArgumentException("usageLimit must not be negative: " + usageLimit);
        }
        if (usageCount < 0) {
            throw new IllegalArgumentException("usageCount must not be negative: " + usageCount);
        }
        if (usageLimit > 0 && usageCount > usageLimit) {
            throw new IllegalArgumentException(
                    "usageCount (" + usageCount + ") must not exceed usageLimit (" + usageLimit + ")");
        }
        this.code = normalizedCode;
        this.type = normalizedType;
        this.value = value;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.usageLimit = usageLimit;
        this.usageCount = usageCount;
    }

    /**
     * Convenience constructor for a not-yet-redeemed coupon (usage count {@code 0}).
     * Enforces the same invariants as the full constructor.
     *
     * @param code       the coupon code identity (business key); non-null, non-blank
     * @param type       the discount type ({@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED});
     *                   non-null
     * @param value      the discount magnitude (percentage or fixed amount); non-null,
     *                   {@code >= 0}, and {@code <= 100} when {@code PERCENTAGE}
     * @param validFrom  inclusive start of validity, or {@code null} for no lower bound
     * @param validUntil inclusive end of validity, or {@code null} for no expiry
     * @param usageLimit maximum permitted redemptions; {@link #UNLIMITED_USAGE} for
     *                   unlimited, otherwise a strictly positive maximum; must not be
     *                   negative
     * @throws NullPointerException     if {@code code}, {@code type}, or {@code value} is
     *                                  {@code null}
     * @throws IllegalArgumentException if any invariant enforced by the full constructor
     *                                  is violated
     */
    public Coupon(String code, String type, BigDecimal value, LocalDate validFrom,
                  LocalDate validUntil, int usageLimit) {
        this(code, type, value, validFrom, validUntil, usageLimit, 0);
    }

    /**
     * Returns the normalized coupon code identity (business key).
     *
     * @return the coupon code (trimmed, upper-cased); never {@code null} or blank
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the discount type.
     *
     * @return {@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED}
     */
    public String getType() {
        return type;
    }

    /**
     * Returns the discount magnitude (a percentage or a fixed currency amount, per
     * {@link #getType()}).
     *
     * @return the discount value as a {@link BigDecimal}
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * Returns the inclusive start of the validity window.
     *
     * @return the start date, or {@code null} if the coupon has no lower bound
     */
    public LocalDate getValidFrom() {
        return validFrom;
    }

    /**
     * Returns the inclusive end of the validity window.
     *
     * @return the end date, or {@code null} if the coupon never expires
     */
    public LocalDate getValidUntil() {
        return validUntil;
    }

    /**
     * Returns the maximum permitted redemptions.
     *
     * @return the usage limit ({@link #UNLIMITED_USAGE} means unlimited)
     */
    public int getUsageLimit() {
        return usageLimit;
    }

    /**
     * Returns the number of redemptions already consumed.
     *
     * @return the usage count (never negative)
     */
    public int getUsageCount() {
        return usageCount;
    }

    /**
     * Tests whether this coupon is active on the supplied date, i.e. {@code asOf} falls
     * within the inclusive {@code [validFrom, validUntil]} window. A {@code null} bound is
     * treated as open-ended (no lower bound and/or no expiry), so a coupon with both
     * bounds {@code null} is always within its window.
     *
     * @param asOf the date to test against (typically {@code LocalDate.now()});
     *             must not be {@code null}
     * @return {@code true} if the coupon is within its validity window on {@code asOf}
     * @throws NullPointerException if {@code asOf} is {@code null}
     */
    public boolean isWithinValidityWindow(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (validFrom != null && asOf.isBefore(validFrom)) {
            return false;
        }
        if (validUntil != null && asOf.isAfter(validUntil)) {
            return false;
        }
        return true;
    }

    /**
     * Tests whether this coupon has reached or exceeded its redemption limit. A
     * {@link #getUsageLimit() usageLimit} of {@link #UNLIMITED_USAGE} denotes an unlimited
     * coupon that can never be exhausted.
     *
     * @return {@code true} if the usage limit is exceeded
     */
    public boolean isUsageLimitExceeded() {
        return usageLimit > UNLIMITED_USAGE && usageCount >= usageLimit;
    }

    /**
     * Value equality based on the coupon {@link #getCode() code}, which is the business
     * identity of a coupon. The code is guaranteed non-null, so equality is always well
     * defined.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Coupon} with an equal code
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Coupon)) {
            return false;
        }
        return code.equals(((Coupon) o).code);
    }

    /**
     * Returns a hash code derived from the coupon {@link #getCode() code}, consistent
     * with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return code.hashCode();
    }

    /**
     * Returns a concise, human-readable description of this coupon for logging and
     * debugging. The format is not part of the API contract and may change.
     *
     * @return a string representation of this coupon
     */
    @Override
    public String toString() {
        return "Coupon{code=" + code + ", type=" + type + ", value=" + value
                + ", validFrom=" + validFrom + ", validUntil=" + validUntil
                + ", usageLimit=" + usageLimit + ", usageCount=" + usageCount + "}";
    }

}
