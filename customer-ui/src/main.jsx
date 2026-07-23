/**
 * Entry point for the `customer-ui` React single-page application.
 *
 * This module is the bootstrap that `customer-ui/index.html` loads via
 * `<script type="module" src="/src/main.jsx">`. Its sole responsibility is to
 * mount the root {@link App} component into the `#root` element declared in
 * `index.html`, using the React 19 client API (`createRoot` from
 * `react-dom/client`).
 *
 * By design this module is intentionally minimal: it owns no application
 * state, performs no data fetching, and configures no routing, stores, or
 * providers. Client-side routing lives inside {@link App}, and all server
 * communication lives in the child components/pages. Keeping the bootstrap
 * free of such concerns makes the mount point trivial to reason about and to
 * replace (for example, when adding a global provider later, it is wrapped
 * around `<App/>` here without touching the rest of the tree).
 *
 * @module main
 */

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App.jsx';

/**
 * The DOM node the React tree is mounted into.
 *
 * `index.html` declares exactly one mount node, `<div id="root"></div>`; the
 * id must remain `'root'` to honor that markup contract. Resolved eagerly at
 * module load because the entry script runs after the document body has been
 * parsed (it is a deferred ES module).
 *
 * @type {HTMLElement | null}
 */
const rootElement = document.getElementById('root');

// Fail loudly when the mount node is missing so a misconfigured `index.html`
// surfaces the problem immediately, rather than silently rendering nothing.
if (!rootElement) {
  throw new Error('Root element #root not found');
}

// Create the React 19 root and render the application. `<StrictMode>` enables
// additional development-only checks and warnings (it renders no visible
// markup and is stripped from production builds).
createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>
);
