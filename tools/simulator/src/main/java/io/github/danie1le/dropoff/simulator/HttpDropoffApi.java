package io.github.danie1le.dropoff.simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link DropoffApi} over {@code java.net.http.HttpClient}, against {@code <baseUrl>/api/v1}. */
final class HttpDropoffApi implements DropoffApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final URI api;

    HttpDropoffApi(URI baseUrl) {
        String base = baseUrl.toString();
        this.api = URI.create((base.endsWith("/") ? base : base + "/") + "api/v1/");
    }

    @Override
    public long createMerchant(String name, String address, LatLng at) {
        return id(
                send("POST", "merchants", Map.of("name", name, "address", address, "lat", at.lat(), "lng", at.lng())));
    }

    @Override
    public long createMenuItem(long merchantId, String name, long priceCents) {
        return id(send(
                "POST", "merchants/" + merchantId + "/menu-items", Map.of("name", name, "priceCents", priceCents)));
    }

    @Override
    public long createCustomer(String name, String address, LatLng at) {
        return id(
                send("POST", "customers", Map.of("name", name, "address", address, "lat", at.lat(), "lng", at.lng())));
    }

    @Override
    public long createCourier(String name) {
        return id(send("POST", "couriers", Map.of("name", name)));
    }

    @Override
    public void updateLocation(long courierId, LatLng at) {
        send("PUT", "couriers/" + courierId + "/location", Map.of("lat", at.lat(), "lng", at.lng()));
    }

    @Override
    public CourierView courier(long courierId) {
        JsonNode body = required(send("GET", "couriers/" + courierId, null));
        JsonNode lat = body.path("lat");
        JsonNode lng = body.path("lng");
        @Nullable
        LatLng position = lat.isNumber() && lng.isNumber() ? new LatLng(lat.asDouble(), lng.asDouble()) : null;
        return new CourierView(CourierStatus.valueOf(body.path("status").asString()), position);
    }

    @Override
    public void changeStatus(long courierId, CourierStatus status) {
        send("PATCH", "couriers/" + courierId + "/status", Map.of("status", status.name()));
    }

    @Override
    public Optional<Offer> currentOffer(long courierId) {
        @Nullable JsonNode body = send("GET", "couriers/" + courierId + "/offers/current", null);
        if (body == null) {
            return Optional.empty();
        }
        return Optional.of(new Offer(
                body.path("offerId").asLong(),
                body.path("orderId").asLong(),
                new LatLng(
                        body.path("pickupLat").asDouble(),
                        body.path("pickupLng").asDouble()),
                new LatLng(
                        body.path("deliveryLat").asDouble(),
                        body.path("deliveryLng").asDouble()),
                Instant.parse(body.path("expiresAt").asString())));
    }

    @Override
    public void acceptOffer(long courierId, long offerId) {
        send("POST", "couriers/" + courierId + "/offers/" + offerId + "/accept", null);
    }

    @Override
    public void declineOffer(long courierId, long offerId) {
        send("POST", "couriers/" + courierId + "/offers/" + offerId + "/decline", null);
    }

    @Override
    public void pickUp(long courierId, long orderId) {
        send("POST", "couriers/" + courierId + "/orders/" + orderId + "/pick-up", null);
    }

    @Override
    public void deliver(long courierId, long orderId) {
        send("POST", "couriers/" + courierId + "/orders/" + orderId + "/deliver", null);
    }

    @Override
    public long placeOrder(long customerId, long merchantId, List<OrderLine> lines) {
        // The endpoint requires an Idempotency-Key (M2-4). The bot never retries, so each order gets a fresh one.
        return id(send(
                "POST",
                "orders",
                Map.of("customerId", customerId, "merchantId", merchantId, "lines", lines),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
    }

    @Override
    public List<Long> placedOrders(long merchantId) {
        JsonNode body = required(send("GET", "merchants/" + merchantId + "/orders?status=PLACED&size=100", null));
        List<Long> ids = new ArrayList<>();
        for (JsonNode order : body.path("content")) {
            ids.add(order.path("id").asLong());
        }
        return ids;
    }

    @Override
    public void merchantStep(long merchantId, long orderId, MerchantStep step) {
        send("POST", "merchants/" + merchantId + "/orders/" + orderId + "/" + step.path(), null);
    }

    @Override
    public String orderStatus(long orderId) {
        return required(send("GET", "orders/" + orderId, null)).path("status").asString();
    }

    /** Sends the request; returns the JSON body, or null for an empty one (204). A non-2xx throws ApiException. */
    private @Nullable JsonNode send(String method, String path, @Nullable Object body) {
        return send(method, path, body, Map.of());
    }

    private @Nullable JsonNode send(String method, String path, @Nullable Object body, Map<String, String> headers) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        HttpRequest.Builder builder = HttpRequest.newBuilder(api.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, publisher);
        headers.forEach(builder::header);
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + method + " " + path, e);
        }
        String text = response.body();
        @Nullable JsonNode parsed = text == null || text.isBlank() ? null : json.readTree(text);
        if (response.statusCode() / 100 != 2) {
            @Nullable
            String code = parsed != null && parsed.path("code").isString()
                    ? parsed.path("code").asString()
                    : null;
            throw new ApiException(response.statusCode(), code);
        }
        return parsed;
    }

    private static JsonNode required(@Nullable JsonNode body) {
        if (body == null) {
            throw new IllegalStateException("expected a response body");
        }
        return body;
    }

    private static long id(@Nullable JsonNode body) {
        return required(body).path("id").asLong();
    }
}
