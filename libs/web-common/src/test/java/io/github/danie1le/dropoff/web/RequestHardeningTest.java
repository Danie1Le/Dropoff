package io.github.danie1le.dropoff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.json.JsonMapper;

// @WebMvcTest only picks up web components; JsonLimitsConfig is a plain @Configuration, so it is imported here.
@WebMvcTest(ProbeController.class)
@Import(JsonLimitsConfig.class)
class RequestHardeningTest {

    private final MockMvcTester mvc;
    private final JsonMapper jsonMapper;

    RequestHardeningTest(@Autowired MockMvcTester mvc, @Autowired JsonMapper jsonMapper) {
        this.mvc = mvc;
        this.jsonMapper = jsonMapper;
    }

    @Test
    void acceptsABodyAtTheSizeLimit() {
        MvcTestResult result =
                postBody(nameOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES - "{\"name\":\"\"}".length()));

        assertThat(result).hasStatusOk();
    }

    @Test
    void rejectsABodyOverTheSizeLimitWithContentTooLarge() {
        MvcTestResult result = postBody(nameOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES));

        assertThat(result).hasStatus(HttpStatus.CONTENT_TOO_LARGE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(413);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CONTENT_TOO_LARGE");
        assertThat(result).headers().hasValue("X-Content-Type-Options", "nosniff");
    }

    @Test
    void capsJsonDocumentsWithoutAContentLength() {
        String json = "{\"name\":\"" + "a".repeat(RequestSizeLimitFilter.MAX_BODY_BYTES) + "\"}";

        assertThatThrownBy(() -> jsonMapper.readTree(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    void reportsJacksonReadLimitsAsContentTooLarge() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"extra\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CONTENT_TOO_LARGE");
    }

    @Test
    void addsSecurityHeadersToSuccessfulResponses() {
        assertSecurityHeaders(mvc.get().uri("/api/v1/probe/items/7").exchange());
    }

    @Test
    void addsSecurityHeadersToErrorResponses() {
        MvcTestResult result = mvc.get().uri("/api/v1/probe/boom").exchange();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        assertSecurityHeaders(result);
    }

    private MvcTestResult postBody(String name) {
        return mvc.post()
                .uri("/api/v1/probe/body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")
                .exchange();
    }

    private static String nameOfLength(int length) {
        return "a".repeat(length);
    }

    private static void assertSecurityHeaders(MvcTestResult result) {
        assertThat(result)
                .headers()
                .hasValue("X-Content-Type-Options", "nosniff")
                .hasValue("X-Frame-Options", "DENY")
                .hasValue("Referrer-Policy", "no-referrer");
    }
}
