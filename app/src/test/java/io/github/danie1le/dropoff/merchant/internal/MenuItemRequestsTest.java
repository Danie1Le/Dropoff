package io.github.danie1le.dropoff.merchant.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.CreateMenuItem;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.UpdateMenuItem;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class MenuItemRequestsTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private static List<String> badFields(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .distinct()
                .toList();
    }

    @Test
    void acceptsANameAndAZeroPrice() {
        assertThat(badFields(new CreateMenuItem("Salsa", BigDecimal.ZERO))).isEmpty();
    }

    @Test
    void rejectsABlankOrMissingName() {
        assertThat(badFields(new CreateMenuItem(" \n ", BigDecimal.ONE))).containsExactly("name");
        assertThat(badFields(new CreateMenuItem(null, BigDecimal.ONE))).containsExactly("name");
    }

    @Test
    void acceptsA200CharacterNameAndRejectsA201CharacterName() {
        assertThat(badFields(new CreateMenuItem("x".repeat(200), BigDecimal.ONE)))
                .isEmpty();
        assertThat(badFields(new CreateMenuItem("x".repeat(201), BigDecimal.ONE)))
                .containsExactly("name");
    }

    @Test
    void rejectsAMissingNegativeOrDecimalPrice() {
        assertThat(badFields(new CreateMenuItem("Salsa", null))).containsExactly("priceCents");
        assertThat(badFields(new CreateMenuItem("Salsa", new BigDecimal("-1")))).containsExactly("priceCents");
        assertThat(badFields(new CreateMenuItem("Salsa", new BigDecimal("9.99"))))
                .containsExactly("priceCents");
    }

    @Test
    void rejectsAPriceTooLargeForALong() {
        assertThat(badFields(new CreateMenuItem("Salsa", new BigDecimal("9223372036854775808"))))
                .containsExactly("priceCents");
    }

    @Test
    void givesTheTrimmedNameAndTheWholePrice() {
        CreateMenuItem request = new CreateMenuItem("  Salsa ", new BigDecimal("399"));

        assertThat(request.trimmedName()).isEqualTo("Salsa");
        assertThat(request.priceCentsValue()).isEqualTo(399L);
    }

    @Test
    void unvalidatedRequestWithoutAPriceThrows() {
        assertThatThrownBy(() -> new CreateMenuItem("Salsa", null).priceCentsValue())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a required field is missing; validate the request with @Valid first");
    }

    @Test
    void acceptsAnEmptyUpdate() {
        assertThat(badFields(new UpdateMenuItem(null, null, null))).isEmpty();
    }

    @Test
    void rejectsABlankOrOver200CharacterNameOnUpdate() {
        assertThat(badFields(new UpdateMenuItem(" ", null, null))).containsExactly("name");
        assertThat(badFields(new UpdateMenuItem("x".repeat(201), null, null))).containsExactly("name");
        assertThat(badFields(new UpdateMenuItem("x".repeat(200), null, null))).isEmpty();
    }

    @Test
    void rejectsANegativeDecimalOrTooLargePriceOnUpdate() {
        assertThat(badFields(new UpdateMenuItem(null, new BigDecimal("-1"), null)))
                .containsExactly("priceCents");
        assertThat(badFields(new UpdateMenuItem(null, new BigDecimal("9.99"), null)))
                .containsExactly("priceCents");
        assertThat(badFields(new UpdateMenuItem(null, new BigDecimal("9223372036854775808"), null)))
                .containsExactly("priceCents");
    }

    @Test
    void givesTheTrimmedNameAndWholePriceOnUpdateOrNullWhenAbsent() {
        UpdateMenuItem request = new UpdateMenuItem(" Salsa  ", new BigDecimal("399"), null);

        assertThat(request.trimmedName()).isEqualTo("Salsa");
        assertThat(request.priceCentsValue()).isEqualTo(399L);
        assertThat(new UpdateMenuItem(null, null, null).trimmedName()).isNull();
        assertThat(new UpdateMenuItem(null, null, null).priceCentsValue()).isNull();
    }
}
