package io.github.danie1le.dropoff.order.internal;

import static io.github.danie1le.dropoff.web.Validated.required;

import io.github.danie1le.dropoff.common.DbClock;
import io.github.danie1le.dropoff.contracts.Actor;
import io.github.danie1le.dropoff.contracts.OrderPlaced;
import io.github.danie1le.dropoff.order.internal.MerchantCopies.MenuItemCopy;
import io.github.danie1le.dropoff.order.internal.MerchantCopies.MerchantCopy;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.order.internal.OrderRequests.PlaceOrder;
import io.github.danie1le.dropoff.web.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final MerchantCopies merchants;
    private final OrderEventLog eventLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OrderService(
            OrderRepository orders,
            CustomerRepository customers,
            MerchantCopies merchants,
            OrderEventLog eventLog,
            ApplicationEventPublisher events,
            Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.merchants = merchants;
        this.eventLog = eventLog;
        this.events = events;
        this.clock = clock;
    }

    /** One order as a response body, built inside the transaction because its lines load lazily. */
    @Transactional(readOnly = true)
    OrderResponse get(long orderId) {
        return OrderResponse.of(orders.findById(orderId).orElseThrow(() -> OrderCommandService.notFound(orderId)));
    }

    /** The outcome of a place-order request: the order, and whether it was an earlier order replayed. */
    record Placement(long orderId, OrderResponse body, boolean replayed) {}

    /**
     * Places a PLACED order and publishes {@link OrderPlaced}, or replays the order already placed with this
     * idempotency key (M2-4). A replay skips every other check, so it succeeds even if, say, the merchant has closed
     * since. Otherwise checks run in this order: customer, merchant, merchant open, then each menu item by ascending
     * id (unknown, wrong merchant, unavailable). Merchants and items are read from the order module's own copies
     * (M5-3, ADR-0007), so an id merchant has not published yet is unknown here. Repeated menu items merge into one line, since an order has one line
     * per menu item.
     *
     * <p>A concurrent request with the same key waits on the unique index and then fails with
     * {@link org.springframework.dao.DataIntegrityViolationException}; the caller then calls {@link #replay}.
     */
    @Transactional
    Placement place(String idempotencyKey, PlaceOrder request) {
        long customerId = required(request.customerId());
        long merchantId = required(request.merchantId());
        Map<Long, Integer> quantities = quantities(request);
        String requestHash = requestHash(customerId, merchantId, quantities);
        Optional<Placement> earlier = replay(idempotencyKey, requestHash);
        if (earlier.isPresent()) {
            return earlier.get();
        }

        Customer customer = customers
                .findById(customerId)
                .orElseThrow(() -> unprocessable("CUSTOMER_UNKNOWN", "The customer does not exist."));
        MerchantCopy merchant = merchants
                .findMerchant(merchantId)
                .orElseThrow(() -> unprocessable("MERCHANT_UNKNOWN", "The merchant does not exist."));
        if (!merchant.open()) {
            throw unprocessable("MERCHANT_CLOSED", "The merchant is closed.");
        }

        Map<Long, MenuItemCopy> items = merchants.findMenuItems(quantities.keySet()).stream()
                .collect(Collectors.toMap(MenuItemCopy::id, Function.identity()));
        List<OrderLine> lines = new ArrayList<>();
        quantities.forEach((menuItemId, quantity) -> {
            MenuItemCopy item = items.get(menuItemId);
            if (item == null) {
                throw unprocessable("MENU_ITEM_UNKNOWN", "A menu item does not exist.");
            }
            if (item.merchantId() != merchantId) {
                throw unprocessable("MENU_ITEM_WRONG_MERCHANT", "A menu item belongs to another merchant.");
            }
            if (!item.available()) {
                throw unprocessable("MENU_ITEM_UNAVAILABLE", "A menu item is unavailable.");
            }
            lines.add(new OrderLine(menuItemId, quantity, item.priceCents()));
        });

        // Postgres keeps microseconds; truncate so the response matches what a replay reads back.
        Instant now = DbClock.now(clock);
        Order order = orders.save(
                new Order(customerId, merchantId, customer.location(), lines, idempotencyKey, requestHash, now));
        eventLog.record(order.id(), null, OrderStatus.PLACED, Actor.CUSTOMER, now);
        events.publishEvent(
                new OrderPlaced(UUID.randomUUID(), order.id(), customerId, merchantId, order.totalCents(), now));
        return new Placement(order.id(), OrderResponse.of(order), false);
    }

    /**
     * The order already placed with this key, replayed, or empty if there is none. Throws 422
     * {@code IDEMPOTENCY_KEY_REUSED} if that order was placed from a different request.
     */
    @Transactional(readOnly = true)
    Optional<Placement> replay(String idempotencyKey, PlaceOrder request) {
        long customerId = required(request.customerId());
        long merchantId = required(request.merchantId());
        return replay(idempotencyKey, requestHash(customerId, merchantId, quantities(request)));
    }

    private Optional<Placement> replay(String idempotencyKey, String requestHash) {
        return orders.findByIdempotencyKey(idempotencyKey).map(order -> {
            if (!order.requestHash().equals(requestHash)) {
                throw unprocessable(
                        "IDEMPOTENCY_KEY_REUSED", "The idempotency key was already used for a different request.");
            }
            return new Placement(order.id(), OrderResponse.of(order), true);
        });
    }

    /** Quantity per menu item, sorted by menu item id, with repeated items merged. */
    private static Map<Long, Integer> quantities(PlaceOrder request) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (PlaceOrder.Line line : request.lines()) {
            quantities.merge(required(line.menuItemId()), required(line.quantity()), Math::addExact);
        }
        return quantities;
    }

    /** SHA-256 hex of the canonical request: customer, merchant, then lines sorted by menu item id (M2-4). */
    private static String requestHash(long customerId, long merchantId, Map<Long, Integer> sortedQuantities) {
        StringBuilder canonical =
                new StringBuilder().append(customerId).append('|').append(merchantId);
        sortedQuantities.forEach(
                (id, quantity) -> canonical.append('|').append(id).append('x').append(quantity));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static DomainException unprocessable(String code, String message) {
        return new DomainException(DomainException.Category.UNPROCESSABLE, code, message);
    }
}
