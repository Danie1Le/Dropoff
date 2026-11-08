package io.github.danie1le.dropoff.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Offset paging parameters for list endpoints ({@code ?page=0&size=20}). Bind with {@code @Valid PageParams} (enforced
 * by {@code PageParamsValidationTest}): a size above {@value #MAX_SIZE}, a size below 1 or a negative page is rejected
 * with 400, never capped (ticket #8).
 */
public record PageParams(
        @Nullable @Min(0) Integer page,
        @Nullable @Min(1) @Max(MAX_SIZE) Integer size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /**
     * Pages must be stable: a page holds the same rows each time it is requested. Pass a sort (for example
     * {@code Sort.by("id")}), or use {@link #toPageable()} when the query's own {@code ORDER BY} already makes pages
     * stable.
     */
    public Pageable toPageable(Sort sort) {
        int number = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        return PageRequest.of(number, pageSize, sort);
    }

    /**
     * An unsorted page, for a query whose own {@code ORDER BY} ends in a unique column (for example
     * {@code ORDER BY created_at, id}), so pages are stable without a sort here.
     */
    public Pageable toPageable() {
        return toPageable(Sort.unsorted());
    }
}
