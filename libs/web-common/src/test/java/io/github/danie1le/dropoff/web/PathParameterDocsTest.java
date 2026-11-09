package io.github.danie1le.dropoff.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathParameterDocsTest {

    private static Parameter pathParameter(String name) {
        return new PathParameter().name(name);
    }

    private static OpenAPI docs(String path, Parameter... parameters) {
        Operation get = new Operation().parameters(List.of(parameters));
        return new OpenAPI().paths(new Paths().addPathItem(path, new PathItem().get(get)));
    }

    private static Parameter first(OpenAPI openApi, String path) {
        return openApi.getPaths().get(path).getGet().getParameters().get(0);
    }

    @Test
    void fillsABareParameterFromItsName() {
        OpenAPI openApi = docs("/orders/{orderId}", pathParameter("orderId"));

        PathParameterDocs.describe(openApi);

        Parameter parameter = first(openApi, "/orders/{orderId}");
        assertThat(parameter.getDescription()).isEqualTo("Id of the order.");
        assertThat(parameter.getExample()).isEqualTo(1042L);
    }

    @Test
    void keepsHandWrittenDescriptionAndExample() {
        Parameter written = pathParameter("orderId");
        written.setDescription("Mine.");
        written.setExample(99);
        OpenAPI openApi = docs("/orders/{orderId}", written);

        PathParameterDocs.describe(openApi);

        Parameter parameter = first(openApi, "/orders/{orderId}");
        assertThat(parameter.getDescription()).isEqualTo("Mine.");
        assertThat(parameter.getExample()).isEqualTo(99);
    }

    @Test
    void namesABareIdAfterThePathSegment() {
        OpenAPI openApi = docs("/menu-items/{id}", pathParameter("id"));

        PathParameterDocs.describe(openApi);

        Parameter parameter = first(openApi, "/menu-items/{id}");
        assertThat(parameter.getDescription()).isEqualTo("Id of the menu item.");
        assertThat(parameter.getExample()).isEqualTo(12L);
    }

    @Test
    void singularisesIesPlurals() {
        OpenAPI openApi = docs("/categories/{id}", pathParameter("id"));

        PathParameterDocs.describe(openApi);

        Parameter parameter = first(openApi, "/categories/{id}");
        assertThat(parameter.getDescription()).isEqualTo("Id of the category.");
        assertThat(parameter.getExample()).isEqualTo(1L);
    }

    @Test
    void ignoresNonPathParameters() {
        OpenAPI openApi = docs("/orders", new Parameter().in("query").name("status"));

        PathParameterDocs.describe(openApi);

        assertThat(first(openApi, "/orders").getDescription()).isNull();
        assertThat(first(openApi, "/orders").getExample()).isNull();
    }
}
