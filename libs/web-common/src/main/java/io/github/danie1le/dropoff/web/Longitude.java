package io.github.danie1le.dropoff.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/**
 * A longitude in degrees, from -180 to 180 inclusive. NaN and infinity are invalid. Null is valid; add
 * {@code @NotNull} when the value is required.
 */
@Documented
@Constraint(validatedBy = Longitude.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Longitude {
    String message() default "must be between -180 and 180";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks {@link Longitude}. */
    final class Validator implements ConstraintValidator<Longitude, Double> {

        @Override
        public boolean isValid(@Nullable Double value, ConstraintValidatorContext context) {
            return value == null || (Double.isFinite(value) && value >= -180 && value <= 180);
        }
    }
}
