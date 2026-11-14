package io.github.danie1le.dropoff.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(ProbeController.class)
class RequestHardeningTest {

    private static final String BODY_URI = "/api/v1/probe/body";
    private static final String EMPTY_BODY = "{\"name\":\"\"}";

    private final MockMvcTester mvc;
    private final JsonMapper jsonMapper;

    RequestHardeningTest(@Autowired MockMvcTester mvc, @Autowired JsonMapper jsonMapper) {
        this.mvc = mvc;
        this.jsonMapper = jsonMapper;
    }

    @Test
    void acceptsABodyAtTheSizeLimit() {
        MvcTestResult result = mvc.post()
                .uri(BODY_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES))
                .exchange();

        assertThat(result).hasStatusOk();
    }

    @Test
    void rejectsABodyOverTheSizeLimitWithContentTooLarge() {
        MvcTestResult result = mvc.post()
                .uri(BODY_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES + 1))
                .exchange();

        assertContentTooLarge(result);
        assertSecurityHeaders(result);
    }

    @Test
    void acceptsAChunkedBodyAtTheSizeLimit() {
        assertThat(mvc.perform(chunkedPost(bodyOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES))))
                .hasStatusOk();
    }

    // No Content-Length, so the filter lets it through and the MVC converter's document cap stops it.
    @Test
    void rejectsAChunkedBodyOverTheSizeLimitWithContentTooLarge() {
        MvcTestResult result = mvc.perform(chunkedPost(bodyOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES + 1)));

        assertContentTooLarge(result);
        assertSecurityHeaders(result);
    }

    @Test
    void bothContentTooLargePathsReturnTheSameProblem() throws Exception {
        String body = bodyOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES + 1);
        MvcTestResult fromFilter = mvc.post()
                .uri(BODY_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        MvcTestResult fromHandler = mvc.perform(chunkedPost(body));

        assertThat(fromHandler).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(fromHandler)
                .bodyJson()
                .isStrictlyEqualTo(fromFilter.getResponse().getContentAsString());
    }

    // KafkaConfig hands this shared mapper to every listener, so the HTTP cap must not reach it.
    @Test
    void leavesTheSharedJsonMapperWithoutADocumentLimit() {
        byte[] json = bodyOfLength(RequestSizeLimitFilter.MAX_BODY_BYTES * 2).getBytes(StandardCharsets.UTF_8);

        assertThat(jsonMapper
                        .readTree(new ByteArrayInputStream(json))
                        .get("name")
                        .asString())
                .hasSize(json.length - EMPTY_BODY.length());
    }

    @Test
    void reportsOtherJacksonReadLimitsAsMalformedRequest() {
        MvcTestResult result = mvc.post()
                .uri(BODY_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"extra\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
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

    // MockMvc does not forward sendError to /error, so this calls Boot's error controller the way the container would.
    @Test
    void addsSecurityHeadersToBootErrorResponses() {
        MvcTestResult result = mvc.get()
                .uri("/error")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, HttpStatus.CONTENT_TOO_LARGE.value())
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
        assertSecurityHeaders(result);
    }

    /** A JSON body of exactly {@code length} bytes. */
    private static String bodyOfLength(int length) {
        return "{\"name\":\"" + "a".repeat(length - EMPTY_BODY.length()) + "\"}";
    }

    /** A POST with no {@code Content-Length}, as a chunked request arrives; MockMvc always sets one otherwise. */
    private static RequestBuilder chunkedPost(String body) {
        return servletContext -> {
            MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "POST", BODY_URI) {
                @Override
                public int getContentLength() {
                    return -1;
                }

                @Override
                public long getContentLengthLong() {
                    return -1;
                }
            };
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            return request;
        };
    }

    private static void assertContentTooLarge(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CONTENT_TOO_LARGE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(413);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CONTENT_TOO_LARGE");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(BODY_URI);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The request body is larger than 65536 bytes.");
    }

    private static void assertSecurityHeaders(MvcTestResult result) {
        assertThat(result)
                .headers()
                .hasValue("X-Content-Type-Options", "nosniff")
                .hasValue("X-Frame-Options", "DENY")
                .hasValue("Referrer-Policy", "no-referrer");
    }
}
