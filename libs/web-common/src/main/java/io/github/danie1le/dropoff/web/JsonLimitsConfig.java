package io.github.danie1le.dropoff.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps every JSON request body the MVC converter reads at {@link RequestSizeLimitFilter#MAX_BODY_BYTES}, so a chunked
 * body with no {@code Content-Length} is bounded too. Going over throws a {@code StreamConstraintsException}, which
 * {@link ApiExceptionHandler} turns into a 413.
 *
 * <p>The cap is for HTTP only. The shared {@link JsonMapper} bean, which the Kafka listeners also use, has no document
 * limit: the converter gets its own mapper, built the way Boot builds the shared one (same customizers) on a copy of
 * Boot's {@link JsonFactory}. The copy keeps the factory's read constraints, including any
 * {@code spring.jackson.factory.constraints.read.*} settings, and changes only {@code maxDocumentLength}.
 */
@Configuration(proxyBeanMethods = false)
class JsonLimitsConfig implements WebMvcConfigurer {

    private final JsonFactory jsonFactory;
    private final ObjectProvider<JsonMapperBuilderCustomizer> customizers;

    JsonLimitsConfig(JsonFactory jsonFactory, ObjectProvider<JsonMapperBuilderCustomizer> customizers) {
        this.jsonFactory = jsonFactory;
        this.customizers = customizers;
    }

    /** Runs after Boot's own MVC configurer (order 0), so it replaces the converter Boot built on the shared mapper. */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.withJsonConverter(new JacksonJsonHttpMessageConverter(requestBodyMapper()));
    }

    /**
     * Jackson checks the length each time it loads the next input buffer, so a chunked body can run up to one buffer
     * (about 8 KB) past the cap before it fails. It is still bounded.
     */
    private JsonMapper requestBodyMapper() {
        StreamReadConstraints constraints = jsonFactory
                .streamReadConstraints()
                .rebuild()
                .maxDocumentLength(RequestSizeLimitFilter.MAX_BODY_BYTES)
                .build();
        JsonMapper.Builder builder = JsonMapper.builder(
                jsonFactory.rebuild().streamReadConstraints(constraints).build());
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }
}
