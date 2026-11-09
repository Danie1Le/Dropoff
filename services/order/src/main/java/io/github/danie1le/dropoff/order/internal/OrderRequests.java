package io.github.danie1le.dropoff.order.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
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

    /**
     * The client sends only ids and quantities; prices, total and delivery address come from the server (#3, #9). At
     * most {@value #MAX_LINES} lines and {@value #MAX_QUANTITY} per line, so merged quantities never overflow.
     */
    record PlaceOrder(
            @Schema(example = "5") @NotNull Long customerId,
            @Schema(example = "3") @NotNull Long merchantId,

            @Schema(description = "What to order, 1 to 100 lines.") @NotNull @NotEmpty @Size(max = MAX_LINES)
            List<@NotNull @Valid Line> lines) {

        static final int MAX_LINES = 100;
        static final int MAX_QUANTITY = 999;

        record Line(
                @Schema(example = "12") @NotNull Long menuItemId,

                @Schema(description = "1 to 999.", example = "2") @NotNull @Positive @Max(MAX_QUANTITY)
                Integer quantity) {}
    }

    record OrderResponse(
            @Schema(example = "1042") long id,
            @Schema(example = "5") long customerId,
            @Schema(example = "3") long merchantId,

            @Schema(description = "Null until a courier accepts the delivery.", example = "7") @Nullable
            Long courierId,

            OrderStatus status,

            @Schema(description = "Sum of the lines, in cents.", example = "2198")
            long totalCents,

            Location deliveryAddress,
            List<LineResponse> lines,
            @Schema(example = "2026-01-15T12:00:00Z") Instant createdAt,
            @Schema(example = "2026-01-15T12:01:30Z") Instant updatedAt) {

        record LineResponse(
                @Schema(example = "12") long menuItemId,
                @Schema(example = "2") int quantity,

                @Schema(description = "Unit price when ordered, in cents.", example = "1099")
                long priceCents) {}

        static OrderResponse of(Order order) {
            return new OrderResponse(
                    order.id(),
                    order.customerId(),
                    order.merchantId(),
                    order.courierId(),
                    order.status(),
                    order.totalCents(),
                    order.deliveryAddress(),
                    order.lines().stream()
                            .map(l -> new LineResponse(l.menuItemId(), l.quantity(), l.priceCents()))
                            .toList(),
                    order.createdAt(),
                    order.updatedAt());
        }
    }
}
