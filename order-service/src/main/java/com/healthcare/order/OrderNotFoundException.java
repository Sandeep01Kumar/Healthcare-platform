package com.healthcare.order;

/**
 * Thrown when an order lookup or status transition targets an id that does not resolve to a
 * stored {@link Order}.
 *
 * <p>It is an unchecked exception so service call sites stay clean; the HTTP layer catches it at
 * the request boundary and maps it to a {@code 404 NOT_FOUND} error envelope.</p>
 */
public class OrderNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The id that could not be resolved. */
    private final String orderId;

    /**
     * Creates the exception for the given unresolved order id.
     *
     * @param orderId the id that did not resolve to a stored order
     */
    public OrderNotFoundException(String orderId) {
        super("order not found: " + orderId);
        this.orderId = orderId;
    }

    /**
     * Returns the id that could not be resolved.
     *
     * @return the unresolved order id
     */
    public String getOrderId() {
        return orderId;
    }
}
