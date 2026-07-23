# Notification Service

Handles:

- Order status change notifications
- An HTTP receiver (`POST /notifications`) that consumes `order-service` status-change events
- Best-effort idempotent notification delivery within a bounded in-memory window (safe to retry)
- Pluggable notification transport

## Architecture — the delivered Java → Node HTTP bridge

This module is the **Node consumer half of a concrete, delivered cross-runtime
bridge**. The `order-service` (Java) producer serializes each successful
`CREATED → CONFIRMED → DELIVERED` transition into an event and POSTs it over HTTP
to this service's receiver, which adapts the event onto the transport-agnostic
core and delivers it through the configured transport sink:

```
order-service (Java)                          notification-service (Node)
HttpNotificationTrigger  --- HTTP POST --->    POST /notifications  (src/index.js)
  serializes the transition                     parses the event and calls
  to event JSON (contract F)                     sendStatusChangeNotification(...)
```

A Java in-process callback cannot invoke Node code directly, so the serialized
HTTP hop between the two runtimes is intrinsic to the design — it is **delivered
here**, not deferred. The two sides are decoupled: there is **no code-level import
back into `order-service`**, and they are wired together only through the
serialized HTTP event below, so each runtime builds and deploys independently.

What remains genuinely out of scope (per AAP Section 0.6.2) is only a **concrete
downstream vendor transport** (email/SMS/push). The injected transport defaults to
a benign console logger; a real deployment swaps in its own sink.

## HTTP receiver (`src/index.js`)

`src/index.js` is the service entry point **and** the HTTP receiver. Importing the
module is **side-effect-free** (it binds no socket); the receiver starts only when
the file is run directly (`npm start` / `node src/index.js`), guarded by an
`import.meta.url` check. The module also exports `createReceiver(service)` (returns
a not-yet-listening `node:http` server) and `start({ port, host, service })`
(binds and resolves with the actual bound port — pass `port: 0` for an ephemeral
port), which the tests use to bind their own ports.

The default bind address is `127.0.0.1:3001` (loopback only), overridable via the
`NOTIFICATION_PORT` (or `PORT`) environment variable. The request body is capped at
64 KiB to bound untrusted input (CWE-400).

### Request — status-change event (contract F)

`POST /notifications` with a JSON body:

```json
{
  "eventId": "<orderId>|<oldStatus>-><newStatus>",
  "orderId": "A1",
  "oldStatus": "CREATED",
  "newStatus": "CONFIRMED",
  "order": { "id": "A1", "status": "CONFIRMED" },
  "at": "2024-01-01T00:00:00.000Z"
}
```

The order identity is taken from `order.id` when present, else `orderId`. This is
the **same event shape** the Java `HttpNotificationTrigger` emits and matches the
order-service `NotificationOutbox` contract.

### Responses

| Status | When | Body |
| --- | --- | --- |
| `200` | Event accepted and delivered (or recognized as a duplicate — both are idempotent successes) | `{ dispatched, duplicate, key, eventId }` |
| `400` | Body was not JSON, or the event failed validation (bad id, unknown status, invalid transition, inconsistent `order.status`) | `{ error: { message } }` |
| `413` | Body exceeded the 64 KiB cap | `{ error: { message } }` |
| `404` | A path other than `/notifications` | `{ error: { message } }` |
| `405` | A method other than `POST` | `{ error: { message } }` |
| `500` | Transport delivery failed (the producer treats this as a **retryable** failure and leaves its outbox entry `PENDING`) | `{ error: { message } }` |

## Core API (`NotificationService`)

`NotificationService.sendStatusChangeNotification(order, oldStatus, newStatus)`
mirrors the Java producer contract (`NotificationTrigger.onStatusChange`) and
returns a `Promise<SendResult>`. It validates the event, then **awaits** delivery
through the transport.

- **Cross-runtime event id (correlation):** every dispatched payload carries a
  stable `eventId` of the form `"<orderId>|<oldStatus>-><newStatus>"` — **identical
  to the id the Java producer emits** (order-service `NotificationOutbox.eventId`).
  It is logged first on both runtimes, so a single `grep` on the shared id ties the
  Java producer log line to this consumer's log line for the same transition, and it
  is the key a durable/downstream de-duplicator should use to collapse a late
  duplicate.
- **Idempotency (bounded-window, best-effort):** each distinct
  `(order.id, oldStatus, newStatus)` transition is delivered to the transport at
  most once **while its key remains within a bounded in-memory de-duplication
  window** (the most recent `maxProcessed` successful dispatches). Internal
  de-duplication uses a collision-free JSON-tuple key (distinct from the
  human-readable `eventId`). The dedupe state is held **in memory only, with no
  persistence** (per AAP 0.6.2) and is **bounded** with oldest-first (FIFO)
  eviction, so it resets on a new instance or process restart. This is intentionally
  a best-effort, bounded-window guarantee, **not** a durable or
  whole-instance-lifetime one: once a key has been evicted, a later replay of that
  same transition can dispatch again. Callers needing a stronger guarantee must
  layer their own persistent de-duplication on top, keyed by `eventId`.
- **Failure handling (retry-safe):** a transition is remembered **only after a
  successful delivery**. If the transport throws (synchronously or asynchronously),
  rejects, or exceeds the per-delivery timeout, the key is not marked processed and
  the error propagates, so the event can be retried; a failed send is never reported
  as delivered. In-flight state is registered **before** the transport is invoked
  (delivery is deferred by a microtask), so even a transport that throws on its first
  synchronous statement remains fully retryable.
- **Resource bounds & cancellation:** each delivery races an instance-configurable
  timeout (`deliveryTimeoutMs`, whose timer is cleared the instant the delivery
  settles, so it holds the event loop for at most the timeout window) and the number
  of concurrently in-flight deliveries is capped (`maxInFlight`). When the in-flight
  cap is reached, a brand-new transition is rejected with a backpressure error rather
  than queued; duplicate or already-in-flight calls are never rejected for capacity.
  The transport is invoked with a second argument `{ signal }` carrying an
  `AbortSignal` that is aborted on timeout **before** the delivery rejects, so a
  signal-aware transport (e.g. one built on `fetch`) cancels its in-flight work and a
  timed-out delivery cannot later complete a duplicate send.
- **Validation:** the order must carry a non-blank, length-bounded `id`, both
  statuses must be in the `CREATED`/`CONFIRMED`/`DELIVERED` vocabulary,
  `oldStatus → newStatus` must be a permitted lifecycle transition, and — when the
  order carries its own `status` — that status must equal `newStatus` so the event
  is internally consistent. Rejection messages echo the offending value through a
  total, length-bounded formatter that never itself throws (even for BigInt, cyclic,
  or unprintable values), so the intended `TypeError`/`RangeError` is preserved.

Transports are pluggable — a function or an object exposing a `send(payload, { signal })`
method — and default to a benign, log-safe console transport when omitted; no
concrete email/SMS/push vendor is included.

## Running

```bash
# Start the HTTP receiver on 127.0.0.1:3001 (override with NOTIFICATION_PORT/PORT):
npm start
# e.g. bind a different port:
NOTIFICATION_PORT=39217 npm start
```

The receiver logs a single truthful readiness line stating the actual bound
address and route. Point the order-service `NOTIFICATION_URL` at
`http://<host>:<port>/notifications` to complete the bridge.

## Testing

```bash
npm test          # node --test — zero external test frameworks or npm packages
```

The suite uses only Node built-ins (`node:test`, `node:assert/strict`) per the
module's zero-dependency contract (AAP Section 0.3). It covers dispatch-on-change
and idempotency (the mandated behaviors), event-integrity rejection, async delivery
and retry recovery, concurrency collapse, bounded-window eviction, backpressure,
timeout **with transport-fencing (abort) and retryability**, the stable
cross-runtime `eventId`, hostile-value rejection messages, and **live HTTP receiver
integration** (real `node:http` server bound on an ephemeral port, exercised over
`fetch`: 200 dispatch, 200 duplicate, 400 malformed/invalid-transition, 404, 405,
and oversized-body refusal).

## Notes / limitations

- **In-memory only** — no persistence of de-duplication state (per AAP 0.6.2);
  state resets on restart. Layer a durable de-duplicator keyed by `eventId` for a
  whole-lifetime guarantee.
- **Unauthenticated receiver** — the `POST /notifications` endpoint performs no
  authentication/authorization (auth is out of scope per AAP 0.6.2). Bind it to
  loopback (the default) or place it behind a trusted network boundary.
- **No concrete vendor transport** — email/SMS/push integrations are out of scope;
  inject your own transport for real delivery.
