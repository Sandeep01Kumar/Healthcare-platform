/**
 * OrderStatusBadge — presentational badge for an order's current lifecycle status.
 *
 * Renders a single, accessible status pill for one of the order-service
 * lifecycle states `CREATED → CONFIRMED → DELIVERED` (a forward-only lifecycle
 * with no `CANCELLED` state). The visible value mirrors the order-service
 * `OrderStatus` enum exactly — the raw uppercase status is never lowercased,
 * translated, remapped, or reordered; only a human-readable *label* is shown.
 *
 * This component is intentionally the foundational, standalone member of the
 * `components/` folder: it is purely presentational (props-in → markup-out),
 * performs no data fetching, holds no state, makes no network/API calls, and
 * imports nothing beyond what the automatic JSX runtime provides. All data
 * arrives via the single `status` prop; the owning page/component is
 * responsible for fetching the order and passing the current status down.
 *
 * Defensive by design: an `undefined`, `null`, or unrecognized `status` renders
 * a safe neutral fallback and never throws, so it cannot crash the React tree.
 * The fallback is a *display* affordance only — it never invents a new
 * lifecycle state.
 *
 * @module OrderStatusBadge
 */

/**
 * Human-readable labels keyed by the exact order-service `OrderStatus` values.
 *
 * Keys are the authoritative uppercase enum strings; values are the concise,
 * title-cased labels shown to the customer. Unrecognized keys intentionally
 * have no entry so the component can fall back to the raw value (see the label
 * resolution inside {@link OrderStatusBadge}).
 *
 * @constant {Record<'CREATED'|'CONFIRMED'|'DELIVERED', string>}
 */
const STATUS_LABELS = {
  CREATED: 'Created',
  CONFIRMED: 'Confirmed',
  DELIVERED: 'Delivered',
};

/**
 * Base inline style shared by every badge variant.
 *
 * Styling is intentionally minimal and inline (React `style`): the module
 * imports no stylesheet and depends on no design system or CSS framework, so it
 * renders inside any host application without build-time CSS resolution. `rem`
 * units are used for type and spacing so the badge scales with the user's
 * font-size preference; the 1px border stays in `px` so it does not scale.
 *
 * @constant {React.CSSProperties}
 */
const BADGE_BASE_STYLE = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: '0.375rem',
  padding: '0.125rem 0.5rem',
  borderRadius: '9999px',
  borderWidth: '1px',
  borderStyle: 'solid',
  fontSize: '0.875rem',
  fontWeight: 600,
  lineHeight: 1.25,
  whiteSpace: 'nowrap',
};

/**
 * Base inline style for the small, decorative status dot.
 *
 * The dot is purely ornamental (it is marked `aria-hidden`) and carries no
 * semantic meaning; color is never the sole indicator of status — the text
 * label always accompanies it, satisfying the "not by color alone" guideline.
 *
 * @constant {React.CSSProperties}
 */
const DOT_BASE_STYLE = {
  display: 'inline-block',
  width: '0.5rem',
  height: '0.5rem',
  borderRadius: '50%',
  flexShrink: 0,
};

/**
 * Per-status color variants: badge surface/text/border plus dot fill.
 *
 * Each palette pairs a light surface with dark text for comfortable contrast.
 * Statuses absent from this map fall back to {@link FALLBACK_STYLE}.
 *
 * @constant {Record<'CREATED'|'CONFIRMED'|'DELIVERED', {badge: React.CSSProperties, dot: React.CSSProperties}>}
 */
const STATUS_STYLES = {
  CREATED: {
    badge: { backgroundColor: '#eef2ff', color: '#3730a3', borderColor: '#c7d2fe' },
    dot: { backgroundColor: '#6366f1' },
  },
  CONFIRMED: {
    badge: { backgroundColor: '#fef3c7', color: '#92400e', borderColor: '#fde68a' },
    dot: { backgroundColor: '#d97706' },
  },
  DELIVERED: {
    badge: { backgroundColor: '#dcfce7', color: '#166534', borderColor: '#bbf7d0' },
    dot: { backgroundColor: '#16a34a' },
  },
};

/**
 * Neutral variant used when `status` is missing or unrecognized.
 *
 * @constant {{badge: React.CSSProperties, dot: React.CSSProperties}}
 */
const FALLBACK_STYLE = {
  badge: { backgroundColor: '#f3f4f6', color: '#374151', borderColor: '#e5e7eb' },
  dot: { backgroundColor: '#9ca3af' },
};

/**
 * Render an accessible badge for an order's current lifecycle status.
 *
 * Purely presentational: it maps the incoming `status` to a friendly label and
 * a subtle color variant, then renders one inline `<span>`. The status value
 * mirrors the order-service `OrderStatus` enum and is emitted unchanged
 * (uppercase); only the *label* is humanized. Unknown or missing values degrade
 * to a neutral fallback badge and never throw.
 *
 * The badge is announced by assistive technology: it carries `role="status"`
 * and `aria-live="polite"` so screen readers politely announce the value when
 * an order advances, and an `aria-label` of the form `Order status: <label>`
 * gives listeners the full context rather than a bare word.
 *
 * @param {object} props - Component props.
 * @param {'CREATED'|'CONFIRMED'|'DELIVERED'} [props.status] - The current order
 *   status, matching the order-service `OrderStatus` enum. `undefined`, `null`,
 *   or unrecognized values render the safe neutral fallback.
 * @returns {JSX.Element} An accessible, inline order-status badge.
 */
export default function OrderStatusBadge({ status }) {
  const label = STATUS_LABELS[status] ?? status ?? 'Unknown';
  const variant = STATUS_STYLES[status] ?? FALLBACK_STYLE;

  return (
    <span
      role="status"
      aria-live="polite"
      aria-label={`Order status: ${label}`}
      style={{ ...BADGE_BASE_STYLE, ...variant.badge }}
    >
      <span aria-hidden="true" style={{ ...DOT_BASE_STYLE, ...variant.dot }} />
      {label}
    </span>
  );
}
