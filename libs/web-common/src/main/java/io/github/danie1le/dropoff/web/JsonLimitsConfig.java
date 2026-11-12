package io.github.danie1le.dropoff.web;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;

/**
 * Caps every JSON document Jackson reads at {@link RequestSizeLimitFilter#MAX_BODY_BYTES}, so a chunked body with no
 * {@code Content-Length} is bounded too. Going over throws a {@code StreamConstraintsException}, which
 * {@link ApiExceptionHandler} turns into a 413.
 */
@Configuration(proxyBeanMethods = false)
class JsonLimitsConfig {

    @Bean
    JsonFactoryBuilderCustomizer maxDocumentLength() {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.defaults()
                .rebuild()
                .maxDocumentLength(RequestSizeLimitFilter.MAX_BODY_BYTES)
                .build());
    }
}
