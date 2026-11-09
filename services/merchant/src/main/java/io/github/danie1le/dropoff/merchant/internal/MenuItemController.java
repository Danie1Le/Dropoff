package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.CreateMenuItem;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.MenuItemResponse;
import io.github.danie1le.dropoff.merchant.internal.MenuItemRequests.UpdateMenuItem;
import io.github.danie1le.dropoff.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Menu item endpoints under {@code /api/v1} (tickets #59, #61). No DELETE: orders keep a link to every item. */
@RestController
class MenuItemController {

    private final MenuItemService service;

    MenuItemController(MenuItemService service) {
        this.service = service;
    }

    @ProblemResponse(status = 404, codes = "MERCHANT_NOT_FOUND")
    @ApiResponse(
            responseCode = "201",
            description = "Created.",
            headers = @Header(name = HttpHeaders.LOCATION, description = "URL of the new menu item."))
    @Operation(summary = "Add a menu item")
    @PostMapping("/merchants/{id}/menu-items")
    ResponseEntity<MenuItemResponse> create(@PathVariable long id, @Valid @RequestBody CreateMenuItem request) {
        MenuItem item = service.create(id, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/menu-items/{id}")
                .buildAndExpand(item.id())
                .toUri();
        return ResponseEntity.created(location).body(MenuItemResponse.of(item));
    }

    @ProblemResponse(status = 404, codes = "MERCHANT_NOT_FOUND")
    @Operation(summary = "List a merchant menu", description = "Every item of the merchant, available or not.")
    @GetMapping("/merchants/{id}/menu")
    List<MenuItemResponse> menu(@PathVariable long id) {
        return service.menu(id).stream().map(MenuItemResponse::of).toList();
    }

    @ProblemResponse(status = 404, codes = "MENU_ITEM_NOT_FOUND")
    @Operation(summary = "Get a menu item")
    @GetMapping("/menu-items/{id}")
    MenuItemResponse get(@PathVariable long id) {
        return MenuItemResponse.of(service.get(id));
    }

    @ProblemResponse(status = 404, codes = "MENU_ITEM_NOT_FOUND")
    @Operation(
            summary = "Update a menu item",
            description =
                    "Partial update: absent or null fields stay as they are. There is no delete; mark an item unavailable instead.")
    @PatchMapping("/menu-items/{id}")
    MenuItemResponse update(@PathVariable long id, @Valid @RequestBody UpdateMenuItem request) {
        return MenuItemResponse.of(service.update(id, request));
    }
}
