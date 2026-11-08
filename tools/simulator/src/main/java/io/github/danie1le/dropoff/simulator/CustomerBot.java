package io.github.danie1le.dropoff.simulator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All the customers as one bot (ticket #33): it places orders at a fixed rate (6 per minute by default). Each order
 * comes from a random customer, at a random merchant, with one to three different items, one or two of each.
 */
final class CustomerBot implements Bot {

    private static final int MAX_LINES = 3;
    private static final int MAX_QUANTITY = 2;

    private static final Logger log = LoggerFactory.getLogger(CustomerBot.class);

    private final SimState state;
    private final Duration interval;
    private final DropoffApi api;
    private final Random random;
    private final Stats stats;

    private Instant nextOrderAt = Instant.MIN;

    CustomerBot(SimState state, int ordersPerMinute, DropoffApi api, Random random, Stats stats) {
        if (ordersPerMinute < 1) {
            throw new IllegalArgumentException("ordersPerMinute must be at least 1");
        }
        this.state = state;
        this.interval = Duration.ofMinutes(1).dividedBy(ordersPerMinute);
        this.api = api;
        this.random = random;
        this.stats = stats;
    }

    @Override
    public void tick(Instant now) {
        if (now.isBefore(nextOrderAt)) {
            return;
        }
        // Keep the rate even if a tick runs late: the next order is due one interval after the last due time.
        nextOrderAt = nextOrderAt.equals(Instant.MIN) ? now.plus(interval) : nextOrderAt.plus(interval);
        long customerId = pick(state.customerIds());
        SimState.MerchantState merchant = pick(state.merchants());
        List<OrderLine> lines = lines(merchant.menuItemIds(), random);
        try {
            long orderId = api.placeOrder(customerId, merchant.id(), lines);
            stats.ordersPlaced++;
            log.info("Customer {} placed order {} at merchant {}", customerId, orderId, merchant.id());
        } catch (RuntimeException e) {
            stats.errors++;
            log.warn("Customer {} could not order at merchant {}: {}", customerId, merchant.id(), e.getMessage());
        }
    }

    /** One to three different items from the menu, one or two of each. The smoke test orders the same way. */
    static List<OrderLine> lines(List<Long> menuItemIds, Random random) {
        List<Long> items = new ArrayList<>(menuItemIds);
        int count = 1 + random.nextInt(Math.min(MAX_LINES, items.size()));
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long itemId = items.remove(random.nextInt(items.size()));
            lines.add(new OrderLine(itemId, 1 + random.nextInt(MAX_QUANTITY)));
        }
        return lines;
    }

    private <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
