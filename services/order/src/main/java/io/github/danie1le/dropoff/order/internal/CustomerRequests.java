package io.github.danie1le.dropoff.order.internal;

import static io.github.danie1le.dropoff.web.Validated.required;

import io.github.danie1le.dropoff.web.Latitude;
import io.github.danie1le.dropoff.web.Longitude;
import io.github.danie1le.dropoff.web.NotBlankIfPresent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the customer endpoints (ticket #9). No geocoder: lat/lng are range-checked only. */
final class CustomerRequests {

    private CustomerRequests() {}

    record CreateCustomer(
            @Schema(example = "Alex Jansen") @NotNull @NotBlankIfPresent @Size(max = 200)
            String name,

            @Schema(example = "20 Prinsengracht, Amsterdam") @NotNull @NotBlankIfPresent @Size(max = 500)
            String address,

            @Schema(description = "Latitude in degrees.", example = "52.3667") @NotNull @Latitude
            Double lat,

            @Schema(description = "Longitude in degrees.", example = "4.8830") @NotNull @Longitude
            Double lng) {

        /** The location. {@code @Valid} rejects a missing lat or lng before this is called. */
        Location location() {
            return new Location(address, required(lat), required(lng));
        }
    }

    /** Partial update: an absent or null field means unchanged. Address, lat and lng move together (ticket #42). */
    @AddressChangedTogether
    record UpdateCustomer(
            @Schema(description = "New name. Absent or null means unchanged.", example = "Alex de Vries")
            @Nullable
            @NotBlankIfPresent
            @Size(max = 200)
            String name,

            @Schema(description = "New address; send with lat and lng.", example = "5 Rokin, Amsterdam")
            @Nullable
            @NotBlankIfPresent
            @Size(max = 500)
            String address,

            @Schema(description = "New latitude; send with address and lng.", example = "52.3702") @Nullable @Latitude
            Double lat,

            @Schema(description = "New longitude; send with address and lat.", example = "4.8952") @Nullable @Longitude
            Double lng) {

        /**
         * The names of the absent location fields, in the order address, lat, lng. Empty when all three are given or
         * all three are absent.
         */
        List<String> missingLocationFields() {
            if (address == null && lat == null && lng == null) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            if (address == null) {
                missing.add("address");
            }
            if (lat == null) {
                missing.add("lat");
            }
            if (lng == null) {
                missing.add("lng");
            }
            return List.copyOf(missing);
        }

        /** The new location, or null when it doesn't change. {@link AddressChangedTogether} rejects a partial one. */
        @Nullable
        Location location() {
            if (!missingLocationFields().isEmpty()) {
                throw new IllegalStateException("location is incomplete; validate with @AddressChangedTogether first");
            }
            if (address == null || lat == null || lng == null) {
                // Complete or absent, so one missing field means all are absent.
                return null;
            }
            return new Location(address, lat, lng);
        }
    }

    record CustomerResponse(
            @Schema(example = "5") long id,
            @Schema(example = "Alex Jansen") String name,
            @Schema(example = "20 Prinsengracht, Amsterdam") String address,
            @Schema(example = "52.3667") double lat,
            @Schema(example = "4.8830") double lng) {

        static CustomerResponse of(Customer customer) {
            Location location = customer.location();
            return new CustomerResponse(
                    customer.id(), customer.name(), location.address(), location.lat(), location.lng());
        }
    }

    /** Address, lat and lng are given all together or not at all; each missing one is reported on its own field. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AddressChangedTogetherValidator.class)
    @interface AddressChangedTogether {
        String message() default "is required when changing the location";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static final class AddressChangedTogetherValidator
            implements ConstraintValidator<AddressChangedTogether, UpdateCustomer> {

        @Override
        public boolean isValid(@Nullable UpdateCustomer request, ConstraintValidatorContext context) {
            if (request == null) {
                return true;
            }
            List<String> missing = request.missingLocationFields();
            if (missing.isEmpty()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            for (String field : missing) {
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode(field)
                        .addConstraintViolation();
            }
            return false;
        }
    }
}
