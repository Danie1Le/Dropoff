package io.github.danie1le.dropoff.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The {@code contracts} module stays plain data that every module and service can depend on (ADR-0008): records and
 * enums only, with no Spring and no dependency on another Dropoff package. Its Gradle module has no dependencies, so a
 * Spring or app import fails to compile; these rules keep the module's shape and guard that classpath.
 */
class ContractsPackageTest {

    private static final String CONTRACTS = "io.github.danie1le.dropoff.contracts";

    private final JavaClasses production = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CONTRACTS);

    @Test
    void importsTheEventRecords() {
        assertThat(production.contain(OrderPlaced.class))
                .as("the import found the contracts classes; an empty import checks nothing")
                .isTrue();
    }

    @Test
    void holdsOnlyRecordsAndEnums() {
        classes()
                .that()
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
    void dependsOnNoSpringAndNoOtherDropoffPackage() {
        noClasses()
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "are Spring or another Dropoff package",
                        (JavaClass target) -> target.getPackageName().startsWith("org.springframework")
                                || (target.getPackageName().startsWith("io.github.danie1le.dropoff")
                                        && !target.getPackageName().startsWith(CONTRACTS))))
                .check(production);
    }

    @Test
    void hasNoSpringOnItsClasspath() {
        assertThatExceptionOfType(ClassNotFoundException.class)
                .as("Spring must not be on the contracts classpath (decision #31)")
                .isThrownBy(() -> Class.forName("org.springframework.core.SpringVersion"));
    }
}
