package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.danie1le.dropoff.web.PageParams;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paging limits only apply when {@link PageParams} is bound with {@code @Valid}, so every controller parameter of that
 * type must carry it. Test classes are imported too, so test controllers count as samples. {@code PageParams} lives in
 * libs/web-common (M5-2).
 *
 * <p>Each service applies it to its own packages from a thin test, naming one controller method it knows takes
 * {@code PageParams}, so a scan that finds nothing fails instead of passing.
 */
public final class PageParamsRule {

    private PageParamsRule() {}

    /**
     * Fails if a {@code @RestController} method under {@code rootPackage} takes {@code PageParams} without
     * {@code @Valid}, or if {@code knownMethod} ({@code Controller.method}) is not among those found.
     */
    public static void check(String rootPackage, String knownMethod) {
        List<JavaMethod> pageParamsMethods = new ClassFileImporter()
                .importPackages(rootPackage).stream()
                        .filter(javaClass -> javaClass.isAnnotatedWith(RestController.class))
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(PageParamsRule::hasPageParams)
                        .toList();

        assertThat(pageParamsMethods)
                .extracting(method -> method.getOwner().getSimpleName() + "." + method.getName())
                .as("@RestController methods taking PageParams; none found means the scan checked nothing")
                .contains(knownMethod);

        List<String> unvalidated = pageParamsMethods.stream()
                .filter(PageParamsRule::hasUnvalidatedPageParams)
                .map(JavaMethod::getFullName)
                .sorted()
                .toList();

        assertThat(unvalidated)
                .as("controller methods taking PageParams without @Valid")
                .isEmpty();
    }

    private static boolean hasPageParams(JavaMethod method) {
        return method.getParameters().stream().anyMatch(PageParamsRule::isPageParams);
    }

    private static boolean hasUnvalidatedPageParams(JavaMethod method) {
        return method.getParameters().stream()
                .filter(PageParamsRule::isPageParams)
                .anyMatch(parameter -> !isValid(parameter));
    }

    private static boolean isPageParams(JavaParameter parameter) {
        return parameter.getRawType().isEquivalentTo(PageParams.class);
    }

    private static boolean isValid(JavaParameter parameter) {
        return parameter.isAnnotatedWith(Valid.class);
    }
}
