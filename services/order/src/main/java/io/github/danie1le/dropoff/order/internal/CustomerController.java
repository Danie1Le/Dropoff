package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.order.internal.CustomerRequests.CreateCustomer;
import io.github.danie1le.dropoff.order.internal.CustomerRequests.CustomerResponse;
import io.github.danie1le.dropoff.order.internal.CustomerRequests.UpdateCustomer;
import io.github.danie1le.dropoff.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
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

    @ApiResponse(
            responseCode = "201",
            description = "Created.",
            headers = @Header(name = HttpHeaders.LOCATION, description = "URL of the new customer."))
    @Operation(summary = "Create a customer")
    @PostMapping
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomer request) {
        Customer customer = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(customer.id())
                .toUri();
        return ResponseEntity.created(location).body(CustomerResponse.of(customer));
    }

    @ProblemResponse(status = 404, codes = "CUSTOMER_NOT_FOUND")
    @Operation(summary = "Get a customer")
    @GetMapping("/{id}")
    CustomerResponse get(@PathVariable long id) {
        return CustomerResponse.of(service.get(id));
    }

    @ProblemResponse(status = 404, codes = "CUSTOMER_NOT_FOUND")
    @Operation(
            summary = "Update a customer",
            description =
                    "Partial update: absent or null fields stay as they are. Address, lat and lng are sent together.")
    @PatchMapping("/{id}")
    CustomerResponse update(@PathVariable long id, @Valid @RequestBody UpdateCustomer request) {
        return CustomerResponse.of(service.update(id, request));
    }
}
