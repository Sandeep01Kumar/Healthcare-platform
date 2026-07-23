/**
 * Tests for OrderTrackingPage (findings C1, C2, M5, M6, M12).
 *
 * - C1/C2: the page fetches a specific order by id via the client (backed by the
 *   delivered `GET /orders/{id}` route + per-id repository).
 * - M5: an AbortController signal is passed to the fetch and aborted on
 *   unmount/supersede; stale order data is cleared at the start of each run; a
 *   manual Refresh/Retry path re-runs the fetch.
 * - M6: a failure renders the SAFE `userMessage`, never raw client error text.
 * - M12: the stepper wraps and exposes explicit current/completed semantics.
 *
 * The order-service client is mocked so no network is used.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import OrderTrackingPage from './OrderTrackingPage.jsx';
import {
  renderComponent,
  click,
  flush,
  deferred,
} from '../testUtils.js';
import { getOrder } from '../api/orderServiceClient.js';

vi.mock('../api/orderServiceClient.js', () => ({
  getOrder: vi.fn(),
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

describe('OrderTrackingPage — successful load (C1/C2, M12)', () => {
  it('renders the stepper with current + completed semantics', async () => {
    getOrder.mockResolvedValue({
      id: 'A',
      status: 'CONFIRMED',
      price: 100,
      discountedTotal: 90,
    });
    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    await flush();

    // The current stage is programmatically marked.
    const currentStep = view.container.querySelector('[aria-current="step"]');
    expect(currentStep).not.toBeNull();
    expect(currentStep.textContent).toContain('Confirmed');

    // Non-color completion cue: the completed CREATED stage shows the ✓ glyph
    // and a visually-hidden "completed" word.
    expect(view.container.textContent).toContain('\u2713');
    expect(view.container.textContent).toMatch(/completed/);

    // The single page-level heading is present and focusable.
    const h1 = view.container.querySelector('h1');
    expect(h1).not.toBeNull();
    expect(h1.getAttribute('tabindex')).toBe('-1');
  });

  it('wraps the stepper on narrow screens (flex-wrap)', async () => {
    getOrder.mockResolvedValue({ id: 'A', status: 'CREATED' });
    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    await flush();
    const ol = view.container.querySelector('ol');
    expect(ol.style.flexWrap).toBe('wrap');
  });
});

describe('OrderTrackingPage — abort + lifecycle (M5)', () => {
  it('passes an AbortSignal to the fetch', async () => {
    getOrder.mockResolvedValue({ id: 'A', status: 'CREATED' });
    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    await flush();
    const opts = getOrder.mock.calls[0][1];
    expect(opts).toBeTruthy();
    expect(opts.signal).toBeInstanceOf(AbortSignal);
  });

  it('aborts the in-flight request on unmount', async () => {
    const d = deferred();
    getOrder.mockReturnValue(d.promise); // never resolves
    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    const signal = getOrder.mock.calls[0][1].signal;
    expect(signal.aborted).toBe(false);
    await view.unmount();
    view = null;
    expect(signal.aborted).toBe(true);
  });

  it('clears stale order data when the id changes', async () => {
    const dA = deferred();
    const dB = deferred();
    getOrder.mockReturnValueOnce(dA.promise).mockReturnValueOnce(dB.promise);

    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    dA.resolve({ id: 'A', status: 'CONFIRMED' });
    await flush();
    expect(view.container.textContent).toContain('Confirmed');

    // Switch to B (still pending): the stale "Confirmed" badge must clear.
    await view.rerender(<OrderTrackingPage orderId="B" />);
    await flush();
    expect(view.container.textContent).toContain('Loading');
    expect(view.container.textContent).not.toContain('Confirmed');

    dB.resolve({ id: 'B', status: 'CREATED' });
    await flush();
    expect(view.container.textContent).toContain('Created');
  });

  it('re-runs the fetch when Refresh is clicked', async () => {
    getOrder.mockResolvedValue({ id: 'A', status: 'CREATED' });
    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    await flush();
    expect(getOrder).toHaveBeenCalledTimes(1);

    const refresh = [...view.container.querySelectorAll('button')].find((b) =>
      /refresh/i.test(b.textContent)
    );
    expect(refresh).toBeTruthy();
    await click(refresh);
    await flush();
    expect(getOrder).toHaveBeenCalledTimes(2);
  });
});

describe('OrderTrackingPage — safe errors (M6)', () => {
  it('renders the safe userMessage in an alert with a Retry control', async () => {
    const err = new Error(
      'order-service GET /orders/A failed: 500 (raw stacktrace)'
    );
    err.status = 500;
    err.userMessage = 'The order service is temporarily unavailable. Please try again.';
    getOrder.mockRejectedValue(err);

    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    await flush();

    const alert = view.container.querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert.textContent).toContain('temporarily unavailable');
    expect(alert.textContent).not.toMatch(/stacktrace|500|\/orders\/A/);

    const retry = [...view.container.querySelectorAll('button')].find((b) =>
      /retry/i.test(b.textContent)
    );
    expect(retry).toBeTruthy();
  });

  it('reports a missing order id without issuing a request', async () => {
    view = await renderComponent(<OrderTrackingPage orderId="" />);
    await flush();
    expect(getOrder).not.toHaveBeenCalled();
    const alert = view.container.querySelector('[role="alert"]');
    expect(alert.textContent).toMatch(/no order id/i);
  });
});

describe('OrderTrackingPage — logical focus after async refresh/retry (MIN-1)', () => {
  it('does not move focus on the initial mount (router owns focus-on-nav, M12)', async () => {
    getOrder.mockResolvedValue({ id: 'A', status: 'CREATED' });
    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    await flush();

    // This component must NOT focus anything on mount; the one-shot flag is
    // armed only by a user refresh/retry. (App.jsx owns focus-on-navigation.)
    const h1 = view.container.querySelector('h1');
    const refresh = [...view.container.querySelectorAll('button')].find((b) =>
      /refresh/i.test(b.textContent)
    );
    expect(refresh).toBeTruthy();
    expect(document.activeElement).not.toBe(h1);
    expect(document.activeElement).not.toBe(refresh);
  });

  it('restores focus to the Refresh control after a manual refresh settles', async () => {
    // Deferred promises drive genuinely-distinct `loading` commits (mirroring a
    // real async network round-trip): the reload must be observed going true
    // then false across separate flushes, and the second payload is distinct so
    // `order` actually changes. (A single already-resolved mock would let the
    // whole round-trip coalesce into a net no-op within one act() drain.)
    const d1 = deferred();
    const d2 = deferred();
    getOrder.mockReturnValueOnce(d1.promise).mockReturnValueOnce(d2.promise);

    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    d1.resolve({ id: 'A', status: 'CREATED' });
    await flush();

    const refreshInitial = [...view.container.querySelectorAll('button')].find(
      (b) => /refresh/i.test(b.textContent)
    );
    expect(refreshInitial).toBeTruthy();
    // Sanity: the mount did not focus the control.
    expect(document.activeElement).not.toBe(refreshInitial);

    // Activate Refresh: mid-reload the button is disabled and (because `order`
    // is cleared) unmounts, which drops focus to <body> before the fix.
    await click(refreshInitial);
    await flush(); // loading=true committed; the Refresh control has unmounted
    d2.resolve({ id: 'A', status: 'CONFIRMED' });
    await flush(); // loading=false committed; focus must return to Refresh

    const refreshAfter = [...view.container.querySelectorAll('button')].find(
      (b) => /refresh/i.test(b.textContent)
    );
    expect(refreshAfter).toBeTruthy();
    expect(document.activeElement).toBe(refreshAfter);
    expect(document.activeElement).not.toBe(document.body);
  });

  it('restores focus to the Retry control after a failed manual retry settles', async () => {
    const makeErr = () => {
      const e = new Error('order-service GET /orders/A failed: 500 (raw)');
      e.status = 500;
      e.userMessage =
        'The order service is temporarily unavailable. Please try again.';
      return e;
    };
    const d1 = deferred();
    const d2 = deferred();
    getOrder.mockReturnValueOnce(d1.promise).mockReturnValueOnce(d2.promise);

    view = await renderComponent(<OrderTrackingPage orderId="A" />);
    d1.reject(makeErr());
    await flush();

    const retryInitial = [...view.container.querySelectorAll('button')].find(
      (b) => /retry/i.test(b.textContent)
    );
    expect(retryInitial).toBeTruthy();

    // Activate Retry: it unmounts while the reload is in flight. After the
    // reload fails again, focus must return to the freshly-mounted Retry
    // control rather than <body>.
    await click(retryInitial);
    await flush(); // loading=true committed; the Retry control has unmounted
    d2.reject(makeErr());
    await flush(); // loading=false committed (error view); focus must return to Retry

    const retryAfter = [...view.container.querySelectorAll('button')].find((b) =>
      /retry/i.test(b.textContent)
    );
    expect(retryAfter).toBeTruthy();
    expect(document.activeElement).toBe(retryAfter);
    expect(document.activeElement).not.toBe(document.body);
  });
});
