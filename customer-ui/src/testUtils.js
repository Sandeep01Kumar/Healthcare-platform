/**
 * Minimal React test helpers for the customer-ui suite.
 *
 * The project intentionally declares no `@testing-library/*` dependency (it is
 * not available in the offline build cache), so these helpers drive components
 * with React 19's built-in {@link act} plus `react-dom/client`'s `createRoot`
 * and native DOM events. `IS_REACT_ACT_ENVIRONMENT` is enabled in
 * `vitest.setup.js` so `act()` runs without warnings.
 *
 * This module is NOT a test file (it does not match the `*.test.*` include
 * pattern) and is imported only by the test suites.
 *
 * @module testUtils
 */

import { act } from 'react';
import { createRoot } from 'react-dom/client';

/**
 * Mount a React element into a fresh detached container appended to `document`.
 *
 * @param {import('react').ReactNode} ui - The element to render.
 * @returns {Promise<{container: HTMLElement, root: import('react-dom/client').Root,
 *   rerender: (next: import('react').ReactNode) => Promise<void>,
 *   unmount: () => Promise<void>}>} Handles for assertions and lifecycle.
 */
export async function renderComponent(ui) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(ui);
  });
  return {
    container,
    root,
    async rerender(next) {
      await act(async () => {
        root.render(next);
      });
    },
    async unmount() {
      await act(async () => {
        root.unmount();
      });
      container.remove();
    },
  };
}

/**
 * Flush pending microtasks/promises inside `act` so resolved fetch mocks and
 * the state updates they trigger are applied before assertions run.
 *
 * @returns {Promise<void>}
 */
export async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

/**
 * Dispatch a left-button click on an element, wrapped in `act`.
 *
 * @param {Element} el - The target element.
 * @returns {Promise<void>}
 */
export async function click(el) {
  await act(async () => {
    el.dispatchEvent(
      new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })
    );
  });
}

/**
 * Set a controlled `<input>`'s value the way React observes it: assign through
 * the native value setter, then dispatch an `input` event so React's change
 * tracking fires `onChange`.
 *
 * @param {HTMLInputElement} input - The input element.
 * @param {string} value - The value to set.
 * @returns {Promise<void>}
 */
export async function setInputValue(input, value) {
  const setter = Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype,
    'value'
  ).set;
  await act(async () => {
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

/**
 * Submit a form by dispatching a cancelable `submit` event, wrapped in `act`.
 *
 * @param {HTMLFormElement} form - The form element.
 * @returns {Promise<void>}
 */
export async function submitForm(form) {
  await act(async () => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
}

/**
 * Build a JSON `Response` (mirrors what the client's `fetch` mock returns).
 *
 * @param {*} body - The JSON-serializable body.
 * @param {number} [status] - The HTTP status (defaults to 200).
 * @returns {Response}
 */
export function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * A deferred promise with externally-callable `resolve`/`reject`, for driving
 * mocked async transports at a controlled time.
 *
 * @template T
 * @returns {{promise: Promise<T>, resolve: (value: T) => void, reject: (err: unknown) => void}}
 */
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}
