package io.github.danie1le.dropoff.merchant.internal;

import static io.github.danie1le.dropoff.web.Validated.required;

import io.github.danie1le.dropoff.web.NotBlankIfPresent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the menu item endpoints (ticket #59). */
final class MenuItemRequests {

    private MenuItemRequests() {}

    /**
     * {@code priceCents} binds as a {@link BigDecimal} so a decimal such as {@code 9.99} reaches validation and is
     * reported on its field, instead of being truncated to a long by the JSON reader.
     */
    record CreateMenuItem(
            @Schema(description = "Display name.", example = "Margherita") @NotNull @NotBlankIfPresent @Size(max = 200)
            String name,

            @Schema(description = "Price in whole cents.", example = "1099")
            @NotNull
            @PositiveOrZero
            @Digits(integer = 19, fraction = 0, message = "must be a whole number of cents")
            @DecimalMax(value = "9223372036854775807", message = "is too large")
            BigDecimal priceCents) {

        /** The name without surrounding whitespace. {@code @Valid} rejects a missing name before this is called. */
        String trimmedName() {
            return required(name).trim();
        }

        /** The price as a long. {@code @Valid} rejects a missing, decimal or out-of-range price first. */
        long priceCentsValue() {
            return required(priceCents).longValueExact();
        }
    }

    /**
     * Partial update: an absent or null field means unchanged (ticket #61). Same rules as {@link CreateMenuItem};
     * {@code priceCents} is a {@link BigDecimal} for the same reason.
     */
    record UpdateMenuItem(
            @Schema(description = "New name. Absent or null means unchanged.", example = "Margherita XL")
            @Nullable
            @NotBlankIfPresent
            @Size(max = 200)
            String name,

            @Schema(description = "New price in whole cents. Absent or null means unchanged.", example = "1299")
            @Nullable
            @PositiveOrZero
            @Digits(integer = 19, fraction = 0, message = "must be a whole number of cents")
            @DecimalMax(value = "9223372036854775807", message = "is too large")
            BigDecimal priceCents,

            @Schema(description = "Whether it can be ordered. Absent or null means unchanged.", example = "false")
            @Nullable
            Boolean available) {

        /** The new name without surrounding whitespace, or null when it doesn't change. */
        @Nullable
        String trimmedName() {
            return name == null ? null : name.trim();
        }

        /** The new price as a long, or null when it doesn't change. {@code @Valid} rejects a bad price first. */
        @Nullable
        Long priceCentsValue() {
            return priceCents == null ? null : priceCents.longValueExact();
        }
    }

    record MenuItemResponse(
            @Schema(example = "12") long id,
            @Schema(example = "3") long merchantId,
            @Schema(example = "Margherita") String name,

            @Schema(description = "Price in whole cents.", example = "1099")
            long priceCents,

            @Schema(example = "true") boolean available) {

        static MenuItemResponse of(MenuItem item) {
            return new MenuItemResponse(
                    item.id(), item.merchantId(), item.name(), item.priceCents(), item.isAvailable());
        }
    }
}
