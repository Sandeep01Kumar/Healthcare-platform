# Healthcare Platform

Handles:

- Coupon validation and coupon-driven discounting across the order lifecycle
- Order status tracking through `CREATED` → `CONFIRMED` → `DELIVERED`
- Order status-change notifications
- Customer-facing UI for coupons and order tracking

An order-commerce system composed of coordinated modules that add coupon-driven
discounting and end-to-end order tracking to the order lifecycle. The `Handles:`
list above is the platform's charter — the capabilities the finished feature
provides — not a claim that every capability is fully wired yet (see **Status**).

## Status: foundation delivered, end-to-end behavior planned

This checkpoint delivers the **foundation** for the feature, not its complete
end-to-end behavior:

- **Delivered now:** the shared coupon domain model (with validated invariants)
  in both Java modules; the `OrderStatus` lifecycle enum with guarded
  transitions; the transport-agnostic notification library; the customer-ui wire
  contract and HTTP client; and the Maven/npm build-and-test scaffolding for
  every module.
- **Planned (not yet implemented):** the `order-service` coupon-validation API
  endpoint and status orchestration; the multi-coupon stacking discount in
  `order-service/pricing-engine`; the concrete notification transport and its
  wiring to the status-transition producer; and the customer-ui components and
  pages. The **Features** and **Architecture** sections below describe this
  target behavior; items are called out as planned where they are not yet built.

Each module's own README states precisely what it delivers today versus what it
plans.

## Repository structure and intended pull requests

This repository is a **single, flat Git repository**. The feature was specified
as a multi-repository / Git-submodule design, but no submodules are wired in this
checkout: `order-service` and `order-service/pricing-engine` are ordinary
directories, and `customer-ui` and `notification-service` are top-level
directories rather than separate repositories. There are therefore **no physical
submodules, no per-repository branches, and no separate pull requests** here; the
intended multi-module decomposition is realized as directories within this one
repository.

The intended decomposition — preserved as guidance — is five pull requests, one
per module, created in submodule dependency order
`pricing-engine → order-service → healthcare-platform`:

| Intended repository | Intended PR title |
| --- | --- |
| `pricing-engine` | Add coupon calculation and discount tests |
| `order-service` | Add coupon validation API and order status handling |
| `customer-ui` | Add coupon input and order tracking UI |
| `notification-service` | Add order status change notifications |
| `healthcare-platform` | Update submodule references and integrate all changes |

## Modules

- [`customer-ui`](./customer-ui/README.md) — React (Vite) single-page app.
  Delivered: the order-service API client and wire contract plus build
  scaffolding; planned: the coupon input field (multi-coupon), order tracking
  page, and current order status display.
- [`order-service`](./order-service/README.md) — Java service. Delivered: the
  coupon domain model and the `CREATED`/`CONFIRMED`/`DELIVERED` `OrderStatus`
  enum with transition guards; planned: the authoritative server-side coupon
  validation API and the order status management that orchestrates it.
- [`order-service/pricing-engine`](./order-service/pricing-engine/README.md) —
  Java module (nested). Delivered: the coupon value object and the baseline
  `calculate(double)` entry point; planned: coupon discount calculation with
  deterministic multi-coupon stacking.
- [`notification-service`](./notification-service/README.md) — Node service.
  Delivered: the transport-agnostic notification library that emits a
  notification when handed an order status change; planned: the concrete
  transport and the wiring to the order-service status-transition producer.

## Features

### Coupons

**Target behavior:** a customer submits one or more coupon codes; `order-service`
validates them authoritatively — server-side, never trusting the client — and
`order-service/pricing-engine` computes the resulting discount, supporting
multiple simultaneously applied coupons that stack deterministically and are
floored at zero so a discount can never produce a negative price.

**Delivered so far:** the shared coupon domain model (with validated invariants)
and the customer-ui wire contract that treats coupon validity as
server-authoritative. **Planned:** the validation API endpoint, the multi-coupon
stacking discount, and the coupon-input UI.

### Order Tracking

**Target behavior:** an order moves through the guarded lifecycle
`CREATED → CONFIRMED → DELIVERED`; invalid transitions are rejected, the current
status is surfaced to the customer, and a notification is emitted on every status
change.

**Delivered so far:** the `OrderStatus` enum with guarded transitions and the
notification library that sends a message when handed a status change.
**Planned:** wiring the lifecycle into `OrderService`, surfacing status through
the UI, and connecting the notification library to a concrete transport.

## Architecture

Modules are built and integrated in dependency order
`pricing-engine → order-service → healthcare-platform`. The discount contract
must be stable before `order-service` consumes it, and the UI and notification
integrations layer on top. The `pricing-engine` module publishes the local Maven
artifact `com.healthcare:pricing-engine`, which `order-service` depends on.

Intended runtime flow (the target once the planned seams are wired):

- `customer-ui` → `order-service`: validate coupons and read order status.
- `order-service` → `order-service/pricing-engine`: apply validated coupons to
  compute the discounted total.
- `order-service` → `notification-service`: trigger a notification on each status
  change.

## Out of scope

Persistence, authentication/authorization, tax/price calculation, order
cancellation, and concrete notification transports (email/SMS/push) are not part
of this feature. Physical multi-repository separation (separate Git repositories,
real submodules, and per-repository branches/PRs) is likewise out of scope for
this single-repository checkout.
