package com.healthcare.order;

/**
 * Order lifecycle service.
 *
 * <p>This class exposes the original {@link #createOrder()} entry point, retained
 * unchanged for backward compatibility. The coupon-validation orchestration, the
 * guarded {@code CREATED -> CONFIRMED -> DELIVERED} status transitions, the
 * pricing-engine discount application, and the decoupled status-change notification
 * trigger are layered on top of this foundation (see the module README) without
 * removing or altering this method's signature or behavior.</p>
 */
public class OrderService {

    /**
     * Creates an order.
     *
     * @return a confirmation message for the newly created order
     */
    public String createOrder() {
        return "Order Created";
    }

}
