package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
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

    @Test
    void everyOperationHasASummary() {
        OPERATIONS.forEach((path, methods) -> {
            for (String method : methods) {
                Map<String, Object> operation = operation(path, method);
                assertThat(operation.get("summary"))
                        .as("%s %s summary", method, path)
                        .asString()
                        .isNotBlank();
            }
        });
    }

    @Test
    void everyPathParameterHasADescriptionAndAnExample() {
        OPERATIONS.forEach((path, methods) -> {
            for (String method : methods) {
                for (Map<String, Object> parameter : parameters(operation(path, method))) {
                    if ("path".equals(parameter.get("in"))) {
                        assertThat(parameter)
                                .as("%s %s {%s}", method, path, parameter.get("name"))
                                .containsKeys("description", "example");
                    }
                }
            }
        });
    }

    @Test
    void everyOperationHasASuccessResponseAndEveryBodyHasASchema() {
        OPERATIONS.forEach((path, methods) -> {
            for (String method : methods) {
                Map<String, Object> operation = operation(path, method);
                assertThat(responses(operation))
                        .as("%s %s success responses", method, path)
                        .anySatisfy((status, response) -> {
                            assertThat(status).startsWith("2");
                            assertThat(map(response)).containsKey("content");
                        });
                if (operation.containsKey("requestBody")) {
                    assertThat(operation.get("requestBody"))
                            .as("%s %s request body", method, path)
                            .asInstanceOf(InstanceOfAssertFactories.MAP)
                            .containsKey("content");
                }
            }
        });
    }

    @Test
    void everyBodyPropertyHasAnExample() {
        Map<String, Object> schemas = map(map(apiDocsJson().get("components")).get("schemas"));
        assertThat(schemas).isNotEmpty();
        schemas.forEach((name, schema) -> {
            if (name.equals("Problem") || name.startsWith("Paged") || name.equals("PageMetadata")) {
                return;
            }
            map(map(schema).get("properties")).forEach((property, definition) -> {
                Map<String, Object> field = map(definition);
                boolean scalar = field.get("type") instanceof String type
                        && List.of("string", "integer", "number", "boolean").contains(type);
                if (scalar && !field.containsKey("enum")) {
                    assertThat(field).as("%s.%s", name, property).containsKey("example");
                }
            });
        });
    }

    private Map<String, Object> apiDocsJson() {
        try {
            return JsonPath.read(apiDocs().getResponse().getContentAsString(), "$");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> operation(String path, String method) {
        return map(map(map(apiDocsJson().get("paths")).get(path)).get(method));
    }

    private static Map<String, Object> responses(Map<String, Object> operation) {
        return map(operation.get("responses"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parameters(Map<String, Object> operation) {
        Object parameters = operation.get("parameters");
        return parameters == null ? List.of() : (List<Map<String, Object>>) parameters;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
