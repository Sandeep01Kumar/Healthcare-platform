/**
 * Tests for CouponInput (findings C3, M4, M6, M12).
 *
 * - C3: the component is controlled — an accepted coupon is emitted to the
 *   parent via `onApplyCoupon` (the parent owns the list).
 * - M4: duplicate detection is case-insensitive (canonical identity); a
 *   differently-cased duplicate is short-circuited without a server round-trip.
 * - M6: a transport failure renders the error's SAFE `userMessage`, never raw
 *   lower-layer text.
 * - M12: error feedback uses `role="alert"` (assertive) while other tones use
 *   `role="status"`; controls meet the touch-target size.
 *
 * The order-service client is mocked so no network is used.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import CouponInput from './CouponInput.jsx';
import {
  renderComponent,
  setInputValue,
  submitForm,
  click,
  flush,
} from '../testUtils.js';
import { validateCoupon } from '../api/orderServiceClient.js';

vi.mock('../api/orderServiceClient.js', () => ({
  validateCoupon: vi.fn(),
}));

let view;
beforeEach(() => {
  vi.clearAllMocks();
});
afterEach(async () => {
  if (view) {
    await view.unmount();
    view = null;
  }
});

function getInput() {
  return view.container.querySelector('input');
}
function getForm() {
  return view.container.querySelector('form');
}

describe('CouponInput — controlled apply (C3)', () => {
  it('emits an accepted coupon to the parent via onApplyCoupon', async () => {
    validateCoupon.mockResolvedValue({
      valid: true,
      reason: 'OK',
      discount: { type: 'PERCENTAGE', value: 10 },
    });
    const onApplyCoupon = vi.fn();
    view = await renderComponent(
      <CouponInput appliedCoupons={[]} onApplyCoupon={onApplyCoupon} />
    );

    await setInputValue(getInput(), 'save10');
    await submitForm(getForm());
    await flush();

    // Server consulted with the canonical code; parent notified with the entry.
    expect(validateCoupon).toHaveBeenCalledWith('SAVE10');
    expect(onApplyCoupon).toHaveBeenCalledTimes(1);
    expect(onApplyCoupon.mock.calls[0][0]).toMatchObject({
      code: 'SAVE10',
      discount: { type: 'PERCENTAGE', value: 10 },
    });
  });
});

describe('CouponInput — canonical dedupe (M4)', () => {
  it('short-circuits a differently-cased duplicate without calling the server', async () => {
    const onApplyCoupon = vi.fn();
    view = await renderComponent(
      <CouponInput
        appliedCoupons={[{ code: 'SAVE10', discount: {}, reason: 'OK' }]}
        onApplyCoupon={onApplyCoupon}
      />
    );

    await setInputValue(getInput(), 'save10');
    await submitForm(getForm());
    await flush();

    expect(validateCoupon).not.toHaveBeenCalled();
    expect(onApplyCoupon).not.toHaveBeenCalled();
    const status = view.container.querySelector('[role="status"]');
    expect(status.textContent).toMatch(/already applied/i);
  });
});

describe('CouponInput — safe error semantics (M6, M12)', () => {
  it('renders the safe userMessage in an assertive alert on transport failure', async () => {
    const err = new Error(
      'order-service POST /coupons/validate failed: 500 (raw internal detail)'
    );
    err.status = 500;
    err.userMessage = 'The order service is temporarily unavailable. Please try again.';
    validateCoupon.mockRejectedValue(err);

    view = await renderComponent(<CouponInput appliedCoupons={[]} />);
    await setInputValue(getInput(), 'SAVE10');
    await submitForm(getForm());
    await flush();

    const alert = view.container.querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert.textContent).toContain('temporarily unavailable');
    // The raw lower-layer diagnostic must NOT be shown.
    expect(alert.textContent).not.toMatch(/raw internal detail|500|\/coupons/);
  });

  it('shows the server reason for an invalid coupon in an alert', async () => {
    validateCoupon.mockResolvedValue({ valid: false, reason: 'expired' });
    const onApplyCoupon = vi.fn();
    view = await renderComponent(
      <CouponInput appliedCoupons={[]} onApplyCoupon={onApplyCoupon} />
    );

    await setInputValue(getInput(), 'OLD10');
    await submitForm(getForm());
    await flush();

    const alert = view.container.querySelector('[role="alert"]');
    expect(alert.textContent).toMatch(/expired/);
    expect(onApplyCoupon).not.toHaveBeenCalled();
  });

  it('announces blank input politely and does not call the server', async () => {
    view = await renderComponent(<CouponInput appliedCoupons={[]} />);
    await submitForm(getForm());
    await flush();
    expect(validateCoupon).not.toHaveBeenCalled();
    const status = view.container.querySelector('[role="status"]');
    expect(status.textContent).toMatch(/enter a coupon/i);
  });
});

describe('CouponInput — touch targets and removal (M12, C3)', () => {
  it('sizes the Apply control to the touch-target minimum', async () => {
    view = await renderComponent(<CouponInput appliedCoupons={[]} />);
    const button = view.container.querySelector('button[type="submit"]');
    expect(button.style.minHeight).toBe('2.75rem');
  });

  it('emits onRemoveCoupon with the canonical code when Remove is clicked', async () => {
    const onRemoveCoupon = vi.fn();
    view = await renderComponent(
      <CouponInput
        appliedCoupons={[{ code: 'SAVE10', discount: {}, reason: 'OK' }]}
        onRemoveCoupon={onRemoveCoupon}
      />
    );
    const removeBtn = view.container.querySelector(
      'button[aria-label="Remove coupon SAVE10"]'
    );
    expect(removeBtn).not.toBeNull();
    await click(removeBtn);
    expect(onRemoveCoupon).toHaveBeenCalledWith('SAVE10');
  });
});
