# Pricing Engine

Handles:

- Coupon-driven discount calculation
- Multi-coupon deterministic stacking (caller list order, floored at zero)
- Price calculation (planned — not yet implemented)
- Tax calculation (planned — not yet implemented)

## Status at this checkpoint

**Delivered:** the discount-side `com.healthcare.pricing.Coupon` value object (a
validated `code`/`type`/`value` triple with a `BigDecimal` magnitude), the
coupon-aware, multi-coupon stacking engine (`CouponDiscountCalculator`), the
coupon-aware `DiscountCalculator.calculate(BigDecimal, List<Coupon>)` overload,
the retained legacy `calculate(double)` baseline, the Maven build (Java 21,
JUnit 5 + Surefire), and the `DiscountCalculatorTest` suite. The `mvn test`
build is green.

**Not yet implemented (out of scope for this feature):** standalone price
calculation and tax calculation. This feature covers only coupon-driven
discounting; the sections below describe the delivered discount behavior.

## Coupon discount calculation

The engine accepts a `price` plus a collection of validated coupons and returns
the discounted total (the amount payable after all coupons are applied). Each
coupon carries a `code`, a `type` (percentage or fixed-amount), and a `value`: a
`PERCENTAGE` coupon reduces the running subtotal by its `value` percent, while a
`FIXED` coupon subtracts its `value` directly.

All monetary math uses `BigDecimal` with `RoundingMode.HALF_UP` at a fixed scale
of two decimal places, so results are free of the binary floating-point rounding
errors that plague `double`-based money. The original `calculate(double price)`
entry point is retained unchanged (a fixed 10% reduction) for backward
compatibility.

## Multiple coupons (stacking)

Several coupons may be applied to a single order at the same time. They stack
**deterministically in the exact order of the supplied list**: each coupon is
applied in turn against a *running subtotal* left by the previous coupon
(compounding), not against the original price.

The engine does **not** sort or reorder the coupons — **the caller controls the
order**. Because a list has a stable iteration order, the same list always
produces the same total (the result is deterministic); however, a *different*
ordering of the *same* coupons may produce a different total, so stacking is
order-dependent. For example, against a price of `100.00`:

- `[10% off, then $5 off]` → `100.00 → 90.00 → 85.00`
- `[$5 off, then 10% off]` → `100.00 → 95.00 → 85.50`

After each coupon is applied, the running subtotal is **floored at zero** — a
discount can never produce a negative price. The base price is likewise floored
at zero before any coupon runs, so the returned total is always `>= 0`, including
on the no-coupon path (an empty, `null`, or all-skipped coupon list).

## Build & tests

Built with Maven (Java 21); production sources live under `src/main/java` in the
`com.healthcare.pricing` package. The JUnit 5 discount tests live under
`src/test/java/` (`DiscountCalculatorTest`, in the default/unnamed package,
referencing the production classes via explicit `com.healthcare.pricing`
imports). They cover the no-coupon baseline, the legacy `double` path, single
percentage and fixed coupons, multiple stacked coupons in list order, the
zero-floor cases, and the negative-input-price floor — all with money computed
via `BigDecimal` / `RoundingMode.HALF_UP`.
