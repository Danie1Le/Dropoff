package io.github.danie1le.dropoff.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Offset paging parameters for list endpoints ({@code ?page=0&size=20}). Bind with {@code @Valid PageParams}: a size
 * above {@value #MAX_SIZE}, a size below 1 or a negative page is then rejected with 400 (ticket #8). As a safety net
 * for an endpoint that forgets {@code @Valid}, {@link #toPageable()} also clamps page to at least 0 and size to 1 ..
 * {@value #MAX_SIZE}.
 */
public record PageParams(
        @Nullable @Min(0) Integer page,
        @Nullable @Min(1) @Max(MAX_SIZE) Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public Pageable toPageable() {
        int number = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE);
        return PageRequest.of(number, pageSize);
    }
}
