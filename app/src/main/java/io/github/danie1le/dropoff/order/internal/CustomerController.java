package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.CustomerRequests.CreateCustomer;
import io.github.danie1le.dropoff.order.internal.CustomerRequests.CustomerResponse;
import io.github.danie1le.dropoff.order.internal.CustomerRequests.UpdateCustomer;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Customer endpoints under {@code /api/v1/customers} (ticket #9). */
@RestController
@RequestMapping("/customers")
class CustomerController {

    private final CustomerService service;

    CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomer request) {
        Customer customer = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(customer.id())
                .toUri();
        return ResponseEntity.created(location).body(CustomerResponse.of(customer));
    }

    @GetMapping("/{id}")
    CustomerResponse get(@PathVariable long id) {
        return CustomerResponse.of(service.get(id));
    }

    @PatchMapping("/{id}")
    CustomerResponse update(@PathVariable long id, @Valid @RequestBody UpdateCustomer request) {
        return CustomerResponse.of(service.update(id, request));
    }
}
