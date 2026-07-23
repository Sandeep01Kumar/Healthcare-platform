/**
 * Authoritative, in-repository wire contract between `customer-ui` and
 * `order-service`.
 *
 * This module is the SINGLE SOURCE OF TRUTH for the HTTP surface the browser
 * uses to talk to the order-service: the route paths and methods, the order
 * lifecycle status vocabulary, the request/response DTO shapes, the input bounds,
 * and the error-envelope shape. Both sides implement against it:
 *
 * - the client ({@link module:orderServiceClient}) imports these endpoints,
 *   bounds, and schema validators so its assumptions are ratified here rather
 *   than hard-coded ad hoc in the caller; and
 * - the delivered order-service HTTP layer (`com.healthcare.order.api`) exposes
 *   exactly these routes (`POST /coupons/validate`, `POST /orders`,
 *   `GET /orders/{id}`, `POST /orders/{id}/status`), returns the documented DTOs
 *   and status vocabulary, and emits the documented error envelope on failure.
 *
 * ## Transport & CORS requirements (server-side contract)
 * All responses are JSON (`Content-Type: application/json`). Because the SPA and
 * the order-service may be served from different origins in development, the
 * server MUST either (a) be reached through the Vite dev proxy configured in
 * `vite.config.js` (same-origin `/coupons` and `/orders` paths forwarded to the
 * backend), or (b) send permissive CORS headers for the SPA origin
 * (`Access-Control-Allow-Origin`, `-Methods: GET, POST, OPTIONS`,
 * `-Headers: Content-Type`) and answer `OPTIONS` preflight requests. The client
 * never sends credentials, so `Access-Control-Allow-Credentials` is not required.
 *
 * ## Error envelope
 * On any non-2xx response the server returns a JSON body of the shape described
 * by {@link ErrorEnvelope}. The client records `error.message`/`error.code` and
 * the HTTP method, path, and status on the thrown {@link Error}'s diagnostic
 * `message` for logs, while surfacing only a safe, status-mapped `userMessage`
 * to the customer (never raw lower-layer text).
 *
 * @module orderServiceContract
 */

/**
 * The exact, ordered order lifecycle status vocabulary. Mirrors the order-service
 * `OrderStatus` enum (forward-only `CREATED -> CONFIRMED -> DELIVERED`); there is
 * deliberately NO `CANCELLED` status.
 *
 * @constant {ReadonlyArray<string>}
 */
export const ORDER_STATUSES = Object.freeze(['CREATED', 'CONFIRMED', 'DELIVERED']);

/**
 * Maximum accepted length of a single coupon code (client-side input bound; the
 * server remains authoritative for business validity).
 *
 * @constant {number}
 */
export const MAX_COUPON_CODE_LENGTH = 64;

/**
 * Maximum number of coupon codes accepted in a single {@code validateCoupons}
 * batch (business maximum; bounds fan-out).
 *
 * @constant {number}
 */
export const MAX_COUPONS = 25;

/**
 * Maximum accepted length of an order identifier (client-side input bound).
 *
 * @constant {number}
 */
export const MAX_ORDER_ID_LENGTH = 128;

/**
 * Maximum number of concurrent in-flight requests the batch coupon validation
 * will run at once (bounds concurrency; CWE-400).
 *
 * @constant {number}
 */
export const REQUEST_CONCURRENCY_LIMIT = 5;

/**
 * Default per-request timeout in milliseconds, enforced via {@code AbortController}
 * when the caller does not supply their own signal/timeout.
 *
 * @constant {number}
 */
export const DEFAULT_TIMEOUT_MS = 10000;

/**
 * The contracted HTTP endpoints. Paths are relative to the configured base URL.
 *
 * @constant
 * @property {{method: string, path: string}} validateCoupon
 *   `POST /coupons/validate` — body `{ code }`.
 * @property {{method: string, path: string}} createOrder
 *   `POST /orders` — body `{ price, couponCodes }`; returns the created order
 *   view (status `CREATED`). Coupon redemption happens here, server-side.
 * @property {{method: string, path: (orderId: string) => string}} getOrder
 *   `GET /orders/{id}` — the id segment is URL-encoded by {@code path()}.
 */
export const ENDPOINTS = Object.freeze({
  validateCoupon: Object.freeze({
    method: 'POST',
    path: '/coupons/validate',
  }),
  createOrder: Object.freeze({
    method: 'POST',
    path: '/orders',
  }),
  getOrder: Object.freeze({
    method: 'GET',
    path: (orderId) => `/orders/${encodeURIComponent(orderId)}`,
  }),
});

/**
 * Canonical identity for a coupon code, shared by the UI (applied-coupon
 * de-duplication) and the client (batch de-duplication before submission) so
 * both sides agree with the server, which treats coupon codes
 * case-insensitively after trimming surrounding whitespace.
 *
 * A code entered as `"save10"`, `"SAVE10"`, or `" SAVE10 "` all canonicalize to
 * the SAME identity (`"SAVE10"`), matching the order-service, which redeems each
 * distinct canonical code once regardless of the casing/spacing submitted.
 *
 * @param {*} code The raw coupon code (coerced to a string).
 * @returns {string} The canonical (trimmed, upper-cased) code identity.
 */
export function canonicalizeCouponCode(code) {
  return String(code == null ? '' : code).trim().toUpperCase();
}

/**
 * Discount metadata carried by a coupon verdict.
 *
 * @typedef {Object} DiscountMetadata
 * @property {string} type The discount type (for example `PERCENTAGE`/`FIXED`).
 * @property {number|string} value The discount magnitude.
 */

/**
 * The server's normalized coupon verdict. The client returns this verbatim and
 * never recomputes the `valid` decision.
 *
 * @typedef {Object} CouponVerdict
 * @property {boolean} valid Whether the server accepted the coupon.
 * @property {string} reason Human-readable explanation of the verdict.
 * @property {DiscountMetadata} [discount] Discount metadata; present when `valid`.
 */

/**
 * The order view returned by the order endpoint.
 *
 * @typedef {Object} OrderView
 * @property {string} id The order identifier.
 * @property {('CREATED'|'CONFIRMED'|'DELIVERED')} status The lifecycle status.
 * @property {number|string} [price] The original price.
 * @property {number|string} [discountedTotal] The discounted total.
 * @property {Array} [appliedCoupons] The coupons applied to the order.
 */

/**
 * The error body returned by the server on a non-2xx response.
 *
 * @typedef {Object} ErrorEnvelope
 * @property {{code: string, message: string, details?: Object}} error
 *   Structured error information.
 */

/**
 * Reports whether a value is one of the exact supported {@link ORDER_STATUSES}.
 *
 * @param {*} value The candidate status.
 * @returns {boolean} `true` if the value is a supported lifecycle status.
 */
export function isOrderStatus(value) {
  return typeof value === 'string' && ORDER_STATUSES.includes(value);
}

/**
 * Validates the structural shape of a coupon verdict WITHOUT recomputing the
 * server's `valid` decision. Ensures the fields the UI relies on are present and
 * correctly typed.
 *
 * @param {*} verdict The parsed response body to validate.
 * @returns {CouponVerdict} The same object, once validated.
 * @throws {TypeError} If the verdict is not an object, `valid` is not a boolean,
 *   `reason` is not a string, or a `valid` verdict lacks object `discount`.
 */
export function assertCouponVerdict(verdict) {
  if (verdict === null || typeof verdict !== 'object' || Array.isArray(verdict)) {
    throw new TypeError('coupon verdict must be an object');
  }
  if (typeof verdict.valid !== 'boolean') {
    throw new TypeError('coupon verdict.valid must be a boolean');
  }
  if (typeof verdict.reason !== 'string') {
    throw new TypeError('coupon verdict.reason must be a string');
  }
  if (
    verdict.valid === true &&
    (verdict.discount === null ||
      typeof verdict.discount !== 'object' ||
      Array.isArray(verdict.discount))
  ) {
    throw new TypeError('a valid coupon verdict must carry object discount metadata');
  }
  return verdict;
}

/**
 * Validates the structural shape of an order view and enforces the exact status
 * vocabulary (rejecting unsupported statuses such as `CANCELLED`).
 *
 * @param {*} order The parsed response body to validate.
 * @returns {OrderView} The same object, once validated.
 * @throws {TypeError} If the order is not an object or `id` is not a non-blank
 *   string.
 * @throws {RangeError} If `status` is not one of {@link ORDER_STATUSES}.
 */
export function assertOrderView(order) {
  if (order === null || typeof order !== 'object' || Array.isArray(order)) {
    throw new TypeError('order view must be an object');
  }
  if (typeof order.id !== 'string' || order.id.trim() === '') {
    throw new TypeError('order view.id must be a non-blank string');
  }
  if (!isOrderStatus(order.status)) {
    throw new RangeError(
      `order view.status must be one of CREATED, CONFIRMED, DELIVERED: ${JSON.stringify(order.status)}`
    );
  }
  if (
    order.appliedCoupons !== undefined &&
    !Array.isArray(order.appliedCoupons)
  ) {
    throw new TypeError('order view.appliedCoupons must be an array when present');
  }
  return order;
}
