import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Base URL of the order-service the dev server proxies to. Overridable via the
 * `ORDER_SERVICE_PROXY_TARGET` environment variable so the same configuration
 * works across local and containerized backends. Defaults to the conventional
 * local order-service port.
 *
 * @type {string}
 */
const ORDER_SERVICE_PROXY_TARGET =
  process.env.ORDER_SERVICE_PROXY_TARGET || 'http://localhost:8080';

/**
 * Vite configuration for the customer-ui React single-page application.
 *
 * Registers the official React plugin (@vitejs/plugin-react) to enable JSX
 * transformation and React Fast Refresh during development. The customer-ui
 * package declares "type": "module", so this configuration is authored with
 * ESM `import` / `export default` syntax rather than CommonJS.
 *
 * ## Dev proxy (same-origin backend access)
 * A development proxy forwards the order-service route prefixes `/coupons` and
 * `/orders` to {@link ORDER_SERVICE_PROXY_TARGET}. This lets the browser call the
 * API using same-origin relative paths, which sidesteps CORS in development and
 * matches the wire contract documented in
 * `src/api/orderServiceContract.js`. In production the SPA is expected to be
 * served behind a reverse proxy (or the server is expected to send the CORS
 * headers described in that contract).
 *
 * ## Entry HTML (planned)
 * The Vite entry `index.html` and the `src/main.jsx` bootstrap it references are
 * PLANNED and are not part of this foundation checkpoint; only the API client and
 * build/test scaffolding are delivered here. Vite's remaining defaults are left
 * untouched, and additional `build`, `resolve`, or `test` options can be layered
 * in as the UI is built out.
 *
 * @see https://vite.dev/config/
 * @type {import('vite').UserConfig}
 */
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/coupons': {
        target: ORDER_SERVICE_PROXY_TARGET,
        changeOrigin: true,
      },
      '/orders': {
        target: ORDER_SERVICE_PROXY_TARGET,
        changeOrigin: true,
      },
    },
  },
});
