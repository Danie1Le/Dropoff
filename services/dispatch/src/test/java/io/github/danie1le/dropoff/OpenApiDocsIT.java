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

/** dispatch-service's OpenAPI document, group v1 (M1-4, M5-4): every endpoint and its Problem Details errors. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DispatchTestcontainers.class)
class OpenApiDocsIT {

    private static final String PROBLEM = "#/components/schemas/Problem";

    /**
     * Every dispatch-service endpoint, path to HTTP methods. A courier's pick-up and deliver stay with the order side
     * ({@code /couriers/{courierId}/orders/...}), since they move the order.
     */
    private static final Map<String, List<String>> OPERATIONS = Map.ofEntries(
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
    }

    @Test
    void documentsCourierCreateAs201() {
        assertThat(apiDocs())
                .bodyJson()
                .extractingPath("$.paths['/api/v1/couriers'].post.responses")
                .asMap()
                .containsKey("201")
                .doesNotContainKey("200");
    }
}
