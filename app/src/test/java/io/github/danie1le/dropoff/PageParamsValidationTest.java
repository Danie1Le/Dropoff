package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.danie1le.dropoff.common.PageParams;
import jakarta.validation.Valid;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paging limits only apply when {@link PageParams} is bound with {@code @Valid}, so every controller parameter of that
 * type must carry it. Test classes are imported too, so test controllers count as samples.
 */
class PageParamsValidationTest {

    @Test
    void everyControllerPageParamsParameterIsValidated() {
        List<String> unvalidated = new ClassFileImporter()
                .importPackages("io.github.danie1le.dropoff").stream()
                        .filter(javaClass -> javaClass.isAnnotatedWith(RestController.class))
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(PageParamsValidationTest::hasUnvalidatedPageParams)
                        .map(JavaMethod::getFullName)
                        .sorted()
                        .toList();

        assertThat(unvalidated)
                .as("controller methods taking PageParams without @Valid")
                .isEmpty();
    }

    private static boolean hasUnvalidatedPageParams(JavaMethod method) {
        return method.getParameters().stream()
                .filter(parameter -> parameter.getRawType().isEquivalentTo(PageParams.class))
                .anyMatch(parameter -> !isValid(parameter));
    }

    private static boolean isValid(JavaParameter parameter) {
        return parameter.isAnnotatedWith(Valid.class);
    }
}
