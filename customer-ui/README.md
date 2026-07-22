# Customer UI

Handles:

- Coupon input (supports multiple coupons)
- Order tracking page
- Order status display

## Overview

`customer-ui` is a React (Vite) single-page application. Data flows one way from
the UI through `src/api/orderServiceClient.js` to the `order-service` API for
coupon validation and order-status retrieval.

## Coupons

The coupon input submits code(s) to the `order-service` validation endpoint.
Multiple coupons are supported, and each applied coupon can be removed. Coupon
validation is authoritative on the server — the UI never decides validity
itself.

## Order tracking

The order tracking page (route `/orders/:id`) shows a stepper over
`CREATED → CONFIRMED → DELIVERED`, highlighting the current stage. An
`OrderStatusBadge` renders the active status wherever an order appears.

## Tech & scripts

Built with React 19 and Vite 6 (Node 22.x).

- `npm run dev` — start the Vite dev server
- `npm run build` — production build
- `npm run preview` — preview the production build
- `npm test` — run the Vitest suite

The `order-service` base URL is configurable via the `VITE_ORDER_SERVICE_URL`
environment variable, defaulting to a local development URL.
