package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** merchant-service's OpenAPI document, group v1 (M1-4, M5-5): every endpoint and its Problem Details errors. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsIT {

    private static final String PROBLEM = "#/components/schemas/Problem";

    /**
     * Every merchant-service endpoint, path to HTTP methods. A merchant's order list and order commands stay with
     * order-service ({@code /merchants/{id}/orders...}), since they read and move orders.
     */
    private static final Map<String, List<String>> OPERATIONS = Map.ofEntries(
            Map.entry("/api/v1/merchants", List.of("get", "post")),
            Map.entry("/api/v1/merchants/{id}", List.of("get", "patch")),
            Map.entry("/api/v1/merchants/{id}/menu-items", List.of("post")),
            Map.entry("/api/v1/merchants/{id}/menu", List.of("get")),
            Map.entry("/api/v1/menu-items/{id}", List.of("get", "patch")));

    private final MockMvcTester mvc;

    OpenApiDocsIT(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    private MvcTestResult apiDocs() {
        return mvc.get().uri("/v3/api-docs/v1").exchange();
    }

    @Test
    void listsEveryEndpointUnderApiV1() {
        MvcTestResult result = apiDocs();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths")
                .asMap()
                .containsOnlyKeys(OPERATIONS.keySet().toArray(String[]::new));
        OPERATIONS.forEach((path, methods) -> assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['" + path + "']")
                .asMap()
                .as(path)
                .containsOnlyKeys(methods));
    }

    @Test
    void servesSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void everyOperationDocumentsValidationAndServerErrors() {
        MvcTestResult result = apiDocs();

        OPERATIONS.forEach((path, methods) -> {
            for (String method : methods) {
                for (String status : List.of("400", "500")) {
                    assertThat(result)
                            .bodyJson()
                            .extractingPath(
                                    "$.paths['%s'].%s.responses.%s.content['application/problem+json'].schema.$ref"
                                            .formatted(path, method, status))
                            .as("%s %s %s", method, path, status)
                            .isEqualTo(PROBLEM);
                }
            }
        });
    }

    @Test
    void documentsEachEndpointsErrorCodes() {
        MvcTestResult result = apiDocs();

        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/merchants/{id}'].get.responses.404.description")
                .asString()
                .contains("MERCHANT_NOT_FOUND");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/merchants/{id}'].get.responses.400.description")
                .asString()
                .contains("VALIDATION_FAILED")
                .doesNotContain("MALFORMED_REQUEST");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/menu-items/{id}'].get.responses.404.description")
                .asString()
                .contains("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void documentsCreatesAs201() {
        MvcTestResult result = apiDocs();

        for (String path : List.of("/api/v1/merchants", "/api/v1/merchants/{id}/menu-items")) {
            assertThat(result)
                    .bodyJson()
                    .extractingPath("$.paths['" + path + "'].post.responses")
                    .asMap()
                    .as(path)
                    .containsKey("201")
                    .doesNotContainKey("200");
        }
    }

    @Test
    void flattensPagingIntoQueryParameters() {
        assertThat(apiDocs())
                .bodyJson()
                .extractingPath("$.paths['/api/v1/merchants'].get.parameters[*].name")
                .asArray()
                .contains("page", "size");
    }
}
