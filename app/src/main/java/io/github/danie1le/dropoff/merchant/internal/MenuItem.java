package io.github.danie1le.dropoff.merchant.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Something a merchant sells, priced in whole cents. Flyway owns the table (V3); Hibernate only validates it. */
@Entity
@Table(name = "menu_items")
class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "is_available", nullable = false)
    private boolean available;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected MenuItem() {}

    MenuItem(long merchantId, String name, long priceCents) {
        this.merchantId = merchantId;
        this.name = name;
        this.priceCents = priceCents;
        this.available = true;
    }

    long id() {
        return Objects.requireNonNull(id, "menu item is not saved yet");
    }

    long merchantId() {
        return merchantId;
    }

    String name() {
        return name;
    }

    long priceCents() {
        return priceCents;
    }

    boolean isAvailable() {
        return available;
    }

    void rename(String name) {
        this.name = name;
    }

    void reprice(long priceCents) {
        this.priceCents = priceCents;
    }

    void changeAvailability(boolean available) {
        this.available = available;
    }
}
