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

import { useState, useEffect, useId } from 'react';
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
  flex: '1 1 0',
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
  display: 'inline-block',
  width: '0.5rem',
  height: '0.5rem',
  borderRadius: '50%',
  flexShrink: 0,
};

/**
 * Render the order tracking page for a single order.
 *
 * On mount, and whenever `orderId` changes, the component fetches the order via
 * {@link getOrder} inside a cancellation-safe {@link useEffect} and drives three
 * pieces of state: `loading` (a request is in flight), `error` (a message string
 * when the fetch fails), and `order` (the fetched order view). A stale request
 * whose `orderId` has since changed -- or whose component has unmounted -- never
 * commits its result, avoiding a React "state update on an unmounted component"
 * warning and out-of-order responses.
 *
 * Rendering is fully guarded and never dereferences a null order:
 * - while loading, a `role="status"` message is announced;
 * - on failure, a `role="alert"` message reports the error;
 * - when no order resolves, a `role="status"` empty fallback is shown;
 * - on success, an ordered `<ol>` status stepper renders the {@link LIFECYCLE}
 *   stages, marking the stage that matches the fetched `order.status` with
 *   `aria-current="step"` (an unknown status highlights nothing), and the
 *   current-status label is shown via {@link OrderStatusBadge}.
 *
 * The displayed stage is derived exclusively from the server-provided
 * `order.status`; this component never computes or advances status locally.
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
  const headingId = useId();

  useEffect(() => {
    let active = true;

    // Guard against a missing id: report it rather than issuing a bad request.
    if (!orderId) {
      setOrder(null);
      setError('No order id was provided.');
      setLoading(false);
      return () => {
        active = false;
      };
    }

    // Reset to the loading state at the start of every run.
    setLoading(true);
    setError(null);

    /**
     * Fetch the order and commit the result only if this effect run is still
     * active (i.e. not superseded by an `orderId` change or an unmount).
     *
     * @returns {Promise<void>} Resolves once the relevant state has been set.
     */
    async function loadOrder() {
      try {
        const data = await getOrder(orderId);
        if (active) {
          setOrder(data);
        }
      } catch (err) {
        if (active) {
          setOrder(null);
          setError(err?.message || 'Failed to load order');
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
    };
  }, [orderId]);

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
        <h1 id={headingId} style={{ margin: 0, fontSize: '1.5rem' }}>
          Order tracking
        </h1>
        {order ? <OrderStatusBadge status={order.status} /> : null}
      </div>
      <p style={{ margin: '0.25rem 0 0', color: '#6b7280' }}>
        Tracking order: {orderId || '\u2014'}
      </p>

      {loading ? (
        <p role="status">Loading order&hellip;</p>
      ) : error ? (
        <p role="alert" style={{ color: '#b91c1c' }}>
          {orderId ? `Could not load order ${orderId}: ${error}` : error}
        </p>
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
              return (
                <li
                  key={stage}
                  aria-current={isCurrent ? 'step' : undefined}
                  style={{ ...STEP_BASE_STYLE, ...variant.step }}
                >
                  <span
                    aria-hidden="true"
                    style={{ ...STEP_DOT_STYLE, ...variant.dot }}
                  />
                  <span>{STAGE_LABELS[stage] ?? stage}</span>
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
