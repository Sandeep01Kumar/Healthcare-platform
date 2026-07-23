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

import { useEffect, useId, useRef, useState } from 'react';
import { validateCoupon } from '../api/orderServiceClient.js';
import { canonicalizeCouponCode, MAX_COUPONS } from '../api/orderServiceContract.js';

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
 * Coupon input with multi-coupon management (controlled component).
 *
 * The applied-coupon list is OWNED BY THE PARENT and supplied via
 * `appliedCoupons`; this component never stores that list locally, so the
 * coupons the customer applies are lifted up to `App`, which forwards them to
 * order creation so they actually participate in pricing and redemption
 * (findings C3/M4). The component still owns purely local UI state — the
 * text-field value, the transient feedback message, and the in-flight flag —
 * and delegates every applied-list mutation to the parent through
 * `onApplyCoupon`/`onRemoveCoupon`. Because the parent performs the actual list
 * update (with a functional update), there is no stale-closure hazard when
 * several applies happen in succession (finding M4).
 *
 * Coupon identity is CASE-INSENSITIVE after trimming: codes are compared and
 * stored in their canonical (trimmed, upper-cased) form via
 * {@link module:orderServiceContract.canonicalizeCouponCode}, matching the
 * order-service, so `save10` and `SAVE10` are recognized as the same coupon and
 * never stack (findings M4/C4).
 *
 * Validity is server-authoritative: the component branches solely on
 * `result.valid` from {@link validateCoupon} and never computes validity
 * locally. Transport/HTTP failures are caught and surfaced as feedback using the
 * error's SAFE `userMessage` (never raw lower-layer text such as method, path,
 * status, or the server envelope), without marking any coupon valid (finding M6).
 *
 * @param {object} [props] - Component props.
 * @param {Array<{code: string, discount: object, reason: string}>} [props.appliedCoupons]
 *   The applied coupons owned by the parent; each entry's `code` is canonical.
 *   Defaults to an empty list.
 * @param {(entry: {code: string, discount: object, reason: string}) => void} [props.onApplyCoupon]
 *   Invoked with the newly applied coupon (canonical `code`) after the server
 *   accepts it; the parent appends it. Defaults to a no-op.
 * @param {(canonicalCode: string) => void} [props.onRemoveCoupon]
 *   Invoked with the canonical code to remove; the parent removes it. Defaults
 *   to a no-op.
 * @returns {JSX.Element} The coupon input UI.
 */
export default function CouponInput({
  appliedCoupons = [],
  onApplyCoupon = () => {},
  onRemoveCoupon = () => {},
} = {}) {
  // Current text-field value (controlled input) — local UI state only.
  const [code, setCode] = useState('');
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

  // A ref to the coupon <input> so keyboard / assistive-technology focus can be
  // RESTORED to it after an async in-place update (finding MIN-1). Disabling the
  // focused control during a submit, or unmounting a Remove button, otherwise
  // drops focus to <body> and forces the user to Tab from the top of the page.
  const inputRef = useRef(null);
  // Armed (set true) right before an operation that will re-render this
  // component — an Apply submit cycle, or a Remove — and consumed by the effect
  // below to move focus back to a logical, always-present control once the
  // update settles.
  const pendingFocusRef = useRef(false);

  // Restore focus after a coupon Apply (success OR error) or a Remove settles.
  // Keyed on `submitting` and `appliedCoupons` so it runs after the DOM has
  // re-rendered (the input is re-enabled once `submitting` clears; the list is
  // updated after a remove). It is a NO-OP on mount and on any unrelated
  // re-render because `pendingFocusRef` is only armed by a user action, so focus
  // is never stolen unexpectedly (finding MIN-1). Focusing is deferred until
  // `submitting` is false because focusing a disabled element is a no-op.
  useEffect(() => {
    if (pendingFocusRef.current && !submitting) {
      pendingFocusRef.current = false;
      inputRef.current?.focus();
    }
  }, [submitting, appliedCoupons]);

  /**
   * Validate and apply the entered coupon.
   *
   * Server-authoritative: only a `result.valid === true` verdict is applied
   * (handed to the parent via `onApplyCoupon`). Empty/whitespace-only input and
   * duplicates — compared by CANONICAL identity against the parent's
   * `appliedCoupons` — are short-circuited as input hygiene (not validity
   * decisions). The request is guarded by `submitting` and wrapped in
   * try/catch/finally so transport errors never mark a coupon valid.
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
    const canonical = canonicalizeCouponCode(trimmed);
    if (
      appliedCoupons.some(
        (entry) => canonicalizeCouponCode(entry.code) === canonical
      )
    ) {
      setFeedback({ tone: 'info', message: `Coupon "${trimmed}" is already applied.` });
      return;
    }
    // Apply-time cap (finding INFO-4): bound the applied list to MAX_COUPONS at
    // the point of entry, matching the client's create/validate cap. Without
    // this gate the interactive list could grow past the limit and only fail at
    // order creation with a message that misattributes the cause to "price".
    // This is input hygiene, not a validity decision, so no request is issued.
    if (appliedCoupons.length >= MAX_COUPONS) {
      setFeedback({
        tone: 'info',
        message: `You can apply at most ${MAX_COUPONS} coupons. Remove one to add another.`,
      });
      return;
    }

    // Arm focus restoration for when this submit cycle settles (finding MIN-1),
    // then disable the form while the validation request is in flight.
    pendingFocusRef.current = true;
    setSubmitting(true);
    try {
      // The server is the sole authority on validity; we relay the canonical
      // code (matching how the server redeems it) and reflect the verdict.
      const result = await validateCoupon(canonical);
      if (result && result.valid) {
        const entry = { code: canonical, discount: result.discount, reason: result.reason };
        // The parent owns the list and appends with a functional update, so
        // there is no stale-closure hazard here (finding M4).
        onApplyCoupon(entry);
        setCode('');
        const discountText = formatDiscount(result.discount);
        setFeedback({
          tone: 'success',
          message: discountText
            ? `Coupon "${canonical}" applied — discount: ${discountText}.`
            : `Coupon "${canonical}" applied.`,
        });
      } else {
        // `result.reason` is the server's normalized, user-facing validity
        // reason (e.g. "expired") — safe to show, unlike an exception message.
        const reason = result && result.reason ? result.reason : 'not valid';
        setFeedback({
          tone: 'error',
          message: `Coupon "${trimmed}" is not valid: ${reason}`,
        });
      }
    } catch (err) {
      // Transport/HTTP failure — surface the SAFE user message only (finding
      // M6). The client attaches a curated `userMessage`; never render the raw
      // `err.message`, which may carry method/path/status/server-envelope
      // diagnostics intended for logs.
      const safe =
        (err && typeof err.userMessage === 'string' && err.userMessage) ||
        'Could not validate the coupon. Please try again.';
      setFeedback({ tone: 'error', message: safe });
    } finally {
      setSubmitting(false);
    }
  }

  /**
   * Remove a previously applied coupon by its canonical code, delegating the
   * actual list mutation to the parent.
   *
   * @param {string} couponCode - The canonical code of the coupon to remove.
   * @returns {void}
   */
  function handleRemove(couponCode) {
    // Arm focus restoration (finding MIN-1): the clicked Remove button is about
    // to unmount as the parent drops the entry, which would otherwise leave
    // focus on <body>. The effect moves focus back to the always-present coupon
    // input once the list re-renders.
    pendingFocusRef.current = true;
    onRemoveCoupon(couponCode);
    setFeedback({ tone: 'info', message: `Coupon "${couponCode}" removed.` });
  }

  // Errors are announced assertively (role="alert"); all other tones use the
  // polite role="status". A single live-region mechanism (the role) governs the
  // announcement — no redundant explicit `aria-live` (finding M12/N2 style).
  const isError = feedback?.tone === 'error';

  return (
    <section aria-label="Coupons" style={{ width: '100%', maxWidth: 480 }}>
      {/*
        Responsive form row: the field and button sit side by side on wide
        screens and WRAP onto separate lines on narrow/mobile screens
        (flexWrap). The field grows/shrinks with a sensible basis and
        `minWidth: 0` so it never forces horizontal overflow (finding M12).
      */}
      <form
        onSubmit={handleSubmit}
        noValidate
        aria-busy={submitting}
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'flex-end',
          gap: '0.5rem',
        }}
      >
        <div style={{ flex: '1 1 12rem', minWidth: 0 }}>
          <label htmlFor={inputId} style={{ display: 'block', marginBottom: 4 }}>
            Coupon code
          </label>
          <input
            ref={inputRef}
            id={inputId}
            type="text"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            disabled={submitting}
            autoComplete="off"
            aria-describedby={statusId}
            style={{
              width: '100%',
              minHeight: '2.75rem', // >= 44px touch target (finding M12)
              padding: '0.5rem 0.625rem',
              fontSize: '1rem',
              boxSizing: 'border-box',
            }}
          />
        </div>
        <button
          type="submit"
          disabled={submitting}
          style={{
            minHeight: '2.75rem', // >= 44px touch target (finding M12)
            padding: '0.5rem 1rem',
            fontSize: '1rem',
          }}
        >
          {submitting ? 'Applying…' : 'Apply'}
        </button>
      </form>

      <p
        id={statusId}
        role={isError ? 'alert' : 'status'}
        style={{
          minHeight: '1.25rem',
          margin: '0.5rem 0',
          color: toneColor(feedback?.tone),
          overflowWrap: 'anywhere',
        }}
      >
        {feedback ? feedback.message : ''}
      </p>

      <p id={appliedLabelId} style={{ margin: '0.5rem 0 0.25rem', fontWeight: 600 }}>
        Applied coupons
      </p>
      {appliedCoupons.length === 0 ? (
        <p style={{ margin: 0, color: '#666666' }}>No coupons applied yet.</p>
      ) : (
        <ul aria-labelledby={appliedLabelId} style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {appliedCoupons.map((entry) => {
            const discountText = formatDiscount(entry.discount);
            return (
              <li
                key={entry.code}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                  gap: '0.5rem',
                  padding: '4px 0',
                }}
              >
                <span style={{ overflowWrap: 'anywhere', minWidth: 0 }}>
                  <strong>{entry.code}</strong>
                  {discountText ? ` — ${discountText}` : ''}
                </span>
                <button
                  type="button"
                  onClick={() => handleRemove(entry.code)}
                  aria-label={`Remove coupon ${entry.code}`}
                  style={{ minHeight: '2.75rem', padding: '0.375rem 0.75rem' }}
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
