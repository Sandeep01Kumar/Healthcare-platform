/**
 * Tests for App (findings C3, M7, M12).
 *
 * - C3: applied coupons are owned by App and submitted to real order creation.
 *   An end-to-end flow (apply a coupon -> create an order) proves the coupon
 *   codes reach `createOrder` and the app then routes to the new order.
 * - M7: `/orders/:id` renders tracking; a malformed encoding or any unknown path
 *   renders an explicit 404 (never a silent fallback to landing); browser
 *   back/forward is honored.
 * - M12: exactly one page-level `<h1>` per view; a blank "track" submit is
 *   announced; navigation manages `document.title` and moves focus to the
 *   heading.
 *
 * The order-service client is mocked and OrderTrackingPage is stubbed so the
 * test focuses on routing, wiring, and accessibility (no network, no fetch).
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import App from './App.jsx';
import {
  renderComponent,
  setInputValue,
  submitForm,
  flush,
} from './testUtils.js';
import { createOrder, validateCoupon } from './api/orderServiceClient.js';

vi.mock('./api/orderServiceClient.js', () => ({
  createOrder: vi.fn(),
  validateCoupon: vi.fn(),
  // getOrder is imported by the real OrderTrackingPage, which we stub below,
  // but provide it so the module shape is complete.
  getOrder: vi.fn(),
}));

// Stub the tracking page with a focusable single <h1>, mirroring the real page.
vi.mock('./pages/OrderTrackingPage.jsx', () => ({
  default: ({ orderId }) => (
    <h1 tabIndex={-1}>Tracking {orderId}</h1>
  ),
}));

let view;
beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState({}, '', '/');
  document.title = '';
});
afterEach(async () => {
  if (view) {
    await view.unmount();
    view = null;
  }
});

function h1s() {
  return [...view.container.querySelectorAll('h1')];
}

describe('App routing (M7)', () => {
  it('renders the landing view at "/" with a single Customer UI heading', async () => {
    view = await renderComponent(<App />);
    const headings = h1s();
    expect(headings).toHaveLength(1);
    expect(headings[0].textContent).toBe('Customer UI');
    expect(view.container.textContent).toMatch(/Apply coupons/);
    expect(view.container.textContent).toMatch(/Create an order/);
    expect(view.container.textContent).toMatch(/Track an order/);
  });

  it('renders the tracking view for /orders/:id with exactly one H1 (M12)', async () => {
    window.history.replaceState({}, '', '/orders/123');
    view = await renderComponent(<App />);
    const headings = h1s();
    expect(headings).toHaveLength(1); // page owns the only H1
    expect(headings[0].textContent).toBe('Tracking 123');
    expect(view.container.textContent).not.toMatch(/Customer UI/);
  });

  it('renders a 404 for a malformed order encoding (M7)', async () => {
    window.history.replaceState({}, '', '/orders/%ZZ');
    view = await renderComponent(<App />);
    expect(h1s()[0].textContent).toBe('Page not found');
    expect(view.container.textContent).not.toMatch(/Tracking/);
  });

  it('renders a 404 for an unknown path instead of falling back to landing (M7)', async () => {
    window.history.replaceState({}, '', '/nope');
    view = await renderComponent(<App />);
    expect(h1s()[0].textContent).toBe('Page not found');
    expect(view.container.textContent).not.toMatch(/Apply coupons/);
  });

  it('honors browser back/forward via popstate', async () => {
    window.history.replaceState({}, '', '/orders/1');
    view = await renderComponent(<App />);
    expect(view.container.textContent).toContain('Tracking 1');

    await act(async () => {
      window.history.replaceState({}, '', '/');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(h1s()[0].textContent).toBe('Customer UI');
  });
});

describe('App coupon -> order wiring (C3)', () => {
  it('submits applied coupon codes to createOrder and routes to the new order', async () => {
    validateCoupon.mockResolvedValue({
      valid: true,
      reason: 'OK',
      discount: { type: 'PERCENTAGE', value: 10 },
    });
    createOrder.mockResolvedValue({ id: 'o-9', status: 'CREATED' });

    view = await renderComponent(<App />);

    // Apply a coupon through the real CouponInput.
    const couponSection = view.container.querySelector('section[aria-label="Coupons"]');
    await setInputValue(couponSection.querySelector('input'), 'save10');
    await submitForm(couponSection.querySelector('form'));
    await flush();
    expect(validateCoupon).toHaveBeenCalledWith('SAVE10');

    // Create an order at a price; the applied coupon must be submitted with it.
    const priceInput = view.container.querySelector('#app-order-price');
    await setInputValue(priceInput, '100');
    await submitForm(priceInput.closest('form'));
    await flush();

    expect(createOrder).toHaveBeenCalledTimes(1);
    expect(createOrder).toHaveBeenCalledWith('100', ['SAVE10']);

    // The app routed to the created order's tracking view.
    expect(window.location.pathname).toBe('/orders/o-9');
    expect(view.container.textContent).toContain('Tracking o-9');
  });

  it('shows a safe message when order creation fails and stays on landing', async () => {
    const err = new Error('order-service POST /orders failed: 500 (raw)');
    err.status = 500;
    err.userMessage = 'The order service is temporarily unavailable. Please try again.';
    createOrder.mockRejectedValue(err);

    view = await renderComponent(<App />);
    const priceInput = view.container.querySelector('#app-order-price');
    await setInputValue(priceInput, '50');
    await submitForm(priceInput.closest('form'));
    await flush();

    const alert = view.container.querySelector('section[aria-labelledby="create-heading"] [role="alert"]');
    expect(alert.textContent).toContain('temporarily unavailable');
    expect(alert.textContent).not.toMatch(/raw|500|\/orders/);
    expect(window.location.pathname).toBe('/'); // stayed on landing
  });
});

describe('App validation + focus/title (M12)', () => {
  it('announces a blank track submit instead of silently ignoring it', async () => {
    view = await renderComponent(<App />);
    const orderIdInput = view.container.querySelector('#app-order-id');
    await submitForm(orderIdInput.closest('form'));
    await flush();
    const alert = view.container.querySelector('#app-order-id-error');
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toMatch(/enter an order id/i);
    // No navigation occurred.
    expect(window.location.pathname).toBe('/');
  });

  it('updates document.title and moves focus to the heading on navigation', async () => {
    view = await renderComponent(<App />);
    expect(document.title).toBe('Customer UI');

    // Navigate via the track form to a valid id.
    const orderIdInput = view.container.querySelector('#app-order-id');
    await setInputValue(orderIdInput, 'ORD-1');
    await submitForm(orderIdInput.closest('form'));
    await flush();

    expect(window.location.pathname).toBe('/orders/ORD-1');
    expect(document.title).toBe('Order ORD-1 \u2014 Customer UI');
    // Focus moved to the (stubbed) tracking heading.
    expect(document.activeElement).toBe(h1s()[0]);
    expect(document.activeElement.textContent).toBe('Tracking ORD-1');
  });
});
