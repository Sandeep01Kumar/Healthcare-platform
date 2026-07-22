/**
 * Coupon input field with multi-coupon support for the customer-ui SPA.
 *
 * Renders a single text field plus an "Apply" action that submits the entered
 * code to the order-service validation endpoint (via the sibling
 * {@link module:orderServiceClient} client) and displays the server's verdict.
 * Because the feature supports several simultaneously applied coupons, the
 * component maintains a list of accepted coupons and lets the user remove each
 * one independently.
 *
 * Server-authoritative validation (hard rule): this component NEVER decides
 * coupon validity itself. It does not hardcode coupon codes, discount rules,
 * expiry windows, or format checks. It only relays the entered code to
 * {@link validateCoupon} and reflects the returned `{ valid, reason, discount }`
 * verdict. Only codes the server marks `valid: true` are shown as applied. The
 * sole client-side gates are input hygiene (trimming and rejecting an
 * empty/whitespace-only entry, and avoiding a duplicate re-submission), which
 * do not decide validity.
 *
 * The module has no external UI/CSS dependencies; any styling is minimal and
 * inline. The only runtime dependencies are React and the order-service client.
 *
 * @module CouponInput
 */

import { useId, useState } from 'react';
import { validateCoupon } from '../api/orderServiceClient.js';

/**
 * Render the opaque `discount` metadata for display without ever throwing.
 *
 * The `discount` returned by the server is treated as opaque object metadata
 * (typically `{ type, value }`) and is NOT assumed to be a number. This helper
 * centralizes defensive formatting for every shape the field may take:
 *
 * - `null` / `undefined` → empty string (caller omits the discount entirely);
 * - an object exposing both `type` and `value` → `"<value> (<type>)"`;
 * - an object exposing only `value` → the stringified value;
 * - any other object → its JSON serialization (falling back to `String` if the
 *   object cannot be serialized, e.g. a circular reference);
 * - a primitive (defensive; the contract says object) → its string form.
 *
 * @param {*} discount - The opaque discount metadata from the validation result.
 * @returns {string} A safe, human-readable representation (possibly empty).
 */
function formatDiscount(discount) {
  if (discount === null || discount === undefined) {
    return '';
  }
  if (typeof discount === 'object') {
    const { type, value } = discount;
    if (value !== undefined && value !== null && type !== undefined && type !== null) {
      return `${value} (${type})`;
    }
    if (value !== undefined && value !== null) {
      return String(value);
    }
    try {
      return JSON.stringify(discount);
    } catch {
      return String(discount);
    }
  }
  return String(discount);
}

/**
 * Map a feedback tone to an inline text color. Unknown tones fall back to a
 * neutral color so the status region is always legible.
 *
 * @param {('error'|'success'|'info')} [tone] - The feedback tone.
 * @returns {string} A CSS color value.
 */
function toneColor(tone) {
  if (tone === 'error') {
    return '#b00020';
  }
  if (tone === 'success') {
    return '#1b7f2e';
  }
  return '#333333';
}

/**
 * Coupon input with multi-coupon management.
 *
 * Works fully without any props (`<CouponInput />`), as rendered by
 * `App.jsx`. An optional callback notifies a parent whenever the applied-coupon
 * list changes (after a successful apply or a removal).
 *
 * Validity is server-authoritative: the component branches solely on
 * `result.valid` from {@link validateCoupon} and never computes validity
 * locally. Transport/HTTP failures are caught and surfaced as feedback without
 * marking any coupon valid.
 *
 * @param {object} [props] - Component props (all optional).
 * @param {(appliedCoupons: Array<{code: string, discount: object, reason: string}>) => void} [props.onAppliedChange]
 *   Invoked with the new applied-coupon list after every change (apply or
 *   remove). Defaults to a no-op so prop-less usage is fully functional.
 * @returns {JSX.Element} The coupon input UI.
 */
export default function CouponInput({ onAppliedChange = () => {} } = {}) {
  // Current text-field value (controlled input).
  const [code, setCode] = useState('');
  // Server-valid coupons currently applied; each entry: { code, discount, reason }.
  const [applied, setApplied] = useState([]);
  // Last verdict/error to announce: { tone: 'error'|'success'|'info', message } | null.
  const [feedback, setFeedback] = useState(null);
  // True while a validation request is in flight (disables the form).
  const [submitting, setSubmitting] = useState(false);

  // Stable, unique ids so labels/descriptions associate correctly even when
  // several CouponInput instances are rendered on one page.
  const baseId = useId();
  const inputId = `${baseId}-code`;
  const statusId = `${baseId}-status`;
  const appliedLabelId = `${baseId}-applied`;

  /**
   * Validate and apply the entered coupon.
   *
   * Server-authoritative: only a `result.valid === true` verdict adds the
   * coupon to the applied list. Empty/whitespace-only input and duplicates are
   * short-circuited as input hygiene (not validity decisions). The request is
   * guarded by `submitting` and wrapped in try/catch/finally so transport
   * errors never mark a coupon valid.
   *
   * @param {import('react').FormEvent<HTMLFormElement>} event - The submit event.
   * @returns {Promise<void>}
   */
  async function handleSubmit(event) {
    event.preventDefault();

    const trimmed = code.trim();
    if (!trimmed) {
      setFeedback({ tone: 'info', message: 'Enter a coupon code.' });
      return;
    }
    if (applied.some((entry) => entry.code === trimmed)) {
      setFeedback({ tone: 'info', message: `Coupon "${trimmed}" is already applied.` });
      return;
    }

    setSubmitting(true);
    try {
      // The server is the sole authority on validity; we relay and reflect.
      const result = await validateCoupon(trimmed);
      if (result && result.valid) {
        const entry = { code: trimmed, discount: result.discount, reason: result.reason };
        // Functional update keeps the list correct under React batching.
        setApplied((prev) => [...prev, entry]);
        // Submits are serialized (the form is disabled while submitting), so the
        // `applied` closure is the current list; notify the parent with the new one.
        onAppliedChange([...applied, entry]);
        setCode('');
        const discountText = formatDiscount(result.discount);
        setFeedback({
          tone: 'success',
          message: discountText
            ? `Coupon "${trimmed}" applied — discount: ${discountText}.`
            : `Coupon "${trimmed}" applied.`,
        });
      } else {
        const reason = result && result.reason ? result.reason : 'not valid';
        setFeedback({
          tone: 'error',
          message: `Coupon "${trimmed}" is not valid: ${reason}`,
        });
      }
    } catch (err) {
      // Transport/HTTP failure — surface it without marking the coupon valid.
      // Extract the message defensively: a thrown value is usually an Error but
      // is not guaranteed to be one.
      const detail = err instanceof Error ? err.message : String(err);
      setFeedback({
        tone: 'error',
        message: `Could not validate coupon. Please try again. (${detail})`,
      });
    } finally {
      setSubmitting(false);
    }
  }

  /**
   * Remove a previously applied coupon by its code.
   *
   * @param {string} couponCode - The code of the coupon to remove.
   * @returns {void}
   */
  function handleRemove(couponCode) {
    // Functional update, then notify the parent with the resulting list.
    setApplied((prev) => prev.filter((entry) => entry.code !== couponCode));
    onAppliedChange(applied.filter((entry) => entry.code !== couponCode));
    setFeedback({ tone: 'info', message: `Coupon "${couponCode}" removed.` });
  }

  return (
    <section aria-label="Coupons" style={{ maxWidth: 480 }}>
      <form onSubmit={handleSubmit} noValidate>
        <label htmlFor={inputId} style={{ display: 'block', marginBottom: 4 }}>
          Coupon code
        </label>
        <input
          id={inputId}
          type="text"
          value={code}
          onChange={(event) => setCode(event.target.value)}
          disabled={submitting}
          autoComplete="off"
          aria-describedby={statusId}
          style={{ marginInlineEnd: 8 }}
        />
        <button type="submit" disabled={submitting}>
          {submitting ? 'Applying…' : 'Apply'}
        </button>
      </form>

      <p
        id={statusId}
        role="status"
        aria-live="polite"
        style={{ minHeight: '1.25rem', margin: '0.5rem 0', color: toneColor(feedback?.tone) }}
      >
        {feedback ? feedback.message : ''}
      </p>

      <p id={appliedLabelId} style={{ margin: '0.5rem 0 0.25rem', fontWeight: 600 }}>
        Applied coupons
      </p>
      {applied.length === 0 ? (
        <p style={{ margin: 0, color: '#666666' }}>No coupons applied yet.</p>
      ) : (
        <ul aria-labelledby={appliedLabelId} style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {applied.map((entry) => {
            const discountText = formatDiscount(entry.discount);
            return (
              <li
                key={entry.code}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '4px 0',
                }}
              >
                <span>
                  <strong>{entry.code}</strong>
                  {discountText ? ` — ${discountText}` : ''}
                </span>
                <button
                  type="button"
                  onClick={() => handleRemove(entry.code)}
                  aria-label={`Remove coupon ${entry.code}`}
                >
                  Remove
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
