package io.github.danie1le.dropoff.simulator;

import java.util.List;
import java.util.Optional;

/**
 * The Dropoff endpoints the seeder and the bots call. A problem response throws {@link ApiException}. The bots depend
 * on this interface only, so their tests run against an in-memory fake with no HTTP.
 */
interface DropoffApi {

    long createMerchant(String name, String address, LatLng at);

    long createMenuItem(long merchantId, String name, long priceCents);

    long createCustomer(String name, String address, LatLng at);

    long createCourier(String name);

    void updateLocation(long courierId, LatLng at);

    CourierView courier(long courierId);

    /** {@link CourierStatus#AVAILABLE} or {@link CourierStatus#OFFLINE}; the server sets BUSY itself. */
    void changeStatus(long courierId, CourierStatus status);

    /** The courier's open offer, or empty on 204. */
    Optional<Offer> currentOffer(long courierId);

    void acceptOffer(long courierId, long offerId);

    void declineOffer(long courierId, long offerId);

    void pickUp(long courierId, long orderId);

    void deliver(long courierId, long orderId);

    long placeOrder(long customerId, long merchantId, List<OrderLine> lines);

    /** Ids of the merchant's PLACED orders, oldest first (first page only). */
    List<Long> placedOrders(long merchantId);

    void merchantStep(long merchantId, long orderId, MerchantStep step);
}
