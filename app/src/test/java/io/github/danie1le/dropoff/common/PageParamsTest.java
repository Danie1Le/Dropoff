package io.github.danie1le.dropoff.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class PageParamsTest {

    @Test
    void defaultsToFirstPageOfTwentyWhenNothingIsGiven() {
        Pageable pageable = new PageParams(null, null).toPageable();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    void usesTheRequestedPageAndSize() {
        Pageable pageable = new PageParams(3, 50).toPageable();

        assertThat(pageable.getPageNumber()).isEqualTo(3);
        assertThat(pageable.getPageSize()).isEqualTo(50);
    }

    @Test
    void appliesTheGivenSortSoPagesAreStable() {
        Pageable pageable = new PageParams(1, 10).toPageable(Sort.by("id"));

        Sort.Order order = pageable.getSort().getOrderFor("id");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(10);
    }
}
