// @vitest-environment node
/**
 * Tests for the Vite configuration's proxy-target validation (finding CONFIG-02).
 *
 * The dev/preview proxy `target` must be an absolute `http(s)` URL. Previously
 * the raw `ORDER_SERVICE_PROXY_TARGET` value was handed to `http-proxy`
 * unvalidated, so a malformed or non-http value surfaced only as an opaque
 * `TypeError` (with an absolute-path stack trace) the moment the first request
 * was proxied. {@link validateProxyTarget} now fails fast AT CONFIG LOAD with an
 * actionable, value-naming message. These tests exercise the exported validator
 * directly (importing the config module also proves it loads without throwing
 * for the default target).
 *
 * @module viteConfig.test
 */

import { describe, it, expect } from 'vitest';
import { validateProxyTarget } from '../vite.config.js';

describe('vite.config validateProxyTarget (CONFIG-02)', () => {
  it('returns a valid absolute http URL unchanged', () => {
    expect(validateProxyTarget('http://localhost:8080')).toBe('http://localhost:8080');
  });

  it('returns a valid absolute https URL (with port and path) unchanged', () => {
    expect(validateProxyTarget('https://api.example.com:8443/base')).toBe(
      'https://api.example.com:8443/base'
    );
  });

  it('rejects an empty target with an actionable message that names the value', () => {
    expect(() => validateProxyTarget('')).toThrow(/ORDER_SERVICE_PROXY_TARGET/);
    expect(() => validateProxyTarget('')).toThrow(/valid absolute URL/);
  });

  it('rejects an unparseable target', () => {
    expect(() => validateProxyTarget('not a url')).toThrow(/valid absolute URL/);
  });

  it('rejects a scheme-less host:port (parsed with a non-http scheme)', () => {
    expect(() => validateProxyTarget('localhost:8080')).toThrow(/http or https/);
  });

  it('rejects a non-http(s) scheme (ftp)', () => {
    expect(() => validateProxyTarget('ftp://host/x')).toThrow(/http or https/);
  });

  it('rejects a file: URL', () => {
    expect(() => validateProxyTarget('file:///etc/hosts')).toThrow(/http or https/);
  });
});
