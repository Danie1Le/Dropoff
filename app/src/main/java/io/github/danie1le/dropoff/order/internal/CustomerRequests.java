package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.Latitude;
import io.github.danie1le.dropoff.common.Longitude;
import io.github.danie1le.dropoff.common.NotBlankIfPresent;
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
            @NotNull @NotBlankIfPresent @Size(max = 200) String name,
            @NotNull @NotBlankIfPresent @Size(max = 500) String address,
            @NotNull @Latitude Double lat,
            @NotNull @Longitude Double lng) {

        /** The location. {@code @Valid} rejects a missing lat or lng before this is called. */
        Location location() {
            if (lat == null || lng == null) {
                throw new IllegalStateException("lat and lng are required; validate with @Valid first");
            }
            return new Location(address, lat, lng);
        }
    }

    /** Partial update: an absent or null field means unchanged. Address, lat and lng move together (ticket #42). */
    @AddressChangedTogether
    record UpdateCustomer(
            @Nullable @NotBlankIfPresent @Size(max = 200) String name,
            @Nullable @NotBlankIfPresent @Size(max = 500) String address,
            @Nullable @Latitude Double lat,
            @Nullable @Longitude Double lng) {

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

    record CustomerResponse(long id, String name, String address, double lat, double lng) {

        static CustomerResponse of(Customer customer) {
            return new CustomerResponse(
                    customer.id(), customer.name(), customer.address(), customer.lat(), customer.lng());
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
