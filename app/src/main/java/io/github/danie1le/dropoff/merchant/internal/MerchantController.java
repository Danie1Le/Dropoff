package io.github.danie1le.dropoff.merchant.internal;

import io.github.danie1le.dropoff.common.PageParams;
import io.github.danie1le.dropoff.common.ProblemResponse;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.CreateMerchant;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.MerchantResponse;
import io.github.danie1le.dropoff.merchant.internal.MerchantRequests.UpdateMerchant;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
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

/** Merchant endpoints under {@code /api/v1/merchants} (tickets #8, #9). */
@RestController
@RequestMapping("/merchants")
class MerchantController {

    private final MerchantService service;

    MerchantController(MerchantService service) {
        this.service = service;
    }

    @ApiResponse(
            responseCode = "201",
            description = "Created.",
            headers = @Header(name = HttpHeaders.LOCATION, description = "URL of the new merchant."))
    @PostMapping
    ResponseEntity<MerchantResponse> create(@Valid @RequestBody CreateMerchant request) {
        Merchant merchant = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(merchant.id())
                .toUri();
        return ResponseEntity.created(location).body(MerchantResponse.of(merchant));
    }

    @ProblemResponse(status = 404, codes = "MERCHANT_NOT_FOUND")
    @GetMapping("/{id}")
    MerchantResponse get(@PathVariable long id) {
        return MerchantResponse.of(service.get(id));
    }

    @GetMapping
    PagedModel<MerchantResponse> list(@ParameterObject @Valid PageParams params) {
        return new PagedModel<>(service.list(params.toPageable(Sort.by("id"))).map(MerchantResponse::of));
    }

    @ProblemResponse(status = 404, codes = "MERCHANT_NOT_FOUND")
    @PatchMapping("/{id}")
    MerchantResponse update(@PathVariable long id, @Valid @RequestBody UpdateMerchant request) {
        return MerchantResponse.of(service.update(id, request));
    }
}
