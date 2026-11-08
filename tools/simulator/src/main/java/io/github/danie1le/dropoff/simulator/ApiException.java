package io.github.danie1le.dropoff.simulator;

import org.jspecify.annotations.Nullable;

/** A non-2xx answer, with the Problem Details {@code code} when the body has one (ticket #8). */
final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final @Nullable String code;

    ApiException(int status, @Nullable String code) {
        super("HTTP " + status + (code == null ? "" : " " + code));
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    @Nullable
    String code() {
        return code;
    }
}
