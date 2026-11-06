package io.github.danie1le.dropoff.order.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;

/** A customer's request to buy menu items from one merchant. Flyway owns the table (V5); Hibernate only validates. */
@Entity
@Table(name = "orders")
class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "customer_id", nullable = false)
    private long customerId;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(name = "courier_id")
    private @Nullable Long courierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(name = "delivery_address", nullable = false, length = 500)
    private String deliveryAddress;

    @Column(name = "delivery_lat", nullable = false)
    private double deliveryLat;

    @Column(name = "delivery_lng", nullable = false)
    private double deliveryLng;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "bpchar(64)")
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id")
    // Lists load the lines of a whole page in one query instead of one per order.
    @BatchSize(size = 100)
    private List<OrderLine> lines = new ArrayList<>();

    /** For JPA only. */
    @SuppressWarnings("NullAway.Init")
    protected Order() {}

    /** A new PLACED order. The total is the sum of each line's price snapshot times its quantity. */
    Order(
            long customerId,
            long merchantId,
            Location deliveryAddress,
            List<OrderLine> lines,
            String idempotencyKey,
            String requestHash,
            Instant now) {
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.status = OrderStatus.PLACED;
        this.deliveryAddress = deliveryAddress.address();
        this.deliveryLat = deliveryAddress.lat();
        this.deliveryLng = deliveryAddress.lng();
        this.lines = new ArrayList<>(lines);
        this.totalCents = lines.stream().mapToLong(OrderLine::subtotalCents).reduce(0, Math::addExact);
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = now;
        this.updatedAt = now;
    }

    long id() {
        return Objects.requireNonNull(id, "order is not saved yet");
    }

    String requestHash() {
        return requestHash;
    }

    long customerId() {
        return customerId;
    }

    long merchantId() {
        return merchantId;
    }

    @Nullable
    Long courierId() {
        return courierId;
    }

    OrderStatus status() {
        return status;
    }

    long totalCents() {
        return totalCents;
    }

    Location deliveryAddress() {
        return new Location(deliveryAddress, deliveryLat, deliveryLng);
    }

    List<OrderLine> lines() {
        return List.copyOf(lines);
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
