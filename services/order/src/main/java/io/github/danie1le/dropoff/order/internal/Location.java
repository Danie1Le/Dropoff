package io.github.danie1le.dropoff.order.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** A street address with its coordinates. The three always change together (ticket #42). */
record Location(
        @Schema(example = "20 Prinsengracht, Amsterdam") String address,
        @Schema(example = "52.3667") double lat,
        @Schema(example = "4.8830") double lng) {}
