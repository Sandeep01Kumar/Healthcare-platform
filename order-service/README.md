# Order Service

Handles:

- Create order
- Authoritative server-side coupon validation
- Order status tracking: CREATED → CONFIRMED → DELIVERED (guarded transitions)
- Status-change notifications (decoupled trigger)

## Coupon Validation API

Coupon validation is authoritative and server-side; the UI is never trusted to approve a code. `CouponValidator.validate(code)` checks code existence, the validity window/expiry, and the usage limit, and returns a normalized result carrying validity, a reason, and discount metadata. Multiple coupons are supported and stack deterministically — the pricing-engine computes the combined (stacked) discount, floored at zero so a total can never go negative.

## Order Status Handling

An order moves through a guarded lifecycle: `CREATED → CONFIRMED → DELIVERED`. Transitions are validated and any invalid transition is rejected. Every successful status change fires a notification through a decoupled trigger, implemented by the `notification-service` module and wired in from outside this module. Order, coupon, and status data are held in memory (no persistence) for this feature.

## Build & Test

Builds with Maven (Java 21) and ships JUnit 5 tests under `src/test/java/` (`OrderServiceTest`, `CouponValidatorTest`).
