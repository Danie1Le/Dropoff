package io.github.danie1le.dropoff.order.internal;

import io.github.danie1le.dropoff.common.DomainException;
import io.github.danie1le.dropoff.merchant.MerchantCatalog;
import io.github.danie1le.dropoff.merchant.MerchantCatalog.MenuItemView;
import io.github.danie1le.dropoff.merchant.MerchantCatalog.MerchantView;
import io.github.danie1le.dropoff.order.OrderPlaced;
import io.github.danie1le.dropoff.order.internal.OrderRequests.OrderResponse;
import io.github.danie1le.dropoff.order.internal.OrderRequests.PlaceOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderService {

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final MerchantCatalog catalog;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    OrderService(
            OrderRepository orders,
            CustomerRepository customers,
            MerchantCatalog catalog,
            JdbcClient jdbc,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.customers = customers;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.events = events;
    }

    /** The outcome of a place-order request: the order, and whether it was an earlier order replayed. */
    record Placement(long orderId, OrderResponse body, boolean replayed) {}

    /**
     * Places a PLACED order and publishes {@link OrderPlaced}, or replays the order already placed with this
     * idempotency key (M2-4). A replay skips every other check, so it succeeds even if, say, the merchant has closed
     * since. Otherwise checks run in this order: customer, merchant, merchant open, then each menu item by ascending
     * id (unknown, wrong merchant, unavailable). Repeated menu items merge into one line, since an order has one line
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
        MerchantView merchant = catalog.findMerchant(merchantId)
                .orElseThrow(() -> unprocessable("MERCHANT_UNKNOWN", "The merchant does not exist."));
        if (!merchant.open()) {
            throw unprocessable("MERCHANT_CLOSED", "The merchant is closed.");
        }

        Map<Long, MenuItemView> items = catalog.findMenuItems(quantities.keySet()).stream()
                .collect(Collectors.toMap(MenuItemView::id, Function.identity()));
        List<OrderLine> lines = new ArrayList<>();
        quantities.forEach((menuItemId, quantity) -> {
            MenuItemView item = items.get(menuItemId);
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
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Order order = orders.save(new Order(
                customerId,
                merchantId,
                new Location(customer.address(), customer.lat(), customer.lng()),
                lines,
                idempotencyKey,
                requestHash,
                now));
        jdbc.sql("INSERT INTO order_events (order_id, from_status, to_status, actor, created_at)"
                        + " VALUES (?, NULL, ?, ?, ?)")
                .params(order.id(), OrderStatus.PLACED.name(), Actor.CUSTOMER.name(), Timestamp.from(now))
                .update();
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

    /** {@code @Valid} rejects nulls before the service runs. */
    private static <T> T required(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("validate the request with @Valid first");
        }
        return value;
    }

    private static DomainException unprocessable(String code, String message) {
        return new DomainException(DomainException.Category.UNPROCESSABLE, code, message);
    }
}
