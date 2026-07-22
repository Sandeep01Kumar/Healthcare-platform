import java.math.BigDecimal;
import java.time.LocalDate;
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
 * <p>It shares a conceptual contract with the discount-side pricing-engine
 * {@code Coupon}: both identify a coupon by its {@code code} and describe the discount
 * with a {@code type}/{@code value} pair. The discount {@code type} is represented here
 * as a {@link String} (see {@link #TYPE_PERCENTAGE} and {@link #TYPE_FIXED}) so the two
 * package-less {@code Coupon} classes, which live in separate Maven modules, stay
 * trivially interchangeable across the module boundary.</p>
 *
 * <p>The {@code value} is a {@link BigDecimal} for monetary and percentage precision,
 * consistent with the pricing engine's BigDecimal-based math. This class holds data plus
 * self-contained validity/usage predicates only; the actual discount arithmetic lives in
 * the pricing engine and the broader validation policy lives in {@code CouponValidator},
 * so no logic is duplicated.</p>
 *
 * <p>Plain in-memory Java object: no persistence, ORM, or framework annotations.</p>
 */
public final class Coupon {

    /** Discount {@link #getType() type} denoting a percentage-off coupon. */
    public static final String TYPE_PERCENTAGE = "PERCENTAGE";

    /** Discount {@link #getType() type} denoting a fixed-amount-off coupon. */
    public static final String TYPE_FIXED = "FIXED";

    /** Coupon code identity (business key), e.g. {@code "SAVE10"}. */
    private final String code;

    /**
     * Discount type: {@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED}. Held as a
     * {@link String} to stay aligned with the pricing-engine {@code Coupon}.
     */
    private final String type;

    /**
     * Discount magnitude. For {@link #TYPE_PERCENTAGE} it is the percent to deduct
     * (e.g. {@code 10} means 10%); for {@link #TYPE_FIXED} it is the currency amount to
     * deduct. Held as {@link BigDecimal} for precision.
     */
    private final BigDecimal value;

    /** Inclusive start of the validity window; {@code null} means "no lower bound". */
    private final LocalDate validFrom;

    /** Inclusive end of the validity window; {@code null} means "never expires". */
    private final LocalDate validUntil;

    /** Maximum permitted redemptions; a value {@code <= 0} means "unlimited". */
    private final int usageLimit;

    /** Number of redemptions already consumed. */
    private final int usageCount;

    /**
     * Full constructor.
     *
     * @param code       the coupon code identity (business key)
     * @param type       the discount type ({@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED})
     * @param value      the discount magnitude (percentage or fixed amount)
     * @param validFrom  inclusive start of validity, or {@code null} for no lower bound
     * @param validUntil inclusive end of validity, or {@code null} for no expiry
     * @param usageLimit maximum permitted redemptions ({@code <= 0} means unlimited)
     * @param usageCount redemptions already consumed
     */
    public Coupon(String code, String type, BigDecimal value, LocalDate validFrom,
                  LocalDate validUntil, int usageLimit, int usageCount) {
        this.code = code;
        this.type = type;
        this.value = value;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.usageLimit = usageLimit;
        this.usageCount = usageCount;
    }

    /**
     * Convenience constructor for a not-yet-redeemed coupon (usage count {@code 0}).
     *
     * @param code       the coupon code identity (business key)
     * @param type       the discount type ({@link #TYPE_PERCENTAGE} or {@link #TYPE_FIXED})
     * @param value      the discount magnitude (percentage or fixed amount)
     * @param validFrom  inclusive start of validity, or {@code null} for no lower bound
     * @param validUntil inclusive end of validity, or {@code null} for no expiry
     * @param usageLimit maximum permitted redemptions ({@code <= 0} means unlimited)
     */
    public Coupon(String code, String type, BigDecimal value, LocalDate validFrom,
                  LocalDate validUntil, int usageLimit) {
        this(code, type, value, validFrom, validUntil, usageLimit, 0);
    }

    /**
     * Returns the coupon code identity (business key).
     *
     * @return the coupon code
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
     * @return the usage limit ({@code <= 0} means unlimited)
     */
    public int getUsageLimit() {
        return usageLimit;
    }

    /**
     * Returns the number of redemptions already consumed.
     *
     * @return the usage count
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
     * {@link #getUsageLimit() usageLimit} of {@code 0} or less denotes an unlimited
     * coupon that can never be exhausted.
     *
     * @return {@code true} if the usage limit is exceeded
     */
    public boolean isUsageLimitExceeded() {
        return usageLimit > 0 && usageCount >= usageLimit;
    }

    /**
     * Value equality based on the coupon {@link #getCode() code}, which is the business
     * identity of a coupon.
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
        return Objects.equals(code, ((Coupon) o).code);
    }

    /**
     * Returns a hash code derived from the coupon {@link #getCode() code}, consistent
     * with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(code);
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
