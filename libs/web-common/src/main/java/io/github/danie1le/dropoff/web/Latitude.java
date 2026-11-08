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
 * A latitude in degrees, from -90 to 90 inclusive. NaN and infinity are invalid. Null is valid; add
 * {@code @NotNull} when the value is required.
 */
@Documented
@Constraint(validatedBy = Latitude.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Latitude {
    String message() default "must be between -90 and 90";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks {@link Latitude}. */
    final class Validator implements ConstraintValidator<Latitude, Double> {

        @Override
        public boolean isValid(@Nullable Double value, ConstraintValidatorContext context) {
            return value == null || (Double.isFinite(value) && value >= -90 && value <= 90);
        }
    }
}
