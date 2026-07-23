# Customer UI

Handles:

- Coupon input (supports multiple coupons)
- Order tracking page
- Order status display

The full single-page application is delivered and covered by tests. Nothing in
the `Handles:` list above is deferred — the coupon input, the order-tracking
page, and the status display are all implemented against the `order-service`
HTTP contract.

Out of scope for this feature (intentionally not implemented): a persistence
layer (the backend keeps order/coupon state in memory), authentication (the
`order-service` HTTP API is currently unauthenticated — see "Known limitations"),
and any design system / component library (there is no Figma reference, so the UI
uses minimal inline styling only). See "Known limitations" below.

## What is delivered

This module is a React 19 + Vite 6 single-page app. Every file listed here is
implemented; there is no "planned but absent" behavior.

API layer (`src/api`):

- **`orderServiceContract.js`** — the single source of truth for the HTTP
  surface: the endpoint table (`POST /coupons/validate`, `POST /orders`,
  `GET /orders/{id}`), the `CREATED | CONFIRMED | DELIVERED` status vocabulary,
  request input bounds, the response DTO shapes, and the error-envelope shape. It
  also exports `canonicalizeCouponCode` (trim + upper-case with `Locale`-stable
  semantics) so the client submits codes exactly as the server redeems them, and
  the response validators (`assertCouponVerdict`, `assertOrderView`). A valid
  coupon verdict carries a **nested** `discount: { type, value }` object.
- **`orderServiceClient.js`** — the one-way HTTP client: `validateCoupon`,
  `validateCoupons`, `createOrder(price, couponCodes)`, `getOrder`, and
  `getOrderStatus`. It composes a caller-supplied `AbortSignal` with a
  per-request timeout, validates request inputs and response structure, and
  surfaces a **safe, curated user message** on every failure (network,
  timeout/abort, or non-2xx) — the raw server text and diagnostics are attached
  only to the thrown `Error`'s `message`/`cause` for logs, never shown to users.
  The base URL is validated at load and **rejects** embedded credentials, a
  query string, or a fragment. The client never decides coupon validity or order
  status itself — those verdicts are authoritative on the server.

UI (`src/`):

- **`index.html` + `src/main.jsx`** — the Vite entry and the React 19 bootstrap
  (`StrictMode` + `createRoot`) it references.
- **`App.jsx`** — the root component and a dependency-free client-side router
  (History API + `popstate`, no `react-router`). `App` **owns the applied-coupon
  list** and submits it, with a price, to `POST /orders` so applied coupons
  actually participate in order creation, pricing, and server-side redemption. It
  renders exactly **one page-level `<h1>` per view**, updates `document.title`,
  and moves focus to the active view's heading on navigation. A malformed order
  path (e.g. `/orders/%ZZ`) or any unknown path renders an **explicit 404** rather
  than silently falling back to the landing view.
- **`components/CouponInput.jsx`** — a controlled multi-coupon input. The applied
  list is owned by the parent and passed in; the component de-duplicates by
  **canonical (case-insensitive) identity**, relays validation to the server, and
  applies only a `valid === true` verdict. Errors are announced (`role="alert"`);
  controls meet a ≥ 44 px touch target and the form wraps on narrow screens.
- **`components/OrderStatusBadge.jsx`** — renders the active status label from an
  own-property lookup (prototype-safe) through a single `role="status"`
  live-region.
- **`pages/OrderTrackingPage.jsx`** — the `/orders/:id` view. It fetches the order
  through an `AbortController` (cancelled on unmount / id change), clears stale
  state before each load, and offers a manual **Refresh**/**Retry** control. It
  renders a `CREATED → CONFIRMED → DELIVERED` stepper whose current/complete/
  upcoming states are conveyed by **shape glyphs (✓ / ● / ○) and screen-reader
  text, not color alone**, and owns the single page-level `<h1>` for this route.

Build / test scaffolding:

- **`package.json`** — pinned dependencies; `test` runs the real Vitest suite.
- **`vite.config.js`** — the React plugin, the dev/preview API proxy, the SPA
  deep-link fallback, and the Vitest (jsdom) configuration.
- **`vitest.setup.js`** — enables `IS_REACT_ACT_ENVIRONMENT`.
- **`src/testUtils.js`** and the `src/**/*.test.{js,jsx}` suites (contract,
  client, `CouponInput`, `OrderStatusBadge`, `OrderTrackingPage`, and `App`
  routing/404/deep-link/coupon-to-order-flow).

## Data flow

Data flows one way: UI components → `orderServiceClient` → the `order-service`
HTTP API. The UI orchestrates and displays; it makes no coupon-validity or
order-status decisions of its own.

## Routes

- `/` — the landing view: the coupon input, an order-creation form (price +
  applied coupons → `POST /orders`), and an "open an order" affordance.
- `/orders/:id` — the order-tracking view (status stepper + badge + totals).
- anything else (including a malformed `/orders/:id` encoding) — an explicit 404.

## Backend dependency (required to run)

This SPA is a **client of `order-service`** and is inert without it. With no
backend reachable at the configured base URL, coupon validation, order creation,
and order tracking all fail with the client's safe "the order service is
unreachable" message. The Java → Node status-change notification bridge is
entirely server-side and independent of this UI.

## Dev proxy and configuration

Because the SPA and the backend may run on different origins, `vite.config.js`
configures a **dev/preview proxy** that forwards the `/coupons` and `/orders`
path prefixes to the order-service (default `http://localhost:8080`, overridable
via `ORDER_SERVICE_PROXY_TARGET`). Since `/orders` is **both** an API prefix and
a client route, the proxy uses an `Accept`-based navigation bypass: a top-level
navigation or hard refresh (whose `Accept` includes `text/html`) is served the
SPA entry so client-side routing can take over, while a `fetch`/XHR call is
proxied to the backend.

- `VITE_ORDER_SERVICE_URL` — the client's API base URL. Unset defaults to
  `http://localhost:8080`; an empty string selects same-origin mode (relative
  paths, forwarded by the proxy). A non-empty value must be a valid absolute
  `http`/`https` URL with no credentials, query, or fragment, or the client throws
  at load.
- `ORDER_SERVICE_PROXY_TARGET` — the dev/preview proxy target (default
  `http://localhost:8080`).
- `VITE_BASE_PATH` — the build `base` for sub-path deployments (default `/`).

In production the built assets are served by a static host configured with an SPA
fallback (e.g. nginx `try_files $uri /index.html`) so deep links like
`/orders/123` load the app; the backend is reached same-origin or via CORS.

## Tech & scripts

Built with React 19 and Vite 6 on Node 22.x. Runtime dependencies are pinned
exactly (`react`/`react-dom` `19.2.8`, `vite` `6.4.3`, `@vitejs/plugin-react`
`4.7.0`); the dev-only test toolchain tracks a patched major line (`vitest`
`^4.1.10`, `jsdom` `^29.1.1`), with the resolved versions recorded in
`package-lock.json` for reproducible installs.

- `npm run dev` — start the Vite dev server.
- `npm run build` — production build to `dist/`.
- `npm run preview` — preview the production build (uses the same API proxy).
- `npm test` — run the Vitest suite (`vitest run`).

### Testing note

`@testing-library/*` is not available in the offline build cache, so the suite
drives components with React 19's built-in `act` plus `react-dom/client`'s
`createRoot` and native DOM events, via the helpers in `src/testUtils.js`.
`vitest.setup.js` sets `IS_REACT_ACT_ENVIRONMENT` so `act()` runs cleanly.

## Known limitations

- **Requires a running `order-service`.** Without it the UI renders but every
  action fails with a safe "service unreachable" message (see above).
- **Unauthenticated API.** The `order-service` HTTP API this UI calls currently
  has no authentication or authorization; anyone who can reach it can validate
  coupons and create/advance orders. This is a known gap (out of scope for this
  feature) and must be addressed before any untrusted exposure.
- **No persistence.** Order, coupon, and status data live in the server's memory
  and do not survive a restart.
- **No design system.** There is no Figma reference or component library, so the
  UI uses semantic HTML with minimal inline styling only.
- **Single-repository realization.** The intended multi-repository / per-module
  pull-request structure is captured as guidance; this checkout is one flat
  repository, so the module boundary is by directory.
