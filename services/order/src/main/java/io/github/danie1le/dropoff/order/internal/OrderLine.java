package io.github.danie1le.dropoff.order.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** One menu item on an order, with its quantity and price snapshot (ADR-0004). One line per menu item. */
@Entity
@Table(name = "order_lines")
class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "menu_item_id", nullable = false)
    private long menuItemId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    /** For JPA only. */
    protected OrderLine() {}

    OrderLine(long menuItemId, int quantity, long priceCents) {
        this.menuItemId = menuItemId;
        this.quantity = quantity;
        this.priceCents = priceCents;
    }

    long menuItemId() {
        return menuItemId;
    }

    int quantity() {
        return quantity;
    }

    long priceCents() {
        return priceCents;
    }

    long subtotalCents() {
        return Math.multiplyExact(priceCents, quantity);
    }
}
