/**
 * @module index
 *
 * Service **entry / bootstrap** for the `notification-service` module
 * (AAP Section 0.5.1, Group 4 — "Add order status change notifications").
 *
 * ## Role
 * This file is the module's programmatic entry point. It is referenced by the
 * parent {@link ../package.json | `package.json`} both as the `"main"` field
 * (`src/index.js`) and by the `"start"` script (`node src/index.js`). Its sole
 * job is to wire up a ready-to-use {@link NotificationService} instance with the
 * default (console) transport and re-export it for programmatic consumers, so
 * that other code — and the module's own `npm start` — has a single, canonical
 * place to obtain a working notification service.
 *
 * ## No HTTP receiver (by design)
 * There is intentionally **no HTTP receiver and no `express` server** in this
 * module (per AAP Sections 0.3 and 0.4). The status-change seam between the Java
 * `order-service` (producer) and this Node `notification-service` (consumer) is
 * a decoupled, externally-wired producer/consumer relationship rather than a
 * network endpoint; the producer injects/invokes an implementation of its
 * `NotificationTrigger` contract, and this module simply exposes a configured
 * {@link NotificationService} whose
 * {@link NotificationService#sendStatusChangeNotification} mirrors that
 * contract. Bootstrapping therefore does not open any socket or bind any port.
 *
 * ## Side-effect-light on import
 * Importing this module (for example, from a unit test or from another module
 * that wants the shared instance) MUST NOT produce console output or start any
 * server. Instantiating {@link NotificationService} with the default transport
 * is cheap and free of external side effects, so a bare `import` is safe. The
 * only observable side effect — a single "ready" log line — is emitted **only
 * when this file is executed directly** (`node src/index.js` / `npm start`),
 * guarded by an `import.meta.url` check so it never fires on import.
 *
 * @remarks ESM module (matches `"type": "module"` in `package.json`). Zero npm
 * dependencies: it uses only the intra-folder {@link NotificationService} import
 * and the Node built-in `node:url` for the robust direct-run guard.
 *
 * @see {@link NotificationService} for the notification-delivery contract.
 */

import { pathToFileURL } from 'node:url';
import { NotificationService } from './NotificationService.js';

/**
 * A ready-to-use {@link NotificationService} wired with the default (console)
 * transport.
 *
 * This is the canonical, shared instance for the `notification-service` module.
 * Because no transport is passed to the constructor, notifications are delivered
 * through the built-in console-logging transport — a benign, vendor-neutral
 * default (per AAP Section 0.6.2). Consumers that need real delivery should
 * construct their own {@link NotificationService} with an injected transport
 * rather than mutating this instance.
 *
 * It is exported as this module's `default` export so that
 * `import notificationService from './index.js'` yields a working instance
 * immediately, with no additional setup required.
 *
 * @type {NotificationService}
 */
const notificationService = new NotificationService();

export { NotificationService };
export default notificationService;

// Direct-run guard: emit a single readiness line ONLY when this file is executed
// directly (e.g. `node src/index.js` or `npm start`) — never when imported. The
// `pathToFileURL(process.argv[1]).href` form is used (rather than a raw string
// comparison) so the equality check is robust against path-encoding edge cases
// such as spaces or non-ASCII characters in the script path. The leading
// `process.argv[1]` truthiness check keeps the guard safe when there is no entry
// script at all (e.g. `node --input-type=module -e "import './index.js'"`, where
// `process.argv[1]` is `undefined`); in that case we short-circuit rather than
// letting `pathToFileURL(undefined)` throw, preserving the side-effect-light
// import contract.
//
// The message states only what is actually true: the transport-agnostic library
// (with its default console transport) is initialized. It deliberately does NOT
// claim to be "listening" — this module binds no HTTP endpoint, port, queue
// subscription, or IPC consumer (by design; see the module header). A producer
// delivers events by invoking `sendStatusChangeNotification` through an injected
// transport, not by connecting to a listener here.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(
    '[notification-service] ready — transport-agnostic notification library ' +
      'initialized with the default console transport; no listener is bound ' +
      '(no HTTP endpoint, queue subscription, or IPC consumer).'
  );
}
