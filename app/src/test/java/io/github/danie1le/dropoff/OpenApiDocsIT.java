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

    /** Every Dropoff endpoint on main, path to HTTP methods. Test-only controllers are left out. */
    private static final Map<String, List<String>> OPERATIONS = Map.ofEntries(
            Map.entry("/api/v1/merchants", List.of("get", "post")),
            Map.entry("/api/v1/merchants/{id}", List.of("get", "patch")),
            Map.entry("/api/v1/merchants/{id}/menu-items", List.of("post")),
            Map.entry("/api/v1/merchants/{id}/menu", List.of("get")),
            Map.entry("/api/v1/menu-items/{id}", List.of("get", "patch")),
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
            Map.entry("/api/v1/couriers", List.of("post")),
            Map.entry("/api/v1/couriers/{id}", List.of("get")),
            Map.entry("/api/v1/couriers/{id}/status", List.of("patch")),
            Map.entry("/api/v1/couriers/{id}/location", List.of("put")),
            Map.entry("/api/v1/couriers/{courierId}/offers/current", List.of("get")),
            Map.entry("/api/v1/couriers/{courierId}/offers/{offerId}/accept", List.of("post")),
            Map.entry("/api/v1/couriers/{courierId}/offers/{offerId}/decline", List.of("post")));

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
                .extractingPath("$.paths['/api/v1/merchants/{id}'].get.responses.404.description")
                .asString()
                .contains("MERCHANT_NOT_FOUND");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/merchants/{merchantId}/orders/{orderId}/accept'].post.responses")
                .asMap()
                .containsKeys("200", "400", "404", "409", "500");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/couriers/{id}/status'].patch.responses.409.description")
                .asString()
                .contains("COURIER_LOCATION_UNKNOWN", "COURIER_BUSY", "COURIER_HAS_ACTIVE_DELIVERY");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.paths['/api/v1/couriers/{courierId}/offers/current'].get.responses")
                .asMap()
                .containsKeys("200", "204", "404");
        for (String command : List.of("accept", "decline")) {
            String responses =
                    "$.paths['/api/v1/couriers/{courierId}/offers/{offerId}/%s'].post.responses".formatted(command);
            assertThat(result)
                    .bodyJson()
                    .extractingPath(responses + ".404.description")
                    .asString()
                    .contains("COURIER_NOT_FOUND", "OFFER_NOT_FOUND");
            assertThat(result)
                    .bodyJson()
                    .extractingPath(responses + ".409.description")
                    .asString()
                    .contains("OFFER_CLOSED");
        }
        assertThat(result)
                .bodyJson()
                .extractingPath(
                        "$.paths['/api/v1/couriers/{courierId}/offers/{offerId}/accept'].post.responses.409.description")
                .asString()
                .contains("COURIER_NOT_BUSY");
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
                .extractingPath("$.paths['/api/v1/merchants/{id}'].get.responses.400.description")
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

        for (String path : List.of(
                "/api/v1/merchants",
                "/api/v1/merchants/{id}/menu-items",
                "/api/v1/customers",
                "/api/v1/orders",
                "/api/v1/couriers")) {
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
                .extractingPath("$.paths['/api/v1/merchants'].get.parameters[*].name")
                .asArray()
                .contains("page", "size");
    }
}
