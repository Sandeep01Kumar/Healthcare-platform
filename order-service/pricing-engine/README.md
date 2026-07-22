# Pricing Engine

Handles:

- Coupon-driven discount calculation
- Multi-coupon deterministic stacking (floored at zero)
- Price calculation (planned — not yet implemented)
- Tax calculation (planned — not yet implemented)

## Status at this checkpoint

**Delivered foundation:** the discount-side `com.healthcare.pricing.Coupon` value
object (a validated `code`/`type`/`value` triple with a `BigDecimal` magnitude),
the Maven build (Java 21, JUnit 5 + Surefire), and the baseline
`calculate(double)` entry point.

**Planned (not yet implemented):** the coupon-aware discount calculation, the
multi-coupon stacking logic, and the `DiscountCalculatorTest` suite. The sections
below describe that planned behavior as the module's contract; they are not a
claim that the calculation code already exists.

## Coupon discount calculation (planned)

When implemented, the engine will accept a `price` plus a collection of validated
coupons and return the discounted total. Each coupon carries a `code`, a `type`
(percentage or fixed-amount), and a `value`: a percentage coupon reduces the
subtotal by its `value` percent, while a fixed-amount coupon subtracts its `value`
directly.

All monetary math will use `BigDecimal` with `RoundingMode.HALF_UP`, so results
are free of the binary floating-point rounding errors that plague `double`-based
money. The original `calculate(double price)` entry point is retained for
backward compatibility.

## Multiple coupons (stacking) (planned)

Several coupons may be applied to a single order at the same time. They stack
**deterministically** using a fixed, canonical order so the same set of coupons
always produces the same total regardless of submission order:

1. **PERCENTAGE coupons are applied before FIXED coupons.**
2. Within the same type, coupons are ordered by their **normalized (trimmed,
   upper-cased) `code` in ascending lexicographic order** as the tie-breaker.

Coupons are then applied in that order against a running subtotal. The discounted
total is **floored at zero** — a discount can never produce a negative price.

## Build & tests

Built with Maven (Java 21); production sources live under `src/main/java` in the
`com.healthcare.pricing` package. The **planned** JUnit 5 discount tests will live
under `src/test/java/` (`DiscountCalculatorTest`) and will cover the no-coupon
baseline, a single coupon, multiple stacked coupons, and the zero-floor case, with
money computed via `BigDecimal` / `RoundingMode.HALF_UP`.
