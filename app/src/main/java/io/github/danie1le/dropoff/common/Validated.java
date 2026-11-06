package io.github.danie1le.dropoff.common;

import org.jspecify.annotations.Nullable;

/**
 * Reads request fields that Bean Validation has already checked. JSON can still bind null into a field typed as
 * non-null, so code that runs after {@code @Valid} unwraps it here instead of trusting the type.
 */
public final class Validated {

    private Validated() {}

    /** The value; a null means the request skipped {@code @Valid}, which is a bug, not a client error. */
    public static <T> T required(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("a required field is missing; validate the request with @Valid first");
        }
        return value;
    }
}
