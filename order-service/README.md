# Order Service

Handles:

- Create order (with coupon application and stacked discounting)
- Authoritative server-side coupon validation and redemption
- Order status tracking: CREATED → CONFIRMED → DELIVERED (guarded transitions)
- Per-order storage and retrieval by id (in-memory)
- Status-change notifications via a decoupled, retryable trigger (Java → Node HTTP bridge)
- HTTP API for the customer-ui (coupon validation, order create/read, status update)

Out of scope for this feature (intentionally not implemented): order update/cancel
beyond the status lifecycle (no `CANCELLED` state), durable persistence, and
authentication/authorization. See "Known limitations" below.

## What is delivered

This module is a self-contained Java 21 service. Everything listed here is
implemented and covered by tests — there is no "planned but absent" behavior.

Domain (`com.healthcare.order`):

- **`OrderStatus`** — the guarded lifecycle enum `CREATED → CONFIRMED → DELIVERED`.
  Only the two adjacent forward steps are legal; every skip, backward move,
  self-transition, and any move out of the terminal `DELIVERED` state is rejected
  (including the null-safe `isValidTransition`).
- **`Coupon`** — an immutable validation-side coupon (canonicalized code, type,
  `BigDecimal` magnitude, validity window, usage limit). Codes are canonicalized
  (trimmed, upper-cased with `Locale.ROOT`) and bounded to `MAX_CODE_LENGTH = 64`.
- **`Order`** — the order aggregate (id, price, status, applied coupons, discounted
  total). Its mutators are **package-private**: only `OrderService` may change an
  order's status, discounted total, or applied-coupon set, so the HTTP layer cannot
  mutate an order directly. Applying a coupon already present is a no-op (dedupe).
- **`CouponValidator`** — the authoritative server-side validator. `validate(code)`
  is **read-only** (existence, validity window, effective usage) and consumes
  nothing; `validateAndRedeem(code)` **atomically** checks and consumes exactly one
  redemption under a lock, so a usage-limited coupon can never be over-redeemed, even
  under concurrency. `releaseRedemption(code)` rolls a redemption back (used when
  order creation fails after some codes were already redeemed) and never drives the
  ledger below zero. Batch validation is bounded by `MAX_BATCH_SIZE = 25` (CWE-400).
- **`OrderRepository`** — a thread-safe, in-memory per-id store
  (`ConcurrentHashMap`) so any order can be retrieved by its id (this replaces the
  earlier single-mutable-order design).
- **`NotificationOutbox`** — a transaction outbox. A status change is recorded
  `PENDING` **before** the transition commits; on successful delivery it is marked
  `DELIVERED`; a delivery failure leaves it `PENDING` (never lost) for retry. The
  stable `eventId` is `"<orderId>|<from>-><to>"`.
- **`OrderService`** — orchestration. `createOrder(price, codes)` deduplicates codes
  to their canonical form, redeems each unique code via `validateAndRedeem`, applies
  the pricing-engine stacked discount, stores the order, and rolls back all
  redemptions if creation fails. `updateStatus(...)` performs the transition under a
  per-order lock (re-reading the status inside the lock to avoid a check-then-act
  race), records the outbox event before committing, then attempts delivery; a failed
  delivery is logged and left pending rather than aborting the committed transition.
  `retryPendingNotifications()` drains pending events once the transport recovers.
  The no-argument `createOrder()` legacy entry point is preserved and still returns
  `"Order Created"`.

HTTP + bridge (`com.healthcare.order.api`):

- **`Json`** — a dependency-free JSON reader/writer (money is emitted as an unquoted
  JSON number via `BigDecimal.toPlainString`, preserving scale). See "HTTP stack".
- **`OrderApiServer`** — the HTTP API (see "HTTP API" below), built on the JDK
  `com.sun.net.httpserver`. It **requires** a non-null notification trigger at
  construction (a production API must always have a transport wired).
- **`HttpNotificationTrigger`** — the producer half of the Java → Node notification
  bridge; POSTs the status-change event JSON to the notification-service receiver
  using `java.net.http.HttpClient`.
- **`OrderServiceApplication`** — the runnable entry point (`main`) that starts the
  API and wires the trigger.

## HTTP API

All requests and responses are `application/json`. CORS is enabled for all origins
(`Access-Control-Allow-Origin: *`) with an `OPTIONS` preflight returning `204`.
Request bodies are capped at 16 KiB (a larger body yields `413`).

| Method | Path | Request body | Success |
| --- | --- | --- | --- |
| POST | `/coupons/validate` | `{"code":"SAVE10"}` | `200` verdict (read-only, no redemption) |
| POST | `/orders` | `{"price":"100.00","couponCodes":["SAVE10"]}` | `201` order view (redeems coupons) |
| GET | `/orders/{id}` | — | `200` order view |
| POST | `/orders/{id}/status` | `{"status":"CONFIRMED"}` | `200` order view (fires a notification) |

**Coupon verdict** (`POST /coupons/validate`) — a valid verdict carries a nested
`discount` block; an invalid verdict omits it:

```json
{ "valid": true, "reason": "OK", "discount": { "type": "PERCENTAGE", "value": 10 } }
{ "valid": false, "reason": "expired" }
```

**Order view** (`POST /orders`, `GET /orders/{id}`, `POST /orders/{id}/status`):

```json
{
  "id": "…", "status": "CREATED",
  "price": 100.00, "discountedTotal": 90.00,
  "appliedCoupons": [ { "code": "SAVE10", "type": "PERCENTAGE", "value": "10" } ]
}
```

**Error envelope** — every error response is `{"error":{"code":"…","message":"…"}}`.
Status/code mapping: `400 VALIDATION`, `404 NOT_FOUND`, `405 METHOD_NOT_ALLOWED`,
`409 ILLEGAL_TRANSITION`, `413 PAYLOAD_TOO_LARGE`, `500 INTERNAL`.

## HTTP stack (zero third-party runtime dependencies)

The API and the notification bridge use only the JDK, so the module pulls in **no**
Jackson/Gson/Spring/Netty at runtime:

- inbound: `com.sun.net.httpserver.HttpServer` (module `jdk.httpserver`);
- outbound: `java.net.http.HttpClient` (module `java.net.http`);
- JSON: the in-module `com.healthcare.order.api.Json`.

This keeps the module buildable and runnable fully **offline** and appropriate for
the small, fixed wire contract above. The only compile/runtime dependency is the
sibling **pricing-engine** artifact; JUnit is test-scoped.

## Build & run

Build order is **pricing-engine → order-service**: install the pricing-engine
artifact into the local Maven repo first, then build this module.

```bash
# 1) pricing-engine must be installed first (publishes com.healthcare:pricing-engine:1.0.0)
(cd pricing-engine && mvn -o -B -ntp clean install)

# 2) build + test + install order-service
mvn -o -B -ntp clean install        # runs all JUnit 5 suites; produces a runnable jar

# 3) run the service (java -jar ignores -cp, so pass an explicit classpath with pricing-engine)
PE=$(find ~/.m2/repository -name pricing-engine-1.0.0.jar | head -1)
java -cp "target/order-service-1.0.0.jar:$PE" com.healthcare.order.api.OrderServiceApplication
```

Environment overrides (both optional): `ORDER_SERVICE_PORT` (default `8080`, bound on
`127.0.0.1`) and `NOTIFICATION_URL` (default `http://127.0.0.1:3001/notifications`,
the notification-service receiver the trigger POSTs to).

Production sources live under `src/main/java` in packages `com.healthcare.order` and
`com.healthcare.order.api`. Named packages are required so this module's
`com.healthcare.order.Coupon` coexists on the classpath with the distinct
pricing-engine `com.healthcare.pricing.Coupon`.

## Tests

JUnit 5 suites under `src/test/java`:

- `OrderServiceTest` — transition guard; valid/invalid transitions; exact discounted
  totals at scale 2 (single, stacked, duplicate-code); per-id storage/retrieval;
  atomic redemption across orders and under concurrency; the outbox
  (failed delivery stays `PENDING`, a later retry drains it).
- `CouponValidatorTest` — accept/expired/usage-limit/unknown; case- and
  whitespace-insensitive lookup; inclusive validity-window boundaries; read-only
  `validate` consumes nothing; `validateAndRedeem` honors the limit exactly
  (including under concurrency); `releaseRedemption` rollback; `MAX_BATCH_SIZE`.
- `api.JsonTest` — stringify/parse of every supported type, string escaping,
  `BigDecimal` precision/scale, malformed-input and trailing-content rejection,
  and a nested round-trip.
- `api.OrderApiServerTest` — a real server on an ephemeral loopback port exercised
  with a real `HttpClient`: coupon verdict (nested discount), order create/read,
  status update (and that it notifies exactly once), the error envelope, CORS/OPTIONS,
  body-limit `413`, malformed `400`, method `405`, unknown-route `404`, and the
  mandatory non-null-trigger wiring.
- `api.HttpNotificationTriggerTest` — the event wire shape (contract) and delivery
  semantics against a stand-in receiver (2xx success; non-2xx and unreachable both
  raise a recoverable delivery failure).

## Known limitations

- **Unauthenticated.** The API performs no authentication or authorization — any
  caller that can reach the port may create orders, read any order by id, and drive
  status transitions. This is acceptable only for the local, in-memory feature
  demo; a production deployment must add authn/authz and bind/expose the port
  accordingly. Authentication is out of scope for this feature by design.
- **In-memory only.** Orders, the coupon registry, the redemption ledger, and the
  notification outbox live in process memory and are lost on restart. There is no
  database or durable storage.
- **No order update/cancel** beyond the `CREATED → CONFIRMED → DELIVERED` lifecycle;
  there is no `CANCELLED` state.
