package com.healthcare.pricing;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Discount-side coupon value object consumed by the pricing-engine discount math.
 *
 * <p>This immutable value object is the representation accepted by
 * {@code CouponDiscountCalculator} and by the coupon-aware overload of
 * {@code DiscountCalculator}; both operate over collections of {@code Coupon} to
 * compute single- and multi-coupon (stacked) discounts. It carries ONLY the data the
 * discount calculation needs — a {@code code} identity, a discount {@link Type}, and a
 * magnitude — and deliberately omits validity-window and usage-limit concerns, which
 * belong to the order-service validation-side coupon model.</p>
 *
 * <p>It lives in the {@code com.healthcare.pricing} package and is a DISTINCT class from
 * the order-service validation-side {@code com.healthcare.order.Coupon}. The two share a
 * conceptual contract — the same {@code code} identity and compatible {@code type}/
 * {@code value} semantics — but they are separate types in separate Maven modules/jars.
 * The order-service class expresses the discount {@code type} as a {@link String}
 * ({@code "PERCENTAGE"}/{@code "FIXED"}), whereas this class uses the type-safe
 * {@link Type} enum; the (later) orchestration layer performs the explicit
 * {@code String}&nbsp;&harr;&nbsp;{@link Type} mapping when handing validated coupons to
 * the pricing engine. Because the two classes are namespaced, they coexist without
 * conflict on the order-service compile/runtime classpath.</p>
 *
 * <p><b>Value semantics.</b> The meaning of {@link #getValue()} depends on
 * {@link #getType()}:</p>
 * <ul>
 *   <li>{@link Type#PERCENTAGE} — {@code value} is a percentage expressed in points,
 *       constrained to the range {@code [0, 100]} (for example {@code new BigDecimal("10")}
 *       means 10% off). The discount amount applied to the running subtotal is
 *       {@code subtotal * value / 100}.</li>
 *   <li>{@link Type#FIXED} — {@code value} is a non-negative absolute monetary amount
 *       subtracted directly from the running subtotal (for example
 *       {@code new BigDecimal("5.00")} means 5 currency units off).</li>
 * </ul>
 *
 * <p>All fields are {@code final}, so instances are immutable and safe to share across
 * threads. {@link BigDecimal} is used for the magnitude to keep monetary arithmetic
 * exact rather than subject to binary floating-point rounding error. All invariants are
 * enforced in the constructor, so an invalid discount definition cannot be constructed.</p>
 */
public class Coupon {

    /**
     * The kind of discount a {@link Coupon} represents. The constant selects how
     * {@link Coupon#getValue()} is interpreted by the discount calculation.
     */
    public enum Type {

        /** Percentage discount: {@code value} is percentage points in {@code [0, 100]}; amount = {@code subtotal * value / 100}. */
        PERCENTAGE,

        /** Fixed discount: {@code value} is a non-negative absolute monetary amount subtracted from the subtotal. */
        FIXED
    }

    /** Upper bound (inclusive) for a {@link Type#PERCENTAGE} coupon {@link #value}. */
    private static final BigDecimal MAX_PERCENTAGE = new BigDecimal("100");

    /** The coupon code identity (for example {@code "SAVE10"}); the stable key shared with the validation-side coupon. */
    private final String code;

    /** The discount kind, selecting how {@link #value} is applied. */
    private final Type type;

    /**
     * The discount magnitude. For {@link Type#PERCENTAGE} this is a percentage in points
     * ({@code [0, 100]}); for {@link Type#FIXED} this is a non-negative absolute monetary
     * amount.
     */
    private final BigDecimal value;

    /**
     * Creates an immutable discount-side coupon, enforcing every invariant so an invalid
     * discount definition is impossible to construct.
     *
     * <p>The {@code code} is normalized to a canonical form (trimmed and upper-cased with
     * {@link Locale#ROOT}) so it matches the identity used by the validation-side coupon.</p>
     *
     * @param code  the coupon code identity; must be non-null and non-blank
     * @param type  the discount kind ({@link Type#PERCENTAGE} or {@link Type#FIXED});
     *              must be non-null
     * @param value the discount magnitude, interpreted per {@code type} (percentage
     *              points for {@code PERCENTAGE}, an absolute amount for {@code FIXED});
     *              must be non-null and {@code >= 0}, and {@code <= 100} for
     *              {@code PERCENTAGE}
     * @throws NullPointerException     if {@code code}, {@code type}, or {@code value} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code code} is blank, {@code value} is
     *                                  negative, or a {@code PERCENTAGE} {@code value}
     *                                  exceeds {@code 100}
     */
    public Coupon(String code, Type type, BigDecimal value) {
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
        if (value.signum() < 0) {
            throw new IllegalArgumentException("value must not be negative: " + value);
        }
        if (type == Type.PERCENTAGE && value.compareTo(MAX_PERCENTAGE) > 0) {
            throw new IllegalArgumentException(
                    "PERCENTAGE value must not exceed 100: " + value);
        }
        this.code = normalizedCode;
        this.type = type;
        this.value = value;
    }

    /**
     * Returns the normalized coupon code identity.
     *
     * @return the coupon code (trimmed, upper-cased)
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
