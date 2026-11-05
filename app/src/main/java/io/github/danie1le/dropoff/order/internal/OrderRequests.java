package io.github.danie1le.dropoff.order.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the order endpoints (docs/PLAN.md, "Shared contracts"). */
final class OrderRequests {

    private OrderRequests() {}

    /** The client sends only ids and quantities; prices, total and delivery address come from the server (#3, #9). */
    record PlaceOrder(
            @NotNull Long customerId,
            @NotNull Long merchantId,
            @NotNull @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines) {

        record Line(
                @NotNull Long menuItemId, @NotNull @Positive Integer quantity) {}
    }

    record OrderResponse(
            long id,
            long customerId,
            long merchantId,
            @Nullable Long courierId,
            OrderStatus status,
            long totalCents,
            DeliveryAddress deliveryAddress,
            List<LineResponse> lines,
            Instant createdAt,
            Instant updatedAt) {

        record DeliveryAddress(String address, double lat, double lng) {}

        record LineResponse(long menuItemId, int quantity, long priceCents) {}

        static OrderResponse of(Order order) {
            Location to = order.deliveryAddress();
            return new OrderResponse(
                    order.id(),
                    order.customerId(),
                    order.merchantId(),
                    order.courierId(),
                    order.status(),
                    order.totalCents(),
                    new DeliveryAddress(to.address(), to.lat(), to.lng()),
                    order.lines().stream()
                            .map(l -> new LineResponse(l.menuItemId(), l.quantity(), l.priceCents()))
                            .toList(),
                    order.createdAt(),
                    order.updatedAt());
        }
    }
}
