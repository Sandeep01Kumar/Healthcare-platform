# Notification Service

Handles:

- Order status change notifications
- Idempotent notification delivery within an instance lifetime (safe to retry)
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

- **Idempotency:** each distinct `(order.id, oldStatus, newStatus)` transition is
  delivered to the transport at most once **per instance lifetime**. The dedupe
  state is held **in memory only, with no persistence** (per AAP 0.6.2) and is
  **bounded** — oldest entries are evicted once a cap is reached — so it resets on
  a new instance or process restart.
- **Failure handling:** a transition is remembered **only after a successful
  delivery**. If the transport rejects, the key is not marked processed and the
  error propagates, so the event can be retried; a failed send is never reported
  as delivered.
- **Validation:** the order must carry a non-blank `id`, both statuses must be in
  the `CREATED`/`CONFIRMED`/`DELIVERED` vocabulary, and `oldStatus → newStatus`
  must be a permitted lifecycle transition.

Transports are pluggable — a function or an object exposing a `send(payload)`
method — and default to a benign, log-safe console transport when omitted; no
concrete email/SMS/push vendor is included.
