package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.Latitude;
import io.github.danie1le.dropoff.common.Longitude;
import io.github.danie1le.dropoff.common.NotBlankIfPresent;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the merchant endpoints (ticket #9). */
final class MerchantRequests {

    private MerchantRequests() {}

    record CreateMerchant(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 500) String address,
            @NotNull @Latitude Double lat,
            @NotNull @Longitude Double lng) {

        Location location() {
            return new Location(address, lat, lng);
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

        /** The new location, or null when it doesn't change. {@link AddressChangedTogether} rejects a partial one. */
        @Nullable
        Location location() {
            if (address == null && lat == null && lng == null) {
                return null;
            }
            if (address == null || lat == null || lng == null) {
                throw new IllegalStateException("address, lat and lng must be given together");
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
            boolean hasAddress = request.address() != null;
            boolean hasLat = request.lat() != null;
            boolean hasLng = request.lng() != null;
            if (hasAddress == hasLat && hasLat == hasLng) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            if (!hasAddress) {
                reportMissing(context, "address");
            }
            if (!hasLat) {
                reportMissing(context, "lat");
            }
            if (!hasLng) {
                reportMissing(context, "lng");
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
