package com.healthcare.pricing;

/**
 * Baseline discount calculator for the pricing engine.
 *
 * <p>This class provides the original, coupon-agnostic entry point
 * {@link #calculate(double)}, which applies a fixed 10% reduction. It is retained
 * unchanged for backward compatibility; the coupon-aware, multi-coupon (stacked)
 * discount logic that supersedes this fixed reduction is layered on top in the
 * pricing engine's coupon discount calculation (see the module README) without
 * removing or altering this method's signature or behavior.</p>
 */
public class DiscountCalculator {

    /**
     * Applies the baseline fixed 10% discount to the supplied price.
     *
     * @param price the original price
     * @return the price after a fixed 10% reduction ({@code price * 0.9})
     */
    public double calculate(double price) {
        return price * 0.9;
    }

}
