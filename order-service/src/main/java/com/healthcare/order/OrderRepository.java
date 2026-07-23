package com.healthcare.order;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory, thread-safe store of {@link Order}s keyed by their {@link Order#getId() id}.
 *
 * <p>The order-service exposes an HTTP surface that must retrieve an order <b>by the id the
 * client asks for</b> ({@code GET /orders/{id}}) and update the status of <b>that specific
 * order</b> ({@code POST /orders/{id}/status}). A single mutable "current order" field cannot
 * satisfy that: concurrent clients each creating and tracking their own order would clobber one
 * another, and a lookup for an older id would return the wrong order. This repository fixes that
 * by holding every created order addressable by its own id.</p>
 *
 * <p><b>Scope.</b> Storage is a {@link ConcurrentHashMap} held in memory only — there is no
 * database, ORM, or migration (persistence is out of scope for this feature). State therefore
 * lives for the lifetime of the process. The map's per-key atomic operations make concurrent
 * {@link #save(Order)} and {@link #findById(String)} safe; the {@link Order} instances it holds
 * are mutated only by {@code OrderService} under the order's own monitor.</p>
 */
public class OrderRepository {

    /** Backing store, keyed by {@link Order#getId()}. Thread-safe for concurrent access. */
    private final ConcurrentMap<String, Order> ordersById = new ConcurrentHashMap<>();

    /**
     * Stores (inserts or replaces) the given order under its own {@link Order#getId() id}.
     * Because {@code Order} identity is its id, saving an already-stored order simply refreshes
     * the mapping to the same logical order.
     *
     * @param order the order to store; must not be {@code null} and must have a non-null id
     * @return the stored order (returned for call-site convenience)
     * @throws NullPointerException if {@code order} or its id is {@code null}
     */
    public Order save(Order order) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(order.getId(), "order.id");
        ordersById.put(order.getId(), order);
        return order;
    }

    /**
     * Looks up an order by the exact id supplied by the caller.
     *
     * @param id the order id to look up; {@code null}-safe (a {@code null} id resolves to empty)
     * @return the order wrapped in an {@link Optional}, or {@link Optional#empty()} if no order
     *         with that id exists
     */
    public Optional<Order> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ordersById.get(id));
    }

    /**
     * Reports whether an order with the given id is stored.
     *
     * @param id the order id to test; {@code null}-safe
     * @return {@code true} if an order with that id exists
     */
    public boolean existsById(String id) {
        return id != null && ordersById.containsKey(id);
    }

    /**
     * Returns the number of orders currently stored. Intended for diagnostics and tests.
     *
     * @return the count of stored orders
     */
    public int size() {
        return ordersById.size();
    }
}
