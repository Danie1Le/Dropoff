package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Binds the {@code Idempotency-Key} header (M2-4). Arguments resolve in declaration order, so a parameter declared
 * before the body is checked before the body is read and validated: a missing or blank key is 400
 * {@code IDEMPOTENCY_KEY_REQUIRED} even when the body is invalid too. A key longer than {@value #MAX_LENGTH}
 * characters is present but invalid, so it is 400 {@code VALIDATION_FAILED} on the header.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@interface IdempotencyKey {

    String HEADER = "Idempotency-Key";
    /** The width of {@code orders.idempotency_key}. */
    int MAX_LENGTH = 255;

    final class Resolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(IdempotencyKey.class)
                    && parameter.getParameterType() == String.class;
        }

        @Override
        public String resolveArgument(
                MethodParameter parameter,
                @Nullable ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                @Nullable WebDataBinderFactory binderFactory)
                throws MethodArgumentNotValidException {
            String key = webRequest.getHeader(HEADER);
            if (key == null || key.isBlank()) {
                throw new DomainException(
                        DomainException.Category.BAD_REQUEST,
                        "IDEMPOTENCY_KEY_REQUIRED",
                        "The Idempotency-Key header is required.");
            }
            if (key.length() > MAX_LENGTH) {
                MapBindingResult errors = new MapBindingResult(new HashMap<>(), "headers");
                errors.addError(new FieldError("headers", HEADER, "size must be between 1 and " + MAX_LENGTH));
                throw new MethodArgumentNotValidException(parameter, errors);
            }
            return key;
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Config implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new Resolver());
        }
    }
}
