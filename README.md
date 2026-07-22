# Healthcare Platform

An order-commerce system composed of coordinated modules that add coupon-driven discounting and end-to-end order tracking to the order lifecycle.

Handles:

- Coupon validation and coupon-driven discounting across the order lifecycle
- Order status tracking through `CREATED` → `CONFIRMED` → `DELIVERED`
- Order status-change notifications
- Customer-facing UI for coupons and order tracking

## Modules

- [`customer-ui`](./customer-ui/README.md) — React (Vite) single-page app: coupon input field (multi-coupon), order tracking page, and current order status display.
- [`order-service`](./order-service/README.md) — Java service: authoritative server-side coupon validation API and order status management (`CREATED`/`CONFIRMED`/`DELIVERED`).
- [`order-service/pricing-engine`](./order-service/pricing-engine/README.md) — Java module (nested): coupon discount calculation with deterministic multi-coupon stacking.
- [`notification-service`](./notification-service/README.md) — Node service: sends a notification whenever an order's status changes.

## Features

### Coupons

A customer submits one or more coupon codes. `order-service` validates them authoritatively — server-side, never trusting the client. `order-service/pricing-engine` then computes the resulting discount, supporting multiple simultaneously applied coupons that stack deterministically and are floored at zero, so a discount can never produce a negative price.

### Order Tracking

An order moves through the guarded lifecycle `CREATED → CONFIRMED → DELIVERED`; invalid transitions are rejected. The current status is surfaced to the customer, and a notification is emitted on every status change.

## Architecture

Modules are built and integrated in dependency order: `pricing-engine → order-service → healthcare-platform`. The discount contract must be stable before `order-service` consumes it, and the UI and notification integrations layer on top.

Runtime flow:

- `customer-ui` → `order-service`: validate coupons and read order status.
- `order-service` → `order-service/pricing-engine`: apply validated coupons to compute the discounted total.
- `order-service` → `notification-service`: trigger a notification on each status change.

The intended multi-module decomposition is realized here as directories within this single repository.

## Out of scope

Persistence, authentication/authorization, tax/price calculation, order cancellation, and concrete notification transports (email/SMS/push) are not part of this feature.
