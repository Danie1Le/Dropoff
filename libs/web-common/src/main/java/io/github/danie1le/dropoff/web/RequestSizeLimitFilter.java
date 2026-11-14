package io.github.danie1le.dropoff.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rejects a request whose declared {@code Content-Length} is over {@link #MAX_BODY_BYTES} with a 413 Problem Details
 * body, before anything reads it. A chunked body has no {@code Content-Length}; {@link JsonLimitsConfig} caps that one
 * while Jackson parses it. Runs outside the DispatcherServlet, so it writes the problem itself: the one
 * {@link ApiExceptionHandler#contentTooLarge()} builds, with {@code instance} set to the request path as Spring MVC
 * does for the handler's problems.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** The largest request body any Dropoff endpoint accepts: 64 KB, far above any real order or menu payload. */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final JsonMapper jsonMapper;

    RequestSizeLimitFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            ProblemDetail problem = ApiExceptionHandler.contentTooLarge();
            problem.setInstance(URI.create(request.getRequestURI()));
            byte[] body = jsonMapper.writeValueAsBytes(problem);
            response.setStatus(problem.getStatus());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return;
        }
        chain.doFilter(request, response);
    }
}
