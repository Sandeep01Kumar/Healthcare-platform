/**
 * Root component and lightweight, dependency-free client-side router for the
 * `customer-ui` single-page application.
 *
 * `App` is the top of the component tree: `src/main.jsx` imports this module's
 * **default export** and renders it into the `#root` element. The component
 * composes the module's three customer-facing capabilities and switches between
 * two views based on the browser URL:
 *
 * - **Landing view** (any path that is not an order route) renders the
 *   multi-coupon input ({@link CouponInput}) plus a small affordance for opening
 *   an order's tracking view.
 * - **Order tracking view** (paths matching `/orders/:id`) renders
 *   {@link OrderTrackingPage} for the captured id, which owns the data fetch,
 *   the `CREATED -> CONFIRMED -> DELIVERED` status stepper, and the status
 *   badge.
 *
 * ## Routing without a router (hard constraint)
 * The `customer-ui` package declares only `react` and `react-dom` as runtime
 * dependencies — there is intentionally **no `react-router-dom`**. Routing is
 * therefore implemented here with nothing but React state and the browser
 * History API: the active `pathname` is held in state, browser back/forward is
 * observed via the `popstate` event, and in-app navigation uses
 * `history.pushState` to change the URL without a full-page reload. The
 * `/orders/:id` shape is matched by the pure {@link parseOrderId} helper rather
 * than any route-matcher library.
 *
 * ## Server-authoritative data
 * `App` never decides coupon validity or order status. Those verdicts are owned
 * by the child components/pages, which call the order-service HTTP API through
 * the shared client. `App` only routes and composes; it makes no network calls
 * and holds no order/coupon domain state.
 *
 * ## Styling & accessibility
 * There is no design system, component library, or Figma design for this module,
 * so the UI uses standard React and semantic HTML with minimal **inline**
 * styling only — no stylesheet is imported (an unresolved CSS import would break
 * the Vite build). Semantic landmarks (`<main>`, `<h1>`, `<nav>`, `<form>`) and
 * real, labelled interactive controls keep the shell accessible.
 *
 * @module App
 */

import { useState, useEffect } from 'react';
import OrderTrackingPage from './pages/OrderTrackingPage.jsx';
import CouponInput from './components/CouponInput.jsx';

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
 * Example order id offered on the landing view so the tracking route is
 * immediately explorable in the scaffold. It carries no business meaning and is
 * not a real order.
 *
 * @constant {string}
 */
const EXAMPLE_ORDER_ID = 'EXAMPLE';

/**
 * Extract the order id from a pathname that matches the `/orders/:id` route.
 *
 * Pure and defensive: it never throws and never mutates its input. A
 * non-matching or non-string pathname yields `null` (signalling "not an order
 * route"). The captured id is URI-decoded so an id that was percent-encoded by
 * {@link App~navigate} round-trips back to its original value; if decoding fails
 * (a malformed escape sequence), the raw captured segment is returned rather
 * than throwing.
 *
 * @param {string} pathname - A URL pathname, e.g. `window.location.pathname`.
 * @returns {string|null} The decoded order id when `pathname` matches
 *   `^/orders/([^/]+)/?$`, otherwise `null`.
 * @example
 * parseOrderId('/orders/123');   // => '123'
 * parseOrderId('/orders/123/');  // => '123'
 * parseOrderId('/orders/a%2Fb'); // => 'a/b'
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
    // Malformed percent-encoding — fall back to the raw segment; never throw.
    return raw;
  }
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
 * Holds the active pathname in state, keeps it in sync with browser
 * back/forward navigation via a `popstate` listener, and exposes an in-app
 * {@link App~navigate} helper that updates the URL with the History API (no full
 * reload). It renders {@link OrderTrackingPage} for `/orders/:id` and the
 * coupon-centric landing view otherwise. Composition only — all data fetching
 * and validity/status decisions live in the child components/pages.
 *
 * @returns {JSX.Element} The application shell for the current route.
 */
function App() {
  // Active pathname; lazy initializer avoids touching `window` during SSR/tests.
  const [path, setPath] = useState(readCurrentPath);
  // Controlled value of the "open an order" input on the landing view.
  const [orderIdDraft, setOrderIdDraft] = useState('');

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
   * Handle submission of the landing-view "open an order" form by routing to the
   * tracking view for the entered id. Whitespace-only input is ignored, and the
   * id is percent-encoded so it forms a single, valid path segment.
   *
   * @param {import('react').FormEvent<HTMLFormElement>} event - The submit event.
   * @returns {void}
   */
  function handleOpenTracking(event) {
    event.preventDefault();
    const trimmed = orderIdDraft.trim();
    if (!trimmed) {
      return;
    }
    navigate(`/orders/${encodeURIComponent(trimmed)}`);
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
    if (event.defaultPrevented || event.button !== 0 || event.metaKey ||
        event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    navigate(to);
  }

  const orderId = parseOrderId(path);

  return (
    <main style={{ maxWidth: 640, margin: '0 auto', padding: 16 }}>
      <h1 style={{ marginTop: 0 }}>Customer UI</h1>

      {orderId ? (
        <>
          <nav aria-label="Breadcrumb" style={{ marginBottom: 16 }}>
            <button type="button" onClick={() => navigate('/')}>
              &larr; Back to coupons
            </button>
          </nav>
          <OrderTrackingPage orderId={orderId} />
        </>
      ) : (
        <>
          <section aria-labelledby="coupons-heading" style={{ marginBottom: 24 }}>
            <h2 id="coupons-heading" style={{ fontSize: '1.125rem' }}>
              Apply coupons
            </h2>
            <CouponInput />
          </section>

          <section aria-labelledby="track-heading">
            <h2 id="track-heading" style={{ fontSize: '1.125rem' }}>
              Track an order
            </h2>
            <form
              onSubmit={handleOpenTracking}
              style={{ display: 'flex', alignItems: 'flex-end', gap: 8, flexWrap: 'wrap' }}
            >
              <span style={{ display: 'flex', flexDirection: 'column' }}>
                <label htmlFor="app-order-id" style={{ marginBottom: 4 }}>
                  Order ID
                </label>
                <input
                  id="app-order-id"
                  type="text"
                  value={orderIdDraft}
                  onChange={(event) => setOrderIdDraft(event.target.value)}
                  autoComplete="off"
                  placeholder="e.g. ORD-1024"
                />
              </span>
              <button type="submit">Track order</button>
            </form>
            <p style={{ margin: '0.5rem 0 0', color: '#555555' }}>
              Or open the{' '}
              <a
                href={`/orders/${EXAMPLE_ORDER_ID}`}
                onClick={(event) => handleAnchorNavigate(event, `/orders/${EXAMPLE_ORDER_ID}`)}
              >
                example order
              </a>
              .
            </p>
          </section>
        </>
      )}
    </main>
  );
}

export default App;
