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

/**
 * {@link DropoffApi} over {@code java.net.http.HttpClient}. Each call goes to {@code <service>/api/v1} of the service
 * that owns it (M5-4, {@link ServiceUrls}).
 */
final class HttpDropoffApi implements DropoffApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final URI merchant;
    private final URI order;
    private final URI dispatch;

    HttpDropoffApi(ServiceUrls urls) {
        this.merchant = api(urls.merchant());
        this.order = api(urls.order());
        this.dispatch = api(urls.dispatch());
    }

    private static URI api(URI baseUrl) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "api/v1/");
    }

    @Override
    public long createMerchant(String name, String address, LatLng at) {
        return id(send(
                merchant,
                "POST",
                "merchants",
                Map.of("name", name, "address", address, "lat", at.lat(), "lng", at.lng())));
    }

    @Override
    public long createMenuItem(long merchantId, String name, long priceCents) {
        return id(send(
                merchant,
                "POST",
                "merchants/" + merchantId + "/menu-items",
                Map.of("name", name, "priceCents", priceCents)));
    }

    @Override
    public long createCustomer(String name, String address, LatLng at) {
        return id(send(
                order,
                "POST",
                "customers",
                Map.of("name", name, "address", address, "lat", at.lat(), "lng", at.lng())));
    }

    @Override
    public long createCourier(String name) {
        return id(send(dispatch, "POST", "couriers", Map.of("name", name)));
    }

    @Override
    public void updateLocation(long courierId, LatLng at) {
        send(dispatch, "PUT", "couriers/" + courierId + "/location", Map.of("lat", at.lat(), "lng", at.lng()));
    }

    @Override
    public CourierView courier(long courierId) {
        JsonNode body = required(send(dispatch, "GET", "couriers/" + courierId, null));
        JsonNode lat = body.path("lat");
        JsonNode lng = body.path("lng");
        @Nullable
        LatLng position = lat.isNumber() && lng.isNumber() ? new LatLng(lat.asDouble(), lng.asDouble()) : null;
        return new CourierView(CourierStatus.valueOf(body.path("status").asString()), position);
    }

    @Override
    public void changeStatus(long courierId, CourierStatus status) {
        send(dispatch, "PATCH", "couriers/" + courierId + "/status", Map.of("status", status.name()));
    }

    @Override
    public Optional<Offer> currentOffer(long courierId) {
        @Nullable JsonNode body = send(dispatch, "GET", "couriers/" + courierId + "/offers/current", null);
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
        send(dispatch, "POST", "couriers/" + courierId + "/offers/" + offerId + "/accept", null);
    }

    @Override
    public void declineOffer(long courierId, long offerId) {
        send(dispatch, "POST", "couriers/" + courierId + "/offers/" + offerId + "/decline", null);
    }

    @Override
    public void pickUp(long courierId, long orderId) {
        send(order, "POST", "couriers/" + courierId + "/orders/" + orderId + "/pick-up", null);
    }

    @Override
    public void deliver(long courierId, long orderId) {
        send(order, "POST", "couriers/" + courierId + "/orders/" + orderId + "/deliver", null);
    }

    @Override
    public long placeOrder(long customerId, long merchantId, List<OrderLine> lines) {
        // The endpoint requires an Idempotency-Key (M2-4). The bot never retries, so each order gets a fresh one.
        return id(send(
                order,
                "POST",
                "orders",
                Map.of("customerId", customerId, "merchantId", merchantId, "lines", lines),
                Map.of("Idempotency-Key", UUID.randomUUID().toString())));
    }

    @Override
    public List<Long> placedOrders(long merchantId) {
        JsonNode body =
                required(send(order, "GET", "merchants/" + merchantId + "/orders?status=PLACED&size=100", null));
        List<Long> ids = new ArrayList<>();
        for (JsonNode order : body.path("content")) {
            ids.add(order.path("id").asLong());
        }
        return ids;
    }

    @Override
    public void merchantStep(long merchantId, long orderId, MerchantStep step) {
        send(order, "POST", "merchants/" + merchantId + "/orders/" + orderId + "/" + step.path(), null);
    }

    @Override
    public String orderStatus(long orderId) {
        return required(send(order, "GET", "orders/" + orderId, null))
                .path("status")
                .asString();
    }

    /** Sends the request; returns the JSON body, or null for an empty one (204). A non-2xx throws ApiException. */
    private @Nullable JsonNode send(URI service, String method, String path, @Nullable Object body) {
        return send(service, method, path, body, Map.of());
    }

    private @Nullable JsonNode send(
            URI service, String method, String path, @Nullable Object body, Map<String, String> headers) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        HttpRequest.Builder builder = HttpRequest.newBuilder(service.resolve(path))
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
