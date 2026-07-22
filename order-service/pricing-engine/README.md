# Pricing Engine

Handles:

- Discount calculation (coupon-driven)
- Coupon discount stacking (multiple coupons, deterministic order, floored at zero)
- Price calculation (planned — not yet implemented)
- Tax calculation (planned — not yet implemented)

## Coupon discount calculation

The engine accepts a `price` plus a collection of validated coupons and returns
the discounted total. Each coupon carries a `code`, a `type` (percentage or
fixed-amount), and a `value`: a percentage coupon reduces the subtotal by its
`value` percent, while a fixed-amount coupon subtracts its `value` directly.

All monetary math uses `BigDecimal` with `RoundingMode.HALF_UP`, so results are
free of the binary floating-point rounding errors that plague `double`-based
money. The original `calculate(double price)` entry point is retained for
backward compatibility.

## Multiple coupons (stacking)

Several coupons may be applied to a single order at the same time. They stack
**deterministically**: coupons are applied in a fixed order against a running
subtotal, so the same set of coupons always produces the same total regardless
of the order in which they were submitted. The discounted total is **floored at
zero** — a discount can never produce a negative price. A maximum-discount cap
and per-coupon mutual-exclusivity flags may optionally be enforced.

## Build & tests

Built with Maven (Java 21). JUnit 5 discount tests live under `src/test/java/`
(`DiscountCalculatorTest`) and cover the no-coupon baseline, a single coupon,
multiple stacked coupons, and the zero-floor case. Money is computed with
`BigDecimal` / `RoundingMode.HALF_UP`.
