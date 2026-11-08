package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * NullAway skips packages without {@code @NullMarked}, so every production package must carry it. Each service applies
 * this to its own packages from a thin test.
 */
public final class NullMarkedPackages {

    private NullMarkedPackages() {}

    /** Fails if a production package under the given roots has no {@code @NullMarked} package-info. */
    public static void check(String... roots) {
        List<String> unmarked = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(roots).stream()
                        .map(JavaClass::getPackageName)
                        .distinct()
                        .sorted()
                        .filter(pkg -> !isNullMarked(pkg))
                        .toList();

        assertThat(unmarked)
                .as("packages without a @NullMarked package-info.java")
                .isEmpty();
    }

    private static boolean isNullMarked(String pkg) {
        try {
            return Class.forName(pkg + ".package-info").isAnnotationPresent(NullMarked.class);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
