/**
 * order-service HTTP client for the customer-ui React SPA.
 *
 * This module is the single, one-way data conduit between the customer-ui front
 * end and the order-service back end. UI components and pages import the named
 * async functions exported here; the module itself imports no UI code and binds
 * to no order-service Java type — its only runtime dependency is the browser
 * built-in {@link fetch}.
 *
 * The HTTP surface (routes, methods, status vocabulary, DTO shapes, input
 * bounds, and error envelope) is defined once in
 * {@link module:orderServiceContract} and imported here; this client does not
 * re-declare endpoints or magic values inline. Server-authoritative decisions
 * (most importantly coupon validity) are surfaced verbatim — the client only
 * validates the STRUCTURE of responses, never their business verdict.
 *
 * The order-service base URL is configurable through the Vite environment
 * variable `VITE_ORDER_SERVICE_URL`. When that variable is unset (for example,
 * under a non-Vite test runner where `import.meta.env` is undefined) the client
 * falls back to the local development default `http://localhost:8080`. Setting
 * it to an empty string selects same-origin mode, where requests use relative
 * paths and are forwarded by the Vite dev proxy configured in `vite.config.js`.
 *
 * Error convention: every exported operation resolves with the parsed JSON body
 * on a 2xx response, and throws a descriptive {@link Error} — including the HTTP
 * method, path, and status (plus the server error envelope when present) — on any
 * non-2xx response, timeout, abort, network failure, or malformed response body,
 * so React consumers can render error states with `try`/`catch`.
 *
 * @module orderServiceClient
 */

import {
  ENDPOINTS,
  DEFAULT_TIMEOUT_MS,
  MAX_COUPON_CODE_LENGTH,
  MAX_COUPONS,
  MAX_ORDER_ID_LENGTH,
  REQUEST_CONCURRENCY_LIMIT,
  assertCouponVerdict,
  assertOrderView,
  canonicalizeCouponCode,
} from './orderServiceContract.js';

/**
 * Resolve and validate the order-service base URL.
 *
 * Trims surrounding whitespace and strips trailing slash(es) so composed paths
 * such as `${BASE_URL}/orders/${id}` never produce a double slash. An empty
 * result is accepted and means "same-origin" (relative paths through the dev
 * proxy). A non-empty result must be a syntactically valid absolute `http`/
 * `https` URL; anything else throws, failing fast at module load rather than on
 * the first request.
 *
 * Exported so the resolution logic can be unit-tested with arbitrary inputs.
 *
 * @param {string} [raw] - The raw configured value. Defaults to
 *   `import.meta.env.VITE_ORDER_SERVICE_URL` with a `http://localhost:8080`
 *   fallback; `import.meta.env` is optional-chained so the module degrades
 *   safely when evaluated outside a Vite bundle.
 * @returns {string} The normalized base URL (possibly the empty string).
 * @throws {TypeError} If the configured value is not a string.
 * @throws {Error} If the value is a non-empty string that is not a valid
 *   absolute `http`/`https` URL, or carries credentials (userinfo), a query
 *   string, or a fragment.
 */
export function resolveBaseUrl(
  raw = import.meta.env?.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8080'
) {
  if (typeof raw !== 'string') {
    throw new TypeError('VITE_ORDER_SERVICE_URL must be a string when set');
  }
  const trimmed = raw.trim().replace(/\/+$/, '');
  if (trimmed === '') {
    // Same-origin mode: relative paths, forwarded by the Vite dev proxy.
    return '';
  }
  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch (err) {
    throw new Error(
      `order-service base URL is not a valid absolute URL: ${trimmed}`,
      { cause: err }
    );
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(
      `order-service base URL must use the http or https scheme: ${trimmed}`
    );
  }
  // Reject secret-bearing / ambiguous URL components (finding M6). Embedded
  // credentials (`http://user:pass@host`) would be a leaked secret; a query
  // string or fragment on a *base* URL is never meaningful for the composed API
  // paths and most often signals a misconfiguration or an attempt to smuggle
  // data. The error message deliberately names only WHICH component is
  // forbidden — it never echoes the userinfo/query/fragment value itself, so a
  // secret cannot leak into logs or thrown messages.
  if (parsed.username !== '' || parsed.password !== '') {
    throw new Error(
      'order-service base URL must not contain embedded credentials (userinfo)'
    );
  }
  if (parsed.search !== '') {
    throw new Error('order-service base URL must not contain a query string');
  }
  if (parsed.hash !== '') {
    throw new Error('order-service base URL must not contain a fragment');
  }
  return trimmed;
}

/**
 * Resolved, validated order-service base URL.
 *
 * @constant {string}
 */
const BASE_URL = resolveBaseUrl();

/**
 * Create an {@link AbortSignal} that fires when either the caller's signal aborts
 * or the timeout elapses, along with a cleanup function that clears the timer and
 * detaches listeners.
 *
 * @private
 * @param {AbortSignal} [callerSignal] - An optional caller-supplied signal.
 * @param {number} timeoutMs - Timeout in milliseconds; `0` disables the timeout.
 * @returns {{signal: AbortSignal, cleanup: () => void}} The composed signal and
 *   its cleanup function.
 */
function createRequestSignal(callerSignal, timeoutMs) {
  const controller = new AbortController();
  let timer = null;

  const onAbort = () => controller.abort(callerSignal?.reason);

  if (callerSignal) {
    if (callerSignal.aborted) {
      controller.abort(callerSignal.reason);
    } else {
      callerSignal.addEventListener('abort', onAbort, { once: true });
    }
  }

  if (timeoutMs > 0) {
    timer = setTimeout(() => {
      const timeoutError = new Error(
        `request timed out after ${timeoutMs}ms`
      );
      timeoutError.name = 'TimeoutError';
      controller.abort(timeoutError);
    }, timeoutMs);
  }

  const cleanup = () => {
    if (timer !== null) {
      clearTimeout(timer);
    }
    if (callerSignal) {
      callerSignal.removeEventListener('abort', onAbort);
    }
  };

  return { signal: controller.signal, cleanup };
}

/**
 * Read and parse a JSON response body, tolerating an empty body and wrapping
 * malformed JSON in a descriptive error that preserves the underlying parse
 * failure as its `cause`.
 *
 * @private
 * @param {Response} res - The fetch response.
 * @param {string} method - The HTTP method, for error messages.
 * @param {string} path - The request path, for error messages.
 * @returns {Promise<*>} The parsed body, or `null` for an empty body.
 * @throws {Error} If the body is present but is not valid JSON.
 */
async function parseJson(res, method, path) {
  const text = await res.text();
  if (text.trim() === '') {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (err) {
    throw new Error(
      `order-service ${method} ${path} returned a malformed JSON body`,
      { cause: err }
    );
  }
}

/**
 * Extract a bounded, human-readable detail suffix from a non-2xx response,
 * preferring the structured {@link module:orderServiceContract.ErrorEnvelope}
 * (`error.code`/`error.message`) and falling back to a short raw snippet.
 *
 * @private
 * @param {Response} res - The failing fetch response.
 * @returns {Promise<string>} A detail suffix (possibly empty) to append to the
 *   error message.
 */
async function readErrorDetail(res) {
  let text;
  try {
    text = await res.text();
  } catch {
    return '';
  }
  if (!text || text.trim() === '') {
    return '';
  }
  try {
    const body = JSON.parse(text);
    const envelopeError = body?.error;
    if (envelopeError && typeof envelopeError === 'object') {
      const parts = [];
      if (typeof envelopeError.code === 'string') {
        parts.push(envelopeError.code);
      }
      if (typeof envelopeError.message === 'string') {
        parts.push(envelopeError.message);
      }
      if (parts.length > 0) {
        return ` (${parts.join(': ')})`;
      }
    }
  } catch {
    // Non-JSON error body: fall through to the raw snippet.
  }
  const snippet = text.trim().slice(0, 200);
  return snippet ? ` (${snippet})` : '';
}

/**
 * Safe, user-facing messages keyed by HTTP status. These are intentionally
 * generic and contain NO server-provided text, path, or internal detail, so a
 * component can render them directly without leaking lower-layer information
 * (finding M6). Diagnostics (status, path, and the server envelope) remain on
 * the thrown {@link Error}'s `message`/`cause` for logging and are never shown
 * to the customer.
 *
 * @constant {Record<number, string>}
 */
const HTTP_USER_MESSAGES = {
  400: 'The request was invalid. Please check your input and try again.',
  404: 'The requested order was not found.',
  405: 'That operation is not supported.',
  409: 'That status change is not allowed.',
  413: 'The request was too large.',
  429: 'Too many requests. Please wait a moment and try again.',
};

/**
 * Map an HTTP status to a safe, generic user-facing message (finding M6).
 *
 * Any unmapped 4xx yields a generic client-error message; any 5xx (or unknown)
 * yields a generic service-unavailable message. The returned text never
 * contains server-provided content.
 *
 * @private
 * @param {number} status - The HTTP status code.
 * @returns {string} A safe user-facing message.
 */
function safeUserMessage(status) {
  if (Object.hasOwn(HTTP_USER_MESSAGES, status)) {
    return HTTP_USER_MESSAGES[status];
  }
  if (status >= 400 && status < 500) {
    return 'The request could not be completed. Please check your input and try again.';
  }
  return 'The order service is temporarily unavailable. Please try again.';
}

/**
 * Create an {@link Error} carrying a developer-facing `message` (diagnostic,
 * which may include method/path/status/server-envelope text) PLUS a safe,
 * user-facing `userMessage` and the HTTP `status` (`0` for transport-level
 * failures such as a network error, timeout, or abort). UI code renders
 * `userMessage`; logs may use `message`/`cause`. Separating the two is what
 * keeps raw lower-layer text out of customer-facing feedback (finding M6).
 *
 * @private
 * @param {string} message - Diagnostic message.
 * @param {{status?: number, userMessage: string, cause?: unknown}} meta - Metadata.
 * @returns {Error} The enriched error.
 */
function createRequestError(message, { status = 0, userMessage, cause }) {
  const error =
    cause !== undefined ? new Error(message, { cause }) : new Error(message);
  error.status = status;
  error.userMessage = userMessage;
  return error;
}

/**
 * Issue a `fetch` request against the order-service and parse the JSON response.
 *
 * Centralizes the request/response handling shared by every exported function:
 * it enforces a timeout (composed with any caller-supplied {@link AbortSignal}),
 * performs the request, throws a descriptive {@link Error} on a network failure,
 * abort/timeout, or a non-2xx status (surfacing the server error envelope when
 * present), and otherwise resolves with the parsed JSON body. This helper is
 * module-private and is intentionally not exported.
 *
 * @private
 * @param {string} path - Path appended to {@link BASE_URL}; must begin with `/`.
 * @param {Object} [options] - Request options.
 * @param {string} [options.method] - HTTP method (defaults to `GET`).
 * @param {Object} [options.headers] - Request headers.
 * @param {string} [options.body] - Request body.
 * @param {AbortSignal} [options.signal] - Caller-supplied abort signal.
 * @param {number} [options.timeoutMs] - Per-request timeout; defaults to
 *   {@link module:orderServiceContract.DEFAULT_TIMEOUT_MS}; `0` disables it.
 * @returns {Promise<*>} The parsed JSON response body (or `null` for an empty body).
 * @throws {Error} On network failure, timeout/abort, non-2xx status, or a
 *   malformed JSON body. The message includes the HTTP method, path, and status
 *   (or the underlying failure), and preserves the originating error as `cause`
 *   where applicable.
 */
async function request(path, options = {}) {
  const {
    method = 'GET',
    headers,
    body,
    signal,
    timeoutMs = DEFAULT_TIMEOUT_MS,
  } = options;

  const { signal: requestSignal, cleanup } = createRequestSignal(
    signal,
    timeoutMs
  );

  try {
    let res;
    try {
      res = await fetch(`${BASE_URL}${path}`, {
        method,
        headers,
        body,
        signal: requestSignal,
      });
    } catch (err) {
      if (requestSignal.aborted) {
        const reason = requestSignal.reason;
        const isTimeout = !!reason && reason.name === 'TimeoutError';
        const detail =
          reason instanceof Error ? reason.message : String(reason ?? 'aborted');
        throw createRequestError(
          `order-service ${method} ${path} aborted: ${detail}`,
          {
            status: 0,
            userMessage: isTimeout
              ? 'The request timed out. Please try again.'
              : 'The request was cancelled.',
            cause: err,
          }
        );
      }
      throw createRequestError(
        `order-service ${method} ${path} failed: ${err.message}`,
        {
          status: 0,
          userMessage: 'The order service is unreachable. Please try again.',
          cause: err,
        }
      );
    }

    if (!res.ok) {
      // The diagnostic detail (server envelope / raw snippet) is attached to the
      // Error `message` for logs only; the customer sees the safe, status-mapped
      // `userMessage` (finding M6).
      const detail = await readErrorDetail(res);
      throw createRequestError(
        `order-service ${method} ${path} failed: ${res.status}${detail}`,
        { status: res.status, userMessage: safeUserMessage(res.status) }
      );
    }

    return await parseJson(res, method, path);
  } finally {
    cleanup();
  }
}

/**
 * Validate a coupon code as client-side input (non-blank string within bounds).
 * The server remains authoritative for business validity.
 *
 * @private
 * @param {string} code - The candidate coupon code.
 * @throws {TypeError} If `code` is not a string.
 * @throws {Error} If `code` is blank.
 * @throws {RangeError} If `code` exceeds
 *   {@link module:orderServiceContract.MAX_COUPON_CODE_LENGTH}.
 */
function validateCouponCode(code) {
  if (typeof code !== 'string') {
    throw new TypeError('coupon code must be a string');
  }
  if (code.trim() === '') {
    throw new Error('coupon code must not be blank');
  }
  if (code.length > MAX_COUPON_CODE_LENGTH) {
    throw new RangeError(
      `coupon code must not exceed ${MAX_COUPON_CODE_LENGTH} characters`
    );
  }
}

/**
 * Validate an order identifier as client-side input (non-blank string within
 * bounds).
 *
 * @private
 * @param {string} orderId - The candidate order identifier.
 * @throws {TypeError} If `orderId` is not a string.
 * @throws {Error} If `orderId` is blank.
 * @throws {RangeError} If `orderId` exceeds
 *   {@link module:orderServiceContract.MAX_ORDER_ID_LENGTH}.
 */
function validateOrderId(orderId) {
  if (typeof orderId !== 'string') {
    throw new TypeError('order id must be a string');
  }
  if (orderId.trim() === '') {
    throw new Error('order id must not be blank');
  }
  if (orderId.length > MAX_ORDER_ID_LENGTH) {
    throw new RangeError(
      `order id must not exceed ${MAX_ORDER_ID_LENGTH} characters`
    );
  }
}

/**
 * Map over items with a bounded number of concurrent workers, preserving input
 * order and duplicates in the results.
 *
 * The pool never runs more than `limit` workers at once. Because `nextIndex++`
 * executes synchronously (no `await` between the read and the increment) each
 * worker claims a distinct index without a race. If any worker rejects, the
 * returned promise rejects with that error.
 *
 * @private
 * @template T, R
 * @param {ReadonlyArray<T>} items - The items to process.
 * @param {number} limit - The maximum number of concurrent workers.
 * @param {(item: T, index: number) => Promise<R>} worker - The async worker.
 * @returns {Promise<R[]>} Results in the same order as `items`.
 */
async function mapWithConcurrency(items, limit, worker) {
  const results = new Array(items.length);
  let nextIndex = 0;
  const poolSize = Math.min(Math.max(limit, 1), items.length);

  const runners = [];
  for (let i = 0; i < poolSize; i += 1) {
    runners.push(
      (async () => {
        for (;;) {
          const current = nextIndex;
          nextIndex += 1;
          if (current >= items.length) {
            return;
          }
          results[current] = await worker(items[current], current);
        }
      })()
    );
  }

  await Promise.all(runners);
  return results;
}

/**
 * Validate a single coupon code against the order-service.
 *
 * Relays the code to the server-authoritative validation endpoint and returns
 * the server's verdict verbatim. This client never computes or second-guesses
 * coupon validity locally; it only checks that the response has the contracted
 * shape via {@link module:orderServiceContract.assertCouponVerdict}.
 *
 * @param {string} code - The coupon code to validate.
 * @param {Object} [options] - Request options.
 * @param {AbortSignal} [options.signal] - Caller-supplied abort signal.
 * @param {number} [options.timeoutMs] - Per-request timeout in milliseconds.
 * @returns {Promise<import('./orderServiceContract.js').CouponVerdict>} The
 *   server's normalized verdict.
 * @throws {TypeError|Error|RangeError} If `code` is not a valid input.
 * @throws {Error} On network failure, timeout/abort, non-2xx status, a malformed
 *   JSON body, or a structurally invalid verdict.
 */
export async function validateCoupon(code, options = {}) {
  validateCouponCode(code);
  const body = await request(ENDPOINTS.validateCoupon.path, {
    method: ENDPOINTS.validateCoupon.method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
    signal: options.signal,
    timeoutMs: options.timeoutMs,
  });
  return assertCouponVerdict(body);
}

/**
 * Validate multiple coupon codes, preserving input order and duplicates.
 *
 * Convenience wrapper for the multi-coupon feature: the batch is bounded to
 * {@link module:orderServiceContract.MAX_COUPONS} codes and dispatched with at
 * most {@link module:orderServiceContract.REQUEST_CONCURRENCY_LIMIT} concurrent
 * requests. The verdicts are returned as an array with one entry per input code,
 * in the same order (duplicate codes each get their own entry). A coupon the
 * server rejects still resolves to its own `{ valid: false, ... }` entry and does
 * NOT reject the batch; only a genuine transport/HTTP failure (or invalid input)
 * rejects the returned promise.
 *
 * @param {string[]} codes - The coupon codes to validate.
 * @param {Object} [options] - Request options forwarded to each request.
 * @param {AbortSignal} [options.signal] - Caller-supplied abort signal.
 * @param {number} [options.timeoutMs] - Per-request timeout in milliseconds.
 * @returns {Promise<Array<import('./orderServiceContract.js').CouponVerdict>>}
 *   One verdict per input code, in input order.
 * @throws {TypeError} If `codes` is not an array.
 * @throws {RangeError} If `codes` exceeds
 *   {@link module:orderServiceContract.MAX_COUPONS}, or any code is out of bounds.
 * @throws {Error} If any code is blank, or any underlying request fails.
 */
export async function validateCoupons(codes, options = {}) {
  if (!Array.isArray(codes)) {
    throw new TypeError('coupon codes must be an array');
  }
  if (codes.length > MAX_COUPONS) {
    throw new RangeError(
      `no more than ${MAX_COUPONS} coupons may be validated at once`
    );
  }
  codes.forEach((code) => validateCouponCode(code));
  return mapWithConcurrency(codes, REQUEST_CONCURRENCY_LIMIT, (code) =>
    validateCoupon(code, options)
  );
}

/**
 * Fetch a single order by its identifier.
 *
 * The response is checked for the contracted shape and exact status vocabulary
 * via {@link module:orderServiceContract.assertOrderView}; an unsupported status
 * (for example `CANCELLED`) is rejected rather than propagated to the UI.
 *
 * @param {string} orderId - The order identifier; URL-encoded before use.
 * @param {Object} [options] - Request options.
 * @param {AbortSignal} [options.signal] - Caller-supplied abort signal.
 * @param {number} [options.timeoutMs] - Per-request timeout in milliseconds.
 * @returns {Promise<import('./orderServiceContract.js').OrderView>} The order
 *   view. `status` is one of the exact uppercase lifecycle strings `CREATED`,
 *   `CONFIRMED`, or `DELIVERED` and is returned unchanged.
 * @throws {TypeError|Error|RangeError} If `orderId` is not a valid input.
 * @throws {Error} On network failure, timeout/abort, non-2xx status, a malformed
 *   JSON body, or a structurally invalid order view.
 */
export async function getOrder(orderId, options = {}) {
  validateOrderId(orderId);
  const body = await request(ENDPOINTS.getOrder.path(orderId), {
    method: ENDPOINTS.getOrder.method,
    signal: options.signal,
    timeoutMs: options.timeoutMs,
  });
  return assertOrderView(body);
}

/**
 * Fetch the current tracking view for an order.
 *
 * Thin alias over {@link getOrder} that resolves with the full order object,
 * keeping a single fetch path and one source of truth. Consumers that only need
 * the lifecycle label can read `.status` from the resolved order.
 *
 * @param {string} orderId - The order identifier; URL-encoded before use.
 * @param {Object} [options] - Request options forwarded to {@link getOrder}.
 * @param {AbortSignal} [options.signal] - Caller-supplied abort signal.
 * @param {number} [options.timeoutMs] - Per-request timeout in milliseconds.
 * @returns {Promise<import('./orderServiceContract.js').OrderView>} The same
 *   order view returned by {@link getOrder}.
 * @throws {TypeError|Error|RangeError} If `orderId` is not a valid input.
 * @throws {Error} On network failure, timeout/abort, non-2xx status, a malformed
 *   JSON body, or a structurally invalid order view.
 */
export async function getOrderStatus(orderId, options = {}) {
  return getOrder(orderId, options);
}

/**
 * Normalize a caller-supplied price into the canonical non-negative decimal
 * string the order-service expects in the `POST /orders` body.
 *
 * The server's `readPrice` accepts either a JSON number or a numeric string
 * (parsed as {@code BigDecimal}); this client always transmits a string so the
 * value survives JSON serialization without binary floating-point reformatting.
 * The accepted grammar is intentionally strict — one or more digits, optionally
 * followed by a fractional part — which rules out signs, scientific notation,
 * and blank input before a request is ever issued.
 *
 * @private
 * @param {number|string} price - The base (pre-discount) price.
 * @returns {string} The canonical decimal string to send.
 * @throws {TypeError} If `price` is neither a number nor a string.
 * @throws {RangeError} If `price` is a non-finite/negative number, or a string
 *   that is not a non-negative decimal.
 * @throws {Error} If `price` is a blank string.
 */
function normalizeOrderPrice(price) {
  if (typeof price === 'number') {
    if (!Number.isFinite(price) || price < 0) {
      throw new RangeError('price must be a finite, non-negative number');
    }
    return String(price);
  }
  if (typeof price === 'string') {
    const trimmed = price.trim();
    if (trimmed === '') {
      throw new Error('price must not be blank');
    }
    if (!/^\d+(\.\d+)?$/.test(trimmed)) {
      throw new RangeError('price must be a non-negative decimal string');
    }
    return trimmed;
  }
  throw new TypeError('price must be a number or a numeric string');
}

/**
 * Normalize the coupon codes submitted with an order into a bounded, canonical,
 * de-duplicated array.
 *
 * Each entry is validated as coupon input, canonicalized via
 * {@link module:orderServiceContract.canonicalizeCouponCode} (trim + upper-case),
 * and de-duplicated by that canonical identity preserving first-seen order. This
 * mirrors the server, which redeems each distinct canonical code exactly once
 * (finding C4), so submitting `"save10"` and `"SAVE10"` never double-applies.
 *
 * @private
 * @param {string[]} [codes] - The applied coupon codes (defaults to none).
 * @returns {string[]} The canonical, de-duplicated codes to send.
 * @throws {TypeError} If `codes` is neither nullish nor an array, or an entry is
 *   not a string.
 * @throws {RangeError} If more than {@link module:orderServiceContract.MAX_COUPONS}
 *   codes are supplied, or an entry exceeds the per-code length bound.
 * @throws {Error} If an entry is blank.
 */
function normalizeCouponCodesForSubmit(codes) {
  if (codes === undefined || codes === null) {
    return [];
  }
  if (!Array.isArray(codes)) {
    throw new TypeError('coupon codes must be an array');
  }
  if (codes.length > MAX_COUPONS) {
    throw new RangeError(
      `no more than ${MAX_COUPONS} coupons may be applied at once`
    );
  }
  const seen = new Set();
  const result = [];
  for (const code of codes) {
    validateCouponCode(code);
    const canonical = canonicalizeCouponCode(code);
    if (!seen.has(canonical)) {
      seen.add(canonical);
      result.push(canonical);
    }
  }
  return result;
}

/**
 * Create an order from a base price and the coupon codes the customer applied,
 * submitting them to the server-authoritative `POST /orders` endpoint so the
 * coupons actually participate in pricing and redemption (findings C1/C3).
 *
 * The coupons the customer entered in the UI are passed here as `couponCodes`;
 * this is the seam that connects the coupon input to the order/pricing flow.
 * Codes are bounded, canonicalized, and de-duplicated (matching the server's
 * once-per-canonical-code redemption) before submission, and the price is
 * normalized to a canonical decimal string. The response is validated for the
 * contracted order-view shape and exact status vocabulary via
 * {@link module:orderServiceContract.assertOrderView}; a freshly created order
 * is returned with status `CREATED`.
 *
 * @param {number|string} price - The base (pre-discount) price.
 * @param {string[]} [couponCodes] - The coupon codes to apply (defaults to none).
 * @param {Object} [options] - Request options.
 * @param {AbortSignal} [options.signal] - Caller-supplied abort signal.
 * @param {number} [options.timeoutMs] - Per-request timeout in milliseconds.
 * @returns {Promise<import('./orderServiceContract.js').OrderView>} The created
 *   order view (status `CREATED`), including its server-assigned `id`, the
 *   `discountedTotal`, and the `appliedCoupons` the server accepted.
 * @throws {TypeError|RangeError|Error} If `price` or `couponCodes` is not valid
 *   input.
 * @throws {Error} On network failure, timeout/abort, non-2xx status, a malformed
 *   JSON body, or a structurally invalid order view. Transport/HTTP failures
 *   carry a safe `userMessage` and numeric `status` for direct UI rendering.
 */
export async function createOrder(price, couponCodes = [], options = {}) {
  const priceString = normalizeOrderPrice(price);
  const codes = normalizeCouponCodesForSubmit(couponCodes);
  const body = await request(ENDPOINTS.createOrder.path, {
    method: ENDPOINTS.createOrder.method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ price: priceString, couponCodes: codes }),
    signal: options.signal,
    timeoutMs: options.timeoutMs,
  });
  return assertOrderView(body);
}
