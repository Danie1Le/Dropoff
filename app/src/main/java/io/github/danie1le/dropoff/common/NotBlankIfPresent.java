package io.github.danie1le.dropoff.common;

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
 * The project's blank check for text: null is valid, otherwise the text must not be empty after
 * {@link String#trim()}. That is the same rule as the trimming done before storage, so a value that passes is never
 * stored empty. Line breaks inside the text are allowed. Use it alone on optional text, such as a PATCH field, and
 * with {@code @NotNull} on required text. Don't use Jakarta {@code @NotBlank}: its whitespace rule differs from
 * {@code trim()}.
 */
@Documented
@Constraint(validatedBy = NotBlankIfPresent.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotBlankIfPresent {
    String message() default "must not be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks {@link NotBlankIfPresent}. */
    final class Validator implements ConstraintValidator<NotBlankIfPresent, CharSequence> {

        @Override
        public boolean isValid(@Nullable CharSequence value, ConstraintValidatorContext context) {
            return value == null || !value.toString().trim().isEmpty();
        }
    }
}
