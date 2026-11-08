package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.ChangeStatus;
import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.CourierResponse;
import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.CreateCourier;
import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.UpdateLocation;
import io.github.danie1le.dropoff.web.ProblemResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Courier endpoints under {@code /api/v1/couriers} (tickets #9, #12, #42). */
@RestController
@RequestMapping("/couriers")
class CourierController {

    private final CourierService service;

    CourierController(CourierService service) {
        this.service = service;
    }

    @ApiResponse(
            responseCode = "201",
            description = "Created.",
            headers = @Header(name = HttpHeaders.LOCATION, description = "URL of the new courier."))
    @PostMapping
    ResponseEntity<CourierResponse> create(@Valid @RequestBody CreateCourier request) {
        Courier courier = service.create(request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(courier.id())
                .toUri();
        return ResponseEntity.created(location).body(CourierResponse.of(courier));
    }

    @ProblemResponse(status = 404, codes = "COURIER_NOT_FOUND")
    @GetMapping("/{id}")
    CourierResponse get(@PathVariable long id) {
        return CourierResponse.of(service.get(id));
    }

    @ProblemResponse(status = 404, codes = "COURIER_NOT_FOUND")
    @ProblemResponse(
            status = 409,
            codes = {"COURIER_LOCATION_UNKNOWN", "COURIER_BUSY", "COURIER_HAS_ACTIVE_DELIVERY"})
    @PatchMapping("/{id}/status")
    CourierResponse changeStatus(@PathVariable long id, @Valid @RequestBody ChangeStatus request) {
        return CourierResponse.of(service.changeStatus(id, request.requested()));
    }

    @ProblemResponse(status = 404, codes = "COURIER_NOT_FOUND")
    @PutMapping("/{id}/location")
    CourierResponse moveTo(@PathVariable long id, @Valid @RequestBody UpdateLocation request) {
        return CourierResponse.of(service.moveTo(id, request.position()));
    }
}
