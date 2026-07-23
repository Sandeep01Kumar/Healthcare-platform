/**
 * Root component and lightweight, dependency-free client-side router for the
 * `customer-ui` single-page application.
 *
 * `App` is the top of the component tree: `src/main.jsx` imports this module's
 * **default export** and renders it into the `#root` element. The component
 * owns the module's cross-cutting state and switches between three views based
 * on the browser URL:
 *
 * - **Landing view** (the root path `/`) renders the multi-coupon input
 *   ({@link CouponInput}), an order-creation form, and an "open an order"
 *   affordance. `App` OWNS the applied-coupon list and submits it — together
 *   with a price — to the real `POST /orders` endpoint via
 *   {@link module:orderServiceClient.createOrder}, so the coupons the customer
 *   applies actually participate in order creation, pricing, and server-side
 *   redemption (finding C3).
 * - **Order tracking view** (paths matching `/orders/:id`) renders
 *   {@link OrderTrackingPage} for the captured id, which owns the data fetch,
 *   the `CREATED -> CONFIRMED -> DELIVERED` status stepper, and the status
 *   badge. The tracking page owns the single page-level `<h1>` for this view.
 * - **Not-found view** (a malformed order path or any other unknown path)
 *   renders an explicit 404 rather than silently falling back to the landing
 *   view (finding M7).
 *
 * ## Routing without a router (hard constraint)
 * The `customer-ui` package declares only `react` and `react-dom` as runtime
 * dependencies — there is intentionally **no `react-router-dom`**. Routing is
 * therefore implemented here with nothing but React state and the browser
 * History API: the active `pathname` is held in state, browser back/forward is
 * observed via the `popstate` event, and in-app navigation uses
 * `history.pushState` to change the URL without a full-page reload. The
 * `/orders/:id` shape is matched by the pure {@link parseOrderId} helper, which
 * REJECTS malformed percent-encoding (routing it to the 404 view) rather than
 * treating the raw, undecodable segment as an id (finding M7).
 *
 * ## Accessibility of navigation
 * There is a single page-level `<h1>` per view (finding M12): `App` renders it
 * for the landing and not-found views, and {@link OrderTrackingPage} renders it
 * for the tracking view. On every client-side navigation the router updates
 * `document.title` and moves keyboard focus to the active view's heading, so
 * assistive-technology and keyboard users are informed of the context change.
 *
 * ## Server-authoritative data
 * `App` never decides coupon validity or order status. Those verdicts are owned
 * by the order-service HTTP API, reached through the shared client. `App`
 * orchestrates: it routes, owns the applied-coupon list, and triggers order
 * creation; it makes no validity/status decisions of its own.
 *
 * ## Styling
 * There is no design system, component library, or Figma design for this module,
 * so the UI uses standard React and semantic HTML with minimal **inline**
 * styling only — no stylesheet is imported (an unresolved CSS import would break
 * the Vite build). Controls meet a >= 44px touch target and forms wrap on narrow
 * screens.
 *
 * @module App
 */

import { useState, useEffect, useRef } from 'react';
import OrderTrackingPage from './pages/OrderTrackingPage.jsx';
import CouponInput from './components/CouponInput.jsx';
import { createOrder } from './api/orderServiceClient.js';
import { canonicalizeCouponCode } from './api/orderServiceContract.js';

/**
 * Matches an order-tracking pathname of the form `/orders/:id`, capturing the id
 * segment. A single optional trailing slash is tolerated. The id segment is any
 * run of characters that are not a slash, so nested paths such as
 * `/orders/a/b` deliberately do NOT match.
 *
 * @constant {RegExp}
 */
const ORDER_PATH_PATTERN = /^\/orders\/([^/]+)\/?$/;

/**
 * Extract the order id from a pathname that matches the `/orders/:id` route.
 *
 * Pure and defensive: it never throws and never mutates its input. A
 * non-matching or non-string pathname yields `null` (signalling "not an order
 * route"). The captured id is URI-decoded so an id that was percent-encoded by
 * {@link App~navigate} round-trips back to its original value.
 *
 * Malformed percent-encoding is REJECTED: an undecodable escape sequence (for
 * example `/orders/%ZZ`) yields `null` rather than the raw, ambiguous segment,
 * so a malformed path is routed to the 404 view instead of issuing a request for
 * a bogus id (finding M7).
 *
 * @param {string} pathname - A URL pathname, e.g. `window.location.pathname`.
 * @returns {string|null} The decoded order id when `pathname` matches
 *   `^/orders/([^/]+)/?$` AND the id decodes cleanly; otherwise `null`.
 * @example
 * parseOrderId('/orders/123');   // => '123'
 * parseOrderId('/orders/123/');  // => '123'
 * parseOrderId('/orders/a%2Fb'); // => 'a/b'
 * parseOrderId('/orders/%ZZ');   // => null  (malformed encoding rejected)
 * parseOrderId('/orders/');      // => null
 * parseOrderId('/orders/a/b');   // => null
 * parseOrderId('/');             // => null
 */
export function parseOrderId(pathname) {
  if (typeof pathname !== 'string') {
    return null;
  }
  const match = ORDER_PATH_PATTERN.exec(pathname);
  if (!match) {
    return null;
  }
  const raw = match[1];
  try {
    return decodeURIComponent(raw);
  } catch {
    // Malformed percent-encoding — reject the route (finding M7); never throw.
    return null;
  }
}

/**
 * Report whether a pathname is the landing (root) path.
 *
 * Only the site root is the landing view; every other non-order path is treated
 * as not-found (finding M7). The empty string is accepted as equivalent to `/`
 * for environments that report a blank pathname.
 *
 * @param {string} pathname - A URL pathname.
 * @returns {boolean} `true` when `pathname` is `'/'` or `''`.
 */
function isLandingPath(pathname) {
  return pathname === '/' || pathname === '';
}

/**
 * Read the current browser pathname in an environment-safe way.
 *
 * In the browser this is simply `window.location.pathname`. Guarding for the
 * absence of `window` keeps the module importable and renderable outside a DOM
 * (server-side rendering or a unit test running in a Node environment), where it
 * defaults to the site root `'/'`. Client behavior is unchanged.
 *
 * @returns {string} The active pathname, or `'/'` when there is no `window`.
 */
function readCurrentPath() {
  if (typeof window !== 'undefined' && window.location) {
    return window.location.pathname;
  }
  return '/';
}

/**
 * Root application component and client-side router.
 *
 * Holds the active pathname and the applied-coupon list in state, keeps the
 * pathname in sync with browser back/forward navigation via a `popstate`
 * listener, manages `document.title` and heading focus on navigation, and
 * exposes an in-app {@link App~navigate} helper that updates the URL with the
 * History API (no full reload). It renders {@link OrderTrackingPage} for
 * `/orders/:id`, the coupon/order-creation landing view for `/`, and a 404 view
 * otherwise. Coupon validity and order status decisions live server-side.
 *
 * @returns {JSX.Element} The application shell for the current route.
 */
function App() {
  // Active pathname; lazy initializer avoids touching `window` during SSR/tests.
  const [path, setPath] = useState(readCurrentPath);
  // Canonical applied-coupon list, owned here so it can be submitted to order
  // creation (finding C3). Each entry: { code (canonical), discount, reason }.
  const [appliedCoupons, setAppliedCoupons] = useState([]);
  // Controlled value of the "open an order" input on the landing view.
  const [orderIdDraft, setOrderIdDraft] = useState('');
  // Announced validation for the "open an order" form (finding M12).
  const [trackError, setTrackError] = useState('');
  // Controlled value of the order-creation "price" input.
  const [priceDraft, setPriceDraft] = useState('');
  // True while an order-creation request is in flight (disables the form).
  const [creating, setCreating] = useState(false);
  // Order-creation feedback: { tone: 'error'|'success'|'info', message } | null.
  const [createFeedback, setCreateFeedback] = useState(null);

  // Container for the active view; used to locate the view's <h1> for focus.
  const mainRef = useRef(null);
  // Skip heading focus on the very first render (only focus on navigation).
  const isInitialRender = useRef(true);

  // Route resolution (pure function of `path`).
  const orderId = parseOrderId(path);
  const routeView = orderId
    ? 'tracking'
    : isLandingPath(path)
      ? 'landing'
      : 'notfound';

  // Keep `path` in sync when the user presses the browser Back/Forward buttons.
  useEffect(() => {
    /** Sync React state with the address bar after a history pop. */
    function handlePopState() {
      setPath(readCurrentPath());
    }
    window.addEventListener('popstate', handlePopState);
    return () => {
      window.removeEventListener('popstate', handlePopState);
    };
  }, []);

  // On navigation: update the document title and move focus to the active
  // view's page-level heading so keyboard/AT users are informed of the context
  // change (finding M12). Focus is skipped on the initial render.
  useEffect(() => {
    if (typeof document !== 'undefined') {
      document.title =
        routeView === 'tracking'
          ? `Order ${orderId} \u2014 Customer UI`
          : routeView === 'notfound'
            ? 'Page not found \u2014 Customer UI'
            : 'Customer UI';
    }
    if (isInitialRender.current) {
      isInitialRender.current = false;
      return;
    }
    const heading = mainRef.current && mainRef.current.querySelector('h1');
    if (heading && typeof heading.focus === 'function') {
      heading.focus();
    }
  }, [routeView, orderId]);

  /**
   * Navigate to an in-app path without a full page reload.
   *
   * Pushes a new History entry (so browser Back returns to the previous view)
   * and updates local state to re-render for the new route. The `window` guard
   * keeps the function safe to call in non-browser environments.
   *
   * @param {string} to - Target pathname, e.g. `"/orders/123"` or `"/"`.
   * @returns {void}
   */
  function navigate(to) {
    if (typeof window !== 'undefined' && window.history) {
      window.history.pushState({}, '', to);
    }
    setPath(to);
  }

  /**
   * Intercept a same-app anchor click so it navigates via the History API
   * instead of triggering a full document load, while leaving the `href` intact
   * for accessibility and middle/modified-click behavior.
   *
   * @param {import('react').MouseEvent<HTMLAnchorElement>} event - The click event.
   * @param {string} to - Target pathname to navigate to.
   * @returns {void}
   */
  function handleAnchorNavigate(event, to) {
    // Respect new-tab / modifier-click gestures — let the browser handle those.
    if (
      event.defaultPrevented ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return;
    }
    event.preventDefault();
    navigate(to);
  }

  /**
   * Add a server-accepted coupon to the applied list, owned here so it can be
   * submitted to order creation (finding C3). The next state is computed inside
   * a functional update and de-duplicated by CANONICAL identity, so repeated or
   * differently-cased applies never stack and there is no stale-closure hazard
   * (finding M4).
   *
   * @param {{code: string, discount: object, reason: string}} entry - The
   *   accepted coupon (canonical `code`).
   * @returns {void}
   */
  function handleApplyCoupon(entry) {
    setAppliedCoupons((prev) => {
      const canonical = canonicalizeCouponCode(entry.code);
      if (prev.some((e) => canonicalizeCouponCode(e.code) === canonical)) {
        return prev;
      }
      return [...prev, entry];
    });
  }

  /**
   * Remove an applied coupon by its canonical code, computing the next state
   * from a functional update (finding M4).
   *
   * @param {string} canonicalCode - The canonical code to remove.
   * @returns {void}
   */
  function handleRemoveCoupon(canonicalCode) {
    setAppliedCoupons((prev) =>
      prev.filter((e) => canonicalizeCouponCode(e.code) !== canonicalCode)
    );
  }

  /**
   * Create an order at the entered price, submitting the applied coupons so they
   * participate in server-side pricing and redemption (finding C3). On success
   * the router navigates to the new order's tracking view. Failures are surfaced
   * as SAFE feedback (finding M6); a blank price is announced rather than
   * silently ignored (finding M12).
   *
   * @param {import('react').FormEvent<HTMLFormElement>} event - The submit event.
   * @returns {Promise<void>}
   */
  async function handleCreateOrder(event) {
    event.preventDefault();
    const trimmedPrice = priceDraft.trim();
    if (!trimmedPrice) {
      setCreateFeedback({ tone: 'error', message: 'Enter a price to create an order.' });
      return;
    }
    setCreating(true);
    setCreateFeedback(null);
    try {
      const codes = appliedCoupons.map((entry) => entry.code);
      const order = await createOrder(trimmedPrice, codes);
      // Leave the landing view for the new order's tracking page.
      navigate(`/orders/${encodeURIComponent(order.id)}`);
    } catch (err) {
      // Show only the SAFE message (finding M6). Transport/HTTP failures carry a
      // curated `userMessage`; input-validation errors (bad price) fall back to
      // a friendly generic message rather than exposing raw error text.
      const safe =
        (err && typeof err.userMessage === 'string' && err.userMessage) ||
        'Could not create the order. Please check the price and try again.';
      setCreateFeedback({ tone: 'error', message: safe });
    } finally {
      setCreating(false);
    }
  }

  /**
   * Handle submission of the landing-view "open an order" form by routing to the
   * tracking view for the entered id. A blank/whitespace-only entry is ANNOUNCED
   * as a validation error rather than silently ignored (finding M12); the id is
   * percent-encoded so it forms a single, valid path segment.
   *
   * @param {import('react').FormEvent<HTMLFormElement>} event - The submit event.
   * @returns {void}
   */
  function handleOpenTracking(event) {
    event.preventDefault();
    const trimmed = orderIdDraft.trim();
    if (!trimmed) {
      setTrackError('Enter an order ID to track.');
      return;
    }
    setTrackError('');
    navigate(`/orders/${encodeURIComponent(trimmed)}`);
  }

  const createIsError = createFeedback?.tone === 'error';

  return (
    <main ref={mainRef} style={{ maxWidth: 640, margin: '0 auto', padding: 16 }}>
      {routeView === 'tracking' ? (
        // Tracking view: the page owns the single page-level <h1> (finding M12).
        <>
          <nav aria-label="Breadcrumb" style={{ marginBottom: 16 }}>
            <button
              type="button"
              onClick={() => navigate('/')}
              style={{ minHeight: '2.75rem', padding: '0.5rem 1rem' }}
            >
              &larr; Back to coupons
            </button>
          </nav>
          <OrderTrackingPage orderId={orderId} />
        </>
      ) : routeView === 'notfound' ? (
        // Explicit 404 (finding M7): App owns the single page-level <h1>.
        <>
          <h1 tabIndex={-1} style={{ marginTop: 0 }}>
            Page not found
          </h1>
          <p>
            We couldn&rsquo;t find the page you were looking for. The order link
            may be mistyped or the order may not exist.
          </p>
          <p>
            <a href="/" onClick={(event) => handleAnchorNavigate(event, '/')}>
              Return to the coupons page
            </a>
          </p>
        </>
      ) : (
        // Landing view: App owns the single page-level <h1>.
        <>
          <h1 tabIndex={-1} style={{ marginTop: 0 }}>
            Customer UI
          </h1>

          <section aria-labelledby="coupons-heading" style={{ marginBottom: 24 }}>
            <h2 id="coupons-heading" style={{ fontSize: '1.125rem' }}>
              Apply coupons
            </h2>
            <CouponInput
              appliedCoupons={appliedCoupons}
              onApplyCoupon={handleApplyCoupon}
              onRemoveCoupon={handleRemoveCoupon}
            />
          </section>

          <section aria-labelledby="create-heading" style={{ marginBottom: 24 }}>
            <h2 id="create-heading" style={{ fontSize: '1.125rem' }}>
              Create an order
            </h2>
            <p style={{ marginTop: 0, color: '#555555' }}>
              Create an order at the entered price. Any coupons applied above are
              submitted with it and redeemed by the order service.
            </p>
            <form
              onSubmit={handleCreateOrder}
              style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}
            >
              <span style={{ display: 'flex', flexDirection: 'column', flex: '1 1 10rem', minWidth: 0 }}>
                <label htmlFor="app-order-price" style={{ marginBottom: 4 }}>
                  Price
                </label>
                <input
                  id="app-order-price"
                  type="text"
                  inputMode="decimal"
                  value={priceDraft}
                  onChange={(event) => setPriceDraft(event.target.value)}
                  autoComplete="off"
                  placeholder="e.g. 100.00"
                  disabled={creating}
                  style={{
                    width: '100%',
                    minHeight: '2.75rem',
                    padding: '0.5rem 0.625rem',
                    fontSize: '1rem',
                    boxSizing: 'border-box',
                  }}
                />
              </span>
              <button
                type="submit"
                disabled={creating}
                style={{ minHeight: '2.75rem', padding: '0.5rem 1rem', fontSize: '1rem' }}
              >
                {creating ? 'Creating…' : 'Create order'}
              </button>
            </form>
            <p
              role={createIsError ? 'alert' : 'status'}
              style={{
                minHeight: '1.25rem',
                margin: '0.5rem 0 0',
                color: createIsError ? '#b00020' : '#333333',
                overflowWrap: 'anywhere',
              }}
            >
              {createFeedback ? createFeedback.message : ''}
            </p>
          </section>

          <section aria-labelledby="track-heading">
            <h2 id="track-heading" style={{ fontSize: '1.125rem' }}>
              Track an order
            </h2>
            <form
              onSubmit={handleOpenTracking}
              noValidate
              style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}
            >
              <span style={{ display: 'flex', flexDirection: 'column', flex: '1 1 10rem', minWidth: 0 }}>
                <label htmlFor="app-order-id" style={{ marginBottom: 4 }}>
                  Order ID
                </label>
                <input
                  id="app-order-id"
                  type="text"
                  value={orderIdDraft}
                  onChange={(event) => {
                    setOrderIdDraft(event.target.value);
                    if (trackError) {
                      setTrackError('');
                    }
                  }}
                  autoComplete="off"
                  placeholder="e.g. ORD-1024"
                  aria-describedby="app-order-id-error"
                  aria-invalid={trackError ? 'true' : undefined}
                  style={{
                    width: '100%',
                    minHeight: '2.75rem',
                    padding: '0.5rem 0.625rem',
                    fontSize: '1rem',
                    boxSizing: 'border-box',
                  }}
                />
              </span>
              <button
                type="submit"
                style={{ minHeight: '2.75rem', padding: '0.5rem 1rem', fontSize: '1rem' }}
              >
                Track order
              </button>
            </form>
            <p
              id="app-order-id-error"
              role="alert"
              style={{
                minHeight: '1.25rem',
                margin: '0.5rem 0 0',
                color: '#b00020',
                overflowWrap: 'anywhere',
              }}
            >
              {trackError || ''}
            </p>
          </section>
        </>
      )}
    </main>
  );
}

export default App;
