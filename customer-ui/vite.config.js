import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Vite configuration for the customer-ui React single-page application.
 *
 * Registers the official React plugin (@vitejs/plugin-react) to enable JSX
 * transformation and React Fast Refresh during development. The customer-ui
 * package declares "type": "module", so this configuration is authored with
 * ESM `import` / `export default` syntax rather than CommonJS.
 *
 * Vite's defaults are intentionally left untouched: the entry `index.html`
 * lives at the project root and references `/src/main.jsx`, so no custom
 * `server`, `build`, `resolve`, or `test` options are required for this
 * scaffold. Additional options can be layered in here as the UI grows.
 *
 * @see https://vite.dev/config/
 * @type {import('vite').UserConfig}
 */
export default defineConfig({
  plugins: [react()],
});
