package io.github.danie1le.dropoff.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the Austin demo data through the public API (ticket #33): 10 merchants with 8 menu items each, 50 customers
 * and 30 couriers. Each courier also sends a first location, so {@code run} can make it AVAILABLE. The same seed
 * creates the same names, prices and places.
 */
final class Seeder {

    static final int MERCHANTS = 10;
    static final int ITEMS_PER_MERCHANT = 8;
    static final int CUSTOMERS = 50;
    static final int COURIERS = 30;

    /**
     * Merchants sit within 4 km of downtown, like a city's restaurant district, so a courier waiting downtown is inside
     * dispatch's 5 km search radius of every merchant. Customers and couriers spread over the whole 8 km city.
     */
    static final double MERCHANT_RADIUS_METERS = 4_000;

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);

    private static final List<String> MERCHANT_NAMES = List.of(
            "Congress Tacos",
            "South Lamar BBQ",
            "Barton Springs Burgers",
            "East Sixth Noodles",
            "Rainey Street Pizza",
            "Zilker Salads",
            "Mueller Curry House",
            "Hyde Park Bagels",
            "Clarksville Sushi",
            "Riverside Pho");
    private static final List<String> DISHES = List.of(
            "Breakfast taco",
            "Brisket plate",
            "Cheeseburger",
            "Spicy noodles",
            "Margherita pizza",
            "Kale salad",
            "Chicken curry",
            "Everything bagel",
            "Salmon roll",
            "Beef pho",
            "Queso and chips",
            "Iced tea");
    private static final List<String> STREETS = List.of(
            "Congress Ave",
            "Lamar Blvd",
            "Guadalupe St",
            "Burnet Rd",
            "Riverside Dr",
            "Cesar Chavez St",
            "Airport Blvd");

    private final DropoffApi api;

    Seeder(DropoffApi api) {
        this.api = api;
    }

    SimState seed(long seed) {
        Random random = new Random(seed);
        List<SimState.MerchantState> merchants = new ArrayList<>();
        for (int i = 0; i < MERCHANTS; i++) {
            LatLng at = Austin.randomPoint(random, MERCHANT_RADIUS_METERS);
            long merchantId = api.createMerchant(MERCHANT_NAMES.get(i), address(random), at);
            List<Long> items = new ArrayList<>();
            for (int j = 0; j < ITEMS_PER_MERCHANT; j++) {
                // Whole dollars minus one cent, from $4.99 to $19.99.
                long priceCents = (5 + random.nextInt(16)) * 100L - 1;
                items.add(api.createMenuItem(merchantId, DISHES.get((i + j) % DISHES.size()), priceCents));
            }
            merchants.add(new SimState.MerchantState(merchantId, at, items));
        }
        log.info("Created {} merchants with {} menu items each", MERCHANTS, ITEMS_PER_MERCHANT);

        List<Long> customers = new ArrayList<>();
        for (int i = 1; i <= CUSTOMERS; i++) {
            customers.add(api.createCustomer("Customer " + i, address(random), Austin.randomPoint(random)));
        }
        log.info("Created {} customers", CUSTOMERS);

        List<SimState.CourierState> couriers = new ArrayList<>();
        for (int i = 1; i <= COURIERS; i++) {
            long courierId = api.createCourier("Courier " + i);
            LatLng home = Austin.randomPoint(random);
            api.updateLocation(courierId, home);
            couriers.add(new SimState.CourierState(courierId, home));
        }
        log.info("Created {} couriers, each with a first location", COURIERS);
        return new SimState(seed, merchants, customers, couriers);
    }

    private static String address(Random random) {
        return (100 + random.nextInt(9_900)) + " " + STREETS.get(random.nextInt(STREETS.size())) + ", Austin, TX";
    }
}
