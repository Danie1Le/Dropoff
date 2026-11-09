package io.github.danie1le.dropoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.assertj.core.api.InstanceOfAssertFactories;
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

        liveOperations().forEach((path, methods) -> {
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

    @Test
    void everyOperationHasASummary() {
        liveOperations().forEach((path, methods) -> {
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
        liveOperations().forEach((path, methods) -> {
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
        liveOperations().forEach((path, methods) -> {
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
        schemas.forEach((name, schema) -> checkExamples(name, schema, schemas, new HashSet<>()));
    }

    /**
     * Problem, the Paged* wrappers and PageMetadata are shaped by springdoc and Spring Data, not by us, and an enum
     * lists its own values. Every other scalar must carry an example, also inside arrays, nested objects and
     * {@code $ref} schemas.
     */
    private static void checkExamples(String where, Object schema, Map<String, Object> schemas, Set<String> seen) {
        String name = where.contains(".") ? where.substring(0, where.indexOf('.')) : where;
        if (name.equals("Problem") || name.startsWith("Paged") || name.equals("PageMetadata")) {
            return;
        }
        map(map(schema).get("properties")).forEach((property, definition) -> {
            Map<String, Object> field = map(definition);
            String at = where + "." + property;
            if (field.get("$ref") instanceof String ref) {
                String target = ref.substring(ref.lastIndexOf('/') + 1);
                if (seen.add(target)) {
                    checkExamples(target, schemas.get(target), schemas, seen);
                }
            } else if ("array".equals(field.get("type"))) {
                Map<String, Object> items = map(field.get("items"));
                if (items.get("$ref") instanceof String ref) {
                    String target = ref.substring(ref.lastIndexOf('/') + 1);
                    if (seen.add(target)) {
                        checkExamples(target, schemas.get(target), schemas, seen);
                    }
                } else if (isScalar(items) && !items.containsKey("enum")) {
                    assertThat(items.containsKey("example") || field.containsKey("example"))
                            .as(at)
                            .isTrue();
                } else if (items.containsKey("properties")) {
                    checkExamples(at, items, schemas, seen);
                }
            } else if ("object".equals(field.get("type")) && field.containsKey("properties")) {
                checkExamples(at, field, schemas, seen);
            } else if (isScalar(field) && !field.containsKey("enum")) {
                assertThat(field).as(at).containsKey("example");
            }
        });
    }

    private static boolean isScalar(Map<String, Object> field) {
        return field.get("type") instanceof String type
                && List.of("string", "integer", "number", "boolean").contains(type);
    }

    /** Every path and method of the live document, not the hand-written OPERATIONS map. */
    private Map<String, List<String>> liveOperations() {
        Map<String, List<String>> live = new TreeMap<>();
        map(apiDocsJson().get("paths"))
                .forEach((path, item) -> live.put(
                        path,
                        map(item).keySet().stream()
                                .filter(key -> List.of("get", "put", "post", "delete", "patch")
                                        .contains(key))
                                .toList()));
        assertThat(live).isNotEmpty();
        return live;
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
