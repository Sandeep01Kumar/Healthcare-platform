# Healthcare Platform

Handles:

- Coupon validation and coupon-driven discounting across the order lifecycle
- Order status tracking through `CREATED` → `CONFIRMED` → `DELIVERED`
- Order status-change notifications
- Customer-facing UI for coupons and order tracking

An order-commerce system composed of coordinated modules that add coupon-driven
discounting and end-to-end order tracking to the order lifecycle. Every
capability in the `Handles:` list is delivered and covered by tests (see
**Status**).

## Status: delivered end-to-end (in-memory, unauthenticated)

This checkpoint delivers the **complete end-to-end feature**: a customer can
apply one or more coupons in the UI, create an order at a price, have the coupons
validated and redeemed authoritatively by `order-service` and priced by
`order-service/pricing-engine`, track the order's status, and have a notification
emitted on every status change through a concrete Java → Node HTTP bridge.

- **Delivered now (implemented and tested):**
  - `order-service/pricing-engine` — deterministic multi-coupon stacking discount
    with `BigDecimal` money math (2-dp, HALF_UP, floored at zero); the legacy
    `calculate(double)` entry point is preserved (`price * 0.9`).
  - `order-service` — the authoritative server-side coupon validator with atomic
    validate-and-redeem, the guarded `OrderStatus` lifecycle, a per-id in-memory
    order repository, a transaction outbox for status-change events, and a
    **JDK-only HTTP API** (`POST /coupons/validate`, `POST /orders`,
    `GET /orders/{id}`, `POST /orders/{id}/status`) with a CORS/OPTIONS layer and
    a JSON error envelope. The legacy no-argument `createOrder()` is preserved
    (`"Order Created"`).
  - `notification-service` — a Node HTTP receiver (`POST /notifications`) that
    consumes status-change events and delivers them through a pluggable transport,
    with abort/fencing, stable event ids, and correlated logs.
  - `customer-ui` — a React (Vite) single-page app: the multi-coupon input, the
    order-creation flow that submits applied coupons to `POST /orders`, the
    `/orders/:id` tracking page with a status stepper, and the status badge — all
    driven by the shared wire contract and HTTP client, with a real Vitest suite.
- **Genuine remaining gaps (intentionally out of scope — see "Out of scope"):**
  durable persistence (state is in memory and does not survive a restart),
  authentication/authorization (the HTTP API is currently unauthenticated), and a
  concrete downstream notification vendor (email/SMS/push); the notification
  transport defaults to a console sink.

Each module's own README states precisely what it delivers and its open
limitations.

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
  Delivered: the multi-coupon input, the order-creation flow that submits applied
  coupons, the `/orders/:id` tracking page with a `CREATED → CONFIRMED →
  DELIVERED` stepper and status badge, the shared wire contract and one-way HTTP
  client, and a real Vitest suite. Requires a running `order-service` to function.
- [`order-service`](./order-service/README.md) — Java 21 service. Delivered: the
  authoritative server-side coupon validation and redemption, the guarded
  `OrderStatus` lifecycle and order orchestration, per-id in-memory storage, the
  JDK-only HTTP API for the UI, and the Java producer half of the Java → Node
  notification bridge.
- [`order-service/pricing-engine`](./order-service/pricing-engine/README.md) —
  Java module (nested). Delivered: the coupon value object and deterministic
  multi-coupon stacking discount with `BigDecimal` money math; the baseline
  `calculate(double)` entry point is preserved.
- [`notification-service`](./notification-service/README.md) — Node service.
  Delivered: the HTTP receiver that consumes `order-service` status-change events
  and the transport-agnostic notification core that emits a notification on each
  change; a concrete downstream vendor transport remains out of scope.

## Features

### Coupons

A customer submits one or more coupon codes; `order-service` validates them
authoritatively — server-side, never trusting the client — and redeems each
unique (canonical) code atomically so a usage-limited coupon can never be
over-redeemed. `order-service/pricing-engine` computes the resulting discount,
supporting multiple simultaneously applied coupons that stack deterministically
and are floored at zero so a discount can never produce a negative price. The
customer-ui coupon input treats coupon validity as server-authoritative and
submits the applied codes with order creation.

### Order Tracking

An order moves through the guarded lifecycle `CREATED → CONFIRMED → DELIVERED`;
invalid transitions are rejected. `OrderService` records each status change to a
transaction outbox **before** the transition commits, then delivers it, so a
transport failure never loses the event (it is retried). The current status is
surfaced to the customer on the `/orders/:id` tracking page, and a notification
is emitted on every status change via the Java → Node HTTP bridge.

## Architecture

Modules are built and integrated in dependency order
`pricing-engine → order-service → healthcare-platform`. The discount contract is
stable before `order-service` consumes it, and the UI and notification
integrations layer on top. The `pricing-engine` module publishes the local Maven
artifact `com.healthcare:pricing-engine`, which `order-service` depends on.

Runtime flow (delivered):

- `customer-ui` → `order-service` (HTTP): validate coupons (`POST
  /coupons/validate`), create an order with applied coupons (`POST /orders`), read
  an order (`GET /orders/{id}`), and advance status (`POST /orders/{id}/status`).
- `order-service` → `order-service/pricing-engine`: apply validated coupons to
  compute the stacked discounted total.
- `order-service` → `notification-service` (HTTP): the Java `HttpNotificationTrigger`
  producer serializes each committed status change to an event and POSTs it to the
  Node receiver (`POST /notifications`), which delivers it through the configured
  transport. The two runtimes are decoupled — wired only by the serialized HTTP
  event, with no code-level import between them.

### Build and run

- Java modules: build `pricing-engine` first so the local Maven artifact is
  published, then `order-service`:
  `mvn -B -ntp -f order-service/pricing-engine/pom.xml clean install` then
  `mvn -B -ntp -f order-service/pom.xml clean install`. Start the API with the
  `order-service` application entry point (`OrderServiceApplication`).
- Node modules: `notification-service` — `npm start` binds the receiver;
  `customer-ui` — `npm install`, then `npm run dev` (or `npm run build` +
  `npm run preview`), and `npm test` for the Vitest suite.

## Out of scope

Durable persistence, authentication/authorization, tax/price calculation, order
cancellation (no `CANCELLED` state), and concrete downstream notification vendor
transports (email/SMS/push) are not part of this feature. Physical
multi-repository separation (separate Git repositories, real submodules, and
per-repository branches/PRs) is likewise out of scope for this single-repository
checkout.
