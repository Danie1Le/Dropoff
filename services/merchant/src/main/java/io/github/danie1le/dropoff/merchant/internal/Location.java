package io.github.danie1le.dropoff.merchant.internal;

/** A street address with its coordinates. The three always change together (ticket #42). */
record Location(String address, double lat, double lng) {}
