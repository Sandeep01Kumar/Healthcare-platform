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
import defaultInstance, {
  NotificationService as NsFromIndex,
  createReceiver,
  start,
} from './index.js';

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

/* ------------------------------------------------------------------ *
 * 11. M11 — stable cross-runtime eventId. The dispatched payload carries
 *     the same "<orderId>|<from>-><to>" correlation id the Java producer
 *     emits (contract F), so a single transition is traceable across
 *     runtimes and by a durable/downstream de-duplicator.
 * ------------------------------------------------------------------ */

test('M11: the dispatched payload carries the stable cross-runtime eventId', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const result = await service.sendStatusChangeNotification({ id: 'ev1' }, 'CREATED', 'CONFIRMED');
  const expected = 'ev1|CREATED->CONFIRMED';
  // The SendResult payload and the payload the transport actually receives both
  // carry the eventId as a first-class field.
  assert.equal(result.payload.eventId, expected);
  assert.equal(spy.calls[0].eventId, expected, 'transport sees the eventId in the payload');
  // The static helper produces the identical id the Java producer emits (contract
  // F): order-service NotificationOutbox.eventId(...) == NotificationService.eventId(...).
  assert.equal(
    NotificationService.eventId({ id: 'ev1' }, 'CREATED', 'CONFIRMED'),
    expected,
    'the Node eventId format matches the Java producer verbatim');
  // The internal dedupe key stays a distinct, collision-free JSON tuple (NOT the
  // human-readable eventId), so an id containing the `|`/`->` delimiters can never
  // cause a dedupe collision.
  assert.equal(result.key, JSON.stringify(['ev1', 'CREATED', 'CONFIRMED']));
  assert.notEqual(result.key, expected);
});

/* ------------------------------------------------------------------ *
 * 12. M3 — transport fencing on timeout. When a delivery exceeds the
 *     per-delivery timeout, the transport's AbortSignal is aborted BEFORE
 *     the outer promise rejects, so a signal-aware (fetch-style) transport
 *     cancels its in-flight work and cannot LATER complete a duplicate
 *     send. The timed-out transition is not remembered and stays retryable.
 * ------------------------------------------------------------------ */

test('M3: a timed-out delivery aborts the transport signal and stays retryable (fencing)', async () => {
  let attempts = 0;
  /** @type {(AbortSignal|undefined)[]} */
  const signals = [];
  let firstObservedAbort = false;
  const transport = {
    send(payload, options) {
      attempts += 1;
      const signal = options && options.signal;
      signals.push(signal);
      if (attempts === 1) {
        // Model a signal-aware transport (like one built on fetch): it stays
        // pending until its signal is aborted, then cancels. By the time the abort
        // fires, the per-delivery timeout has already rejected the outer delivery,
        // so this late settle is fenced out and cannot complete a duplicate send.
        return new Promise((resolve, reject) => {
          if (signal) {
            signal.addEventListener('abort', () => {
              firstObservedAbort = true;
              reject(new Error('aborted'));
            }, { once: true });
          }
        });
      }
      // The retry succeeds promptly.
      return Promise.resolve();
    },
  };
  const service = new NotificationService(transport, { deliveryTimeoutMs: 20 });

  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'fence-1' }, 'CREATED', 'CONFIRMED'),
    /timed out after 20 ms/);

  // The transport was invoked with an AbortSignal, and the timeout aborted it
  // BEFORE the delivery rejected — so a signal-aware transport cancels in-flight
  // work and cannot later complete a duplicate (finding M3).
  assert.ok(signals[0] instanceof AbortSignal, 'transport received an AbortSignal');
  assert.equal(signals[0].aborted, true, 'the signal was aborted on timeout');
  assert.equal(firstObservedAbort, true, 'the transport observed the abort');

  // The timed-out transition was NOT remembered: a retry re-invokes the transport
  // (not short-circuited as a duplicate) and now succeeds.
  const retry = await service.sendStatusChangeNotification({ id: 'fence-1' }, 'CREATED', 'CONFIRMED');
  assert.equal(retry.dispatched, true);
  assert.equal(retry.duplicate, false);
  assert.equal(attempts, 2, 'the transition remained retryable after the timeout');
});

/* ------------------------------------------------------------------ *
 * 13. N1 — hostile-value validation messages. A rejection message built
 *     from an untrusted field must NEVER be replaced by an opaque
 *     `JSON.stringify` TypeError: the intended RangeError/TypeError (and
 *     an actionable, BOUNDED echo of the bad value) must survive, even for
 *     BigInt, cyclic, unprintable, and oversized inputs.
 * ------------------------------------------------------------------ */

test('N1: a BigInt status is rejected with a bounded RangeError, not a JSON.stringify TypeError', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'n1' }, 'CREATED', 10n),
    (err) => {
      // The real validation failure survives — WITHOUT the total echoForError, the
      // message construction would have thrown a TypeError from JSON.stringify(10n)
      // and masked this RangeError.
      assert.ok(err instanceof RangeError, 'must be the intended RangeError, not a TypeError');
      assert.match(err.message, /newStatus must be one of/);
      assert.match(err.message, /10n/, 'the BigInt is echoed readably as 10n');
      return true;
    });
  assert.equal(spy.calls.length, 0, 'a rejected event never reaches the transport');
});

test('N1: a cyclic order.status is rejected with a RangeError (no stringify throw)', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const cyclic = {};
  cyclic.self = cyclic;
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'n2', status: cyclic }, 'CREATED', 'CONFIRMED'),
    (err) => {
      assert.ok(err instanceof RangeError, 'must be a RangeError, not a JSON.stringify TypeError');
      assert.match(err.message, /order\.status must be one of/);
      return true;
    });
  assert.equal(spy.calls.length, 0);
});

test('N1: an order.status whose toString AND toJSON throw yields an [unprintable] echo', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const hostile = {
    toJSON() { throw new Error('no json'); },
    toString() { throw new Error('no string'); },
    [Symbol.toPrimitive]() { throw new Error('no primitive'); },
  };
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'n3', status: hostile }, 'CREATED', 'CONFIRMED'),
    (err) => {
      // Both JSON.stringify and String() coercion throw for this value; the total
      // echoForError falls back to a safe marker instead of escaping the throw.
      assert.ok(err instanceof RangeError, 'the real validation error is preserved');
      assert.match(err.message, /order\.status must be one of/);
      assert.match(err.message, /\[unprintable object\]/, 'hostile value is safely rendered');
      return true;
    });
  assert.equal(spy.calls.length, 0);
});

test('N1: an oversized status echo is bounded (defense-in-depth against amplification)', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const huge = 'X'.repeat(10000);
  await assert.rejects(
    () => service.sendStatusChangeNotification({ id: 'n4' }, 'CREATED', huge),
    (err) => {
      assert.ok(err instanceof RangeError);
      // The echoed value is truncated with an ellipsis so the message stays bounded
      // rather than ballooning to ~10 000 characters.
      assert.ok(err.message.length < 400, `message must be bounded, got ${err.message.length}`);
      assert.match(err.message, /…/, 'oversized echo is truncated with an ellipsis');
      return true;
    });
  assert.equal(spy.calls.length, 0);
});

/* ------------------------------------------------------------------ *
 * 14. C6/M8 — HTTP receiver integration (the delivered Java -> Node bridge
 *     consumer). A real node:http server is bound on an ephemeral port and
 *     exercised over real HTTP with fetch, so a wiring or response-code
 *     defect cannot hide behind a green in-process suite (finding M8, the
 *     same class of defect that a live test caught in order-service).
 * ------------------------------------------------------------------ */

/**
 * Starts the receiver on an ephemeral port with a fresh spy transport, so a test
 * can assert both the HTTP response and what actually reached the transport.
 *
 * @param {object} [serviceOptions] Options forwarded to the NotificationService.
 * @returns {Promise<{ baseUrl: string, transport: { calls: object[] }, service: NotificationService, close: () => Promise<void> }>}
 */
async function startSpyReceiver(serviceOptions) {
  const transport = createSpyTransport();
  const service = new NotificationService(transport, serviceOptions);
  const { server, port, host } = await start({ port: 0, host: '127.0.0.1', service });
  return {
    baseUrl: `http://${host}:${port}`,
    transport,
    service,
    close: () => new Promise((resolve) => server.close(() => resolve())),
  };
}

/**
 * POSTs a body to the receiver. By default the body is JSON-encoded and sent to
 * `/notifications`; pass `raw` to send a verbatim string, or `path` to target a
 * different route.
 *
 * @param {string} baseUrl The receiver base URL.
 * @param {unknown} body The body to send.
 * @param {{ raw?: boolean, path?: string, contentType?: string | null }} [opts]
 *   Encoding/route options. `contentType` overrides the request media type
 *   (default `application/json`); pass `null` to omit the header entirely.
 * @returns {Promise<Response>} The fetch response.
 */
function postEvent(baseUrl, body, { raw = false, path = '/notifications', contentType = 'application/json' } = {}) {
  const headers = {};
  if (contentType !== null) {
    headers['Content-Type'] = contentType;
  }
  return fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers,
    body: raw ? body : JSON.stringify(body),
  });
}

/**
 * Builds a well-formed contract-F status-change event, with optional overrides.
 *
 * @param {object} [overrides] Fields to override on the base event.
 * @returns {object} A contract-F event body.
 */
function contractFEvent(overrides = {}) {
  return {
    eventId: 'R1|CREATED->CONFIRMED',
    orderId: 'R1',
    oldStatus: 'CREATED',
    newStatus: 'CONFIRMED',
    order: { id: 'R1', status: 'CONFIRMED' },
    at: new Date().toISOString(),
    ...overrides,
  };
}

test('C6: the receiver dispatches a valid contract-F event (200) and echoes the eventId', async () => {
  const rec = await startSpyReceiver();
  try {
    const res = await postEvent(rec.baseUrl, contractFEvent());
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.dispatched, true);
    assert.equal(body.duplicate, false);
    assert.equal(body.eventId, 'R1|CREATED->CONFIRMED');
    // The mapped payload reached the transport exactly once, carrying the same id.
    assert.equal(rec.transport.calls.length, 1);
    assert.equal(rec.transport.calls[0].eventId, 'R1|CREATED->CONFIRMED');
    assert.equal(rec.transport.calls[0].orderId, 'R1');
    assert.equal(rec.transport.calls[0].oldStatus, 'CREATED');
    assert.equal(rec.transport.calls[0].newStatus, 'CONFIRMED');
    assertIso8601(rec.transport.calls[0].at);
  } finally {
    await rec.close();
  }
});

test('C6: a replayed event is reported as a duplicate (200) and not re-dispatched', async () => {
  const rec = await startSpyReceiver();
  try {
    const first = await (await postEvent(rec.baseUrl, contractFEvent())).json();
    const second = await postEvent(rec.baseUrl, contractFEvent());
    const secondBody = await second.json();
    assert.equal(first.dispatched, true);
    assert.equal(second.status, 200);
    assert.equal(secondBody.dispatched, false);
    assert.equal(secondBody.duplicate, true);
    assert.equal(secondBody.eventId, 'R1|CREATED->CONFIRMED', 'the duplicate still carries the eventId');
    assert.equal(rec.transport.calls.length, 1, 'the duplicate must not reach the transport again');
  } finally {
    await rec.close();
  }
});

test('C6: a malformed JSON body is rejected (400) and never dispatched', async () => {
  const rec = await startSpyReceiver();
  try {
    const res = await postEvent(rec.baseUrl, 'not-json{', { raw: true });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.match(body.error.message, /invalid JSON/i);
    assert.equal(rec.transport.calls.length, 0);
  } finally {
    await rec.close();
  }
});

test('C6: an invalid transition is rejected (400) and never dispatched', async () => {
  const rec = await startSpyReceiver();
  try {
    const res = await postEvent(rec.baseUrl, contractFEvent({
      eventId: 'R1|CREATED->DELIVERED',
      newStatus: 'DELIVERED',
      order: { id: 'R1', status: 'DELIVERED' },
    }));
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.match(body.error.message, /invalid transition CREATED -> DELIVERED/);
    assert.equal(rec.transport.calls.length, 0);
  } finally {
    await rec.close();
  }
});

test('C6: a non-POST method is rejected (405)', async () => {
  const rec = await startSpyReceiver();
  try {
    const res = await fetch(`${rec.baseUrl}/notifications`, { method: 'GET' });
    assert.equal(res.status, 405);
    const body = await res.json();
    assert.match(body.error.message, /POST/);
    assert.equal(rec.transport.calls.length, 0);
  } finally {
    await rec.close();
  }
});

test('C6: an unknown route is rejected (404)', async () => {
  const rec = await startSpyReceiver();
  try {
    const res = await postEvent(rec.baseUrl, contractFEvent(), { path: '/nope' });
    assert.equal(res.status, 404);
    const body = await res.json();
    assert.match(body.error.message, /no such route/);
    assert.equal(rec.transport.calls.length, 0);
  } finally {
    await rec.close();
  }
});

test('C6/API-04: an oversized body is refused with a CLEAN 413 (not a socket reset)', async () => {
  const rec = await startSpyReceiver();
  try {
    // Exceed MAX_BODY_BYTES (64 KiB) so the receiver caps the read. The receiver
    // must answer with a deterministic 413 JSON and close the connection
    // gracefully — NOT reset the socket — so the fetch MUST resolve. A thrown
    // fetch (connection reset) is the exact API-04 defect and is now a failure.
    const huge = 'x'.repeat(70 * 1024);
    const res = await postEvent(rec.baseUrl, huge, { raw: true });
    assert.equal(res.status, 413, 'an oversized body must be refused with a clean 413');
    assert.match(
      res.headers.get('content-type') || '',
      /application\/json/i,
      'the 413 must be JSON'
    );
    // SEC-02: the security headers must be present even on the 413.
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(res.headers.get('cache-control'), 'no-store');
    const body = await res.json();
    assert.match(body.error.message, /too large/i, 'the 413 body must explain the cap');
    assert.equal(rec.transport.calls.length, 0, 'an oversized body must never be dispatched');
  } finally {
    await rec.close();
  }
});

test('C6/API-01: a non-JSON Content-Type is rejected (415) before any parse or dispatch', async () => {
  const rec = await startSpyReceiver();
  try {
    // A syntactically-valid JSON body, but mislabelled as text/plain: it must be
    // refused with 415 up front, never parsed and never dispatched.
    const res = await postEvent(rec.baseUrl, contractFEvent(), { contentType: 'text/plain' });
    assert.equal(res.status, 415, 'a non-JSON media type must be rejected with 415');
    const body = await res.json();
    assert.match(body.error.message, /application\/json/i);
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(res.headers.get('cache-control'), 'no-store');
    assert.equal(rec.transport.calls.length, 0, 'a mislabelled body must never be dispatched');
  } finally {
    await rec.close();
  }
});

test('C6/INT-02: a nested order.id that disagrees with the top-level orderId is rejected (400)', async () => {
  const rec = await startSpyReceiver();
  try {
    // The receiver must not silently pick one identity over a conflicting other;
    // an internally inconsistent event is refused and never dispatched.
    const res = await postEvent(
      rec.baseUrl,
      contractFEvent({ order: { id: 'MISMATCH', status: 'CONFIRMED' } })
    );
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.match(body.error.message, /inconsistent/i);
    assert.equal(rec.transport.calls.length, 0, 'an inconsistent event must never be dispatched');
  } finally {
    await rec.close();
  }
});

test("C6/INT-02: the producer's eventId and at are preserved end-to-end (not re-minted)", async () => {
  const rec = await startSpyReceiver();
  try {
    // Deliberately distinct sentinel values so preservation is provable against
    // the receiver's self-computed fallbacks (`<id>|<old>-><new>` and now()).
    const producerEventId = 'PRODUCER-SENTINEL-XYZ';
    const producerAt = '2020-01-02T03:04:05.678Z';
    const res = await postEvent(
      rec.baseUrl,
      contractFEvent({ eventId: producerEventId, at: producerAt })
    );
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.dispatched, true);
    assert.equal(body.eventId, producerEventId, 'the response echoes the producer eventId');
    assert.equal(rec.transport.calls.length, 1);
    assert.equal(
      rec.transport.calls[0].eventId,
      producerEventId,
      "the transport payload carries the producer's eventId verbatim"
    );
    assert.equal(
      rec.transport.calls[0].at,
      producerAt,
      "the transport payload carries the producer's at verbatim"
    );
  } finally {
    await rec.close();
  }
});

test('C6/SEC-01: a transport failure yields a GENERIC 500 that never leaks transport detail', async () => {
  // A transport that throws a message carrying sensitive infrastructure detail
  // (host, port, filesystem path) — none of which may reach the client.
  const secret = 'connect ECONNREFUSED 127.0.0.1:3001 /internal/secret/path';
  const transport = {
    send() {
      throw new Error(secret);
    },
  };
  const service = new NotificationService(transport);
  const { server, port, host } = await start({ port: 0, host: '127.0.0.1', service });
  const baseUrl = `http://${host}:${port}`;
  try {
    const res = await postEvent(baseUrl, contractFEvent());
    assert.equal(res.status, 500, 'a transport failure is a 500');
    const raw = await res.text();
    const body = JSON.parse(raw);
    assert.equal(body.error.code, 'DELIVERY_FAILED');
    assert.equal(body.error.message, 'notification delivery failed', 'the 500 message is generic');
    assert.ok(!raw.includes('ECONNREFUSED'), 'the raw error string must not leak');
    assert.ok(!raw.includes('/internal/secret'), 'the filesystem path must not leak');
    assert.ok(!raw.includes('3001'), 'the port must not leak');
    // SEC-02 headers present on the 500 too.
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(res.headers.get('cache-control'), 'no-store');
  } finally {
    await new Promise((resolve) => server.close(() => resolve()));
  }
});

test('C6/SEC-02: a successful 200 carries nosniff and no-store headers', async () => {
  const rec = await startSpyReceiver();
  try {
    const res = await postEvent(rec.baseUrl, contractFEvent());
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(res.headers.get('cache-control'), 'no-store');
  } finally {
    await rec.close();
  }
});

/* ------------------------------------------------------------------ *
 * 15. C6 — direct factory/listener contract. createReceiver() builds a
 *     configured server WITHOUT binding a socket (import-safe), and start()
 *     binds an ephemeral port and is cleanly closable.
 * ------------------------------------------------------------------ */

test('C6: createReceiver returns a configured server that is not yet listening', () => {
  const server = createReceiver();
  assert.equal(typeof server.listen, 'function', 'is an http.Server');
  assert.equal(server.listening, false, 'the factory must not bind a socket (import-safe)');
});

test('C6: start() binds an ephemeral port and can be closed', async () => {
  const { server, port } = await start({ port: 0, host: '127.0.0.1' });
  try {
    assert.ok(Number.isInteger(port) && port > 0, 'an ephemeral port was assigned');
    assert.equal(server.listening, true, 'the server is listening after start()');
  } finally {
    await new Promise((resolve) => server.close(() => resolve()));
  }
});
