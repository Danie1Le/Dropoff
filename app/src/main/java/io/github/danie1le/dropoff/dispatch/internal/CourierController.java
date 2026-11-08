package io.github.danie1le.dropoff.dispatch.internal;

import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.ChangeStatus;
import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.CourierResponse;
import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.CreateCourier;
import io.github.danie1le.dropoff.dispatch.internal.CourierRequests.UpdateLocation;
import jakarta.validation.Valid;
import java.net.URI;
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

    @PostMapping
    ResponseEntity<CourierResponse> create(@Valid @RequestBody CreateCourier request) {
        Courier courier = service.create(request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(courier.id())
                .toUri();
        return ResponseEntity.created(location).body(CourierResponse.of(courier));
    }

    @GetMapping("/{id}")
    CourierResponse get(@PathVariable long id) {
        return CourierResponse.of(service.get(id));
    }

    @PatchMapping("/{id}/status")
    CourierResponse changeStatus(@PathVariable long id, @Valid @RequestBody ChangeStatus request) {
        return CourierResponse.of(service.changeStatus(id, request.requested()));
    }

    @PutMapping("/{id}/location")
    CourierResponse moveTo(@PathVariable long id, @Valid @RequestBody UpdateLocation request) {
        return CourierResponse.of(service.moveTo(id, request.position()));
    }
}
