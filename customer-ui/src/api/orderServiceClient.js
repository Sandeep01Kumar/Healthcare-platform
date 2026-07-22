/**
 * order-service HTTP client for the customer-ui React SPA.
 *
 * This module is the single, one-way data conduit between the customer-ui front
 * end and the order-service back end. UI components and pages import the named
 * async functions exported here; the module itself imports no UI code and binds
 * to no order-service Java type — its only runtime dependency is the browser
 * built-in {@link fetch}.
 *
 * The order-service base URL is configurable through the Vite environment
 * variable `VITE_ORDER_SERVICE_URL`. When that variable is unset (for example,
 * under a non-Vite test runner where `import.meta.env` is undefined) the client
 * falls back to the local development default `http://localhost:8080`.
 *
 * Error convention: every exported operation resolves with the parsed JSON body
 * on a 2xx response, and throws a descriptive {@link Error} — including the HTTP
 * method, path, and status — on any non-2xx response or network failure, so
 * React consumers can render error states with `try`/`catch`.
 *
 * @module orderServiceClient
 */

/**
 * Resolved order-service base URL.
 *
 * Derived from `import.meta.env.VITE_ORDER_SERVICE_URL` with a
 * `http://localhost:8080` development fallback. Access to `import.meta.env` is
 * optional-chained so the module degrades safely when evaluated outside a Vite
 * bundle. Any trailing slash(es) are stripped so composed paths such as
 * `${BASE_URL}/orders/${id}` never produce a double slash.
 *
 * @constant {string}
 */
const BASE_URL = (
  import.meta.env?.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8080'
).replace(/\/+$/, '');

/**
 * Issue a `fetch` request against the order-service and parse the JSON response.
 *
 * Centralizes the request/response handling shared by every exported function:
 * it performs the request, throws a descriptive {@link Error} on a network
 * failure or a non-2xx status, and otherwise resolves with the parsed JSON body.
 * This helper is module-private and is intentionally not exported.
 *
 * @private
 * @param {string} path - Path appended to {@link BASE_URL}; must begin with `/`.
 * @param {RequestInit} [options] - Optional `fetch` options (method, headers, body).
 * @returns {Promise<*>} The parsed JSON response body.
 * @throws {Error} When the request fails at the network layer, or when the
 *   response status is outside the 2xx range. The message includes the HTTP
 *   method, the path, and the failing status (or the network error message).
 */
async function request(path, options = {}) {
  const method = options.method ?? 'GET';
  let res;
  try {
    res = await fetch(`${BASE_URL}${path}`, options);
  } catch (err) {
    throw new Error(`order-service ${method} ${path} failed: ${err.message}`);
  }
  if (!res.ok) {
    throw new Error(`order-service ${method} ${path} failed: ${res.status}`);
  }
  return res.json();
}

/**
 * Validate a single coupon code against the order-service.
 *
 * Relays the code to the server-authoritative validation endpoint and returns
 * the server's verdict verbatim. This client never computes or second-guesses
 * coupon validity locally and never transforms the returned fields.
 *
 * @param {string} code - The coupon code to validate.
 * @returns {Promise<{valid: boolean, reason: string, discount: object}>} The
 *   server's normalized verdict, where `valid` indicates acceptance, `reason`
 *   carries a human-readable explanation, and `discount` holds the discount
 *   metadata (for example its `type` and `value`).
 * @throws {Error} When the request fails at the network layer or the response
 *   status is outside the 2xx range.
 */
export async function validateCoupon(code) {
  return request('/coupons/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  });
}

/**
 * Validate multiple coupon codes, preserving input order.
 *
 * Convenience wrapper for the multi-coupon feature: each code is validated with
 * {@link validateCoupon} and the verdicts are returned as an array with one
 * entry per input code, in the same order. A coupon the server rejects still
 * resolves to its own `{ valid: false, ... }` entry and does NOT reject the
 * batch; only a genuine transport/HTTP failure rejects the returned promise.
 *
 * @param {string[]} codes - The coupon codes to validate.
 * @returns {Promise<Array<{valid: boolean, reason: string, discount: object}>>}
 *   One verdict per input code, in input order.
 * @throws {Error} When any underlying request fails at the network layer or
 *   returns a non-2xx status.
 */
export async function validateCoupons(codes) {
  return Promise.all(codes.map((code) => validateCoupon(code)));
}

/**
 * Fetch a single order by its identifier.
 *
 * @param {string} orderId - The order identifier; URL-encoded before use.
 * @returns {Promise<{id: string, status: ('CREATED'|'CONFIRMED'|'DELIVERED'), price: (number|string), discountedTotal: (number|string), appliedCoupons: Array}>}
 *   The order view. `status` is one of the exact uppercase lifecycle strings
 *   `CREATED`, `CONFIRMED`, or `DELIVERED` and is returned unchanged.
 * @throws {Error} When the request fails at the network layer or the response
 *   status is outside the 2xx range.
 */
export async function getOrder(orderId) {
  return request(`/orders/${encodeURIComponent(orderId)}`);
}

/**
 * Fetch the current tracking view for an order.
 *
 * Thin alias over {@link getOrder} that resolves with the full order object,
 * keeping a single fetch path and one source of truth. Consumers that only need
 * the lifecycle label can read `.status` from the resolved order.
 *
 * @param {string} orderId - The order identifier; URL-encoded before use.
 * @returns {Promise<{id: string, status: ('CREATED'|'CONFIRMED'|'DELIVERED'), price: (number|string), discountedTotal: (number|string), appliedCoupons: Array}>}
 *   The same order view returned by {@link getOrder}.
 * @throws {Error} When the request fails at the network layer or the response
 *   status is outside the 2xx range.
 */
export async function getOrderStatus(orderId) {
  return getOrder(orderId);
}
