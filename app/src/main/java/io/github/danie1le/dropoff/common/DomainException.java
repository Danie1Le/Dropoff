package io.github.danie1le.dropoff.common;

/**
 * A broken business rule. Domain code throws it without knowing about HTTP; the web layer maps the {@link Category}
 * to a status code and puts {@link #code()} in the error body so clients can branch on it (ticket #8). The message is
 * shown to API clients as {@code detail}, so it must be a fixed, human-readable sentence and never include secrets,
 * stack details or raw input.
 */
public class DomainException extends RuntimeException {

    /** What kind of rule was broken. Decides the HTTP status. */
    public enum Category {
        /** The request itself is incomplete, for example a required header is missing (400). */
        BAD_REQUEST,
        /** The resource named in the URL path does not exist (404). */
        NOT_FOUND,
        /** The request conflicts with the current state of the target resource (409). */
        CONFLICT,
        /** The request is well-formed, but its content breaks a business rule (422). */
        UNPROCESSABLE
    }

    private final Category category;
    private final String code;

    public DomainException(Category category, String code, String message) {
        super(message);
        this.category = category;
        this.code = code;
    }

    public Category category() {
        return category;
    }

    /** Stable UPPER_SNAKE error code, for example {@code MERCHANT_NOT_FOUND}. */
    public String code() {
        return code;
    }
}
