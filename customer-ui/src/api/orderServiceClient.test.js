/**
 * Client tests for orderServiceClient.js.
 *
 * Covers the findings owned by this file:
 * - M6: `resolveBaseUrl` rejects secret-bearing/ambiguous URL components without
 *   echoing the secret; non-2xx/transport failures map to SAFE `userMessage`s
 *   with a numeric `status`, never surfacing raw lower-layer text.
 * - C1/C3: the previously-missing `createOrder` operation POSTs the contract-C
 *   body with bounded, canonical, de-duplicated coupon codes and validates the
 *   response as an order view.
 * - C5: `validateCoupon` returns the server's nested-discount verdict verbatim.
 *
 * `global.fetch` is mocked with real `Response` objects; no network is used.
 */

import { describe, it, expect, vi, afterEach } from 'vitest';
import { jsonResponse } from '../testUtils.js';
import {
  resolveBaseUrl,
  createOrder,
  validateCoupon,
  getOrder,
} from './orderServiceClient.js';

afterEach(() => {
  vi.restoreAllMocks();
});

/** Install a fetch mock returning whatever the handler produces. */
function mockFetch(handler) {
  global.fetch = vi.fn(handler);
  return global.fetch;
}

describe('resolveBaseUrl (M6)', () => {
  it('rejects embedded credentials without echoing the secret', () => {
    let thrown;
    try {
      resolveBaseUrl('http://user:s3cret@host:8080');
    } catch (err) {
      thrown = err;
    }
    expect(thrown).toBeInstanceOf(Error);
    expect(thrown.message).toMatch(/credentials|userinfo/i);
    expect(thrown.message).not.toMatch(/s3cret/);
  });

  it('rejects a query string without echoing its value', () => {
    let thrown;
    try {
      resolveBaseUrl('http://host:8080?token=abc123');
    } catch (err) {
      thrown = err;
    }
    expect(thrown.message).toMatch(/query/i);
    expect(thrown.message).not.toMatch(/abc123/);
  });

  it('rejects a fragment', () => {
    expect(() => resolveBaseUrl('http://host:8080#frag')).toThrow(/fragment/i);
  });

  it('rejects a non-http(s) scheme', () => {
    expect(() => resolveBaseUrl('ftp://host')).toThrow(/http/i);
  });

  it('accepts a clean absolute URL and strips a trailing slash', () => {
    expect(resolveBaseUrl('http://host:8080/')).toBe('http://host:8080');
  });

  it('treats blank as same-origin (empty string)', () => {
    expect(resolveBaseUrl('   ')).toBe('');
  });
});

describe('createOrder (C1/C3)', () => {
  it('POSTs a canonical, de-duplicated body and returns the validated order', async () => {
    let seen;
    mockFetch(async (url, init) => {
      seen = { url, init };
      return jsonResponse({
        id: 'o-1',
        status: 'CREATED',
        price: 100.0,
        discountedTotal: 90.0,
        appliedCoupons: [{ code: 'SAVE10', type: 'PERCENTAGE', value: '10' }],
      });
    });

    const order = await createOrder('100.00', ['save10', ' SAVE10 ', 'HALF']);

    expect(seen.init.method).toBe('POST');
    const body = JSON.parse(seen.init.body);
    expect(body.price).toBe('100.00');
    // save10 / " SAVE10 " collapse to a single canonical SAVE10 (C4/M4 identity).
    expect(body.couponCodes).toEqual(['SAVE10', 'HALF']);
    expect(order.id).toBe('o-1');
    expect(order.status).toBe('CREATED');
  });

  it('accepts a numeric price and no coupons', async () => {
    let body;
    mockFetch(async (url, init) => {
      body = JSON.parse(init.body);
      return jsonResponse({ id: 'x', status: 'CREATED' });
    });
    await createOrder(100, []);
    expect(body.price).toBe('100');
    expect(body.couponCodes).toEqual([]);
  });

  it('rejects invalid price/coupon input before issuing a request', async () => {
    const fetchSpy = mockFetch(async () => {
      throw new Error('must not be called');
    });
    await expect(createOrder(-1, [])).rejects.toThrow(/non-negative/);
    await expect(createOrder('1e5', [])).rejects.toThrow(/decimal/);
    const many = Array.from({ length: 26 }, (_, i) => `C${i}`);
    await expect(createOrder('10', many)).rejects.toThrow(/no more than 25/);
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe('error mapping (M6)', () => {
  it('maps 404 to a safe userMessage + status and hides server detail', async () => {
    mockFetch(async () =>
      jsonResponse(
        { error: { code: 'NOT_FOUND', message: 'order 42 missing internally' } },
        404
      )
    );
    let thrown;
    try {
      await getOrder('missing');
    } catch (err) {
      thrown = err;
    }
    expect(thrown.status).toBe(404);
    expect(thrown.userMessage).toBe('The requested order was not found.');
    expect(thrown.userMessage).not.toMatch(/internally/);
  });

  it('maps 5xx to a generic service-unavailable message', async () => {
    mockFetch(async () => new Response('raw stacktrace text', { status: 500 }));
    let thrown;
    try {
      await getOrder('x');
    } catch (err) {
      thrown = err;
    }
    expect(thrown.status).toBe(500);
    expect(thrown.userMessage).toMatch(/temporarily unavailable/i);
    expect(thrown.userMessage).not.toMatch(/stacktrace/);
  });

  it('maps a network failure to status 0 with an unreachable message', async () => {
    mockFetch(async () => {
      throw new TypeError('fetch failed');
    });
    let thrown;
    try {
      await getOrder('x');
    } catch (err) {
      thrown = err;
    }
    expect(thrown.status).toBe(0);
    expect(thrown.userMessage).toMatch(/unreachable/i);
  });

  it('maps a timeout to status 0 with a timeout message', async () => {
    mockFetch(
      (url, init) =>
        new Promise((_resolve, reject) => {
          init.signal.addEventListener('abort', () => {
            const err = new Error('aborted');
            err.name = 'AbortError';
            reject(err);
          });
        })
    );
    let thrown;
    try {
      await getOrder('x', { timeoutMs: 20 });
    } catch (err) {
      thrown = err;
    }
    expect(thrown.status).toBe(0);
    expect(thrown.userMessage).toMatch(/timed out/i);
  });
});

describe('validateCoupon (C5)', () => {
  it('returns the server nested-discount verdict verbatim', async () => {
    mockFetch(async () =>
      jsonResponse({
        valid: true,
        reason: 'OK',
        discount: { type: 'PERCENTAGE', value: 10 },
      })
    );
    const verdict = await validateCoupon('SAVE10');
    expect(verdict.valid).toBe(true);
    expect(verdict.discount).toEqual({ type: 'PERCENTAGE', value: 10 });
  });

  it('surfaces an invalid verdict without throwing', async () => {
    mockFetch(async () => jsonResponse({ valid: false, reason: 'expired' }));
    const verdict = await validateCoupon('OLD');
    expect(verdict.valid).toBe(false);
    expect(verdict.reason).toBe('expired');
  });
});
