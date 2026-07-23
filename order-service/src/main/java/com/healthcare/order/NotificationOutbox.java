package com.healthcare.order;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * In-memory transactional outbox for order status-change notifications (implements the
 * reliability half of the "every status change emits a notification" rule).
 *
 * <p><b>Why an outbox.</b> A status transition and its notification must not be able to
 * diverge. The naive approach — mutate the order's status and then call the notification
 * transport, letting a transport failure propagate — loses the event when delivery fails: the
 * status has already advanced but no notification was (or ever will be) sent. This outbox
 * decouples the two: {@code OrderService} <b>records the event here as {@code PENDING} before it
 * attempts delivery</b>. If delivery succeeds the event is marked {@code DELIVERED}; if it fails
 * the event stays {@code PENDING} and can be re-attempted later via
 * {@code OrderService.retryPendingNotifications()}. The transition itself always commits, and no
 * event is ever silently dropped.</p>
 *
 * <p><b>Idempotency.</b> Each event has a stable {@link Entry#getEventId() eventId} derived from
 * the order id and the {@code from -> to} transition, so recording the same transition twice (for
 * example a retry) refers to the same entry rather than creating a duplicate. This is the
 * correlation id downstream consumers use to deduplicate re-deliveries.</p>
 *
 * <p><b>Scope &amp; threading.</b> Storage is a {@link ConcurrentHashMap} held in memory only (no
 * durable store — persistence is out of scope for this feature), so state lives for the process
 * lifetime. All operations are safe for concurrent use.</p>
 */
public class NotificationOutbox {

    /** Delivery state of an outbox entry. */
    public enum State {
        /** Recorded but not yet successfully delivered; eligible for (re)delivery. */
        PENDING,
        /** Successfully delivered to the notification trigger. */
        DELIVERED
    }

    /** Entries keyed by their stable {@link Entry#getEventId() eventId}. */
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Creates an empty outbox backed by an in-memory, thread-safe {@link ConcurrentHashMap}. No
     * durable store is opened (persistence is out of scope for this feature), so all recorded
     * entries live for the lifetime of the process.
     */
    public NotificationOutbox() {
        // No initialization required: the backing map is created as a final field above.
    }

    /**
     * Builds the stable, correlation-friendly event id for a transition.
     *
     * @param orderId the order's id
     * @param from    the previous status
     * @param to      the new status
     * @return an id of the form {@code "<orderId>|<from>-><to>"}
     */
    public static String eventId(String orderId, OrderStatus from, OrderStatus to) {
        return orderId + "|" + from + "->" + to;
    }

    /**
     * Records a status-change event as {@link State#PENDING}, to be called <b>before</b> the
     * caller attempts delivery. Idempotent per {@link #eventId(String, OrderStatus, OrderStatus)}:
     * recording the same transition again returns the existing entry unchanged (preserving a
     * prior {@code DELIVERED} state) rather than resurrecting it as pending.
     *
     * @param order the order whose status changed; must not be {@code null}
     * @param from  the previous status; must not be {@code null}
     * @param to    the new status; must not be {@code null}
     * @return the outbox entry for this transition (new or pre-existing); never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public Entry record(Order order, OrderStatus from, OrderStatus to) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        String id = eventId(order.getId(), from, to);
        return entries.computeIfAbsent(id, key -> new Entry(key, order, from, to));
    }

    /**
     * Marks the entry with the given id {@link State#DELIVERED}. A no-op if the id is unknown.
     *
     * @param eventId the id of the entry to mark delivered
     */
    public void markDelivered(String eventId) {
        Entry entry = entries.get(eventId);
        if (entry != null) {
            entry.state = State.DELIVERED;
        }
    }

    /**
     * Returns a snapshot list of the entries still awaiting delivery ({@link State#PENDING}),
     * in no particular order.
     *
     * @return the pending entries; never {@code null} (empty when none are pending)
     */
    public List<Entry> pending() {
        return entries.values().stream()
                .filter(e -> e.state == State.PENDING)
                .collect(Collectors.toList());
    }

    /**
     * Returns the current delivery state of an event, or {@code null} if the id is unknown.
     *
     * @param eventId the event id to inspect
     * @return the {@link State}, or {@code null} if no such entry exists
     */
    public State stateOf(String eventId) {
        Entry entry = entries.get(eventId);
        return entry == null ? null : entry.state;
    }

    /**
     * Returns the total number of recorded entries (pending plus delivered). Intended for
     * diagnostics and tests.
     *
     * @return the entry count
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns how many recorded events are still awaiting delivery ({@link State#PENDING}).
     *
     * <p>This is the primary delivery-health signal driving the automatic retry loop (finding
     * INT-01): a non-zero count means at least one committed status change has not yet reached the
     * notification transport, so a retry sweep is warranted; a zero count means the outbox is fully
     * drained and the retry loop can relax its backoff.</p>
     *
     * @return the number of {@link State#PENDING} entries (never negative)
     */
    public long pendingCount() {
        return entries.values().stream().filter(e -> e.state == State.PENDING).count();
    }

    /**
     * Returns how many recorded events have been successfully delivered ({@link State#DELIVERED}).
     * Together with {@link #pendingCount()} this exposes the outbox delivery health for diagnostics
     * and tests (finding INT-01).
     *
     * @return the number of {@link State#DELIVERED} entries (never negative)
     */
    public long deliveredCount() {
        return entries.values().stream().filter(e -> e.state == State.DELIVERED).count();
    }

    /**
     * An immutable-payload record of a single status-change event and its mutable delivery
     * {@link State}. The captured {@link #getOrder() order}, {@link #getFrom() from}, and
     * {@link #getTo() to} are exactly the arguments a re-delivery must replay.
     */
    public static final class Entry {

        private final String eventId;
        private final Order order;
        private final OrderStatus from;
        private final OrderStatus to;
        private final Instant recordedAt;
        private volatile State state;

        private Entry(String eventId, Order order, OrderStatus from, OrderStatus to) {
            this.eventId = eventId;
            this.order = order;
            this.from = from;
            this.to = to;
            this.recordedAt = Instant.now();
            this.state = State.PENDING;
        }

        /**
         * Returns the stable correlation id for this transition.
         *
         * @return the stable correlation id for this transition
         */
        public String getEventId() {
            return eventId;
        }

        /**
         * Returns the order whose status changed.
         *
         * @return the order whose status changed
         */
        public Order getOrder() {
            return order;
        }

        /**
         * Returns the previous status the order transitioned from.
         *
         * @return the previous status
         */
        public OrderStatus getFrom() {
            return from;
        }

        /**
         * Returns the new status the order transitioned to.
         *
         * @return the new status
         */
        public OrderStatus getTo() {
            return to;
        }

        /**
         * Returns the instant this event was recorded.
         *
         * @return the instant this event was recorded
         */
        public Instant getRecordedAt() {
            return recordedAt;
        }

        /**
         * Returns the current delivery state of this entry.
         *
         * @return the current delivery state
         */
        public State getState() {
            return state;
        }
    }
}
