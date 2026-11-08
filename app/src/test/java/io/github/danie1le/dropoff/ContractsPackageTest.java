package io.github.danie1le.dropoff;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The {@code contracts} package stays plain data that any module can depend on (ADR-0008): records and enums only, with
 * no Spring and no dependency on another Dropoff package. M5-2 moves it into a Gradle module with no Spring on its
 * classpath, so anything else here would break that step.
 */
class ContractsPackageTest {

    private static final String CONTRACTS = "io.github.danie1le.dropoff.contracts..";

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.danie1le.dropoff");

    @Test
    void holdsOnlyRecordsAndEnums() {
        classes()
                .that()
                .resideInAPackage(CONTRACTS)
                .and()
                .doNotHaveSimpleName("package-info")
                .and()
                .areNotAnonymousClasses()
                .should()
                .beRecords()
                .orShould()
                .beEnums()
                .check(production);
    }

    @Test
    void dependsOnNoSpringAndNoOtherModule() {
        noClasses()
                .that()
                .resideInAPackage(CONTRACTS)
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "are Spring or another Dropoff package",
                        (JavaClass target) -> target.getPackageName().startsWith("org.springframework")
                                || (target.getPackageName().startsWith("io.github.danie1le.dropoff")
                                        && !target.getPackageName()
                                                .startsWith("io.github.danie1le.dropoff.contracts"))))
                .check(production);
    }
}
