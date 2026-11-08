package io.github.danie1le.dropoff.order;

import static io.github.danie1le.dropoff.order.OrderFixtures.customer;
import static io.github.danie1le.dropoff.order.OrderFixtures.item;
import static io.github.danie1le.dropoff.order.OrderFixtures.merchant;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/** Placing an order end to end, against a real Postgres. Each test rolls back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PlaceOrderApiIT {

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;

    PlaceOrderApiIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private MvcTestResult place(long customerId, long merchantId, String lines) {
        return mvc.post()
                .uri("/api/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":%d,\"merchantId\":%d,\"lines\":%s}".formatted(customerId, merchantId, lines))
                .exchange();
    }

    private static String line(long menuItemId, int quantity) {
        return "{\"menuItemId\":%d,\"quantity\":%d}".formatted(menuItemId, quantity);
    }

    private static void assertRejected(MvcTestResult result, String code) {
        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    @Test
    void placesAnOrderWithServerComputedTotalAndSnapshots() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);
        long soda = item(jdbc, merchantId, 150, true);

        MvcTestResult result = place(customerId, merchantId, "[" + line(taco, 2) + "," + line(soda, 1) + "]");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).matches(".*/api/v1/orders/\\d+");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PLACED");
        assertThat(result).bodyJson().extractingPath("$.totalCents").isEqualTo(948);
        assertThat(result).bodyJson().extractingPath("$.courierId").isNull();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.deliveryAddress.address")
                .isEqualTo("200 Lavaca St");
        assertThat(result).bodyJson().extractingPath("$.deliveryAddress.lat").isEqualTo(30.2669);
        assertThat(result).bodyJson().extractingPath("$.deliveryAddress.lng").isEqualTo(-97.7466);
        assertThat(result).bodyJson().extractingPath("$.lines[0].menuItemId").isEqualTo((int) taco);
        assertThat(result).bodyJson().extractingPath("$.lines[0].quantity").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.lines[0].priceCents").isEqualTo(399);
        assertThat(result).bodyJson().extractingPath("$.lines[1].priceCents").isEqualTo(150);
    }

    @Test
    void mergesRepeatedMenuItemsIntoOneLine() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);

        MvcTestResult result = place(customerId, merchantId, "[" + line(taco, 1) + "," + line(taco, 2) + "]");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.lines.length()").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.lines[0].quantity").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.totalCents").isEqualTo(1197);
    }

    @Test
    void keepsSnapshotsWhenPricesAndAddressChangeLater() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);
        MvcTestResult placed = place(customerId, merchantId, "[" + line(taco, 1) + "]");
        assertThat(placed).hasStatus(HttpStatus.CREATED);

        jdbc.sql("UPDATE menu_item_copies SET price_cents = 999 WHERE menu_item_id = ?")
                .param(taco)
                .update();
        jdbc.sql("UPDATE customers SET address = 'Elsewhere' WHERE id = ?")
                .param(customerId)
                .update();

        var row = jdbc.sql("SELECT o.total_cents, o.delivery_address, l.price_cents FROM orders o JOIN order_lines l ON"
                        + " l.order_id = o.id WHERE o.customer_id = ?")
                .param(customerId)
                .query()
                .singleRow();
        assertThat(row).containsEntry("total_cents", 399L);
        assertThat(row).containsEntry("delivery_address", "200 Lavaca St");
        assertThat(row).containsEntry("price_cents", 399L);
    }

    @Test
    void recordsThePlacementInTheStatusHistory() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);

        assertThat(place(customerId, merchantId, "[" + line(taco, 1) + "]")).hasStatus(HttpStatus.CREATED);

        var row = jdbc.sql("SELECT e.from_status, e.to_status, e.actor FROM order_events e JOIN orders o ON o.id ="
                        + " e.order_id WHERE o.customer_id = ?")
                .param(customerId)
                .query()
                .singleRow();
        assertThat(row.get("from_status")).isNull();
        assertThat(row).containsEntry("to_status", "PLACED").containsEntry("actor", "CUSTOMER");
    }

    @Test
    void rejectsAnUnknownCustomer() {
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);

        assertRejected(place(999_999, merchantId, "[" + line(taco, 1) + "]"), "CUSTOMER_UNKNOWN");
    }

    @Test
    void rejectsAnUnknownMerchant() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);

        assertRejected(place(customerId, 999_999, "[" + line(taco, 1) + "]"), "MERCHANT_UNKNOWN");
    }

    @Test
    void rejectsAnUnknownMenuItem() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);

        assertRejected(place(customerId, merchantId, "[" + line(999_999, 1) + "]"), "MENU_ITEM_UNKNOWN");
    }

    @Test
    void rejectsAnUnavailableMenuItem() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, false);

        assertRejected(place(customerId, merchantId, "[" + line(taco, 1) + "]"), "MENU_ITEM_UNAVAILABLE");
    }

    @Test
    void rejectsAMenuItemFromAnotherMerchant() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long otherTaco = item(jdbc, merchant(jdbc, true), 399, true);

        assertRejected(place(customerId, merchantId, "[" + line(otherTaco, 1) + "]"), "MENU_ITEM_WRONG_MERCHANT");
    }

    @Test
    void rejectsAClosedMerchant() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, false);
        long taco = item(jdbc, merchantId, 399, true);

        assertRejected(place(customerId, merchantId, "[" + line(taco, 1) + "]"), "MERCHANT_CLOSED");
    }

    // Validation reads the order module's copies, not the merchant tables (M5-3, ADR-0007). Each test below makes the
    // two disagree, and the copy wins.

    @Test
    void rejectsAMerchantWithNoCopyAsUnknown() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);
        jdbc.sql("DELETE FROM merchant_copies WHERE merchant_id = ?")
                .param(merchantId)
                .update();

        assertRejected(place(customerId, merchantId, "[" + line(taco, 1) + "]"), "MERCHANT_UNKNOWN");
    }

    @Test
    void readsOpenFromTheMerchantCopy() {
        long customerId = customer(jdbc);
        long closedInCopy = merchant(jdbc, true);
        long taco = item(jdbc, closedInCopy, 399, true);
        jdbc.sql("UPDATE merchant_copies SET open = false WHERE merchant_id = ?")
                .param(closedInCopy)
                .update();
        long openInCopy = merchant(jdbc, true);
        long soda = item(jdbc, openInCopy, 150, true);
        jdbc.sql("UPDATE merchants SET is_open = false WHERE id = ?")
                .param(openInCopy)
                .update();

        assertRejected(place(customerId, closedInCopy, "[" + line(taco, 1) + "]"), "MERCHANT_CLOSED");
        assertThat(place(customerId, openInCopy, "[" + line(soda, 1) + "]")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void rejectsAMenuItemWithNoCopyAsUnknown() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);
        jdbc.sql("DELETE FROM menu_item_copies WHERE menu_item_id = ?")
                .param(taco)
                .update();

        assertRejected(place(customerId, merchantId, "[" + line(taco, 1) + "]"), "MENU_ITEM_UNKNOWN");
    }

    @Test
    void readsAvailabilityFromTheMenuItemCopy() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);
        jdbc.sql("UPDATE menu_item_copies SET available = false WHERE menu_item_id = ?")
                .param(taco)
                .update();

        assertRejected(place(customerId, merchantId, "[" + line(taco, 1) + "]"), "MENU_ITEM_UNAVAILABLE");
    }

    @Test
    void pricesLinesFromTheMenuItemCopy() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);
        jdbc.sql("UPDATE menu_item_copies SET price_cents = 450 WHERE menu_item_id = ?")
                .param(taco)
                .update();

        MvcTestResult result = place(customerId, merchantId, "[" + line(taco, 2) + "]");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.lines[0].priceCents").isEqualTo(450);
        assertThat(result).bodyJson().extractingPath("$.totalCents").isEqualTo(900);
    }

    @Test
    void rejectsEmptyLinesAndNonPositiveQuantities() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);

        MvcTestResult empty = place(customerId, merchantId, "[]");
        MvcTestResult zero = place(customerId, merchantId, "[" + line(1, 0) + "]");

        assertThat(empty).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(empty).bodyJson().extractingPath("$.errors[0].field").isEqualTo("lines");
        assertThat(zero).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(zero).bodyJson().extractingPath("$.errors[0].field").isEqualTo("lines[0].quantity");
    }

    @Test
    void rejectsAQuantityAboveTheLimit() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);

        MvcTestResult tooMany = place(customerId, merchantId, "[" + line(1, 1000) + "]");

        assertThat(tooMany).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(tooMany).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(tooMany).bodyJson().extractingPath("$.errors[0].field").isEqualTo("lines[0].quantity");
    }

    @Test
    void acceptsAQuantityAtTheLimit() {
        long customerId = customer(jdbc);
        long merchantId = merchant(jdbc, true);
        long taco = item(jdbc, merchantId, 399, true);

        assertThat(place(customerId, merchantId, "[" + line(taco, 999) + "]")).hasStatus(HttpStatus.CREATED);
    }
}
