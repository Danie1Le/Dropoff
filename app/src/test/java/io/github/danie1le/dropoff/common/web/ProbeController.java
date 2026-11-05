package io.github.danie1le.dropoff.common.web;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.common.PageParams;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/** Test-only endpoints that trigger each error shape and the paging rules. */
@RestController
@RequestMapping("/probe")
class ProbeController {

    record Body(@NotBlank String name) {}

    /** Class-level constraint, so a violation is a global {@code ObjectError}, not a {@code FieldError}. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = OrderedValidator.class)
    @interface Ordered {
        String message() default "min must not exceed max";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static class OrderedValidator implements ConstraintValidator<Ordered, Range> {
        @Override
        public boolean isValid(Range range, ConstraintValidatorContext context) {
            return range.min() <= range.max();
        }
    }

    @Ordered
    record Range(int min, int max) {}

    /** Its getter throws, so writing it as JSON fails with {@code HttpMessageNotWritableException}. */
    static class Unwritable {
        public String getValue() {
            throw new IllegalStateException("secret serialization detail");
        }
    }

    @GetMapping("/domain/{category}")
    void domain(@PathVariable DomainException.Category category) {
        throw new DomainException(category, "PROBE_" + category.name(), "probe failed with " + category);
    }

    @PostMapping("/body")
    Body body(@Valid @RequestBody Body body) {
        return body;
    }

    @PostMapping("/range")
    Range range(@Valid @RequestBody Range range) {
        return range;
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

    @GetMapping("/unwritable")
    Unwritable unwritable() {
        return new Unwritable();
    }

    @GetMapping("/page")
    PagedModel<String> page(@Valid PageParams params) {
        Pageable pageable = params.toPageable(Sort.by("id"));
        return new PagedModel<>(new PageImpl<>(List.of("a", "b"), pageable, 2));
    }

    @GetMapping("/timeout")
    void timeout() {
        throw new AsyncRequestTimeoutException();
    }
}
