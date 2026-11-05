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

/** A latitude in degrees, from -90 to 90 inclusive. Null is valid; add {@code @NotNull} when the value is required. */
@DecimalMin("-90")
@DecimalMax("90")
@ReportAsSingleViolation
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Latitude {
    String message() default "must be between -90 and 90";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
