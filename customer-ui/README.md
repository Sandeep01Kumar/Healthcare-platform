# Customer UI

Handles:

- Coupon input (supports multiple coupons)
- Order tracking page
- Order status display

## Status: foundation delivered, UI planned

This module currently ships the **API and build foundation** for the customer UI.
The visual React application (entry HTML, components, and pages) is **planned** and
is not part of this checkpoint. The `Handles:` list above is the module's charter —
what the finished module will do — not a claim that every item is implemented yet.

**Delivered now:**

- `src/api/orderServiceContract.js` — the authoritative wire contract with the
  `order-service` (endpoints, methods, order-status vocabulary, input bounds,
  response DTO shapes, and the error-envelope shape).
- `src/api/orderServiceClient.js` — the one-way HTTP client that calls the
  `order-service` using that contract. It validates request inputs and the
  structure of responses, enforces a per-request timeout, and surfaces server
  errors — but never decides coupon validity itself.
- Build/test scaffolding: `package.json` (pinned dependencies), `vite.config.js`
  (React plugin plus a dev proxy), and this README.

**Planned (not yet implemented):**

- The Vite entry `index.html` and the `src/main.jsx` React bootstrap it references.
- `src/App.jsx` and routing.
- `src/components/CouponInput.jsx` (coupon input with per-coupon removal).
- `src/components/OrderStatusBadge.jsx` (active status label).
- `src/pages/OrderTrackingPage.jsx` (route `/orders/:id` with a
  `CREATED → CONFIRMED → DELIVERED` stepper highlighting the current stage).
- The one-way UI data flow from those components through the delivered API client.

## Wire contract and dev proxy

`src/api/orderServiceContract.js` is the single source of truth for the HTTP
surface, and both the client and the (planned) `order-service` HTTP layer
implement against it:

- `POST /coupons/validate` — body `{ code }`; returns a normalized coupon verdict
  (`{ valid, reason, discount? }`). Coupon validity is **authoritative on the
  server**; the client returns the verdict verbatim.
- `GET /orders/{id}` — returns the order view, whose `status` is exactly one of
  `CREATED`, `CONFIRMED`, or `DELIVERED`.
- On a non-2xx response the server returns a JSON error envelope
  (`{ error: { code, message, details? } }`), which the client surfaces in the
  thrown `Error`.

Because the SPA and the backend may run on different origins, `vite.config.js`
configures a **dev proxy** that forwards the `/coupons` and `/orders` path
prefixes to the order-service (default `http://localhost:8080`, overridable via
the `ORDER_SERVICE_PROXY_TARGET` environment variable). Using the proxy keeps API
calls same-origin in development and avoids CORS. In production the SPA is served
behind a reverse proxy, or the server sends the CORS headers documented in the
contract.

## Configuration

The `order-service` base URL is configurable via the `VITE_ORDER_SERVICE_URL`
environment variable. When unset it defaults to `http://localhost:8080`; setting
it to an empty string selects same-origin mode (relative paths forwarded by the
dev proxy). A non-empty value must be a valid absolute `http`/`https` URL or the
client throws at load.

## Tech & scripts

Built with React 19 and Vite 6 (Node 22.x). Dependency versions are pinned
exactly (`react`/`react-dom` `19.2.8`, `vite` `6.4.3`, `@vitejs/plugin-react`
`4.7.0`, `vitest` `2.1.9`) for reproducible installs.

- `npm run dev` — start the Vite dev server (requires the planned `index.html` entry)
- `npm run build` — production build (requires the planned `index.html` entry)
- `npm run preview` — preview the production build
- `npm test` — run the Vitest suite (added as UI components land)
