/**
 * Vitest global setup for the customer-ui component tests.
 *
 * React 19's `act()` (used to flush state updates and effects in tests without
 * `@testing-library`) requires the environment flag `IS_REACT_ACT_ENVIRONMENT`
 * to be `true`; without it React logs a "current testing environment is not
 * configured to support act(...)" warning. Setting it here — before any test
 * module is imported — keeps the test run warning-free.
 *
 * This file is referenced by `test.setupFiles` in `vite.config.js`.
 */
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
