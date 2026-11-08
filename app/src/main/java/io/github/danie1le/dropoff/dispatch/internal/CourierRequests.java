package io.github.danie1le.dropoff.dispatch.internal;

import static io.github.danie1le.dropoff.web.Validated.required;

import io.github.danie1le.dropoff.web.Latitude;
import io.github.danie1le.dropoff.web.Longitude;
import io.github.danie1le.dropoff.web.NotBlankIfPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the courier endpoints (tickets #12, #42). */
final class CourierRequests {

    private CourierRequests() {}

    record CreateCourier(
            @NotNull @NotBlankIfPresent @Size(max = 200) String name) {}

    /** BUSY is set by the system only, so a request for it fails validation with 400 (ticket #42, point 10). */
    record ChangeStatus(
            @NotNull @Pattern(regexp = "AVAILABLE|OFFLINE", message = "must be AVAILABLE or OFFLINE")
            String status) {

        /** The requested status. {@code @Valid} rejects any other value before this is called. */
        CourierStatus requested() {
            return CourierStatus.valueOf(required(status));
        }
    }

    record UpdateLocation(
            @NotNull @Latitude Double lat,
            @NotNull @Longitude Double lng) {

        /** The new position. {@code @Valid} rejects a missing lat or lng before this is called. */
        Position position() {
            return new Position(required(lat), required(lng));
        }
    }

    /** {@code lat} and {@code lng} are null until the courier sends a first location. */
    record CourierResponse(
            long id,
            String name,
            CourierStatus status,
            @Nullable Double lat,
            @Nullable Double lng) {

        static CourierResponse of(Courier courier) {
            @Nullable Position position = courier.position().orElse(null);
            return new CourierResponse(
                    courier.id(),
                    courier.name(),
                    courier.status(),
                    position == null ? null : position.lat(),
                    position == null ? null : position.lng());
        }
    }
}
