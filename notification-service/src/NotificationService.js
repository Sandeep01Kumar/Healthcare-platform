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
 * ## Idempotency (in-memory, instance-lifetime, bounded)
 * Each {@link NotificationService} instance remembers which status-change
 * transitions it has already **successfully dispatched**, keyed by an unambiguous
 * encoded tuple of `(order.id, oldStatus, newStatus)`. A repeated call with the
 * same key is short-circuited and reported as a duplicate rather than dispatched a
 * second time. This de-duplication state is held **in memory only, with no
 * persistence** (per AAP Section 0.6.2): it protects against duplicate delivery
 * within a single process/instance lifetime and is reset when a new instance is
 * created or the process restarts. To avoid unbounded growth (CWE-400) the set is
 * capped and evicts its oldest entries FIFO once the cap is exceeded; a transition
 * evicted long after delivery could, in principle, be dispatched again.
 *
 * ## Delivery integrity (await + retry-safe)
 * Delivery is awaited. A transition is added to the processed set **only after the
 * transport resolves successfully**. If the transport throws or rejects, the
 * pending state is cleared, the key is NOT marked processed, and the error
 * propagates to the caller so the event can be retried — a failed send is never
 * silently reported as delivered.
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
 *   processed-key set before oldest-first eviction. Defaults to
 *   {@link DEFAULT_MAX_PROCESSED}.
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
 * @property {string} [status] The order's current status, if provided.
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
 * vocabulary; and that `oldStatus -> newStatus` is one of the exact permitted
 * lifecycle transitions.
 *
 * @param {OrderDTO} order The order whose status changed.
 * @param {string} oldStatus The status transitioned FROM.
 * @param {string} newStatus The status transitioned TO.
 * @returns {void}
 * @throws {TypeError} If `order` is not a valid object or `order.id` is not a
 *   non-blank string.
 * @throws {RangeError} If `order.id` is too long, a status is outside the
 *   supported vocabulary, or the transition is not a permitted lifecycle pair.
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
 * ## Idempotency contract
 * Every distinct `(order.id, oldStatus, newStatus)` transition is delivered to
 * the transport **at most once for the lifetime of a given instance**. Repeated
 * calls with the same key are short-circuited (not re-delivered) and reported as
 * duplicates in the returned {@link SendResult}. A transition is remembered
 * **only after a successful delivery**; a failed delivery is not remembered and
 * may be retried. The de-duplication set is held **in memory only, with no
 * persistence** (per AAP Section 0.6.2), and is bounded (oldest-first eviction).
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
     * Upper bound on {@link _processed} before oldest-first eviction (CWE-400).
     *
     * @private
     * @type {number}
     */
    this._maxProcessed =
      Number.isInteger(options.maxProcessed) && options.maxProcessed > 0
        ? options.maxProcessed
        : DEFAULT_MAX_PROCESSED;
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
   * the configured cap is exceeded to keep the set bounded (CWE-400).
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
   * Sends a notification for a single order status change — the authoritative
   * contract method mirroring the planned Java
   * `NotificationTrigger.onStatusChange`.
   *
   * ## Behavior
   * 1. Validates the event (`order`/`order.id`, status vocabulary, and that
   *    `oldStatus -> newStatus` is a permitted lifecycle transition).
   * 2. If the transition was already dispatched by this instance, returns
   *    `{ dispatched: false, duplicate: true, key }` without re-delivering.
   * 3. If an identical transition is already in flight, awaits and returns that
   *    single shared dispatch (no double-send).
   * 4. Otherwise builds the payload, **awaits** delivery through the transport,
   *    records the key **only on success**, and resolves with
   *    `{ dispatched: true, duplicate: false, key, payload }`.
   * 5. If delivery throws/rejects, the pending state is cleared, the key is NOT
   *    remembered, and the error propagates so the caller can retry.
   *
   * The de-duplication state is in-memory only (no persistence, per AAP 0.6.2).
   *
   * @param {OrderDTO} order The order whose status changed. Must be an object
   *   carrying a non-blank, length-bounded string `id`.
   * @param {string} oldStatus The status transitioned FROM (one of
   *   `CREATED`/`CONFIRMED`/`DELIVERED`).
   * @param {string} newStatus The status transitioned TO (one of
   *   `CREATED`/`CONFIRMED`/`DELIVERED`), forming a permitted transition with
   *   `oldStatus`.
   * @returns {Promise<SendResult>} Resolves with the dispatch outcome. When newly
   *   dispatched: `{ dispatched: true, duplicate: false, key, payload }`. When a
   *   duplicate: `{ dispatched: false, duplicate: true, key }` (no `payload`).
   * @throws {TypeError} If `order`/`order.id` is invalid.
   * @throws {RangeError} If a status is unsupported or the transition is invalid.
   *   (Both surface as a rejected promise.) Any transport delivery error is also
   *   propagated as a rejection.
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
    /** @type {NotificationPayload} */
    const payload = {
      orderId: order.id,
      oldStatus,
      newStatus,
      at: new Date().toISOString(),
    };
    const attempt = (async () => {
      try {
        await this._transport.send(payload);
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
