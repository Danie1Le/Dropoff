package io.github.danie1le.dropoff.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the basic security response headers to every response, including Swagger UI, error and 413 responses: no MIME
 * sniffing, no framing and no referrer. It runs before every other Dropoff filter, so even a rejected request gets
 * them. Swagger UI is never framed, so {@code DENY} does not break it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
