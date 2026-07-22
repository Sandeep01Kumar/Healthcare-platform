/**
 * Order lifecycle status with a guarded, forward-only transition model.
 *
 * <p>An order advances strictly through the lifecycle
 * {@code CREATED -> CONFIRMED -> DELIVERED}. Each state may progress only to its
 * immediate successor; every other movement is rejected by the transition
 * guard, namely:</p>
 * <ul>
 *   <li>skips (for example {@code CREATED -> DELIVERED}),</li>
 *   <li>backward moves (for example {@code CONFIRMED -> CREATED}),</li>
 *   <li>self-transitions (for example {@code CREATED -> CREATED}), and</li>
 *   <li>any transition out of the terminal {@code DELIVERED} state.</li>
 * </ul>
 *
 * <p>The guard is deliberately side-effect free and never throws; a
 * {@code null} target is simply treated as an invalid transition. Callers such
 * as {@code OrderService.updateStatus} are responsible for deciding whether a
 * rejected transition should raise an error.</p>
 */
public enum OrderStatus {

    /** Initial state assigned when an order is first created. */
    CREATED,

    /** Order has been confirmed and is awaiting delivery. */
    CONFIRMED,

    /** Terminal state: the order has been delivered. */
    DELIVERED;

    /**
     * Reports whether this status may advance directly to {@code next}.
     *
     * <p>Only the forward steps {@code CREATED -> CONFIRMED} and
     * {@code CONFIRMED -> DELIVERED} are permitted. Every other pairing,
     * including a {@code null} target, a self-transition, a skip, a backward
     * move, or any transition from the terminal {@code DELIVERED} state,
     * yields {@code false}.</p>
     *
     * @param next the candidate target status; may be {@code null}
     * @return {@code true} if and only if {@code next} is the immediate,
     *         forward successor of this status
     */
    public boolean canTransitionTo(OrderStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case CREATED -> next == CONFIRMED;
            case CONFIRMED -> next == DELIVERED;
            case DELIVERED -> false;
        };
    }

    /**
     * Null-safe static convenience mirroring
     * {@link #canTransitionTo(OrderStatus)}.
     *
     * @param from the source status; may be {@code null}
     * @param to   the candidate target status; may be {@code null}
     * @return {@code true} if and only if {@code from} is non-null and may
     *         advance directly to {@code to}
     */
    public static boolean isValidTransition(OrderStatus from, OrderStatus to) {
        return from != null && from.canTransitionTo(to);
    }

}
