/**
 * Contract-shape tests for the customer-ui <-> order-service wire contract.
 *
 * These are the consumer-side contract tests required by finding C5: they pin
 * the exact DTO shapes the client relies on (a valid coupon verdict MUST carry
 * nested `discount` metadata, matching the delivered Java validation result) and
 * the exact order-status vocabulary, plus the canonical coupon identity shared
 * with the server.
 */

import { describe, it, expect } from 'vitest';
import {
  assertCouponVerdict,
  assertOrderView,
  canonicalizeCouponCode,
  isOrderStatus,
  ORDER_STATUSES,
  ENDPOINTS,
} from './orderServiceContract.js';

describe('orderServiceContract — coupon verdict (C5)', () => {
  it('accepts a valid verdict carrying nested discount metadata', () => {
    const verdict = {
      valid: true,
      reason: 'OK',
      discount: { type: 'PERCENTAGE', value: 10 },
    };
    expect(assertCouponVerdict(verdict)).toBe(verdict);
    expect(verdict.discount.type).toBe('PERCENTAGE');
  });

  it('rejects a valid verdict that lacks nested discount metadata (C5)', () => {
    expect(() => assertCouponVerdict({ valid: true, reason: 'OK' })).toThrow(
      /discount/
    );
  });

  it('accepts an invalid verdict without discount', () => {
    const verdict = { valid: false, reason: 'expired' };
    expect(assertCouponVerdict(verdict)).toBe(verdict);
  });

  it('rejects non-object / malformed verdicts', () => {
    expect(() => assertCouponVerdict(null)).toThrow(TypeError);
    expect(() => assertCouponVerdict({ valid: 'yes', reason: 'x' })).toThrow(
      TypeError
    );
    expect(() => assertCouponVerdict({ valid: true, reason: 5 })).toThrow(
      TypeError
    );
  });
});

describe('orderServiceContract — order view status vocabulary', () => {
  it('accepts the exact lifecycle statuses', () => {
    for (const status of ORDER_STATUSES) {
      const view = { id: 'o-1', status };
      expect(assertOrderView(view)).toBe(view);
      expect(isOrderStatus(status)).toBe(true);
    }
  });

  it('rejects an unsupported status such as CANCELLED', () => {
    expect(() => assertOrderView({ id: 'o-1', status: 'CANCELLED' })).toThrow(
      RangeError
    );
    expect(isOrderStatus('CANCELLED')).toBe(false);
  });

  it('rejects a missing/blank id', () => {
    expect(() => assertOrderView({ status: 'CREATED' })).toThrow(TypeError);
    expect(() => assertOrderView({ id: '   ', status: 'CREATED' })).toThrow(
      TypeError
    );
  });

  it('rejects appliedCoupons that is present but not an array', () => {
    expect(() =>
      assertOrderView({ id: 'o-1', status: 'CREATED', appliedCoupons: {} })
    ).toThrow(TypeError);
  });
});

describe('orderServiceContract — canonical coupon identity', () => {
  it('trims and upper-cases so casing/spacing collapse to one identity', () => {
    expect(canonicalizeCouponCode('save10')).toBe('SAVE10');
    expect(canonicalizeCouponCode(' SAVE10 ')).toBe('SAVE10');
    expect(canonicalizeCouponCode('SaVe10')).toBe('SAVE10');
  });

  it('coerces nullish input to an empty string without throwing', () => {
    expect(canonicalizeCouponCode(null)).toBe('');
    expect(canonicalizeCouponCode(undefined)).toBe('');
  });
});

describe('orderServiceContract — endpoints', () => {
  it('exposes the delivered routes including order creation', () => {
    expect(ENDPOINTS.validateCoupon).toEqual({
      method: 'POST',
      path: '/coupons/validate',
    });
    expect(ENDPOINTS.createOrder).toEqual({ method: 'POST', path: '/orders' });
    expect(ENDPOINTS.getOrder.method).toBe('GET');
    expect(ENDPOINTS.getOrder.path('a b')).toBe('/orders/a%20b');
  });
});
