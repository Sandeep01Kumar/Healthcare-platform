/**
 * @module index
 *
 * Service **entry / bootstrap** and **HTTP receiver** for the `notification-service`
 * module (AAP Section 0.5.1, Group 4 — "Add order status change notifications").
 *
 * ## Role — the Node consumer half of the delivered Java → Node bridge
 * This file is the module's programmatic entry point AND the concrete HTTP
 * receiver that consumes status-change events from the `order-service` (Java)
 * producer. The producer's `HttpNotificationTrigger` POSTs a serialized transition
 * event (contract F) to this receiver, which adapts it onto
 * {@link NotificationService#sendStatusChangeNotification} and delivers it through
 * the configured transport:
 *
 *   order-service (Java)                          notification-service (Node)
 *   HttpNotificationTrigger  --- HTTP POST --->    POST /notifications  (this file)
 *                                                   -> sendStatusChangeNotification
 *
 * The wire event (contract F) is:
 *   `{ eventId, orderId, oldStatus, newStatus, order: { id, status }, at }`
 * and the receiver responds `200 { dispatched, duplicate, key, eventId }` on
 * success, `400 { error }` for a malformed/invalid event, and `5xx { error }` for a
 * transport/delivery failure (which the producer treats as a retryable delivery
 * failure, leaving its outbox entry PENDING).
 *
 * ## Side-effect-free on import
 * Importing this module (from a unit test, or from other code that wants the
 * shared instance or the {@link createReceiver}/{@link start} factories) MUST NOT
 * bind a socket or emit output. The receiver is started **only** when this file is
 * executed directly (`node src/index.js` / `npm start`), guarded by an
 * `import.meta.url` check. Tests import {@link createReceiver}/{@link start} and
 * bind an ephemeral port themselves.
 *
 * @remarks ESM module (matches `"type": "module"` in `package.json`). Zero npm
 * dependencies: it uses only the intra-folder {@link NotificationService} import
 * and the Node built-ins `node:http` and `node:url`.
 *
 * @see {@link NotificationService} for the notification-delivery contract.
 */

import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { NotificationService } from './NotificationService.js';

/** The single route the receiver serves. */
const NOTIFICATIONS_PATH = '/notifications';

/** Maximum accepted request-body size (bounds untrusted input; CWE-400). */
const MAX_BODY_BYTES = 64 * 1024;

/** Default TCP port; overridable via `NOTIFICATION_PORT` (or `PORT`). */
const DEFAULT_PORT = 3001;

/** Default bind host — loopback only, matching the order-service default. */
const DEFAULT_HOST = '127.0.0.1';

/**
 * A ready-to-use {@link NotificationService} wired with the default (console)
 * transport. Exported as the module `default` so
 * `import notificationService from './index.js'` yields a working instance with no
 * setup. Consumers needing real delivery should construct their own
 * {@link NotificationService} with an injected transport rather than mutating this
 * shared instance.
 *
 * @type {NotificationService}
 */
const notificationService = new NotificationService();

/**
 * Reads the full request body up to {@link MAX_BODY_BYTES}, rejecting a body that
 * exceeds the cap (so a hostile client cannot exhaust memory).
 *
 * @param {import('node:http').IncomingMessage} req The incoming request.
 * @returns {Promise<string>} The UTF-8 body text.
 */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let overflowed = false;
    req.on('data', (chunk) => {
      // API-04: once the cap is exceeded, stop buffering but KEEP DRAINING the
      // socket (rather than destroying the request stream) so the handler can
      // still write a clean, deterministic 413 JSON response and close the
      // connection gracefully. Tearing the stream down here (the previous
      // `req.destroy()`) raced the response and surfaced to the client as a
      // connection reset instead of a 413 — the exact defect this guards against.
      if (overflowed) {
        return;
      }
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        overflowed = true;
        chunks.length = 0;
        reject(Object.assign(new Error('request body too large'), { statusCode: 413 }));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/**
 * Writes a JSON response with the given status code.
 *
 * @param {import('node:http').ServerResponse} res The response.
 * @param {number} status The HTTP status code.
 * @param {object} body The JSON-serializable body.
 * @param {Record<string, string>} [extraHeaders] Additional response headers to
 *   merge in (e.g. `{ Connection: 'close' }` on a 413). Applied last so a caller
 *   can add — but not silently drop — the standard headers below.
 * @returns {void}
 */
function sendJson(res, status, body, extraHeaders) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(text),
    // SEC-02: defense-in-depth headers on EVERY JSON response so neither a client
    // nor an intermediary can MIME-sniff the body into an executable type or cache
    // a per-request notification result.
    'X-Content-Type-Options': 'nosniff',
    'Cache-Control': 'no-store',
    ...(extraHeaders || {}),
  });
  res.end(text);
}

/**
 * Strictly parses and validates a contract-F status-change event off the wire,
 * enforcing a single, immutable event schema (INT-02).
 *
 * Unlike the previous lenient mapping, this parser:
 *  - requires a non-blank top-level `orderId`, `oldStatus` and `newStatus`;
 *  - when a nested `order` object is present, requires its `id` (when it carries
 *    one) to AGREE with the top-level `orderId` — a receiver must never silently
 *    pick one identity over a conflicting other;
 *  - passes the producer's `order.status` THROUGH unchanged (rather than forcing
 *    it to `newStatus`), so the service's internal-consistency guard actually
 *    fires on an inconsistent event instead of being masked; and
 *  - extracts the producer's own `eventId`/`at` correlation metadata so
 *    {@link NotificationService#sendStatusChangeNotification} can preserve them
 *    verbatim end-to-end.
 *
 * @param {unknown} event The parsed JSON request body.
 * @returns {{ order: { id: string, status?: unknown }, oldStatus: string,
 *   newStatus: string, meta: { eventId?: string, at?: string } }} The normalized
 *   arguments plus preserved producer metadata.
 * @throws {TypeError} If `event` is not an object or a required field is missing
 *   or of the wrong type.
 * @throws {RangeError} If a present nested `order.id` disagrees with `orderId`.
 */
function parseEvent(event) {
  if (event === null || typeof event !== 'object' || Array.isArray(event)) {
    throw new TypeError('event must be a JSON object');
  }
  const { orderId, oldStatus, newStatus, eventId, at, order: nested } = event;
  if (typeof orderId !== 'string' || orderId.trim() === '') {
    throw new TypeError('event.orderId must be a non-blank string');
  }
  if (typeof oldStatus !== 'string' || oldStatus.trim() === '') {
    throw new TypeError('event.oldStatus must be a non-blank string');
  }
  if (typeof newStatus !== 'string' || newStatus.trim() === '') {
    throw new TypeError('event.newStatus must be a non-blank string');
  }
  /** @type {{ id: string, status?: unknown }} */
  const order = { id: orderId };
  if (nested !== undefined) {
    if (nested === null || typeof nested !== 'object' || Array.isArray(nested)) {
      throw new TypeError('event.order must be an object when present');
    }
    if (nested.id !== undefined && nested.id !== orderId) {
      throw new RangeError('event.order.id is inconsistent with event.orderId');
    }
    // Pass the producer status THROUGH; the service's guard enforces == newStatus.
    if (nested.status !== undefined) {
      order.status = nested.status;
    }
  }
  return {
    order,
    oldStatus,
    newStatus,
    meta: {
      eventId: typeof eventId === 'string' && eventId.trim() !== '' ? eventId : undefined,
      at: typeof at === 'string' && at.trim() !== '' ? at : undefined,
    },
  };
}

/**
 * Returns whether a request `Content-Type` header denotes a JSON body —
 * `application/json`, matched case-insensitively and ignoring any `; charset=...`
 * parameters (API-01).
 *
 * @param {string | undefined} contentType The raw `Content-Type` header value.
 * @returns {boolean} `true` when the media type is `application/json`.
 */
function isJsonContentType(contentType) {
  return String(contentType || '').split(';')[0].trim().toLowerCase() === 'application/json';
}

/**
 * Builds the `node:http` receiver that consumes status-change events on
 * `POST /notifications` and delivers them through the supplied service.
 *
 * The returned server is **not** yet listening — the caller (or {@link start})
 * calls `.listen(...)`. This keeps the factory import-safe and lets tests bind an
 * ephemeral port.
 *
 * Responses:
 * - `200 { dispatched, duplicate, key, eventId }` — the event was accepted and
 *   delivered (or recognized as a duplicate; both are idempotent successes);
 * - `400 { error }` — the body was not JSON, or the event failed validation
 *   (`TypeError`/`RangeError` from the service: bad id, unknown status, invalid
 *   transition, or inconsistent `order.status`);
 * - `413 { error }` — the body exceeded {@link MAX_BODY_BYTES};
 * - `404 { error }` — a path other than `/notifications`;
 * - `405 { error }` — a method other than `POST` on `/notifications`;
 * - `500 { error }` — the transport delivery failed (retryable by the producer).
 *
 * @param {NotificationService} [service] The service to deliver through; defaults
 *   to the shared {@link notificationService}.
 * @returns {import('node:http').Server} A configured, not-yet-listening server.
 */
export function createReceiver(service = notificationService) {
  return createServer(async (req, res) => {
    // Resolve the path without query/fragment noise.
    const path = (req.url || '').split('?')[0];

    if (path !== NOTIFICATIONS_PATH) {
      sendJson(res, 404, { error: { code: 'NOT_FOUND', message: `no such route: ${path}` } });
      return;
    }
    if (req.method !== 'POST') {
      sendJson(res, 405, { error: { code: 'METHOD_NOT_ALLOWED', message: 'use POST /notifications' } });
      return;
    }
    // API-01: require a JSON media type BEFORE reading or parsing the body, so a
    // mislabelled (e.g. text/plain) or unlabelled payload is refused up front with
    // 415 and never reaches the parser or the service.
    if (!isJsonContentType(req.headers['content-type'])) {
      sendJson(res, 415, {
        error: { code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Content-Type must be application/json' },
      });
      return;
    }

    let event;
    try {
      const raw = await readBody(req);
      if (raw.trim() === '') {
        throw new Error('request body must be a JSON object');
      }
      event = JSON.parse(raw);
    } catch (err) {
      if (err && err.statusCode === 413) {
        // API-04: the body was capped by readBody, which kept draining rather than
        // destroying the socket. Drain any remainder and answer with a
        // deterministic 413 JSON, closing the connection cleanly (Connection:
        // close) instead of resetting it.
        req.resume();
        sendJson(
          res,
          413,
          { error: { code: 'PAYLOAD_TOO_LARGE', message: 'request body too large' } },
          { Connection: 'close' }
        );
        return;
      }
      sendJson(res, 400, { error: { code: 'INVALID_JSON', message: 'invalid JSON body' } });
      return;
    }

    try {
      // INT-02: strictly parse the event (one immutable schema) and carry the
      // producer's eventId/at through unchanged.
      const { order, oldStatus, newStatus, meta } = parseEvent(event);
      const result = await service.sendStatusChangeNotification(order, oldStatus, newStatus, meta);
      sendJson(res, 200, {
        dispatched: result.dispatched,
        duplicate: result.duplicate,
        key: result.key,
        eventId: result.payload ? result.payload.eventId : NotificationService.eventId(order, oldStatus, newStatus),
      });
    } catch (err) {
      // A validation failure (a bad event) is a client error carrying a safe,
      // descriptive message. Anything else is a transport/delivery failure whose
      // detail (host, path, cause) must NOT leak to the caller (SEC-01): answer
      // with a generic 500 envelope.
      const isValidation = err instanceof TypeError || err instanceof RangeError;
      if (isValidation) {
        sendJson(res, 400, { error: { code: 'INVALID_EVENT', message: String(err.message) } });
      } else {
        sendJson(res, 500, {
          error: { code: 'DELIVERY_FAILED', message: 'notification delivery failed' },
        });
      }
    }
  });
}

/**
 * Creates a receiver over the given (or shared) service and begins listening.
 *
 * @param {object} [options] Startup options.
 * @param {number} [options.port] TCP port to bind; defaults to
 *   `NOTIFICATION_PORT` / `PORT` env, else {@link DEFAULT_PORT}. Pass `0` for an
 *   ephemeral port (used by tests).
 * @param {string} [options.host] Bind host; defaults to {@link DEFAULT_HOST}.
 * @param {NotificationService} [options.service] Service to deliver through;
 *   defaults to the shared {@link notificationService}.
 * @returns {Promise<{ server: import('node:http').Server, port: number, host: string, service: NotificationService }>}
 *   Resolves once the socket is bound, carrying the actual bound port.
 */
export function start(options = {}) {
  const host = options.host || DEFAULT_HOST;
  const envPort = process.env.NOTIFICATION_PORT || process.env.PORT;
  const port =
    options.port !== undefined
      ? options.port
      : envPort !== undefined
        ? Number(envPort)
        : DEFAULT_PORT;
  const service = options.service || notificationService;
  const server = createReceiver(service);
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      const address = server.address();
      const boundPort = typeof address === 'object' && address ? address.port : port;
      resolve({ server, port: boundPort, host, service });
    });
  });
}

export { NotificationService };
export default notificationService;

// Direct-run guard: start the HTTP receiver ONLY when executed directly
// (`node src/index.js` / `npm start`) — never on import. The
// `pathToFileURL(process.argv[1]).href` comparison is robust to path-encoding
// edge cases (spaces, non-ASCII); the `process.argv[1]` truthiness check keeps the
// guard safe when there is no entry script (e.g. `node -e`), preserving the
// side-effect-free import contract.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  start()
    .then(({ host, port }) => {
      // Truthful readiness line: it states the actual bound address and route, and
      // includes the correlation-friendly note that this is the Node consumer half
      // of the Java -> Node HTTP bridge.
      console.log(
        `[notification-service] listening on http://${host}:${port}${NOTIFICATIONS_PATH} ` +
          `— receiving order-service status-change events (default console transport).`
      );
    })
    .catch((err) => {
      console.error(`[notification-service] failed to start receiver: ${err && err.message}`);
      process.exitCode = 1;
    });
}
