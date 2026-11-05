package io.github.danie1le.dropoff.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A longitude in degrees, from -180 to 180 inclusive. Null is valid; add {@code @NotNull} when the value is required. */
@DecimalMin("-180")
@DecimalMax("180")
@ReportAsSingleViolation
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Longitude {
    String message() default "must be between -180 and 180";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
