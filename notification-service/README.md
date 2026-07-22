# Notification Service

Handles:

- Order status change notifications
- Best-effort idempotent notification delivery within a bounded in-memory window (safe to retry)
- Pluggable notification transport

## Integration (transport-agnostic library)

This module is a **transport-agnostic library**. It builds a serialized,
language-neutral status-change payload and delivers it through an injected
transport sink; it does **not** itself open a socket, bind an HTTP endpoint, or
subscribe to a queue. The `order-service` (Java) side is the **planned** producer:
on each successful `CREATED → CONFIRMED → DELIVERED` transition it will serialize
the event and hand it to a transport that reaches this Node consumer.

A Java in-process callback cannot invoke this Node code directly, so a concrete
cross-runtime bridge — for example an HTTP POST receiver, a message-queue
subscriber, or an IPC listener — is required between the two runtimes. That bridge
is a **pluggable transport and is out of scope for this feature** (per AAP Section
0.6.2); no concrete receiver or vendor is bundled here. There is no hard code
dependency from this module back into `order-service`.

## Core API

`NotificationService.sendStatusChangeNotification(order, oldStatus, newStatus)`
mirrors the planned producer contract and returns a `Promise<SendResult>`. It
validates the event, then **awaits** delivery through the transport.

- **Idempotency (bounded-window, best-effort):** each distinct
  `(order.id, oldStatus, newStatus)` transition is delivered to the transport at
  most once **while its key remains within a bounded in-memory de-duplication
  window** (the most recent `maxProcessed` successful dispatches). The dedupe state
  is held **in memory only, with no persistence** (per AAP 0.6.2) and is **bounded**
  with oldest-first (FIFO) eviction, so it resets on a new instance or process
  restart. This is intentionally a best-effort, bounded-window guarantee, **not** a
  durable or whole-instance-lifetime one: once a key has been evicted, a later
  replay of that same transition can dispatch again. Callers needing a stronger
  guarantee must layer their own persistent de-duplication on top.
- **Failure handling (retry-safe):** a transition is remembered **only after a
  successful delivery**. If the transport throws (synchronously or
  asynchronously), rejects, or exceeds the per-delivery timeout, the key is not
  marked processed and the error propagates, so the event can be retried; a failed
  send is never reported as delivered. In-flight state is registered **before** the
  transport is invoked (delivery is deferred by a microtask), so even a transport
  that throws on its first synchronous statement remains fully retryable.
- **Resource bounds:** each delivery races an instance-configurable timeout
  (`deliveryTimeoutMs`, whose timer is cleared the instant the delivery settles,
  so it holds the event loop for at most the timeout window) and the number of
  concurrently in-flight deliveries is capped (`maxInFlight`). When the in-flight
  cap is reached, a brand-new transition is rejected with a backpressure error
  rather than queued; duplicate or already-in-flight calls are never rejected for
  capacity.
- **Validation:** the order must carry a non-blank `id`, both statuses must be in
  the `CREATED`/`CONFIRMED`/`DELIVERED` vocabulary, `oldStatus → newStatus` must be
  a permitted lifecycle transition, and — when the order carries its own `status` —
  that status must equal `newStatus` so the event is internally consistent.

Transports are pluggable — a function or an object exposing a `send(payload)`
method — and default to a benign, log-safe console transport when omitted; no
concrete email/SMS/push vendor is included.
