package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.CreateMenuItem;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.MenuItemResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Menu item endpoints under {@code /api/v1} (ticket #59). No DELETE: orders keep a link to every item. */
@RestController
class MenuItemController {

    private final MenuItemService service;

    MenuItemController(MenuItemService service) {
        this.service = service;
    }

    @PostMapping("/merchants/{id}/menu-items")
    ResponseEntity<MenuItemResponse> create(@PathVariable long id, @Valid @RequestBody CreateMenuItem request) {
        MenuItem item = service.create(id, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/menu-items/{id}")
                .buildAndExpand(item.id())
                .toUri();
        return ResponseEntity.created(location).body(MenuItemResponse.of(item));
    }

    @GetMapping("/menu-items/{id}")
    MenuItemResponse get(@PathVariable long id) {
        return MenuItemResponse.of(service.get(id));
    }
}
