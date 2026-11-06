package io.github.danie1le.dropoff.merchant.internal;

import static io.github.danie1le.dropoff.common.Validated.required;

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

/** Request and response bodies of the merchant endpoints (ticket #9). */
final class MerchantRequests {

    private MerchantRequests() {}

    record CreateMerchant(
            @NotNull @NotBlankIfPresent @Size(max = 200) String name,
            @NotNull @NotBlankIfPresent @Size(max = 500) String address,
            @NotNull @Latitude Double lat,
            @NotNull @Longitude Double lng) {

        /** The location. {@code @Valid} rejects a missing lat or lng before this is called. */
        Location location() {
            return new Location(address, required(lat), required(lng));
        }
    }

    /** Partial update: an absent or null field means unchanged. Address, lat and lng move together (ticket #42). */
    @AddressChangedTogether
    record UpdateMerchant(
            @Nullable @NotBlankIfPresent @Size(max = 200) String name,
            @Nullable Boolean open,
            @Nullable @NotBlankIfPresent @Size(max = 500) String address,
            @Nullable @Latitude Double lat,
            @Nullable @Longitude Double lng) {

        /** True when address, lat and lng are all given or all absent. {@link AddressChangedTogether} checks this. */
        boolean locationIsCompleteOrAbsent() {
            return missingLocationFields().isEmpty();
        }

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
            if (!locationIsCompleteOrAbsent()) {
                throw new IllegalStateException("location is incomplete; validate with @AddressChangedTogether first");
            }
            if (address == null || lat == null || lng == null) {
                // Complete or absent, so one missing field means all are absent.
                return null;
            }
            return new Location(address, lat, lng);
        }
    }

    record MerchantResponse(long id, String name, String address, double lat, double lng, boolean open) {

        static MerchantResponse of(Merchant merchant) {
            return new MerchantResponse(
                    merchant.id(),
                    merchant.name(),
                    merchant.address(),
                    merchant.lat(),
                    merchant.lng(),
                    merchant.isOpen());
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
            implements ConstraintValidator<AddressChangedTogether, UpdateMerchant> {

        @Override
        public boolean isValid(@Nullable UpdateMerchant request, ConstraintValidatorContext context) {
            if (request == null) {
                return true;
            }
            List<String> missing = request.missingLocationFields();
            if (missing.isEmpty()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            for (String field : missing) {
                reportMissing(context, field);
            }
            return false;
        }

        private static void reportMissing(ConstraintValidatorContext context, String field) {
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode(field)
                    .addConstraintViolation();
        }
    }
}
