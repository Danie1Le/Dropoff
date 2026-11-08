package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The OpenAPI document behind Swagger UI, group v1 (M1-4): every endpoint and its Problem Details errors. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsIT {

    private static final String PROBLEM = "#/components/schemas/Problem";

    /**
     * Every endpoint of the app, path to HTTP methods. Test-only controllers are left out. Courier, location and offer
     * endpoints moved to dispatch-service in M5-4, and merchant and menu item endpoints to merchant-service in M5-5
     * (each with its own OpenApiDocsIT). A courier's pick-up and deliver, and a merchant's order list and order
     * commands, stay here.
     */
    private static final Map<String, List<String>> OPERATIONS = Map.ofEntries(
            Map.entry("/api/v1/customers", List.of("post")),
            Map.entry("/api/v1/customers/{id}", List.of("get", "patch")),
            Map.entry("/api/v1/orders", List.of("post")),
            Map.entry("/api/v1/orders/{id}", List.of("get")),
            Map.entry("/api/v1/merchants/{id}/orders", List.of("get")),
            Map.entry("/api/v1/customers/{id}/orders", List.of("get")),
            Map.entry("/api/v1/merchants/{merchantId}/orders/{orderId}/accept", List.of("post")),
            Map.entry("/api/v1/merchants/{merchantId}/orders/{orderId}/start-preparing", List.of("post")),
            Map.entry("/api/v1/merchants/{merchantId}/orders/{orderId}/ready", List.of("post")),
            Map.entry("/api/v1/merchants/{merchantId}/orders/{orderId}/cancel", List.of("post")),
            Map.entry("/api/v1/customers/{customerId}/orders/{orderId}/cancel", List.of("post")),
            Map.entry("/api/v1/couriers/{courierId}/orders/{orderId}/pick-up", List.of("post")),
            Map.entry("/api/v1/couriers/{courierId}/orders/{orderId}/deliver", List.of("post")));

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
                .containsKeys(OPERATIONS.keySet().toArray(String[]::new))
                .doesNotContainKey("/actuator/health");
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
    void describesTheProblemDetailsBody() {
        assertThat(apiDocs())
                .bodyJson()
                .extractingPath("$.components.schemas.Problem.properties")
                .asMap()
                .containsKeys("type", "title", "status", "detail", "instance", "code", "errors");
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
                .extractingPath("$.paths['/api/v1/orders/{id}'].get.responses.404.description")
                .asString()
                .contains("ORDER_NOT_FOUND");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/merchants/{merchantId}/orders/{orderId}/accept'].post.responses")
                .asMap()
                .containsKeys("200", "400", "404", "409", "500");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders'].post.responses.422.description")
                .asString()
                .contains(
                        "CUSTOMER_UNKNOWN",
                        "MERCHANT_UNKNOWN",
                        "MERCHANT_CLOSED",
                        "MENU_ITEM_UNKNOWN",
                        "MENU_ITEM_WRONG_MERCHANT",
                        "MENU_ITEM_UNAVAILABLE",
                        "IDEMPOTENCY_KEY_REUSED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders'].post.responses.400.description")
                .asString()
                .contains("IDEMPOTENCY_KEY_REQUIRED", "VALIDATION_FAILED", "MALFORMED_REQUEST");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders/{id}'].get.responses.400.description")
                .asString()
                .contains("VALIDATION_FAILED")
                .doesNotContain("MALFORMED_REQUEST");
    }

    @Test
    void documentsTheIdempotencyKeyAsARequiredHeader() {
        assertThat(apiDocs())
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders'].post.parameters[?(@.name == 'Idempotency-Key')]")
                .asArray()
                .singleElement(InstanceOfAssertFactories.MAP)
                .containsEntry("in", "header")
                .containsEntry("required", true);
    }

    @Test
    void documentsCreatesAs201() {
        MvcTestResult result = apiDocs();

        for (String path : List.of("/api/v1/customers", "/api/v1/orders")) {
            assertThat(result)
                    .bodyJson()
                    .extractingPath("$.paths['" + path + "'].post.responses")
                    .asMap()
                    .as(path)
                    .containsKey("201")
                    .doesNotContainKey("200");
        }
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders'].post.responses.201.headers")
                .asMap()
                .containsKeys("Location", "Idempotent-Replayed");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders'].post.responses.201.content")
                .asMap()
                .containsOnlyKeys("application/json");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/orders'].post.responses.201.content['application/json'].schema.$ref")
                .isEqualTo("#/components/schemas/OrderResponse");
    }

    @Test
    void flattensPagingIntoQueryParameters() {
        assertThat(apiDocs())
                .bodyJson()
                .extractingPath("$.paths['/api/v1/customers/{id}/orders'].get.parameters[*].name")
                .asArray()
                .contains("page", "size");
    }
}
