/**
 * @module notificationService.test
 *
 * Unit tests for {@link NotificationService}, executed by the built-in Node test
 * runner via `node --test` (the `"test"` script in `notification-service/`'s
 * `package.json`). These tests use only Node built-ins — `node:test` and
 * `node:assert/strict` — and pull in **zero external test frameworks or npm
 * packages**, honoring the notification-service module's zero-dependency
 * contract (AAP Section 0.3). They satisfy the feature's test mandate (AAP
 * Section 0.7) for the `notification-service` module (AAP Section 0.5.1,
 * Group 4).
 *
 * ## What is proven here
 * The two behaviors called out as MANDATORY by the specification are asserted
 * first, followed by cases that exercise the harder correctness, failure,
 * concurrency, and resource-bound paths the implementation promises:
 *  1. **Dispatch on status change** — a transition dispatches exactly one
 *     notification carrying the correct order id, old/new status, and a **valid
 *     ISO-8601** timestamp (not merely a truthy value).
 *  2. **Idempotency** — replaying the identical transition on the same instance
 *     dispatches only once; the duplicate is reported, not re-sent.
 *  3. **Distinct transition dispatches anew** — a different, internally
 *     consistent transition for the same order is not treated as a duplicate.
 *  4. **Bare-function transport** and **`index.js` wiring**.
 *  5. **Event integrity** — malformed events (bad `order`/`id`, unsupported
 *     status, illegal transition) and internally inconsistent events (an
 *     `order.status` that disagrees with `newStatus`) are rejected and never
 *     reach the transport.
 *  6. **Async delivery** — a Promise-returning transport is awaited; an async
 *     rejection propagates and is NOT remembered.
 *  7. **Retry recovery** — a transition that failed via an **async rejection**
 *     OR a **synchronous throw** is fully retryable and succeeds on retry (the
 *     transport is invoked twice); this guards the synchronous-throw retry
 *     defect explicitly.
 *  8. **Concurrency** — two in-flight calls for the same transition collapse to a
 *     single dispatch.
 *  9. **Bounded-window idempotency** — once a key is evicted from the bounded
 *     de-duplication window it can dispatch again (best-effort, not lifetime).
 * 10. **Resource bounds** — in-flight capacity applies backpressure, and a
 *     never-settling transport is timed out (and stays retryable).
 *
 * ## Test isolation via injected spy/fake transports
 * Every case injects a fake/spy transport (a function or a `{ send }` object)
 * that records the payloads handed to it and/or simulates async/failing/slow
 * delivery. No real transport (console, network, queue, or vendor) is exercised,
 * so the assertions observe only the service's own dispatch/idempotency/failure
 * logic. Every case is deterministic and self-cleaning: gated deliveries are
 * always released and awaited, and timeout cases use a short, explicit
 * `deliveryTimeoutMs`, so `node --test` never hangs.
 *
 * ## Why the calls are awaited
 * {@link NotificationService#sendStatusChangeNotification} is an **async** method
 * that awaits transport delivery and records a transition's dedupe key only
 * **after** that delivery resolves (it returns a `Promise<SendResult>`). Each
 * test therefore uses an `async` callback and `await`s every call so that (a) the
 * resolved `SendResult` — not the pending promise — is asserted against, and
 * (b) the idempotency bookkeeping is observed only once a prior dispatch has
 * fully settled. This mirrors how a real producer would await delivery before
 * treating an event as handled.
 *
 * @remarks ESM module (matches `"type": "module"`); relative imports carry
 * explicit `.js` extensions as required for ESM resolution.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NotificationService } from './NotificationService.js';
import defaultInstance, { NotificationService as NsFromIndex } from './index.js';

/**
 * Strict `Date.prototype.toISOString()` grammar
 * (`YYYY-MM-DDTHH:mm:ss.sssZ`) used to prove the payload timestamp is a real
 * ISO-8601 instant rather than merely a truthy value.
 *
 * @type {RegExp}
 */
const ISO_8601 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

/**
 * Creates a spy transport that records every notification handed to it.
 *
 * The returned object satisfies the service's transport contract (an object with
 * a `send` method) while capturing each delivered payload in the `calls` array,
 * so a test can assert both how many notifications were dispatched and their
 * exact contents.
 *
 * @returns {{ send: (n: object) => void, calls: object[] }} A transport whose
 *   `send` records into the exposed `calls` array.
 */
function createSpyTransport() {
  const calls = [];
  return { calls, send(notification) { calls.push(notification); } };
}

/**
 * Creates an externally-controllable deferred promise, used to hold a transport
 * "in flight" until the test explicitly releases it — enabling deterministic
 * concurrency and backpressure assertions with no timers or races.
 *
 * @returns {{ promise: Promise<void>, resolve: () => void, reject: (e?: unknown) => void }}
 */
function createDeferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Asserts that a value is a valid ISO-8601 timestamp string: it matches the
 * strict `toISOString()` grammar, parses to a finite instant, and round-trips
 * back to the identical string.
 *
 * @param {unknown} value The timestamp to validate.
 * @returns {void}
 */
function assertIso8601(value) {
  assert.equal(typeof value, 'string', 'timestamp must be a string');
  assert.match(value, ISO_8601, 'timestamp must match strict ISO-8601 grammar');
  const parsed = Date.parse(value);
  assert.ok(!Number.isNaN(parsed), 'timestamp must parse to a real instant');
  assert.equal(
    new Date(parsed).toISOString(),
    value,
    'timestamp must round-trip through Date.toISOString()'
  );
}

/* ------------------------------------------------------------------ *
 * 1–2. MANDATORY behaviors: dispatch on change, and idempotency.
 * ------------------------------------------------------------------ */

test('dispatches exactly one notification on a status change', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const result = await service.sendStatusChangeNotification(
    { id: 'o1', status: 'CONFIRMED', price: 100 }, 'CREATED', 'CONFIRMED');
  assert.equal(result.dispatched, true);
  assert.equal(result.duplicate, false);
  assert.equal(spy.calls.length, 1);
  assert.equal(spy.calls[0].orderId, 'o1');
  assert.equal(spy.calls[0].oldStatus, 'CREATED');
  assert.equal(spy.calls[0].newStatus, 'CONFIRMED');
  // The timestamp must be a genuine ISO-8601 instant, not merely truthy.
  assertIso8601(spy.calls[0].at);
});

test('is idempotent: the same transition twice dispatches only once', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const order = { id: 'o1', status: 'CONFIRMED', price: 100 };
  const first = await service.sendStatusChangeNotification(order, 'CREATED', 'CONFIRMED');
  const second = await service.sendStatusChangeNotification(order, 'CREATED', 'CONFIRMED');
  assert.equal(first.dispatched, true);
  assert.equal(second.dispatched, false);
  assert.equal(second.duplicate, true);
  assert.equal(first.key, second.key, 'both calls resolve the same dedupe key');
  assert.equal(spy.calls.length, 1, 'duplicate transition must not re-dispatch');
});

/* ------------------------------------------------------------------ *
 * 3–5. Distinct transition, bare-function transport, index.js wiring.
 * ------------------------------------------------------------------ */

test('a distinct transition for the same order dispatches a new notification', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  // Each event is internally consistent: the carried order.status equals the
  // status just transitioned TO (CONFIRMED, then DELIVERED). Identity is order.id.
  await service.sendStatusChangeNotification(
    { id: 'o1', status: 'CONFIRMED', price: 100 }, 'CREATED', 'CONFIRMED');
  const next = await service.sendStatusChangeNotification(
    { id: 'o1', status: 'DELIVERED', price: 100 }, 'CONFIRMED', 'DELIVERED');
  assert.equal(next.dispatched, true);
  assert.equal(spy.calls.length, 2);
  assert.equal(spy.calls[1].oldStatus, 'CONFIRMED');
  assert.equal(spy.calls[1].newStatus, 'DELIVERED');
});

test('accepts a bare function as a transport', async () => {
  const calls = [];
  const service = new NotificationService((n) => calls.push(n));
  await service.sendStatusChangeNotification({ id: 'o2' }, 'CREATED', 'CONFIRMED');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].orderId, 'o2');
});

test('index.js exposes the class and a ready default instance', () => {
  assert.equal(NsFromIndex, NotificationService);
  assert.ok(defaultInstance instanceof NotificationService);
});

/* ------------------------------------------------------------------ *
 * 6–8. Event integrity: malformed & internally inconsistent events are
 *      rejected and NEVER reach the transport; consistent events dispatch.
 * ------------------------------------------------------------------ */

test('rejects malformed events and never invokes the transport', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);

  // Invalid `order` / `order.id`.
  await assert.rejects(
    () => service.sendStatusChangeNotification(null, 'CREATED', 'CONFIRMED'),
    TypeError);
  await assert.rejects(
    () => service.sendStatusChangeNotification(['not', 'an', 'object'], 'CREATED', 'CONFIRMED'),
    TypeError);
  await assert.rejects(
    () => service.sendStatusChangeNotification({}, 'CREATED', 'CONFIRMED'),
    TypeError);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: '   ' }, 'CREATED', 'CONFIRMED'),
    TypeError);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'x'.repeat(513) }, 'CREATED', 'CONFIRMED'),
    RangeError);

  // Unsupported status vocabulary.
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'o1' }, 'BOGUS', 'CONFIRMED'),
    RangeError);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'o1' }, 'CREATED', 'SHIPPED'),
    RangeError);

  // Illegal (non-forward / skipping / regressing) transitions.
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'o1' }, 'CREATED', 'DELIVERED'),
    /invalid transition CREATED -> DELIVERED/);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'o1' }, 'CONFIRMED', 'CREATED'),
    /invalid transition CONFIRMED -> CREATED/);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'o1' }, 'CREATED', 'CREATED'),
    /invalid transition CREATED -> CREATED/);

  assert.equal(spy.calls.length, 0, 'no malformed event may reach the transport');
});

test('rejects an internally inconsistent order.status', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);

  // order.status disagrees with the transition target -> rejected.
  await assert.rejects(
    () => service.sendStatusChangeNotification(
      { id: 'o1', status: 'DELIVERED' }, 'CREATED', 'CONFIRMED'),
    /inconsistent with the transition target newStatus/);

  // order.status present but not a supported value -> rejected.
  await assert.rejects(
    () => service.sendStatusChangeNotification(
      { id: 'o1', status: 'SHIPPED' }, 'CREATED', 'CONFIRMED'),
    /order\.status must be one of/);

  assert.equal(spy.calls.length, 0, 'inconsistent events must not dispatch');
});

test('accepts an event whose order.status equals newStatus', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const result = await service.sendStatusChangeNotification(
    { id: 'o1', status: 'CONFIRMED' }, 'CREATED', 'CONFIRMED');
  assert.equal(result.dispatched, true);
  assert.equal(spy.calls.length, 1);
});

/* ------------------------------------------------------------------ *
 * 9–10. Async delivery: Promise-returning transport is awaited; an async
 *       rejection propagates and is NOT remembered.
 * ------------------------------------------------------------------ */

test('awaits a Promise-returning (async) transport before resolving', async () => {
  const calls = [];
  const service = new NotificationService(async (n) => {
    // Yield to the event loop to prove the service truly awaits async delivery.
    await Promise.resolve();
    calls.push(n);
  });
  const result = await service.sendStatusChangeNotification({ id: 'a' }, 'CREATED', 'CONFIRMED');
  assert.equal(result.dispatched, true);
  assert.equal(calls.length, 1, 'the async transport must have completed before resolution');
  assert.equal(calls[0].orderId, 'a');
});

test('propagates an async rejection and does not remember the key', async () => {
  let attempts = 0;
  const service = new NotificationService(async () => {
    attempts += 1;
    throw new Error('async delivery failed');
  });
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'a' }, 'CREATED', 'CONFIRMED'),
    /async delivery failed/);
  assert.equal(attempts, 1);
  // A failed delivery is not marked processed: a second attempt re-invokes the
  // transport (it is not short-circuited as a duplicate).
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'a' }, 'CREATED', 'CONFIRMED'),
    /async delivery failed/);
  assert.equal(attempts, 2, 'failed delivery must remain retryable, not deduped');
});

/* ------------------------------------------------------------------ *
 * 11–12. Retry recovery after failure — the transport is invoked twice and
 *        the retry succeeds. Covers BOTH an async rejection and a
 *        SYNCHRONOUS throw (the synchronous-throw retry defect guard).
 * ------------------------------------------------------------------ */

test('retries after a rejected Promise and recovers (transport invoked twice)', async () => {
  let attempts = 0;
  const service = new NotificationService(async () => {
    attempts += 1;
    if (attempts === 1) throw new Error('transient async failure');
  });
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'r1' }, 'CREATED', 'CONFIRMED'),
    /transient async failure/);
  const retry = await service.sendStatusChangeNotification({ id: 'r1' }, 'CREATED', 'CONFIRMED');
  assert.equal(retry.dispatched, true, 'the retry must succeed');
  assert.equal(retry.duplicate, false);
  assert.equal(attempts, 2, 'transport must be invoked again on retry');
});

test('retries after a SYNCHRONOUS throw and recovers (transport invoked twice)', async () => {
  // Regression guard: a transport that throws synchronously on its first call must
  // NOT poison the in-flight map. The retry must re-invoke the transport and
  // succeed rather than returning a permanently-stored stale rejection.
  let attempts = 0;
  const service = new NotificationService(() => {
    attempts += 1;
    if (attempts === 1) throw new Error('transient synchronous failure');
  });
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'r2' }, 'CREATED', 'CONFIRMED'),
    /transient synchronous failure/);
  const retry = await service.sendStatusChangeNotification({ id: 'r2' }, 'CREATED', 'CONFIRMED');
  assert.equal(retry.dispatched, true, 'the retry must succeed after a synchronous throw');
  assert.equal(retry.duplicate, false);
  assert.equal(attempts, 2, 'transport must be invoked again on retry');
});

/* ------------------------------------------------------------------ *
 * 13. Concurrency: two in-flight calls for the SAME transition collapse to a
 *     single transport dispatch (no double-send).
 * ------------------------------------------------------------------ */

test('de-duplicates concurrent in-flight calls into a single dispatch', async () => {
  let calls = 0;
  const gate = createDeferred();
  const service = new NotificationService(async () => {
    calls += 1;
    await gate.promise; // hold the delivery "in flight" until released
  });
  // Fire two identical transitions before either settles.
  const p1 = service.sendStatusChangeNotification({ id: 'c' }, 'CREATED', 'CONFIRMED');
  const p2 = service.sendStatusChangeNotification({ id: 'c' }, 'CREATED', 'CONFIRMED');
  gate.resolve();
  const [r1, r2] = await Promise.all([p1, p2]);
  assert.equal(calls, 1, 'concurrent duplicates share a single transport call');
  assert.equal(r1.dispatched, true);
  assert.equal(r2.dispatched, true);
  assert.equal(r1.key, r2.key, 'both callers observe the same shared dispatch');
});

/* ------------------------------------------------------------------ *
 * 14. Bounded-window idempotency: once a key is evicted from the bounded
 *     de-duplication window, replaying that transition dispatches again.
 * ------------------------------------------------------------------ */

test('re-dispatches a transition after it is evicted from the bounded window', async () => {
  let calls = 0;
  // A window of size 1 keeps only the most recent successful dispatch.
  const service = new NotificationService(() => { calls += 1; }, { maxProcessed: 1 });

  // Dispatch key A (order X). While A is still the only key, a replay is a duplicate.
  await service.sendStatusChangeNotification({ id: 'X', status: 'CONFIRMED' }, 'CREATED', 'CONFIRMED');
  const dupA = await service.sendStatusChangeNotification({ id: 'X', status: 'CONFIRMED' }, 'CREATED', 'CONFIRMED');
  assert.equal(dupA.duplicate, true, 'A is still inside the window -> duplicate');

  // Dispatch key B (order Y); with maxProcessed=1 this evicts A oldest-first.
  await service.sendStatusChangeNotification({ id: 'Y', status: 'CONFIRMED' }, 'CREATED', 'CONFIRMED');

  // Replaying A now dispatches again (best-effort, bounded-window — not lifetime).
  const replayA = await service.sendStatusChangeNotification({ id: 'X', status: 'CONFIRMED' }, 'CREATED', 'CONFIRMED');
  assert.equal(replayA.dispatched, true, 'evicted key must be dispatchable again');
  assert.equal(calls, 3, 'transport saw A, B, then A again');
});

/* ------------------------------------------------------------------ *
 * 15–16. Resource bounds: in-flight capacity backpressure, and delivery
 *        timeout for a transport that never settles (still retryable).
 * ------------------------------------------------------------------ */

test('applies in-flight backpressure once capacity is reached', async () => {
  const gate = createDeferred();
  // Capacity of 1; a generous-but-finite timeout guards against any hang.
  const service = new NotificationService(async () => { await gate.promise; }, {
    maxInFlight: 1,
    deliveryTimeoutMs: 1000,
  });

  // First transition occupies the single in-flight slot (not awaited yet).
  const inFlight = service.sendStatusChangeNotification({ id: 'c1' }, 'CREATED', 'CONFIRMED');
  // A second, DISTINCT transition is refused with a backpressure error.
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'c2' }, 'CREATED', 'CONFIRMED'),
    /in-flight capacity of 1 reached/);

  // Release and await the first delivery so nothing is left pending.
  gate.resolve();
  const settled = await inFlight;
  assert.equal(settled.dispatched, true);
});

test('times out a transport that never settles and stays retryable', async () => {
  let attempts = 0;
  const service = new NotificationService(
    () => { attempts += 1; return new Promise(() => {}); }, // never settles
    { deliveryTimeoutMs: 25 });

  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 't' }, 'CREATED', 'CONFIRMED'),
    /timed out after 25 ms/);
  assert.equal(attempts, 1);

  // A timed-out delivery is a failure: the key is not remembered, so a subsequent
  // attempt re-invokes the transport rather than short-circuiting as a duplicate.
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 't' }, 'CREATED', 'CONFIRMED'),
    /timed out after 25 ms/);
  assert.equal(attempts, 2, 'a timed-out transition must remain retryable');
});
