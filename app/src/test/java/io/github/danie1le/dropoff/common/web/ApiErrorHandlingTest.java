package io.github.danie1le.dropoff.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(ProbeController.class)
class ApiErrorHandlingTest {

    private final MockMvcTester mvc;

    ApiErrorHandlingTest(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void servesControllersUnderTheApiV1Prefix() {
        assertThat(mvc.get().uri("/api/v1/probe/items/7")).hasStatusOk();
        assertThat(mvc.get().uri("/probe/items/7")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsNotFoundDomainErrorsTo404WithTheirCode() {
        var result = mvc.get().uri("/api/v1/probe/domain/NOT_FOUND");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PROBE_NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("probe failed with NOT_FOUND");
    }

    @Test
    void mapsConflictDomainErrorsTo409() {
        var result = mvc.get().uri("/api/v1/probe/domain/CONFLICT");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PROBE_CONFLICT");
    }

    @Test
    void mapsUnprocessableDomainErrorsTo422() {
        var result = mvc.get().uri("/api/v1/probe/domain/UNPROCESSABLE");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PROBE_UNPROCESSABLE");
    }

    @Test
    void reportsInvalidBodyFieldsAsValidationFailed() {
        var result = mvc.post()
                .uri("/api/v1/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isNotNull();
    }

    @Test
    void reportsInvalidRequestParametersAsValidationFailed() {
        var result = mvc.get().uri("/api/v1/probe/param?count=0");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("count");
    }

    @Test
    void reportsUnreadableJsonAsMalformedRequestWithoutErrorsList() {
        var result = mvc.post()
                .uri("/api/v1/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        assertThat(result).bodyJson().doesNotHavePath("$.errors");
    }

    @Test
    void reportsPathVariableTypeMismatchAsValidationFailed() {
        var result = mvc.get().uri("/api/v1/probe/items/abc");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("id");
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() {
        var result = mvc.get().uri("/api/v1/probe/boom");

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyText().doesNotContain("secret internal detail", "IllegalStateException");
    }

    @Test
    void givesUnknownRoutesANotFoundCode() {
        var result = mvc.get().uri("/api/v1/no-such-route");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void givesUnsupportedMethodsAMethodNotAllowedCode() {
        var result = mvc.delete().uri("/api/v1/probe/items/7");

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void returnsPagesWithDefaultSizeAndPagedModelShape() {
        var result = mvc.get().uri("/api/v1/probe/page");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.page.size").isEqualTo(20);
        assertThat(result).bodyJson().extractingPath("$.page.number").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.content").asArray().hasSize(2);
    }

    @Test
    void acceptsThePageSizeLimit() {
        assertThat(mvc.get().uri("/api/v1/probe/page?size=100")).hasStatusOk();
    }

    @Test
    void rejectsPageSizesAboveTheLimit() {
        var result = mvc.get().uri("/api/v1/probe/page?size=101");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("size");
    }

    @Test
    void rejectsNegativePageNumbers() {
        var result = mvc.get().uri("/api/v1/probe/page?page=-1");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("page");
    }
}
