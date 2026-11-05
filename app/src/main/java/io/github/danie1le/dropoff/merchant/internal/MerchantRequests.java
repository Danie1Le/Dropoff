package io.github.danie1le.dropoff.merchant.internal;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the merchant endpoints (ticket #9). */
final class MerchantRequests {

    private static final String NOT_BLANK = ".*\\S.*";

    private MerchantRequests() {}

    record CreateMerchant(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 500) String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {}

    /** Partial update: only the given fields change. Address, lat and lng move together (ticket #42). */
    @AddressChangedTogether
    record UpdateMerchant(
            @Nullable @Pattern(regexp = NOT_BLANK, message = "must not be blank") @Size(max = 200)
            String name,

            @Nullable Boolean open,

            @Nullable @Pattern(regexp = NOT_BLANK, message = "must not be blank") @Size(max = 500)
            String address,

            @Nullable @DecimalMin("-90") @DecimalMax("90") Double lat,
            @Nullable @DecimalMin("-180") @DecimalMax("180") Double lng) {}

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

    /** Address, lat and lng are given all together or not at all; reported on the {@code address} field. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AddressChangedTogetherValidator.class)
    @interface AddressChangedTogether {
        String message() default "address, lat and lng must be changed together";

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
            int given = (request.address() == null ? 0 : 1)
                    + (request.lat() == null ? 0 : 1)
                    + (request.lng() == null ? 0 : 1);
            if (given == 0 || given == 3) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("address")
                    .addConstraintViolation();
            return false;
        }
    }
}
