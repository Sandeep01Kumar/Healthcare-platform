# Notification Service

Handles:

- Order status change notifications
- Idempotent notification delivery (safe to retry)
- Pluggable notification transport

## Integration

Decoupled event/observer seam: `order-service` (Java producer) publishes an
order status-change event; `notification-service` (Node consumer) receives it
and sends the notification. There is no hard code dependency from this module
back into `order-service`.

## Core API

`NotificationService.sendStatusChangeNotification(order, oldStatus, newStatus)`
is invoked on each successful `CREATED → CONFIRMED → DELIVERED` transition and
is handled idempotently, so retries never send a duplicate notification.

Transports are pluggable; no concrete email/SMS/push vendor is included.
