package io.github.danie1le.dropoff.web;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gives every path parameter a description and an example in Swagger (M8-1, ticket #8). A parameter called
 * {@code merchantId} is "Id of the merchant"; a bare {@code id} is named after the path segment before it, so
 * {@code /menu-items/{id}} says "Id of the menu item". A description set by hand with {@code @Parameter} is kept.
 */
@Configuration(proxyBeanMethods = false)
class PathParameterDocs {

    @Bean
    GlobalOpenApiCustomizer describePathParameters() {
        return openApi -> {
            Map<String, PathItem> paths = openApi.getPaths();
            if (paths == null) {
                return;
            }
            paths.forEach((path, item) -> {
                for (Operation operation : item.readOperations()) {
                    describe(path, operation.getParameters());
                }
                describe(path, item.getParameters());
            });
        };
    }

    private static void describe(String path, List<Parameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (Parameter parameter : parameters) {
            if (!"path".equals(parameter.getIn())) {
                continue;
            }
            if (parameter.getDescription() == null) {
                parameter.setDescription("Id of the " + resource(path, parameter.getName()) + ".");
            }
            if (parameter.getExample() == null && parameter.getExamples() == null) {
                parameter.setExample(1);
            }
        }
    }

    /** {@code merchantId} gives "merchant"; {@code id} gives the singular of the segment before {@code {id}}. */
    private static String resource(String path, String name) {
        if (name.endsWith("Id") && name.length() > 2) {
            return words(name.substring(0, name.length() - 2));
        }
        String[] segments = path.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            if (segments[i].equals("{" + name + "}")) {
                String previous = segments[i - 1];
                return previous.endsWith("s")
                        ? previous.substring(0, previous.length() - 1).replace('-', ' ')
                        : previous;
            }
        }
        return name;
    }

    /** {@code menuItem} becomes "menu item". */
    private static String words(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
    }
}
