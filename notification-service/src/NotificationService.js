/**
 * @module NotificationService
 *
 * Core of the `notification-service` module. Its single responsibility is to
 * **send a notification whenever an order's status changes**.
 *
 * ## Cross-language consumer role
 * This module is the CONSUMER end of a decoupled, cross-language
 * producer/consumer seam. The Java `order-service` is the PRODUCER: it defines
 * a nested functional interface
 * `NotificationTrigger { void onStatusChange(Order order, OrderStatus from, OrderStatus to); }`
 * that fires on every successful order status transition
 * (`CREATED -> CONFIRMED -> DELIVERED`). This JavaScript file MIRRORS that
 * contract as {@link NotificationService#sendStatusChangeNotification}. There is
 * intentionally **no code-level import back into `order-service`** — the two
 * sides are wired together externally (the Java side injects/invokes an
 * implementation of its trigger), so this module never references order-service
 * source directly.
 *
 * ## Idempotency (in-memory only)
 * Each {@link NotificationService} instance remembers which status-change
 * transitions it has already dispatched, keyed by
 * `` `${order.id}:${oldStatus}->${newStatus}` ``. A repeated call with the same
 * key is short-circuited and reported as a duplicate rather than dispatched a
 * second time. This de-duplication state is held **in memory only, with no
 * persistence** (per AAP Section 0.6.2) — it protects against duplicate
 * delivery within a single process/instance lifetime and is reset when a new
 * instance is created or the process restarts.
 *
 * ## Pluggable transport (no concrete vendor)
 * Delivery is performed through a pluggable transport sink. A benign
 * console-logging {@link defaultTransport} is used when none is injected. No
 * concrete email/SMS/push vendor integration is included (per AAP Section
 * 0.6.2); consumers inject their own transport to deliver notifications for
 * real.
 *
 * @remarks ESM module (`"type": "module"`); zero runtime dependencies — only
 * built-in JavaScript/Node globals are used.
 */

/**
 * A transport delivery function. Receives the fully-built notification payload
 * and delivers it through some sink (log, queue, HTTP call, etc.). May be
 * synchronous or asynchronous; its return value is not consumed by this module.
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
 * A plain, serialized order data-transfer object as seen by this consumer.
 *
 * **This is NOT the Java `Order` object** — it is the serialized, cross-language
 * representation. Only `id` is required and it acts as the identity key used to
 * build the idempotency dedupe key.
 *
 * @typedef {Object} OrderDTO
 * @property {string} id The unique order identifier (the identity key).
 * @property {string} [status] The order's current status, if provided.
 * @property {number} [price] The order's price, if provided.
 */

/**
 * The payload handed to the transport for a single status-change notification.
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
 * Default transport used when no transport is injected into
 * {@link NotificationService}. It logs a single, human-readable line to the
 * console and performs no other side effects.
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
   * Logs the notification as a single console line.
   *
   * @param {NotificationPayload} notification The notification to log.
   * @returns {void}
   */
  send(notification) {
    console.log(
      `[notification-service] order ${notification.orderId}: ` +
        `${notification.oldStatus} -> ${notification.newStatus} @ ${notification.at}`
    );
  },
};

/**
 * Normalizes a caller-supplied transport into a uniform {@link TransportObject}.
 *
 * Accepts any of:
 * - a {@link TransportFn} function — wrapped as `{ send: fn }`;
 * - a {@link TransportObject} (any object exposing a `send` method) — returned
 *   as-is;
 * - `undefined`/`null`/anything else — falls back to {@link defaultTransport}.
 *
 * @param {TransportFn|TransportObject} [transport] The transport to normalize.
 * @returns {TransportObject} A transport object exposing a `send` method.
 */
function normalizeTransport(transport) {
  if (typeof transport === 'function') return { send: transport };
  if (transport && typeof transport.send === 'function') return transport;
  return defaultTransport;
}

/**
 * Sends notifications on order status changes.
 *
 * This is the authoritative consumer of the cross-language status-change seam.
 * It mirrors the Java `order-service` `NotificationTrigger.onStatusChange`
 * contract via {@link NotificationService#sendStatusChangeNotification} and
 * delivers each notification through a pluggable transport.
 *
 * ## Idempotency contract
 * Every distinct `(order.id, oldStatus, newStatus)` transition is dispatched to
 * the transport **at most once for the lifetime of a given instance**. Repeated
 * calls with the same key are short-circuited (not re-delivered) and reported
 * as duplicates in the returned {@link SendResult}. The de-duplication set is
 * held **in memory only, with no persistence** (per AAP Section 0.6.2).
 *
 * @example
 * // Default console transport
 * const svc = new NotificationService();
 * svc.sendStatusChangeNotification({ id: 'A1' }, 'CREATED', 'CONFIRMED');
 *
 * @example
 * // Injected function transport
 * const svc = new NotificationService((n) => queue.publish(n));
 * svc.sendStatusChangeNotification({ id: 'A1' }, 'CONFIRMED', 'DELIVERED');
 */
export class NotificationService {
  /**
   * @param {TransportFn|TransportObject} [transport] Pluggable delivery sink.
   *   May be a function (called with the payload) or an object exposing a
   *   `send` method. Defaults to a console-logging transport when omitted.
   */
  constructor(transport) {
    /**
     * The normalized transport used to deliver notifications.
     *
     * @private
     * @type {TransportObject}
     */
    this._transport = normalizeTransport(transport);

    /**
     * Set of dedupe keys for transitions already dispatched by this instance.
     *
     * In-memory only — no persistence (per AAP Section 0.6.2). This backs the
     * idempotency contract described on the class.
     *
     * @private
     * @type {Set<string>}
     */
    this._processed = new Set();
  }

  /**
   * Builds the stable de-duplication key for a status-change transition.
   *
   * The key format is `` `${order.id}:${oldStatus}->${newStatus}` ``, making
   * each distinct transition of a given order uniquely identifiable.
   *
   * @param {OrderDTO} order The order (only `order.id` is used as identity).
   * @param {string} oldStatus The status transitioned FROM.
   * @param {string} newStatus The status transitioned TO.
   * @returns {string} The stable dedupe key for this transition.
   */
  static dedupeKey(order, oldStatus, newStatus) {
    return `${order.id}:${oldStatus}->${newStatus}`;
  }

  /**
   * Sends a notification for a single order status change — the authoritative
   * contract method mirroring the Java `NotificationTrigger.onStatusChange`.
   *
   * ## Idempotency contract
   * Each distinct `(order.id, oldStatus, newStatus)` transition is dispatched at
   * most once for this instance's lifetime. On the first call for a key the
   * payload is built, delivered to the transport, and the key is remembered; any
   * subsequent call with the same key is short-circuited — the transport is
   * **not** invoked again — and the result reports `duplicate: true`. The
   * de-duplication state is in-memory only (no persistence, per AAP 0.6.2).
   *
   * @param {OrderDTO} order The order whose status changed. Must carry an `id`
   *   (the identity key); `status`/`price` are optional and unused here.
   * @param {string} oldStatus The status transitioned FROM (e.g. one of
   *   `CREATED`/`CONFIRMED`/`DELIVERED`).
   * @param {string} newStatus The status transitioned TO (e.g. one of
   *   `CREATED`/`CONFIRMED`/`DELIVERED`).
   * @returns {SendResult} The dispatch outcome. When newly dispatched:
   *   `{ dispatched: true, duplicate: false, key, payload }`. When a duplicate:
   *   `{ dispatched: false, duplicate: true, key }` (no `payload`).
   */
  sendStatusChangeNotification(order, oldStatus, newStatus) {
    const key = NotificationService.dedupeKey(order, oldStatus, newStatus);
    if (this._processed.has(key)) {
      return { dispatched: false, duplicate: true, key };
    }
    /** @type {NotificationPayload} */
    const payload = {
      orderId: order.id,
      oldStatus,
      newStatus,
      at: new Date().toISOString(),
    };
    this._transport.send(payload);
    this._processed.add(key);
    return { dispatched: true, duplicate: false, key, payload };
  }
}

/**
 * Convenience default export of {@link NotificationService}.
 *
 * @type {typeof NotificationService}
 */
export default NotificationService;
