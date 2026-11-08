package io.github.danie1le.dropoff.common.web;

import io.github.danie1le.dropoff.common.ProblemResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Documents the RFC 9457 Problem Details errors from {@link ApiExceptionHandler} in Swagger (M1-4, ticket #8). Adds a
 * {@code Problem} schema, then gives every operation its error responses: 400 and 500 always, plus the statuses and
 * codes listed by {@link ProblemResponse} on the controller method.
 */
@Configuration(proxyBeanMethods = false)
class ProblemDetailsOpenApi {

    private static final String PROBLEM = "Problem";
    private static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM;
    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    @Bean
    GlobalOpenApiCustomizer problemSchema() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSchemas(PROBLEM, problem());
        };
    }

    @Bean
    GlobalOperationCustomizer problemResponses() {
        return (operation, handlerMethod) -> {
            Map<Integer, Set<String>> codes = new TreeMap<>();
            for (ProblemResponse response : handlerMethod.getMethod().getAnnotationsByType(ProblemResponse.class)) {
                codes.computeIfAbsent(response.status(), status -> new LinkedHashSet<>())
                        .addAll(Arrays.asList(response.codes()));
            }
            Set<String> badRequest = codes.computeIfAbsent(400, status -> new LinkedHashSet<>());
            badRequest.add("VALIDATION_FAILED");
            if (Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(parameter -> parameter.hasParameterAnnotation(RequestBody.class))) {
                badRequest.add("MALFORMED_REQUEST");
            }
            codes.computeIfAbsent(500, status -> new LinkedHashSet<>()).add("INTERNAL_ERROR");

            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            for (Map.Entry<Integer, Set<String>> entry : codes.entrySet()) {
                responses.addApiResponse(String.valueOf(entry.getKey()), response(entry.getKey(), entry.getValue()));
            }
            return operation;
        };
    }

    private static ApiResponse response(int status, Set<String> codes) {
        HttpStatus resolved = HttpStatus.resolve(status);
        String reason = resolved == null ? "Error" : resolved.getReasonPhrase();
        return new ApiResponse()
                .description(reason + ". Problem Details; `code` is one of: " + String.join(", ", codes) + ".")
                .content(new Content()
                        .addMediaType(PROBLEM_JSON, new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))));
    }

    private static Schema<?> problem() {
        Schema<?> violation = new ObjectSchema()
                .addProperty("field", new StringSchema().description("The invalid field, parameter or header."))
                .addProperty("message", new StringSchema().description("Why it is invalid."));
        return new ObjectSchema()
                .description("RFC 9457 Problem Details with a stable `code` to branch on.")
                .addProperty("type", new StringSchema().format("uri").example("about:blank"))
                .addProperty("title", new StringSchema().example("Not Found"))
                .addProperty("status", new IntegerSchema().example(404))
                .addProperty("detail", new StringSchema().example("Merchant 42 does not exist."))
                .addProperty("instance", new StringSchema().format("uri").example("/api/v1/merchants/42"))
                .addProperty(
                        "code",
                        new StringSchema()
                                .description("Stable UPPER_SNAKE error code.")
                                .example("MERCHANT_NOT_FOUND"))
                .addProperty(
                        "errors",
                        new ArraySchema()
                                .items(violation)
                                .description("Each invalid input; only on VALIDATION_FAILED."))
                .required(List.of("type", "title", "status", "detail", "code"));
    }
}
