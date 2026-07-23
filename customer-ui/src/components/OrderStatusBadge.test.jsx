/**
 * Tests for OrderStatusBadge (finding N2).
 *
 * Proves the own-key (prototype-safe) status lookup and the single live-region
 * mechanism: `role="status"` with NO redundant explicit `aria-live`.
 */

import { describe, it, expect, afterEach } from 'vitest';
import OrderStatusBadge from './OrderStatusBadge.jsx';
import { renderComponent } from '../testUtils.js';

let view;
afterEach(async () => {
  if (view) {
    await view.unmount();
    view = null;
  }
});

describe('OrderStatusBadge (N2)', () => {
  it('renders the friendly label for a known status', async () => {
    view = await renderComponent(<OrderStatusBadge status="CONFIRMED" />);
    const badge = view.container.querySelector('[role="status"]');
    expect(badge).not.toBeNull();
    expect(badge.textContent).toContain('Confirmed');
    expect(badge.getAttribute('aria-label')).toBe('Order status: Confirmed');
  });

  it('uses a SINGLE live-region mechanism: role=status and no explicit aria-live', async () => {
    view = await renderComponent(<OrderStatusBadge status="CREATED" />);
    const badge = view.container.querySelector('[role="status"]');
    expect(badge.hasAttribute('aria-live')).toBe(false);
  });

  it('does not resolve prototype keys to inherited members (own-key lookup)', async () => {
    // "toString" would return Object.prototype.toString under a plain [] lookup.
    view = await renderComponent(<OrderStatusBadge status="toString" />);
    const badge = view.container.querySelector('[role="status"]');
    // Renders the raw string, never a function/[object] representation.
    expect(badge.textContent).toContain('toString');
    expect(badge.textContent).not.toMatch(/function|native code|\[object/i);
  });

  it('degrades unknown/missing/non-string status to the neutral fallback', async () => {
    view = await renderComponent(<OrderStatusBadge status={undefined} />);
    let badge = view.container.querySelector('[role="status"]');
    expect(badge.textContent).toContain('Unknown');
    await view.rerender(<OrderStatusBadge status={42} />);
    badge = view.container.querySelector('[role="status"]');
    expect(badge.textContent).toContain('Unknown');
  });

  it('renders a decorative marker that is hidden from assistive tech', async () => {
    view = await renderComponent(<OrderStatusBadge status="DELIVERED" />);
    const hidden = view.container.querySelector('[aria-hidden="true"]');
    expect(hidden).not.toBeNull();
  });
});
