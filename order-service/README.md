# Order Service

Handles:

- Create order
- Update order (planned — beyond status handling; out of scope for this feature)
- Cancel order (planned — out of scope for this feature; no `CANCELLED` state)
- Authoritative server-side coupon validation
- Order status tracking: CREATED → CONFIRMED → DELIVERED (guarded transitions)
- Status-change notifications (decoupled trigger)

## Status at this checkpoint

**Delivered foundation:** the guarded `OrderStatus` lifecycle enum (package
`com.healthcare.order`; `CREATED → CONFIRMED → DELIVERED` with every invalid
transition rejected), the validation-side `Coupon` model (validated invariants,
`BigDecimal` magnitude), the Maven build (Java 21, JUnit 5 + Surefire), and the
baseline `createOrder()` entry point.

**Planned (not yet implemented):** the `CouponValidator`, the `Order` aggregate,
the `OrderService` orchestration (coupon validation + discount application +
status transitions), the notification-trigger wiring, and the JUnit tests
(`OrderServiceTest`, `CouponValidatorTest`). The sections below describe that
planned behavior as the module's contract.

## Coupon Validation API (planned)

Coupon validation is authoritative and server-side; the UI is never trusted to
approve a code. The planned `CouponValidator.validate(code)` will check code
existence, the validity window/expiry, and the usage limit, and return a
normalized result carrying validity, a reason, and discount metadata. Multiple
coupons are supported and stack deterministically — the pricing-engine computes
the combined (stacked) discount, floored at zero so a total can never go negative.

## Order Status Handling

The delivered `OrderStatus` enum models a guarded lifecycle:
`CREATED → CONFIRMED → DELIVERED`. Only the two adjacent forward transitions are
permitted; every other movement (a skip, a backward move, a self-transition, or
any transition out of the terminal `DELIVERED` state) is rejected. The planned
`OrderService.updateStatus()` will drive these transitions and, on every
successful change, fire a notification through a decoupled trigger — implemented
by the `notification-service` module and wired in from outside this module. Order,
coupon, and status data are held in memory (no persistence) for this feature.

## Build & Test

Builds with Maven (Java 21); production sources live under `src/main/java` in the
`com.healthcare.order` package. The **planned** JUnit 5 tests will live under
`src/test/java/` (`OrderServiceTest`, `CouponValidatorTest`).
