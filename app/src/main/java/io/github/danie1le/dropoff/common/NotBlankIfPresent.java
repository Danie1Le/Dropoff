package io.github.danie1le.dropoff.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For optional text, such as a PATCH field: null is valid, otherwise the text must contain a non-whitespace character.
 * Line breaks are allowed, as with {@code @NotBlank}.
 */
@Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotBlankIfPresent {
    String message() default "must not be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
