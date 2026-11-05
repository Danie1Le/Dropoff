package io.github.danie1le.dropoff.order.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A person who places orders. Flyway owns the table (V4); Hibernate only validates the mapping. */
@Entity
@Table(name = "customers")
class Customer {

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

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Customer() {}

    Customer(String name, Location location) {
        this.name = name;
        this.address = location.address();
        this.lat = location.lat();
        this.lng = location.lng();
    }

    long id() {
        return Objects.requireNonNull(id, "customer is not saved yet");
    }

    String name() {
        return name;
    }

    Location location() {
        return new Location(address, lat, lng);
    }

    void rename(String newName) {
        this.name = newName;
    }

    void moveTo(Location newLocation) {
        this.address = newLocation.address();
        this.lat = newLocation.lat();
        this.lng = newLocation.lng();
    }
}
