/**
 * Order tracking page for the customer-ui single-page application.
 *
 * Fetches a single order by its identifier from the order-service HTTP API and
 * renders the order's position in the forward-only lifecycle
 * `CREATED -> CONFIRMED -> DELIVERED` as an accessible, semantic status stepper,
 * alongside the current-status badge and (optionally) the order's monetary
 * totals and applied coupons.
 *
 * This is the sole route-level (page) component of the SPA. Routing lives in
 * `App.jsx`, which mounts this page for the `/orders/:id` route and passes the
 * captured id down as the `orderId` prop; this component performs no routing of
 * its own and owns no navigation.
 *
 * Server-authoritative status (hard rule): the displayed stage is derived SOLELY
 * from the `status` returned by {@link getOrder}. This page never hardcodes,
 * infers, advances, or otherwise computes an order's status locally -- the
 * {@link LIFECYCLE} constant below is a fixed *display ordering* of the stage
 * columns, not a status decision. All order data arrives over HTTP; no
 * order-service Java type is imported or referenced.
 *
 * The module has no external UI/CSS dependencies; any styling is minimal and
 * inline. Its only runtime dependencies are React, the order-service client, and
 * the sibling {@link OrderStatusBadge} component.
 *
 * @module OrderTrackingPage
 */

import { useState, useEffect, useId, useRef } from 'react';
import { getOrder } from '../api/orderServiceClient.js';
import OrderStatusBadge from '../components/OrderStatusBadge.jsx';

/**
 * The exact, ordered order lifecycle stages, mirroring the order-service
 * `OrderStatus` enum (forward-only; deliberately NO `CANCELLED`).
 *
 * Used purely to render the stepper columns left-to-right in lifecycle order;
 * this is display ordering only and never drives a status decision.
 *
 * @constant {ReadonlyArray<'CREATED'|'CONFIRMED'|'DELIVERED'>}
 */
const LIFECYCLE = Object.freeze(['CREATED', 'CONFIRMED', 'DELIVERED']);

/**
 * Human-readable labels for each lifecycle stage, keyed by the authoritative
 * uppercase status value. A stage absent from this map falls back to its raw
 * value in the stepper, so an unexpected key can never render as blank.
 *
 * @constant {Record<'CREATED'|'CONFIRMED'|'DELIVERED', string>}
 */
const STAGE_LABELS = Object.freeze({
  CREATED: 'Created',
  CONFIRMED: 'Confirmed',
  DELIVERED: 'Delivered',
});

/**
 * Resolve the zero-based index of a status within {@link LIFECYCLE}.
 *
 * Pure and defensive: an unknown, missing, or unsupported status yields `-1`,
 * which the stepper renders with no active step highlighted rather than
 * throwing. Never mutates its input and never throws.
 *
 * @param {*} status - The order status to locate (expected to be one of
 *   `CREATED`, `CONFIRMED`, or `DELIVERED`).
 * @returns {number} The stage index in the range `0..2`, or `-1` when not found.
 */
function stageIndex(status) {
  return LIFECYCLE.indexOf(status);
}

/**
 * Classify a stepper stage relative to the active stage index.
 *
 * Drives per-stage styling only; it makes no status decision. When `current` is
 * `-1` (unknown/missing status) every stage is reported as `upcoming`, so
 * nothing is highlighted.
 *
 * @param {number} index - The stage's index within {@link LIFECYCLE}.
 * @param {number} current - The active stage index (`-1` when unknown/missing).
 * @returns {'complete'|'current'|'upcoming'} The stage's visual state.
 */
function stepState(index, current) {
  if (current === -1) {
    return 'upcoming';
  }
  if (index < current) {
    return 'complete';
  }
  if (index === current) {
    return 'current';
  }
  return 'upcoming';
}

/**
 * Format a monetary value for display without ever throwing.
 *
 * The order-service serializes `price`/`discountedTotal` from a Java
 * `BigDecimal`, which may arrive as EITHER a JSON number OR a string. This
 * helper coerces defensively:
 *
 * - `null` / `undefined` -> empty string (the caller omits the field);
 * - a value that coerces to a finite number -> fixed to two decimal places;
 * - any other value -> its raw string form (never a thrown error and never a
 *   misleading `"NaN"` from a blind numeric format).
 *
 * @param {number|string|null|undefined} value - The raw monetary value.
 * @returns {string} A safe, human-readable representation (possibly empty).
 */
function formatMoney(value) {
  if (value === null || value === undefined) {
    return '';
  }
  const numeric = Number(value);
  if (Number.isFinite(numeric)) {
    return numeric.toFixed(2);
  }
  return String(value);
}

/**
 * Coerce a single applied-coupon entry to a readable label without throwing.
 *
 * An entry may be a bare coupon-code string or an object (for example
 * `{ code, type, value }`); this helper prefers a non-blank `code` field, then
 * any JSON-serializable shape, and finally the primitive's string form.
 *
 * @param {*} coupon - A single entry from `order.appliedCoupons`.
 * @returns {string} A safe, human-readable coupon label (possibly empty).
 */
function formatCoupon(coupon) {
  if (coupon === null || coupon === undefined) {
    return '';
  }
  if (typeof coupon === 'string') {
    return coupon;
  }
  if (typeof coupon === 'object') {
    if (typeof coupon.code === 'string' && coupon.code.trim() !== '') {
      return coupon.code;
    }
    try {
      return JSON.stringify(coupon);
    } catch {
      return String(coupon);
    }
  }
  return String(coupon);
}

/**
 * Page shell container: centered, comfortably padded, capped width.
 *
 * @constant {React.CSSProperties}
 */
const CONTAINER_STYLE = {
  maxWidth: '40rem',
  margin: '0 auto',
  padding: '1rem',
};

/**
 * Header row pairing the page heading with the current-status badge.
 *
 * @constant {React.CSSProperties}
 */
const HEADER_STYLE = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: '0.75rem',
  flexWrap: 'wrap',
};

/**
 * Ordered-list style for the horizontal status stepper (semantic `<ol>`).
 *
 * @constant {React.CSSProperties}
 */
const STEPPER_STYLE = {
  display: 'flex',
  flexWrap: 'wrap', // steps wrap onto new rows on narrow screens (finding M12)
  alignItems: 'stretch',
  gap: '0.5rem',
  listStyle: 'none',
  margin: '1rem 0',
  padding: 0,
};

/**
 * Base style shared by every stepper step; state palettes are layered on top.
 *
 * @constant {React.CSSProperties}
 */
const STEP_BASE_STYLE = {
  // A non-zero basis lets steps sit in a row on wide screens yet wrap to full
  // width (rather than being crushed) on narrow screens (finding M12).
  flex: '1 1 8rem',
  minWidth: 0,
  display: 'flex',
  alignItems: 'center',
  gap: '0.5rem',
  padding: '0.5rem 0.75rem',
  borderWidth: '1px',
  borderStyle: 'solid',
  borderRadius: '0.5rem',
  fontSize: '0.875rem',
};

/**
 * Per-state visual treatment for a stepper step. Color is never the sole
 * indicator: the text label and `aria-current` also convey the active stage.
 *
 * @constant {Record<'complete'|'current'|'upcoming', {step: React.CSSProperties, dot: React.CSSProperties}>}
 */
const STEP_STATE_STYLES = Object.freeze({
  complete: {
    step: { backgroundColor: '#dcfce7', color: '#166534', borderColor: '#bbf7d0' },
    dot: { backgroundColor: '#16a34a' },
  },
  current: {
    step: {
      backgroundColor: '#eef2ff',
      color: '#3730a3',
      borderColor: '#c7d2fe',
      fontWeight: 600,
    },
    dot: { backgroundColor: '#6366f1' },
  },
  upcoming: {
    step: { backgroundColor: '#f9fafb', color: '#6b7280', borderColor: '#e5e7eb' },
    dot: { backgroundColor: '#d1d5db' },
  },
});

/**
 * Base style for the small decorative dot inside each step (marked
 * `aria-hidden`; the text label carries the meaning).
 *
 * @constant {React.CSSProperties}
 */
const STEP_DOT_STYLE = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '1.1rem',
  height: '1.1rem',
  borderRadius: '50%',
  flexShrink: 0,
  fontSize: '0.8rem',
  fontWeight: 700,
  lineHeight: 1,
};

/**
 * Shape-based marker glyph per stepper state. This is the NON-COLOR cue required
 * by WCAG 1.4.1 (finding M12): the three states are distinguished by glyph SHAPE
 * (`✓` filled check, `●` filled circle, `○` hollow circle), not by color alone,
 * so the stepper remains legible to color-blind users. The glyph is decorative
 * (`aria-hidden`); the accessible state is carried by `aria-current="step"` and
 * the visually-hidden {@link STEP_STATE_SR_LABEL} text.
 *
 * @constant {Record<'complete'|'current'|'upcoming', string>}
 */
const STEP_STATE_GLYPH = Object.freeze({
  complete: '\u2713', // ✓
  current: '\u25CF', // ●
  upcoming: '\u25CB', // ○
});

/**
 * Visually-hidden state words announced to assistive technology so the stepper's
 * meaning does not depend on the visual glyph/color. The `current` stage relies
 * on `aria-current="step"` instead, so its entry is intentionally empty to avoid
 * a duplicated announcement.
 *
 * @constant {Record<'complete'|'current'|'upcoming', string>}
 */
const STEP_STATE_SR_LABEL = Object.freeze({
  complete: 'completed',
  current: '',
  upcoming: 'upcoming',
});

/**
 * Visually-hidden ("screen-reader only") style: removed from the visual layout
 * but still present in the accessibility tree. The standard 1px clip technique.
 *
 * @constant {React.CSSProperties}
 */
const SR_ONLY_STYLE = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
};

/**
 * Inline style for the small "Refresh"/"Retry" control shown on the tracking
 * page. Sized to meet the >= 44px touch-target guideline (finding M12).
 *
 * @constant {React.CSSProperties}
 */
const REFRESH_BUTTON_STYLE = {
  minHeight: '2.75rem',
  padding: '0.5rem 1rem',
  fontSize: '0.95rem',
};

/**
 * Render the order tracking page for a single order.
 *
 * On mount, whenever `orderId` changes, and whenever the customer triggers a
 * manual refresh/retry, the component fetches the order via {@link getOrder}
 * inside a cancellation-safe {@link useEffect} and drives three pieces of state:
 * `loading` (a request is in flight), `error` (a SAFE message string when the
 * fetch fails), and `order` (the fetched order view). Each run:
 * - clears the previously displayed `order` at the START of the run so stale
 *   data from a different id (for example the status badge) is never shown while
 *   the new order loads (finding M5);
 * - passes an {@link AbortController} signal to {@link getOrder} and aborts it in
 *   the effect cleanup, so a superseded request (id change, refresh, or unmount)
 *   is actually cancelled at the transport layer rather than merely ignored
 *   (finding M5); the guarded `active` flag additionally prevents any state
 *   update from a stale run.
 *
 * A manual refresh is available via a monotonically increasing `reloadToken`
 * that is part of the effect's dependency list; a "Refresh" control (when an
 * order is loaded) and a "Retry" control (on failure) increment it to re-run the
 * fetch (finding M5).
 *
 * Rendering is fully guarded and never dereferences a null order:
 * - while loading, a `role="status"` message is announced;
 * - on failure, a `role="alert"` message reports the SAFE error text (the
 *   client's `userMessage`, never raw transport/HTTP detail — finding M6) with a
 *   Retry control;
 * - when no order resolves, a `role="status"` empty fallback is shown;
 * - on success, an ordered `<ol>` status stepper renders the {@link LIFECYCLE}
 *   stages, marking the stage that matches the fetched `order.status` with
 *   `aria-current="step"` (an unknown status highlights nothing), with a
 *   shape-based non-color state glyph and a visually-hidden state word, and the
 *   current-status label is shown via {@link OrderStatusBadge}.
 *
 * The displayed stage is derived exclusively from the server-provided
 * `order.status`; this component never computes or advances status locally.
 *
 * The page owns the SINGLE page-level `<h1>` (finding M12); it is programmatically
 * focusable (`tabIndex={-1}`) so the router in `App.jsx` can move focus to it on
 * navigation.
 *
 * @param {object} props - Component props.
 * @param {string} props.orderId - The identifier of the order to track (the
 *   `:id` segment of the `/orders/:id` route). A falsy value is reported as an
 *   error rather than triggering a malformed request.
 * @returns {JSX.Element} The order tracking page.
 */
export default function OrderTrackingPage({ orderId }) {
  const [order, setOrder] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  // Bumped by the Refresh/Retry controls to re-run the fetch effect (finding M5).
  const [reloadToken, setReloadToken] = useState(0);
  const headingId = useId();

  // Focus-restoration wiring for keyboard/accessibility (finding MIN-1).
  //
  // Activating "Refresh" disables that button while the reload is in flight
  // (and "Retry" is unmounted when the error view is replaced by the loading
  // view), which blurs the just-activated control and drops focus to
  // `document.body`. To keep keyboard focus logical, we restore it to a
  // sensible control once the reload settles: the Refresh button if an order
  // loaded, else the Retry button if the reload failed, else the page heading.
  //
  // `restoreFocusRef` is a one-shot flag armed ONLY by `refresh()` (a user
  // action). It is deliberately NOT armed on mount or on `orderId` navigation,
  // so the router's own focus-to-<h1>-on-navigation behavior in `App.jsx`
  // (finding M12) is fully preserved and never fought over.
  const refreshButtonRef = useRef(null);
  const retryButtonRef = useRef(null);
  const headingRef = useRef(null);
  const restoreFocusRef = useRef(false);

  useEffect(() => {
    let active = true;

    // Clear any previously displayed order at the start of EVERY run so stale
    // data (e.g. the header status badge) is never shown while a different order
    // loads (finding M5).
    setOrder(null);

    // Guard against a missing id: report it rather than issuing a bad request.
    if (!orderId) {
      setError('No order id was provided.');
      setLoading(false);
      return () => {
        active = false;
      };
    }

    // Reset to the loading state at the start of every run.
    setLoading(true);
    setError(null);

    // Cancel the in-flight request when this run is superseded (id change,
    // refresh, or unmount) so the transport is actually aborted (finding M5).
    const controller =
      typeof AbortController !== 'undefined' ? new AbortController() : null;

    /**
     * Fetch the order and commit the result only if this effect run is still
     * active (i.e. not superseded by an `orderId`/`reloadToken` change or an
     * unmount).
     *
     * @returns {Promise<void>} Resolves once the relevant state has been set.
     */
    async function loadOrder() {
      try {
        const data = await getOrder(orderId, { signal: controller?.signal });
        if (active) {
          setOrder(data);
        }
      } catch (err) {
        // A cancellation is expected on supersede/unmount — never surface it.
        if (active) {
          setOrder(null);
          // Show only the SAFE, curated message (finding M6); the raw
          // `err.message` may carry method/path/status/server-envelope text.
          setError(
            (err && typeof err.userMessage === 'string' && err.userMessage) ||
              'Could not load the order. Please try again.'
          );
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    }

    loadOrder();

    return () => {
      active = false;
      controller?.abort();
    };
  }, [orderId, reloadToken]);

  // Restore keyboard focus after a user-initiated refresh/retry settles
  // (finding MIN-1). This runs after each load state change, but acts ONLY when
  // the reload was armed by `refresh()` and the request is no longer in flight,
  // so it fires exactly once per manual refresh/retry and never steals focus on
  // mount or navigation. The target is chosen from whatever control is actually
  // present after the load: Refresh (order loaded) -> Retry (reload failed) ->
  // heading (neither control rendered, e.g. an empty result).
  useEffect(() => {
    if (loading) {
      return;
    }
    if (!restoreFocusRef.current) {
      return;
    }
    restoreFocusRef.current = false;
    const target =
      refreshButtonRef.current || retryButtonRef.current || headingRef.current;
    target?.focus();
  }, [loading, error, order]);

  /**
   * Trigger a manual refresh/retry by advancing the reload token, which re-runs
   * the fetch effect (and aborts any in-flight request first).
   *
   * Arms the one-shot focus-restoration flag first so keyboard focus returns to
   * a logical control once the reload settles (finding MIN-1); the flag is read
   * and cleared by the focus-restoration effect above.
   *
   * @returns {void}
   */
  function refresh() {
    restoreFocusRef.current = true;
    setReloadToken((token) => token + 1);
  }

  // Derived, null-safe view data (never dereference a null order).
  const current = order ? stageIndex(order.status) : -1;
  const price = order ? formatMoney(order.price) : '';
  const discountedTotal = order ? formatMoney(order.discountedTotal) : '';
  const hasCoupons =
    !!order &&
    Array.isArray(order.appliedCoupons) &&
    order.appliedCoupons.length > 0;

  return (
    <section aria-labelledby={headingId} style={CONTAINER_STYLE}>
      <div style={HEADER_STYLE}>
        {/*
          The SINGLE page-level heading (finding M12). `tabIndex={-1}` makes it
          programmatically focusable so the router can move focus here on
          navigation without adding it to the sequential tab order.
        */}
        <h1
          ref={headingRef}
          id={headingId}
          tabIndex={-1}
          style={{ margin: 0, fontSize: '1.5rem' }}
        >
          Order tracking
        </h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
          {order ? <OrderStatusBadge status={order.status} /> : null}
          {order ? (
            <button
              ref={refreshButtonRef}
              type="button"
              onClick={refresh}
              disabled={loading}
              style={REFRESH_BUTTON_STYLE}
            >
              {loading ? 'Refreshing…' : 'Refresh'}
            </button>
          ) : null}
        </div>
      </div>
      <p style={{ margin: '0.25rem 0 0', color: '#6b7280', overflowWrap: 'anywhere' }}>
        Tracking order: {orderId || '\u2014'}
      </p>

      {loading ? (
        <p role="status">Loading order&hellip;</p>
      ) : error ? (
        <div>
          <p role="alert" style={{ color: '#b91c1c', overflowWrap: 'anywhere' }}>
            {error}
          </p>
          {orderId ? (
            <button
              ref={retryButtonRef}
              type="button"
              onClick={refresh}
              style={REFRESH_BUTTON_STYLE}
            >
              Retry
            </button>
          ) : null}
        </div>
      ) : !order ? (
        <p role="status">
          {orderId ? `No order found for ${orderId}.` : 'No order found.'}
        </p>
      ) : (
        <>
          <ol aria-label="Order status" style={STEPPER_STYLE}>
            {LIFECYCLE.map((stage, index) => {
              const state = stepState(index, current);
              const isCurrent = state === 'current';
              const variant = STEP_STATE_STYLES[state];
              const srLabel = STEP_STATE_SR_LABEL[state];
              return (
                <li
                  key={stage}
                  aria-current={isCurrent ? 'step' : undefined}
                  style={{ ...STEP_BASE_STYLE, ...variant.step }}
                >
                  {/*
                    Shape-based, color-independent state marker (finding M12):
                    ✓ complete, ● current, ○ upcoming. Decorative (aria-hidden);
                    the accessible state is conveyed by aria-current on the step
                    and the visually-hidden state word below.
                  */}
                  <span
                    aria-hidden="true"
                    style={{ ...STEP_DOT_STYLE, color: variant.dot.backgroundColor }}
                  >
                    {STEP_STATE_GLYPH[state]}
                  </span>
                  <span style={{ overflowWrap: 'anywhere', minWidth: 0 }}>
                    {STAGE_LABELS[stage] ?? stage}
                    {srLabel ? (
                      <span style={SR_ONLY_STYLE}>{`, ${srLabel}`}</span>
                    ) : null}
                  </span>
                </li>
              );
            })}
          </ol>

          {price || discountedTotal ? (
            <dl style={{ margin: '1rem 0', display: 'grid', gap: '0.25rem' }}>
              {price ? (
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <dt style={{ fontWeight: 600 }}>Price:</dt>
                  <dd style={{ margin: 0 }}>{price}</dd>
                </div>
              ) : null}
              {discountedTotal ? (
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <dt style={{ fontWeight: 600 }}>Discounted total:</dt>
                  <dd style={{ margin: 0 }}>{discountedTotal}</dd>
                </div>
              ) : null}
            </dl>
          ) : null}

          {hasCoupons ? (
            <div>
              <h2 style={{ fontSize: '1rem', margin: '0 0 0.5rem' }}>
                Applied coupons
              </h2>
              <ul style={{ margin: 0, paddingInlineStart: '1.25rem' }}>
                {order.appliedCoupons.map((coupon, index) => (
                  <li key={`coupon-${index}-${formatCoupon(coupon)}`}>
                    {formatCoupon(coupon)}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      )}
    </section>
  );
}
