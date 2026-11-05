package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class LatitudeTest {

    record Point(@Nullable @Latitude Double lat) {}

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsTheFullRangeAndNull() {
        assertThat(VALIDATOR.validate(new Point(-90.0))).isEmpty();
        assertThat(VALIDATOR.validate(new Point(0.0))).isEmpty();
        assertThat(VALIDATOR.validate(new Point(90.0))).isEmpty();
        assertThat(VALIDATOR.validate(new Point(null))).isEmpty();
    }

    @Test
    void rejectsValuesBeyondThePoles() {
        assertThat(VALIDATOR.validate(new Point(-90.01)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be between -90 and 90");
        assertThat(VALIDATOR.validate(new Point(90.01)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be between -90 and 90");
    }

    @Test
    void rejectsNaN() {
        assertThat(VALIDATOR.validate(new Point(Double.NaN)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be between -90 and 90");
    }

    @Test
    void rejectsInfinity() {
        assertThat(VALIDATOR.validate(new Point(Double.POSITIVE_INFINITY)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be between -90 and 90");
    }
}
