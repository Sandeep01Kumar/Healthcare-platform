/**
 * @module NotificationService
 *
 * Core of the `notification-service` module. Its single responsibility is to
 * **send a notification whenever an order's status changes**.
 *
 * ## Transport-agnostic library (no cross-process transport bundled)
 * This module is a **transport-agnostic library**: it builds a serialized,
 * language-neutral notification payload (see {@link NotificationPayload}) and
 * hands it to a pluggable transport sink. It deliberately does NOT open a socket,
 * bind an HTTP endpoint, or attach to a message queue itself. The concrete
 * cross-runtime bridge that carries an `order-service` (Java) status-change event
 * to this Node consumer — for example an HTTP POST, a message-queue subscriber, or
 * an IPC listener — is a **pluggable transport that is out of scope for this
 * feature** (per AAP Section 0.6.2, "concrete notification transports"). When that
 * bridge is realized, the producer serializes its event into the
 * {@link NotificationPayload} shape and this library delivers it through the
 * injected transport. A Java in-process callback cannot invoke this Node code
 * directly; a serialized transport is always required between the two runtimes.
 *
 * ## Planned producer contract
 * The `order-service` side is **planned** to expose a functional interface
 * (conceptually `NotificationTrigger.onStatusChange(order, from, to)`) that fires
 * on every successful order status transition
 * (`CREATED -> CONFIRMED -> DELIVERED`). That Java interface does not exist yet;
 * this file mirrors the *planned* contract as
 * {@link NotificationService#sendStatusChangeNotification}. There is intentionally
 * **no code-level import back into `order-service`** — the two sides are wired
 * together only through the serialized transport described above.
 *
 * ## Idempotency (in-memory, bounded-window, best-effort)
 * Each {@link NotificationService} instance remembers which status-change
 * transitions it has already **successfully dispatched**, keyed by an unambiguous
 * encoded tuple of `(order.id, oldStatus, newStatus)`. A repeated call with the
 * same key is short-circuited and reported as a duplicate rather than dispatched a
 * second time. This de-duplication state is held **in memory only, with no
 * persistence** (per AAP Section 0.6.2) and is reset when a new instance is created
 * or the process restarts.
 *
 * The guarantee is deliberately **bounded-window, best-effort — NOT an
 * instance-lifetime guarantee.** To avoid unbounded memory growth (CWE-400) the
 * processed-key set is capped at `maxProcessed` entries and evicts its oldest
 * entries oldest-first (FIFO) once the cap is exceeded. Concretely: a transition is
 * suppressed as a duplicate **only while its key still resides in the bounded
 * window of the most recent `maxProcessed` successful dispatches**. Once a key has
 * been evicted, a later replay of that same transition **can be dispatched again**.
 * Callers that require a stronger (durable / whole-lifetime) guarantee must layer
 * their own persistent de-duplication on top; this library does not provide it.
 *
 * ## Delivery integrity (await + retry-safe)
 * Delivery is awaited. A transition is added to the processed set **only after the
 * transport resolves successfully**. If the transport throws (synchronously or
 * asynchronously), rejects, or exceeds the delivery timeout, the pending in-flight
 * state is cleared, the key is NOT marked processed, and the error propagates to
 * the caller so the event can be retried — a failed send is never silently
 * reported as delivered. Pending in-flight state is registered **before** the
 * transport is ever invoked (delivery is deferred by a microtask), so even a
 * transport that throws on its very first synchronous statement leaves the
 * in-flight map clean and remains fully retryable.
 *
 * ## Resource bounds (timeout + backpressure)
 * A transport that never settles cannot pin resources indefinitely: each delivery
 * races an instance-configurable timeout (`deliveryTimeoutMs`) whose timer is
 * cleared the instant the delivery settles (holding the event loop for at most the
 * timeout window), and the number of concurrently in-flight deliveries is capped
 * (`maxInFlight`). When the in-flight cap is reached, a brand-new transition is
 * rejected with a backpressure error instead of being queued, so callers can slow
 * down or retry later. Duplicate / already-in-flight calls are never rejected for
 * capacity — they join the existing single dispatch.
 *
 * ## Pluggable transport (no concrete vendor)
 * Delivery is performed through a pluggable transport sink. A benign
 * console-logging {@link defaultTransport} is used when none is injected. No
 * concrete email/SMS/push vendor integration is included (per AAP Section 0.6.2);
 * consumers inject their own transport to deliver notifications for real.
 *
 * @remarks ESM module (`"type": "module"`); zero runtime dependencies — only
 * built-in JavaScript/Node globals are used.
 */

/**
 * The exact set of supported order lifecycle statuses.
 *
 * @type {ReadonlySet<string>}
 */
const ORDER_STATUSES = new Set(['CREATED', 'CONFIRMED', 'DELIVERED']);

/**
 * The only permitted forward status transitions, as an ordered
 * `from -> to` map (`CREATED -> CONFIRMED -> DELIVERED`).
 *
 * @type {ReadonlyMap<string, string>}
 */
const VALID_TRANSITIONS = new Map([
  ['CREATED', 'CONFIRMED'],
  ['CONFIRMED', 'DELIVERED'],
]);

/** Maximum accepted length of an `order.id` (bounds untrusted input). */
const MAX_ORDER_ID_LENGTH = 512;

/** Default cap on the in-memory processed-key set before FIFO eviction. */
const DEFAULT_MAX_PROCESSED = 10000;

/**
 * Default cap on the number of concurrently in-flight deliveries before new
 * transitions are refused with a backpressure error (CWE-400 availability guard).
 */
const DEFAULT_MAX_IN_FLIGHT = 1000;

/**
 * Default per-delivery timeout in milliseconds. A transport that neither resolves
 * nor rejects within this window is treated as a failed delivery (and is therefore
 * retryable), preventing a hung transport from pinning an in-flight slot forever.
 */
const DEFAULT_DELIVERY_TIMEOUT_MS = 30000;

/** Maximum length of any single field emitted by the default log transport. */
const MAX_LOG_FIELD_LENGTH = 128;

/**
 * A transport delivery function. Receives the fully-built notification payload
 * and delivers it through some sink (log, queue, HTTP call, etc.). May be
 * synchronous or asynchronous; when it returns a promise, that promise is awaited
 * and its rejection is treated as a delivery failure.
 *
 * @callback TransportFn
 * @param {NotificationPayload} notification The notification payload to deliver.
 * @returns {void|Promise<void>} Nothing, or a promise that resolves when done.
 */

/**
 * A transport expressed as an object exposing a `send` method. This is the
 * normalized transport shape used internally by {@link NotificationService}.
 *
 * @typedef {Object} TransportObject
 * @property {TransportFn} send Delivers a single notification payload.
 */

/**
 * Options accepted by the {@link NotificationService} constructor.
 *
 * @typedef {Object} NotificationServiceOptions
 * @property {number} [maxProcessed] Positive integer cap on the in-memory
 *   processed-key (de-duplication) set before oldest-first eviction. Defaults to
 *   {@link DEFAULT_MAX_PROCESSED}. This is the size of the bounded best-effort
 *   de-duplication window (see the module idempotency section).
 * @property {number} [maxInFlight] Positive integer cap on the number of
 *   concurrently in-flight deliveries. When reached, a brand-new transition is
 *   rejected with a backpressure error rather than queued. Defaults to
 *   {@link DEFAULT_MAX_IN_FLIGHT}.
 * @property {number} [deliveryTimeoutMs] Positive integer per-delivery timeout in
 *   milliseconds. A transport that does not settle within this window is treated
 *   as a failed (retryable) delivery. Defaults to
 *   {@link DEFAULT_DELIVERY_TIMEOUT_MS}.
 */

/**
 * A plain, serialized order data-transfer object as seen by this consumer.
 *
 * **This is NOT the Java `Order` object** — it is the serialized, cross-language
 * representation carried by the transport. Only `id` is required and it acts as
 * the identity key used to build the idempotency dedupe key.
 *
 * @typedef {Object} OrderDTO
 * @property {string} id The unique order identifier (the identity key); must be a
 *   non-blank string no longer than {@link MAX_ORDER_ID_LENGTH} characters.
 * @property {string} [status] The order's current status, if provided. **When
 *   present it must be a supported status equal to the transition's `newStatus`**
 *   — a status-change event whose carried order status disagrees with the status
 *   it transitioned TO is internally inconsistent and is rejected (see
 *   {@link validateEvent}). Omit this field to send a status-agnostic event.
 * @property {number} [price] The order's price, if provided.
 */

/**
 * The payload handed to the transport for a single status-change notification.
 * This is the serialized event DTO exchanged across the runtime boundary.
 *
 * @typedef {Object} NotificationPayload
 * @property {string} orderId The order identifier (taken from `order.id`).
 * @property {string} oldStatus The status the order transitioned FROM.
 * @property {string} newStatus The status the order transitioned TO.
 * @property {string} at ISO-8601 timestamp of when the notification was built.
 */

/**
 * The result of a {@link NotificationService#sendStatusChangeNotification} call.
 *
 * @typedef {Object} SendResult
 * @property {boolean} dispatched `true` if the notification was delivered to the
 *   transport on this call; `false` if it was short-circuited as a duplicate.
 * @property {boolean} duplicate `true` if this exact transition had already been
 *   dispatched by this instance (idempotency short-circuit); otherwise `false`.
 * @property {string} key The stable dedupe key for this transition.
 * @property {NotificationPayload} [payload] The dispatched payload; present only
 *   when `dispatched` is `true`.
 */

/**
 * Neutralizes a value for single-line, log-safe output.
 *
 * Coerces the value to a string, replaces control characters (including CR/LF)
 * with the Unicode replacement character so a crafted identifier cannot forge or
 * split log lines (CWE-117), and truncates to {@link MAX_LOG_FIELD_LENGTH} to
 * avoid emitting unbounded/oversized identifiers.
 *
 * @param {*} value The value to neutralize.
 * @returns {string} A control-character-free, length-bounded string.
 */
function sanitizeForLog(value) {
  const text = String(value).replace(/[\u0000-\u001F\u007F]/g, '\uFFFD');
  return text.length > MAX_LOG_FIELD_LENGTH
    ? `${text.slice(0, MAX_LOG_FIELD_LENGTH)}…`
    : text;
}

/**
 * Validates a status-change event before any dispatch or dedupe work.
 *
 * Enforces that `order` is a non-null, non-array object carrying a non-blank,
 * length-bounded string `id`; that both statuses belong to the supported
 * vocabulary; that `oldStatus -> newStatus` is one of the exact permitted
 * lifecycle transitions; and that, **when the order carries its own `status`
 * field, that field is a supported status equal to `newStatus`** so the event is
 * internally consistent (an order claiming a status different from the one it just
 * transitioned to is a malformed event and is rejected).
 *
 * @param {OrderDTO} order The order whose status changed.
 * @param {string} oldStatus The status transitioned FROM.
 * @param {string} newStatus The status transitioned TO.
 * @returns {void}
 * @throws {TypeError} If `order` is not a valid object or `order.id` is not a
 *   non-blank string.
 * @throws {RangeError} If `order.id` is too long, a status is outside the
 *   supported vocabulary, the transition is not a permitted lifecycle pair, or a
 *   present `order.status` is unsupported or does not equal `newStatus`.
 */
function validateEvent(order, oldStatus, newStatus) {
  if (order === null || typeof order !== 'object' || Array.isArray(order)) {
    throw new TypeError('order must be a non-null object');
  }
  if (typeof order.id !== 'string' || order.id.trim() === '') {
    throw new TypeError('order.id must be a non-blank string');
  }
  if (order.id.length > MAX_ORDER_ID_LENGTH) {
    throw new RangeError(
      `order.id must not exceed ${MAX_ORDER_ID_LENGTH} characters`
    );
  }
  if (!ORDER_STATUSES.has(oldStatus)) {
    throw new RangeError(
      `oldStatus must be one of CREATED, CONFIRMED, DELIVERED: ${JSON.stringify(oldStatus)}`
    );
  }
  if (!ORDER_STATUSES.has(newStatus)) {
    throw new RangeError(
      `newStatus must be one of CREATED, CONFIRMED, DELIVERED: ${JSON.stringify(newStatus)}`
    );
  }
  if (VALID_TRANSITIONS.get(oldStatus) !== newStatus) {
    throw new RangeError(
      `invalid transition ${oldStatus} -> ${newStatus}`
    );
  }
  // Event-integrity guard: `order.status` is optional, but when supplied it must
  // be a supported status that agrees with the status just transitioned TO. This
  // rejects internally inconsistent events (e.g. an order marked DELIVERED carried
  // on a CREATED -> CONFIRMED transition) instead of silently dispatching them.
  if (order.status !== undefined) {
    if (typeof order.status !== 'string' || !ORDER_STATUSES.has(order.status)) {
      throw new RangeError(
        `order.status must be one of CREATED, CONFIRMED, DELIVERED: ${JSON.stringify(order.status)}`
      );
    }
    if (order.status !== newStatus) {
      throw new RangeError(
        `order.status ${JSON.stringify(order.status)} is inconsistent with the ` +
          `transition target newStatus ${JSON.stringify(newStatus)}`
      );
    }
  }
}

/**
 * Default transport used when no transport is injected into
 * {@link NotificationService}. It logs a single **structured** record to the
 * console with control-character-neutralized, length-bounded fields and performs
 * no other side effects.
 *
 * This is intentionally a benign, side-effect-light default: it deliberately
 * does **not** integrate any concrete email/SMS/push vendor (per AAP Section
 * 0.6.2). Replace it by injecting your own transport when real delivery is
 * required.
 *
 * @type {TransportObject}
 */
const defaultTransport = {
  /**
   * Logs the notification as a single structured console record. All string
   * fields are neutralized via {@link sanitizeForLog} so untrusted identifiers
   * cannot forge additional log lines (CWE-117).
   *
   * @param {NotificationPayload} notification The notification to log.
   * @returns {void}
   */
  send(notification) {
    console.log('[notification-service] order status change', {
      orderId: sanitizeForLog(notification.orderId),
      oldStatus: sanitizeForLog(notification.oldStatus),
      newStatus: sanitizeForLog(notification.newStatus),
      at: sanitizeForLog(notification.at),
    });
  },
};

/**
 * Normalizes a caller-supplied transport into a uniform {@link TransportObject}.
 *
 * Policy:
 * - `undefined`/`null` (transport **omitted**) — falls back to
 *   {@link defaultTransport};
 * - a {@link TransportFn} function — wrapped as `{ send: fn }`;
 * - a {@link TransportObject} (an object exposing a `send` method) — returned
 *   as-is;
 * - anything else (a **malformed explicit** transport, e.g. a number, string, or
 *   object with no `send` method) — throws a {@link TypeError} so a production
 *   misconfiguration surfaces immediately instead of silently degrading to the
 *   console.
 *
 * @param {TransportFn|TransportObject} [transport] The transport to normalize.
 * @returns {TransportObject} A transport object exposing a `send` method.
 * @throws {TypeError} If an explicit but malformed transport value is supplied.
 */
function normalizeTransport(transport) {
  if (transport === undefined || transport === null) {
    return defaultTransport;
  }
  if (typeof transport === 'function') {
    return { send: transport };
  }
  if (typeof transport === 'object' && typeof transport.send === 'function') {
    return transport;
  }
  throw new TypeError(
    'transport must be a function or an object exposing a send() method'
  );
}

/**
 * Sends notifications on order status changes.
 *
 * This is the transport-agnostic consumer of the *planned* cross-language
 * status-change seam. It mirrors the planned Java `order-service`
 * `NotificationTrigger.onStatusChange` contract via
 * {@link NotificationService#sendStatusChangeNotification} and delivers each
 * notification through a pluggable transport.
 *
 * ## Idempotency contract (bounded-window, best-effort)
 * Every distinct `(order.id, oldStatus, newStatus)` transition is delivered to the
 * transport **at most once while its key remains within the bounded
 * de-duplication window** — the most recent `maxProcessed` successful dispatches.
 * Repeated calls with a key still inside that window are short-circuited (not
 * re-delivered) and reported as duplicates in the returned {@link SendResult}. A
 * transition is remembered **only after a successful delivery**; a failed delivery
 * is not remembered and may be retried. The de-duplication set is held **in memory
 * only, with no persistence** (per AAP Section 0.6.2), and is bounded with
 * oldest-first (FIFO) eviction: once a key is evicted, a later replay of that
 * transition **may dispatch again**. This is therefore a best-effort, bounded-
 * window guarantee, **not** a durable or whole-instance-lifetime one.
 *
 * @example
 * // Default console transport
 * const svc = new NotificationService();
 * await svc.sendStatusChangeNotification({ id: 'A1' }, 'CREATED', 'CONFIRMED');
 *
 * @example
 * // Injected async function transport
 * const svc = new NotificationService((n) => queue.publish(n));
 * await svc.sendStatusChangeNotification({ id: 'A1' }, 'CONFIRMED', 'DELIVERED');
 */
export class NotificationService {
  /**
   * @param {TransportFn|TransportObject} [transport] Pluggable delivery sink.
   *   May be a function (called with the payload) or an object exposing a
   *   `send` method. Defaults to a console-logging transport when omitted. An
   *   explicit but malformed value throws a {@link TypeError}.
   * @param {NotificationServiceOptions} [options] Optional configuration.
   * @throws {TypeError} If an explicit but malformed `transport` is supplied.
   */
  constructor(transport, options = {}) {
    /**
     * The normalized transport used to deliver notifications.
     *
     * @private
     * @type {TransportObject}
     */
    this._transport = normalizeTransport(transport);

    /**
     * Set of dedupe keys for transitions already **successfully dispatched** by
     * this instance. In-memory only — no persistence (per AAP Section 0.6.2) —
     * and bounded by {@link _maxProcessed} with oldest-first eviction.
     *
     * @private
     * @type {Set<string>}
     */
    this._processed = new Set();

    /**
     * In-flight deliveries keyed by dedupe key, so concurrent calls for the same
     * transition share a single dispatch rather than double-sending.
     *
     * @private
     * @type {Map<string, Promise<SendResult>>}
     */
    this._inFlight = new Map();

    /**
     * Upper bound on {@link _processed} (the bounded de-duplication window) before
     * oldest-first eviction (CWE-400).
     *
     * @private
     * @type {number}
     */
    this._maxProcessed =
      Number.isInteger(options.maxProcessed) && options.maxProcessed > 0
        ? options.maxProcessed
        : DEFAULT_MAX_PROCESSED;

    /**
     * Upper bound on the number of concurrently in-flight deliveries. New
     * transitions are refused with a backpressure error once this many deliveries
     * are already in flight, preventing unbounded {@link _inFlight} growth from a
     * transport that never settles (CWE-400).
     *
     * @private
     * @type {number}
     */
    this._maxInFlight =
      Number.isInteger(options.maxInFlight) && options.maxInFlight > 0
        ? options.maxInFlight
        : DEFAULT_MAX_IN_FLIGHT;

    /**
     * Per-delivery timeout in milliseconds. A transport that does not settle within
     * this window is treated as a failed (retryable) delivery so a hung transport
     * cannot pin an in-flight slot indefinitely.
     *
     * @private
     * @type {number}
     */
    this._deliveryTimeoutMs =
      Number.isInteger(options.deliveryTimeoutMs) && options.deliveryTimeoutMs > 0
        ? options.deliveryTimeoutMs
        : DEFAULT_DELIVERY_TIMEOUT_MS;
  }

  /**
   * Builds the stable, **unambiguous** de-duplication key for a status-change
   * transition.
   *
   * The key is the JSON encoding of the `[order.id, oldStatus, newStatus]` tuple.
   * Encoding the tuple (rather than concatenating with a delimiter) guarantees
   * that identifiers containing delimiter-like characters cannot collide with a
   * different transition.
   *
   * @param {OrderDTO} order The order (only `order.id` is used as identity).
   * @param {string} oldStatus The status transitioned FROM.
   * @param {string} newStatus The status transitioned TO.
   * @returns {string} The stable, collision-free dedupe key for this transition.
   */
  static dedupeKey(order, oldStatus, newStatus) {
    return JSON.stringify([order?.id, oldStatus, newStatus]);
  }

  /**
   * Records a successfully dispatched key, evicting the oldest entries FIFO once
   * the configured cap ({@link _maxProcessed}) is exceeded to keep the set bounded
   * (CWE-400).
   *
   * Because eviction is oldest-first, this implements the **bounded-window,
   * best-effort** de-duplication described in the class idempotency contract:
   * a key that has been evicted is no longer recognized as a duplicate, so a later
   * replay of that transition can dispatch again. It is intentionally NOT a
   * whole-lifetime guarantee.
   *
   * @private
   * @param {string} key The dedupe key to remember.
   * @returns {void}
   */
  _rememberKey(key) {
    this._processed.add(key);
    while (this._processed.size > this._maxProcessed) {
      const oldest = this._processed.values().next().value;
      this._processed.delete(oldest);
    }
  }

  /**
   * Delivers a payload through the configured transport, racing the delivery
   * against {@link _deliveryTimeoutMs} and guaranteeing the transport is invoked
   * **asynchronously** (never on the caller's synchronous stack).
   *
   * The transport invocation is deferred onto a microtask, which has two
   * important consequences:
   * - a transport that throws **synchronously** surfaces that error as a promise
   *   rejection (never as a synchronous throw out of this method), so the caller's
   *   in-flight bookkeeping stays consistent and the transition remains retryable;
   *   and
   * - the returned promise settles exactly once — on transport resolution,
   *   transport rejection, or timeout, whichever occurs first (subsequent
   *   outcomes are ignored via the `settled` latch).
   *
   * The timeout timer is always cleared via `clearTimeout` the instant the
   * delivery settles, so it never fires spuriously afterwards and holds the Node
   * event loop for **at most** {@link _deliveryTimeoutMs} while a delivery is
   * genuinely in progress. It is intentionally NOT `unref`'d: an `unref`'d timer
   * can fail to fire in an otherwise-idle event loop, which would let a hung
   * transport hang an awaiting caller forever and defeat the timeout's purpose.
   *
   * @private
   * @param {NotificationPayload} payload The notification payload to deliver.
   * @returns {Promise<void>} Resolves when the transport reports success; rejects
   *   if the transport throws/rejects or the delivery exceeds
   *   {@link _deliveryTimeoutMs}.
   */
  _deliverWithTimeout(payload) {
    const timeoutMs = this._deliveryTimeoutMs;
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        reject(new Error(`notification delivery timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      /**
       * Settles the outer promise at most once and cancels the timeout timer.
       * @param {(value?: unknown) => void} settle `resolve` or `reject`.
       * @param {unknown} [arg] The value/reason to settle with.
       * @returns {void}
       */
      const finish = (settle, arg) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        settle(arg);
      };
      // Defer the transport call to a microtask: a synchronous throw becomes a
      // rejection, and in-flight registration in the caller always runs first.
      Promise.resolve()
        .then(() => this._transport.send(payload))
        .then(
          () => finish(resolve),
          (err) => finish(reject, err)
        );
    });
  }

  /**
   * Sends a notification for a single order status change — the authoritative
   * contract method mirroring the planned Java
   * `NotificationTrigger.onStatusChange`.
   *
   * ## Behavior
   * 1. Validates the event (`order`/`order.id`, status vocabulary, that
   *    `oldStatus -> newStatus` is a permitted lifecycle transition, and — when
   *    `order.status` is present — that it equals `newStatus`).
   * 2. If the transition is still inside the bounded de-duplication window
   *    (already successfully dispatched), returns
   *    `{ dispatched: false, duplicate: true, key }` without re-delivering.
   * 3. If an identical transition is already in flight, returns that single shared
   *    dispatch (no double-send); such calls are never rejected for capacity.
   * 4. If the in-flight capacity ({@link _maxInFlight}) is already reached for a
   *    brand-new transition, rejects with a backpressure error so the caller can
   *    retry later.
   * 5. Otherwise builds the payload and registers the in-flight promise **before**
   *    the transport is invoked; delivery is deferred by a microtask and raced
   *    against the per-delivery timeout ({@link _deliveryTimeoutMs}). On success it
   *    records the key **only then** and resolves with
   *    `{ dispatched: true, duplicate: false, key, payload }`.
   * 6. If delivery throws (synchronously or asynchronously), rejects, or times out,
   *    the in-flight state is cleared, the key is NOT remembered, and the error
   *    propagates so the caller can retry the transition.
   *
   * The de-duplication state is in-memory only (no persistence, per AAP 0.6.2) and
   * is a bounded, best-effort window (see the class idempotency contract).
   *
   * @param {OrderDTO} order The order whose status changed. Must be an object
   *   carrying a non-blank, length-bounded string `id`; if it carries a `status`
   *   it must equal `newStatus`.
   * @param {string} oldStatus The status transitioned FROM (one of
   *   `CREATED`/`CONFIRMED`/`DELIVERED`).
   * @param {string} newStatus The status transitioned TO (one of
   *   `CREATED`/`CONFIRMED`/`DELIVERED`), forming a permitted transition with
   *   `oldStatus`.
   * @returns {Promise<SendResult>} Resolves with the dispatch outcome. When newly
   *   dispatched: `{ dispatched: true, duplicate: false, key, payload }`. When a
   *   duplicate: `{ dispatched: false, duplicate: true, key }` (no `payload`).
   * @throws {TypeError} If `order`/`order.id` is invalid.
   * @throws {RangeError} If a status is unsupported, the transition is invalid, or
   *   a present `order.status` disagrees with `newStatus`.
   * @throws {Error} If the in-flight capacity is reached (backpressure) or the
   *   transport delivery fails or times out. (All rejection reasons surface as a
   *   rejected promise.)
   */
  async sendStatusChangeNotification(order, oldStatus, newStatus) {
    validateEvent(order, oldStatus, newStatus);
    const key = NotificationService.dedupeKey(order, oldStatus, newStatus);
    if (this._processed.has(key)) {
      return { dispatched: false, duplicate: true, key };
    }
    const pending = this._inFlight.get(key);
    if (pending) {
      return pending;
    }
    // Backpressure: refuse a brand-new transition once the in-flight cap is
    // reached so a slow or hung transport cannot grow the in-flight map without
    // bound (CWE-400). Duplicate and already-in-flight calls are handled above and
    // never reach this check, so they are never rejected for capacity.
    if (this._inFlight.size >= this._maxInFlight) {
      throw new Error(
        `notification delivery refused: in-flight capacity of ${this._maxInFlight} reached`
      );
    }
    /** @type {NotificationPayload} */
    const payload = {
      orderId: order.id,
      oldStatus,
      newStatus,
      at: new Date().toISOString(),
    };
    // Register the in-flight promise BEFORE the transport can run. Because
    // `_deliverWithTimeout` defers the transport invocation to a microtask, the
    // transport (and therefore the `finally` cleanup below) cannot execute until
    // after `this._inFlight.set(key, attempt)` has run. This ordering is what
    // makes even a synchronously-throwing transport fully retryable: the key the
    // `finally` clears is guaranteed to have been installed first.
    const attempt = (async () => {
      try {
        await this._deliverWithTimeout(payload);
        this._rememberKey(key);
        return { dispatched: true, duplicate: false, key, payload };
      } finally {
        this._inFlight.delete(key);
      }
    })();
    this._inFlight.set(key, attempt);
    return attempt;
  }
}

/**
 * Convenience default export of {@link NotificationService}.
 *
 * @type {typeof NotificationService}
 */
export default NotificationService;
