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
 * first, followed by cases that strengthen coverage:
 *  1. **Dispatch on status change** — a status transition dispatches exactly one
 *     notification carrying the correct order id, old/new status, and a
 *     timestamp.
 *  2. **Idempotency** — replaying the identical transition on the same instance
 *     dispatches only once; the duplicate is reported, not re-sent.
 *  3. **Distinct transition dispatches anew** — a different transition for the
 *     same order is not treated as a duplicate.
 *  4. **Bare-function transport** — a plain function is accepted as a transport.
 *  5. **`index.js` wiring** — the module entry re-exports the class and exposes a
 *     ready default instance.
 *
 * ## Test isolation via an injected spy transport
 * Every case injects a fake/spy transport (see {@link createSpyTransport}) — a
 * function or a `{ send }` object that merely records the payloads handed to it.
 * No real transport (console, network, queue, or vendor) is exercised, so the
 * assertions observe only the service's own dispatch/idempotency logic.
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
  assert.ok(spy.calls[0].at, 'payload carries a timestamp');
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
  assert.equal(spy.calls.length, 1, 'duplicate transition must not re-dispatch');
});

test('a distinct transition for the same order dispatches a new notification', async () => {
  const spy = createSpyTransport();
  const service = new NotificationService(spy);
  const order = { id: 'o1', status: 'DELIVERED', price: 100 };
  await service.sendStatusChangeNotification(order, 'CREATED', 'CONFIRMED');
  const next = await service.sendStatusChangeNotification(order, 'CONFIRMED', 'DELIVERED');
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
