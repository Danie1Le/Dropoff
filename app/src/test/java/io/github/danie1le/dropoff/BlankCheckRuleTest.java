package io.github.danie1le.dropoff;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noConstructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

/**
 * Jakarta {@code @NotBlank} uses its own whitespace rule, which can pass text that {@code trim()} empties before
 * storage. Production code must use {@code @NotNull @NotBlankIfPresent} instead. The rule checks declarations
 * (fields, record components, methods, constructors, parameters), not type-use positions such as
 * {@code List<@NotBlank String>}, and not custom annotations that compose {@code @NotBlank}; review covers those.
 */
class BlankCheckRuleTest {

    private static final String BECAUSE =
            "use @NotNull @NotBlankIfPresent: Jakarta @NotBlank disagrees with the trim() used before storing";

    @Test
    void productionCodeUsesTheTrimBasedBlankCheck() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.danie1le.dropoff");

        noFields().should().beAnnotatedWith(NotBlank.class).because(BECAUSE).check(production);
        noMethods().should().beAnnotatedWith(NotBlank.class).because(BECAUSE).check(production);
        noConstructors()
                .should()
                .beAnnotatedWith(NotBlank.class)
                .because(BECAUSE)
                .check(production);
        codeUnits()
                .should(haveNoParameterAnnotatedWithNotBlank())
                .because(BECAUSE)
                .check(production);
    }

    private static ArchCondition<JavaCodeUnit> haveNoParameterAnnotatedWithNotBlank() {
        return new ArchCondition<>("have no parameter annotated with @NotBlank") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                for (JavaParameter parameter : codeUnit.getParameters()) {
                    if (parameter.isAnnotatedWith(NotBlank.class)) {
                        events.add(SimpleConditionEvent.violated(
                                codeUnit, parameter.getDescription() + " is annotated with @NotBlank"));
                    }
                }
            }
        };
    }
}
