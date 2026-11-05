package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class LongitudeTest {

    record Point(@Nullable @Longitude Double lng) {}

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsTheFullRangeAndNull() {
        assertThat(VALIDATOR.validate(new Point(-180.0))).isEmpty();
        assertThat(VALIDATOR.validate(new Point(0.0))).isEmpty();
        assertThat(VALIDATOR.validate(new Point(180.0))).isEmpty();
        assertThat(VALIDATOR.validate(new Point(null))).isEmpty();
    }

    @Test
    void rejectsValuesBeyondTheAntimeridian() {
        assertThat(VALIDATOR.validate(new Point(-180.01)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be greater than or equal to -180");
        assertThat(VALIDATOR.validate(new Point(180.01)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be less than or equal to 180");
    }
}
