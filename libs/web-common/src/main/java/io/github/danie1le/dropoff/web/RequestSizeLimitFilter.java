package io.github.danie1le.dropoff.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects a request whose declared {@code Content-Length} is over {@link #MAX_BODY_BYTES} with a 413 Problem Details
 * body, before anything reads it. A chunked body has no {@code Content-Length}; {@link JsonLimitsConfig} caps that one
 * while Jackson parses it. Runs outside the DispatcherServlet, so it writes the problem itself, in the same shape and
 * with the same {@code CONTENT_TOO_LARGE} code {@link ApiExceptionHandler} uses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** The largest request body any Dropoff endpoint accepts: 64 KB, far above any real order or menu payload. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    static final String CONTENT_TOO_LARGE = "CONTENT_TOO_LARGE";

    private static final byte[] PROBLEM = ("{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
                    + "\"detail\":\"The request body is larger than " + MAX_BODY_BYTES + " bytes.\","
                    + "\"code\":\"" + CONTENT_TOO_LARGE + "\"}")
            .getBytes(StandardCharsets.UTF_8);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setContentLength(PROBLEM.length);
            response.getOutputStream().write(PROBLEM);
            return;
        }
        chain.doFilter(request, response);
    }
}
