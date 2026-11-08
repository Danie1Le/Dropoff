package io.github.danie1le.dropoff.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts every Dropoff REST controller under {@code /api/v1}, configured once (ticket #8). Only controllers in the
 * {@value #BASE_PACKAGE} package get the prefix, so library endpoints such as springdoc's {@code /v3/api-docs} and
 * actuator stay unversioned.
 */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    private static final String BASE_PACKAGE = "io.github.danie1le.dropoff";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                "/api/v1",
                HandlerTypePredicate.forAnnotation(RestController.class)
                        .and(HandlerTypePredicate.forBasePackage(BASE_PACKAGE)));
    }
}
