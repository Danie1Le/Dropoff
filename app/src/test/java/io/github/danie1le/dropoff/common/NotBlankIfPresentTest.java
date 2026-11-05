package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class NotBlankIfPresentTest {

    record Named(@Nullable @NotBlankIfPresent String name) {}

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsNullAndText() {
        assertThat(VALIDATOR.validate(new Named(null))).isEmpty();
        assertThat(VALIDATOR.validate(new Named("Taco Shop"))).isEmpty();
        assertThat(VALIDATOR.validate(new Named("  Taco Shop  "))).isEmpty();
    }

    @Test
    void acceptsTextWithALineBreak() {
        assertThat(VALIDATOR.validate(new Named("Taco\nShop"))).isEmpty();
    }

    @Test
    void rejectsEmptyAndWhitespaceOnlyText() {
        assertThat(VALIDATOR.validate(new Named("")))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must not be blank");
        assertThat(VALIDATOR.validate(new Named(" \n\t ")))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must not be blank");
    }
}
