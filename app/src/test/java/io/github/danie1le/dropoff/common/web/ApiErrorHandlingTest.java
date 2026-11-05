package io.github.danie1le.dropoff.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

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
        MvcTestResult result = mvc.get().uri("/api/v1/probe/domain/NOT_FOUND").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PROBE_NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("probe failed with NOT_FOUND");
    }

    @Test
    void mapsConflictDomainErrorsTo409() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/domain/CONFLICT").exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PROBE_CONFLICT");
    }

    @Test
    void mapsUnprocessableDomainErrorsTo422() {
        MvcTestResult result =
                mvc.get().uri("/api/v1/probe/domain/UNPROCESSABLE").exchange();

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PROBE_UNPROCESSABLE");
    }

    @Test
    void reportsInvalidBodyFieldsAsValidationFailed() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo("must not be blank");
    }

    @Test
    void reportsClassLevelViolationsWithTheObjectName() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/probe/range")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"min\":5,\"max\":1}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors").asArray().isNotEmpty();
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo("min must not exceed max");
    }

    @Test
    void reportsInvalidRequestParametersAsValidationFailed() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/param?count=0").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("count");
    }

    @Test
    void reportsMissingRequiredParameterAsValidationFailed() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/param").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("count");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo("is required");
    }

    @Test
    void reportsUnreadableJsonAsMalformedRequestWithoutErrorsList() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
        assertThat(result).bodyJson().doesNotHavePath("$.errors");
    }

    @Test
    void reportsPathVariableTypeMismatchAsValidationFailed() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/items/abc").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("id");
    }

    @Test
    void hidesInternalDetailsOfUnexpectedErrors() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/boom").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyText().doesNotContain("secret internal detail", "IllegalStateException");
    }

    @Test
    void givesUnknownRoutesANotFoundCode() {
        MvcTestResult result = mvc.get().uri("/api/v1/no-such-route").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void givesUnsupportedMethodsAMethodNotAllowedCode() {
        MvcTestResult result = mvc.delete().uri("/api/v1/probe/items/7").exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void returnsPagesWithDefaultSizeAndPagedModelShape() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/page").exchange();

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
        MvcTestResult result = mvc.get().uri("/api/v1/probe/page?size=101").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("size");
    }

    @Test
    void rejectsNonNumericPageSizeAsValidationFailed() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/page?size=abc").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("size");
        assertThat(result).bodyJson().extractingPath("$.errors[0].message").isEqualTo("must be a valid Integer");
    }

    @Test
    void reportsTypeMismatchInUnvalidatedQueryObjectAsValidationFailed() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/raw-page?size=abc").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("size");
    }

    @Test
    void rejectsNegativePageNumbers() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/page?page=-1").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("page");
    }
}
