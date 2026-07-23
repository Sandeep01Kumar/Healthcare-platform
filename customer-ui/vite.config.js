/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
// `defineConfig` is sourced from `vitest/config` (a superset of Vite's own) so
// the `test` block below is type-checked and a single config drives both the
// production build and the Vitest test run. It re-exports Vite's builder, so
// `vite build` / `vite dev` behave exactly as with `vite`'s `defineConfig`.
import { defineConfig } from 'vitest/config';

/**
 * Base URL of the order-service the dev/preview server proxies to. Overridable
 * via the `ORDER_SERVICE_PROXY_TARGET` environment variable so the same
 * configuration works across local and containerized backends. Defaults to the
 * conventional local order-service port.
 *
 * @type {string}
 */
const ORDER_SERVICE_PROXY_TARGET =
  process.env.ORDER_SERVICE_PROXY_TARGET || 'http://localhost:8080';

/**
 * Public base path the application is deployed under (finding M7).
 *
 * Defaults to `'/'` (root deployment). For a sub-path deployment (for example
 * behind `https://host/app/`), set `VITE_BASE_PATH=/app/` at build time; Vite
 * then rewrites asset URLs accordingly. The value MUST begin and end with a
 * slash. This is the authoritative deployment-base policy for the SPA.
 *
 * @type {string}
 */
const BASE_PATH = process.env.VITE_BASE_PATH || '/';

/**
 * SPA deep-link fallback for paths that also form an API prefix (finding M7).
 *
 * The order-service API prefixes `/coupons` and `/orders` overlap with the
 * client-side route `/orders/:id`. A browser DOCUMENT navigation (a direct load
 * or hard refresh of `/orders/123`, i.e. a request whose `Accept` includes
 * `text/html`) must be served the SPA entry (`index.html`) so client-side
 * routing can render the tracking view — it must NOT be proxied to the backend.
 * An API call (fetch/XHR, whose `Accept` is not `text/html`) is proxied to the
 * order-service. Returning a path from a proxy `bypass` serves that file via
 * Vite; returning `undefined` proxies the request.
 *
 * @param {import('http').IncomingMessage} req - The incoming request.
 * @returns {string|undefined} `'/index.html'` for HTML navigations; otherwise
 *   `undefined` (proxy to the backend).
 */
function spaNavigationBypass(req) {
  const accept = req.headers && req.headers.accept;
  if (typeof accept === 'string' && accept.includes('text/html')) {
    return '/index.html';
  }
  return undefined;
}

/**
 * Proxy table shared by the dev server and the preview server so API calls are
 * forwarded to the order-service while HTML navigations fall through to the SPA.
 *
 * @type {Record<string, import('vite').ProxyOptions>}
 */
const API_PROXY = {
  '/coupons': {
    target: ORDER_SERVICE_PROXY_TARGET,
    changeOrigin: true,
    bypass: spaNavigationBypass,
  },
  '/orders': {
    target: ORDER_SERVICE_PROXY_TARGET,
    changeOrigin: true,
    bypass: spaNavigationBypass,
  },
};

/**
 * Vite configuration for the customer-ui React single-page application.
 *
 * Registers the official React plugin (@vitejs/plugin-react) to enable JSX
 * transformation and React Fast Refresh during development. The customer-ui
 * package declares "type": "module", so this configuration is authored with
 * ESM `import` / `export default` syntax rather than CommonJS.
 *
 * ## Delivered entry structure
 * The Vite entry `index.html` loads the `src/main.jsx` bootstrap, which mounts
 * the root `src/App.jsx` component into `#root`. These are DELIVERED (not
 * planned); the full component tree (`src/components`, `src/pages`) and the
 * order-service client (`src/api`) build on them.
 *
 * ## Deployment base path (finding M7)
 * `base` is set from {@link BASE_PATH} (default `'/'`). Root deployment needs no
 * override; a sub-path deployment sets `VITE_BASE_PATH` at build time.
 *
 * ## SPA deep-link fallback (finding M7)
 * `appType: 'spa'` makes Vite's dev and preview servers serve `index.html` for
 * unmatched paths (history fallback), so a direct load of `/orders/:id` renders
 * the app rather than 404ing. Because `/orders` is ALSO an API prefix, the proxy
 * uses {@link spaNavigationBypass} to distinguish HTML navigations (served the
 * SPA) from API calls (proxied to the backend). In PRODUCTION the app is served
 * as static files, so the hosting layer MUST rewrite unknown non-asset paths to
 * `/index.html` (for example nginx `try_files $uri /index.html;`, or the
 * equivalent single-page rewrite on the CDN/static host); API paths must be
 * routed to the order-service ahead of that rewrite.
 *
 * ## Dev proxy (same-origin backend access)
 * The proxy forwards the order-service route prefixes `/coupons` and `/orders`
 * to {@link ORDER_SERVICE_PROXY_TARGET}, letting the browser call the API using
 * same-origin relative paths (which sidesteps CORS in development) per the wire
 * contract in `src/api/orderServiceContract.js`.
 *
 * @see https://vite.dev/config/
 * @type {import('vite').UserConfig}
 */
export default defineConfig({
  base: BASE_PATH,
  appType: 'spa',
  plugins: [react()],
  server: {
    proxy: API_PROXY,
  },
  preview: {
    proxy: API_PROXY,
  },
  test: {
    // jsdom provides window/document/fetch/AbortController for component tests.
    environment: 'jsdom',
    // Configures React's act() environment flag before any test module loads.
    setupFiles: ['./vitest.setup.js'],
    include: ['src/**/*.test.{js,jsx}'],
    // No global stylesheet is imported; skip CSS processing during tests.
    css: false,
    // Silence noise from the intentional error-path tests.
    clearMocks: true,
  },
});
