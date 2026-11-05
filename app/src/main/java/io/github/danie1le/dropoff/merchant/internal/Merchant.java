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

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Merchant() {}

    Merchant(String name, String address, double lat, double lng) {
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.open = true;
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

    void moveTo(String newAddress, double newLat, double newLng) {
        this.address = newAddress;
        this.lat = newLat;
        this.lng = newLng;
    }

    void setOpen(boolean isOpen) {
        this.open = isOpen;
    }
}
