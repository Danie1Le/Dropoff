package io.github.danie1le.dropoff.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents in Swagger the Problem Details error an endpoint can return, with its stable {@code code} values (M1-4,
 * ticket #8). Put one per status on the controller method, for example
 * {@code @ProblemResponse(status = 404, codes = "MERCHANT_NOT_FOUND")}. Every endpoint already gets 400
 * {@code VALIDATION_FAILED} (plus {@code MALFORMED_REQUEST} and 413 {@code CONTENT_TOO_LARGE} when it reads a body) and 500 {@code INTERNAL_ERROR}, so
 * list only the codes the endpoint adds. Documentation only: it changes no behaviour.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ProblemResponse.List.class)
public @interface ProblemResponse {

    /** The HTTP status, for example 404. */
    int status();

    /** The {@code code} values the endpoint returns with this status, for example {@code MERCHANT_NOT_FOUND}. */
    String[] codes();

    /** Holds several {@link ProblemResponse}s on one method. */
    @Documented
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        ProblemResponse[] value();
    }
}
