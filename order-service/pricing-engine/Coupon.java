import java.math.BigDecimal;

/**
 * Discount-side coupon value object consumed by the pricing-engine discount math.
 *
 * <p>This immutable value object is the representation accepted by
 * {@code CouponDiscountCalculator} and by the coupon-aware overload of
 * {@code DiscountCalculator}; both operate over collections of {@code Coupon} to
 * compute single- and multi-coupon (stacked) discounts. It carries ONLY the data the
 * discount calculation needs — a {@code code} identity, a discount {@link Type}, and a
 * magnitude — and deliberately omits validity-window and usage-limit concerns, which
 * belong to the order-service validation-side {@code Coupon} class.</p>
 *
 * <p>It shares a conceptual contract (the same {@code code} identity and compatible
 * {@code type}/{@code value} semantics) with that order-service-side {@code Coupon},
 * but the two are DISTINCT classes living in different modules/jars.</p>
 *
 * <p><b>Value semantics.</b> The meaning of {@link #getValue()} depends on
 * {@link #getType()}:</p>
 * <ul>
 *   <li>{@link Type#PERCENTAGE} — {@code value} is a percentage expressed in points,
 *       typically in the range {@code [0, 100]} (for example {@code new BigDecimal("10")}
 *       means 10% off). The discount amount applied to the running subtotal is
 *       {@code subtotal * value / 100}.</li>
 *   <li>{@link Type#FIXED} — {@code value} is an absolute monetary amount subtracted
 *       directly from the running subtotal (for example {@code new BigDecimal("5.00")}
 *       means 5 currency units off).</li>
 * </ul>
 *
 * <p>All fields are {@code final}, so instances are immutable and safe to share across
 * threads. {@link BigDecimal} is used for the magnitude to keep monetary arithmetic
 * exact rather than subject to binary floating-point rounding error.</p>
 */
public class Coupon {

    /**
     * The kind of discount a {@link Coupon} represents. The constant selects how
     * {@link Coupon#getValue()} is interpreted by the discount calculation.
     */
    public enum Type {

        /** Percentage discount: {@code value} is percentage points in {@code [0, 100]}; amount = {@code subtotal * value / 100}. */
        PERCENTAGE,

        /** Fixed discount: {@code value} is an absolute monetary amount subtracted from the subtotal. */
        FIXED
    }

    /** The coupon code identity (for example {@code "SAVE10"}); the stable key shared with the validation-side coupon. */
    private final String code;

    /** The discount kind, selecting how {@link #value} is applied. */
    private final Type type;

    /**
     * The discount magnitude. For {@link Type#PERCENTAGE} this is a percentage in points
     * ({@code [0, 100]}); for {@link Type#FIXED} this is an absolute monetary amount.
     */
    private final BigDecimal value;

    /**
     * Creates an immutable discount-side coupon.
     *
     * @param code  the coupon code identity
     * @param type  the discount kind ({@link Type#PERCENTAGE} or {@link Type#FIXED})
     * @param value the discount magnitude, interpreted per {@code type} (percentage
     *              points for {@code PERCENTAGE}, an absolute amount for {@code FIXED})
     */
    public Coupon(String code, Type type, BigDecimal value) {
        this.code = code;
        this.type = type;
        this.value = value;
    }

    /**
     * Returns the coupon code identity.
     *
     * @return the coupon code
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the discount kind.
     *
     * @return the coupon {@link Type}
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the discount magnitude, interpreted according to {@link #getType()}.
     *
     * @return the discount value
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * Returns a compact, human-readable representation for debugging and test output.
     *
     * @return a string of the form {@code Coupon{code=..., type=..., value=...}}
     */
    @Override
    public String toString() {
        return "Coupon{code=" + code + ", type=" + type + ", value=" + value + "}";
    }

}
