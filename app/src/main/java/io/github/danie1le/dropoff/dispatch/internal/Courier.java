package io.github.danie1le.dropoff.dispatch.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A person who delivers orders. Flyway owns the table (V8); Hibernate only validates the mapping. */
@Entity
@Table(name = "couriers")
class Courier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourierStatus status;

    @Column(name = "last_lat")
    private @Nullable Double lastLat;

    @Column(name = "last_lng")
    private @Nullable Double lastLng;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Courier() {}

    /** A new courier: OFFLINE with no location (ticket #42). */
    Courier(String name, Instant now) {
        this.name = name;
        this.status = CourierStatus.OFFLINE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    long id() {
        return Objects.requireNonNull(id, "courier is not saved yet");
    }

    String name() {
        return name;
    }

    CourierStatus status() {
        return status;
    }

    /** The last location the courier sent, or empty before the first one. */
    Optional<Position> position() {
        return lastLat == null || lastLng == null ? Optional.empty() : Optional.of(new Position(lastLat, lastLng));
    }

    /** Applies the courier's own status request; see {@link CourierStatus#change}. */
    void changeStatus(CourierStatus requested, boolean hasActiveDelivery, Instant now) {
        this.status = status.change(requested, position().isPresent(), hasActiveDelivery);
        this.updatedAt = now;
    }

    /** Takes the courier off work after they let an offer time out (ticket #11, point 8). */
    void timeOut(Instant now) {
        this.status = CourierStatus.OFFLINE;
        this.updatedAt = now;
    }

    /**
     * Frees the courier after they decline an offer (ticket #11, point 8) or deliver an order (M3-5): BUSY becomes
     * AVAILABLE. Any other status is left as it is.
     */
    void release(Instant now) {
        if (status == CourierStatus.BUSY) {
            this.status = CourierStatus.AVAILABLE;
            this.updatedAt = now;
        }
    }

    /** Stores a new location. Allowed in every status (ticket #42, second gate pass). */
    void moveTo(Position position, Instant now) {
        this.lastLat = position.lat();
        this.lastLng = position.lng();
        this.updatedAt = now;
    }
}
