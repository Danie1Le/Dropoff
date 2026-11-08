package io.github.danie1le.dropoff.simulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** An in-memory {@link DropoffApi} for bot tests: records every call and returns what the test set up. */
final class FakeApi implements DropoffApi {

    final List<String> calls = new ArrayList<>();
    final Map<Long, CourierView> couriers = new HashMap<>();
    /** The Idempotency-Key of every placeOrder call, in order. */
    final List<String> idempotencyKeys = new ArrayList<>();

    final Map<Long, Offer> offers = new HashMap<>();
    final Map<Long, Deque<List<Long>>> placedOrders = new HashMap<>();
    final List<LatLng> locations = new ArrayList<>();
    final List<PlacedOrder> orders = new ArrayList<>();
    final Map<Long, String> orderStatuses = new HashMap<>();

    record PlacedOrder(long customerId, long merchantId, List<OrderLine> lines) {}

    /** The next mutating call whose description starts with this prefix throws {@link #failure}. */
    @Nullable
    String failOn;

    @Nullable
    RuntimeException failure;

    private long nextId = 1;

    private void record(String call) {
        calls.add(call);
        if (failOn != null && call.startsWith(failOn) && failure != null) {
            RuntimeException toThrow = failure;
            failOn = null;
            failure = null;
            throw toThrow;
        }
    }

    @Override
    public long createMerchant(String name, String address, LatLng at) {
        record("createMerchant " + name);
        return nextId++;
    }

    @Override
    public long createMenuItem(long merchantId, String name, long priceCents) {
        record("createMenuItem " + merchantId);
        return nextId++;
    }

    @Override
    public long createCustomer(String name, String address, LatLng at) {
        record("createCustomer " + name);
        return nextId++;
    }

    @Override
    public long createCourier(String name) {
        record("createCourier " + name);
        return nextId++;
    }

    @Override
    public void updateLocation(long courierId, LatLng at) {
        record("location " + courierId);
        locations.add(at);
    }

    @Override
    public CourierView courier(long courierId) {
        record("courier " + courierId);
        CourierView view = couriers.get(courierId);
        if (view == null) {
            throw new ApiException(404, "COURIER_NOT_FOUND");
        }
        return view;
    }

    @Override
    public void changeStatus(long courierId, CourierStatus status) {
        record("status " + courierId + " " + status);
        CourierView old = couriers.get(courierId);
        couriers.put(courierId, new CourierView(status, old == null ? null : old.position()));
    }

    @Override
    public Optional<Offer> currentOffer(long courierId) {
        record("offer " + courierId);
        return Optional.ofNullable(offers.get(courierId));
    }

    @Override
    public void acceptOffer(long courierId, long offerId) {
        record("accept " + courierId + " " + offerId);
        offers.remove(courierId);
    }

    @Override
    public void declineOffer(long courierId, long offerId) {
        record("decline " + courierId + " " + offerId);
        offers.remove(courierId);
    }

    @Override
    public void pickUp(long courierId, long orderId) {
        record("pickUp " + courierId + " " + orderId);
    }

    @Override
    public void deliver(long courierId, long orderId) {
        record("deliver " + courierId + " " + orderId);
    }

    @Override
    public long placeOrder(long customerId, long merchantId, List<OrderLine> lines, String idempotencyKey) {
        idempotencyKeys.add(idempotencyKey);
        record("placeOrder " + customerId + " " + merchantId + " " + lines.size());
        orders.add(new PlacedOrder(customerId, merchantId, List.copyOf(lines)));
        return nextId++;
    }

    @Override
    public List<Long> placedOrders(long merchantId) {
        record("placed " + merchantId);
        Deque<List<Long>> answers = placedOrders.getOrDefault(merchantId, new ArrayDeque<>());
        List<Long> answer = answers.poll();
        return answer == null ? List.of() : answer;
    }

    @Override
    public void merchantStep(long merchantId, long orderId, MerchantStep step) {
        record(step + " " + merchantId + " " + orderId);
    }

    @Override
    public String orderStatus(long orderId) {
        record("orderStatus " + orderId);
        String status = orderStatuses.get(orderId);
        if (status == null) {
            throw new ApiException(404, "ORDER_NOT_FOUND");
        }
        return status;
    }

    /** Calls recorded so far that start with the prefix. */
    List<String> callsStartingWith(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).toList();
    }
}
