package io.github.danie1le.dropoff.common.web;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.common.PageParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that trigger each error shape and the paging rules. */
@RestController
@RequestMapping("/probe")
class ProbeController {

    record Body(@NotBlank String name) {}

    @GetMapping("/domain/{category}")
    void domain(@PathVariable DomainException.Category category) {
        throw new DomainException(category, "PROBE_" + category.name(), "probe failed with " + category);
    }

    @PostMapping("/body")
    Body body(@Valid @RequestBody Body body) {
        return body;
    }

    @GetMapping("/param")
    int param(@RequestParam @Min(1) int count) {
        return count;
    }

    @GetMapping("/items/{id}")
    long item(@PathVariable long id) {
        return id;
    }

    @GetMapping("/boom")
    void boom() {
        throw new IllegalStateException("secret internal detail");
    }

    @GetMapping("/page")
    PagedModel<String> page(@Valid PageParams params) {
        var pageable = params.toPageable();
        return new PagedModel<>(new PageImpl<>(List.of("a", "b"), pageable, 2));
    }
}
