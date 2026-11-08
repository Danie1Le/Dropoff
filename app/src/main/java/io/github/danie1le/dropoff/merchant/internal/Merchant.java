package io.github.danie1le.dropoff.merchant.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A business that sells menu items. Flyway owns the table (V2); Hibernate only validates the mapping. */
@Entity
@Table(name = "merchants")
class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "is_open", nullable = false)
    private boolean open;

    /** 1 on create, plus 1 per {@link #changed()}. Not JPA's {@code @Version}: the update path locks the row. */
    @Column(nullable = false)
    private long version;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Merchant() {}

    Merchant(String name, Location location) {
        this.name = name;
        this.address = location.address();
        this.lat = location.lat();
        this.lng = location.lng();
        this.open = true;
        this.version = 1;
    }

    long id() {
        return Objects.requireNonNull(id, "merchant is not saved yet");
    }

    String name() {
        return name;
    }

    String address() {
        return address;
    }

    double lat() {
        return lat;
    }

    double lng() {
        return lng;
    }

    boolean isOpen() {
        return open;
    }

    void rename(String newName) {
        this.name = newName;
    }

    void moveTo(Location newLocation) {
        this.address = newLocation.address();
        this.lat = newLocation.lat();
        this.lng = newLocation.lng();
    }

    void setOpen(boolean isOpen) {
        this.open = isOpen;
    }

    long version() {
        return version;
    }

    /** Marks one change. Call it once per update, with the row locked, so versions follow commit order. */
    void changed() {
        version++;
    }
}
