package io.github.danie1le.dropoff.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.contracts.MenuItemChanged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/** Menu item endpoints end to end, against a real Postgres. Each test rolls back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class MenuItemApiIT {

    private static final String TACO_SHOP = """
            {"name":"Taco Shop","address":"100 Congress Ave","lat":30.2672,"lng":-97.7431}
            """;

    private final MockMvcTester mvc;
    private final JdbcTemplate jdbc;

    MenuItemApiIT(@Autowired MockMvcTester mvc, @Autowired JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private long merchant() {
        return idFromLocation(mvc.post()
                .uri("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TACO_SHOP)
                .exchange());
    }

    private static long idFromLocation(MvcTestResult result) {
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private MvcTestResult addItem(Object merchantId, String json) {
        return mvc.post()
                .uri("/api/v1/merchants/{id}/menu-items", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static void assertFieldError(MvcTestResult result, String field) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .isNotEmpty()
                .allMatch(field::equals);
    }

    @Test
    void addsAnAvailableItemWithATrimmedNameAndPointsToIt() {
        long merchantId = merchant();

        MvcTestResult result = addItem(merchantId, """
                {"name":"  Al Pastor Taco ","priceCents":399}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).matches(".*/api/v1/menu-items/\\d+");
        long itemId = idFromLocation(result);
        assertThat(result).bodyJson().isEqualTo("""
                {"id":%d,"merchantId":%d,"name":"Al Pastor Taco","priceCents":399,"available":true}
                """.formatted(itemId, merchantId));
    }

    @Test
    void returnsAMenuItemById() {
        long merchantId = merchant();
        long itemId = idFromLocation(addItem(merchantId, """
                {"name":"Horchata","priceCents":250}
                """));

        MvcTestResult result = mvc.get().uri("/api/v1/menu-items/{id}", itemId).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isEqualTo("""
                {"id":%d,"merchantId":%d,"name":"Horchata","priceCents":250,"available":true}
                """.formatted(itemId, merchantId));
    }

    @Test
    void acceptsAFreeItem() {
        MvcTestResult result = addItem(merchant(), """
                {"name":"Salsa","priceCents":0}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.priceCents").isEqualTo(0);
    }

    @Test
    void reportsAnUnknownMerchantOnCreateAsNotFound() {
        MvcTestResult result = addItem(999_999, """
                {"name":"Salsa","priceCents":0}
                """);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MERCHANT_NOT_FOUND");
    }

    @Test
    void reportsAnUnknownMenuItemAsNotFound() {
        MvcTestResult result = mvc.get().uri("/api/v1/menu-items/{id}", 999_999).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void rejectsABlankName() {
        assertFieldError(addItem(merchant(), """
                {"name":"   ","priceCents":100}
                """), "name");
    }

    @Test
    void rejectsAMissingName() {
        assertFieldError(addItem(merchant(), """
                {"priceCents":100}
                """), "name");
    }

    @Test
    void rejectsANameOver200Characters() {
        assertFieldError(addItem(merchant(), """
                {"name":"%s","priceCents":100}
                """.formatted("x".repeat(201))), "name");
    }

    @Test
    void rejectsAMissingPrice() {
        assertFieldError(addItem(merchant(), """
                {"name":"Salsa"}
                """), "priceCents");
    }

    @Test
    void rejectsANegativePrice() {
        assertFieldError(addItem(merchant(), """
                {"name":"Salsa","priceCents":-1}
                """), "priceCents");
    }

    @Test
    void rejectsADecimalPrice() {
        assertFieldError(addItem(merchant(), """
                {"name":"Salsa","priceCents":9.99}
                """), "priceCents");
    }

    @Test
    void rejectsMalformedJson() {
        MvcTestResult result = addItem(merchant(), "{\"name\":");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void rejectsANonNumericMerchantIdNamingTheParameter() {
        assertFieldError(addItem("abc", """
                {"name":"Salsa","priceCents":0}
                """), "id");
    }

    @Test
    void rejectsANonNumericMenuItemIdNamingTheParameter() {
        assertFieldError(mvc.get().uri("/api/v1/menu-items/{id}", "abc").exchange(), "id");
    }

    @Test
    void readsAMerchantsWholeMenuInIdOrderIncludingUnavailableItems() {
        long merchantId = merchant();
        long first = idFromLocation(addItem(merchantId, """
                {"name":"Horchata","priceCents":250}
                """));
        // Inserted directly: the API cannot yet mark an item unavailable (#61).
        long second = jdbc.queryForObject(
                "INSERT INTO menu_items (merchant_id, name, price_cents, is_available)"
                        + " VALUES (?, 'Salsa', 0, FALSE) RETURNING id",
                Long.class,
                merchantId);
        addItem(merchant(), """
                {"name":"Other Shop Taco","priceCents":100}
                """);

        MvcTestResult result =
                mvc.get().uri("/api/v1/merchants/{id}/menu", merchantId).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [{"id":%d,"merchantId":%d,"name":"Horchata","priceCents":250,"available":true},
                 {"id":%d,"merchantId":%d,"name":"Salsa","priceCents":0,"available":false}]
                """.formatted(first, merchantId, second, merchantId));
    }

    @Test
    void readsAnEmptyMenu() {
        MvcTestResult result =
                mvc.get().uri("/api/v1/merchants/{id}/menu", merchant()).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void readsAClosedMerchantsMenu() {
        long merchantId = merchant();
        addItem(merchantId, """
                {"name":"Horchata","priceCents":250}
                """);
        jdbc.update("UPDATE merchants SET is_open = FALSE WHERE id = ?", merchantId);

        MvcTestResult result =
                mvc.get().uri("/api/v1/merchants/{id}/menu", merchantId).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[*].name").asArray().containsExactly("Horchata");
    }

    @Test
    void reportsAnUnknownMerchantsMenuAsNotFound() {
        MvcTestResult result =
                mvc.get().uri("/api/v1/merchants/{id}/menu", 999_999).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MERCHANT_NOT_FOUND");
    }

    @Test
    void databaseRejectsANegativePrice() {
        long merchantId = merchant();

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO menu_items (merchant_id, name, price_cents) VALUES (?, 'Salsa', -1)", merchantId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long item(String json) {
        return idFromLocation(addItem(merchant(), json));
    }

    private MvcTestResult patchItem(Object itemId, String json) {
        return mvc.patch()
                .uri("/api/v1/menu-items/{id}", itemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    @Test
    void updatesNamePriceAndAvailabilityTrimmingTheName() {
        long itemId = item("""
                {"name":"Horchata","priceCents":250}
                """);

        MvcTestResult result = patchItem(itemId, """
                {"name":"  Large Horchata ","priceCents":350,"available":false}
                """);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Large Horchata");
        assertThat(result).bodyJson().extractingPath("$.priceCents").isEqualTo(350);
        assertThat(result).bodyJson().extractingPath("$.available").isEqualTo(false);
        assertThat(mvc.get().uri("/api/v1/menu-items/{id}", itemId).exchange())
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Large Horchata");
    }

    /** The order module validates and prices orders from its copy of each item (ADR-0007). */
    @Test
    void publishesTheNewItem(ApplicationEvents events) {
        long merchantId = merchant();

        long itemId = idFromLocation(addItem(merchantId, """
                {"name":"Horchata","priceCents":250}
                """));

        assertThat(events.stream(MenuItemChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.menuItemId()).isEqualTo(itemId);
            assertThat(e.merchantId()).isEqualTo(merchantId);
            assertThat(e.name()).isEqualTo("Horchata");
            assertThat(e.priceCents()).isEqualTo(250);
            assertThat(e.available()).isTrue();
            assertThat(e.eventId()).isNotNull();
            assertThat(e.occurredAt()).isNotNull();
        });
    }

    @Test
    void publishesTheWholeItemAfterAnUpdate(ApplicationEvents events) {
        long itemId = item("""
                {"name":"Horchata","priceCents":250}
                """);

        assertThat(patchItem(itemId, "{\"available\":false}")).hasStatusOk();

        assertThat(events.stream(MenuItemChanged.class).toList())
                .hasSize(2)
                .last()
                .satisfies(e -> {
                    assertThat(e.menuItemId()).isEqualTo(itemId);
                    assertThat(e.available()).isFalse();
                    assertThat(e.name()).isEqualTo("Horchata");
                    assertThat(e.priceCents()).isEqualTo(250);
                });
    }

    @Test
    void emptyOrNullUpdateLeavesTheItemUnchanged() {
        long merchantId = merchant();
        long itemId = idFromLocation(addItem(merchantId, """
                {"name":"Horchata","priceCents":250}
                """));

        for (String body : new String[] {"{}", "{\"name\":null,\"priceCents\":null,\"available\":null}"}) {
            MvcTestResult result = patchItem(itemId, body);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().isEqualTo("""
                    {"id":%d,"merchantId":%d,"name":"Horchata","priceCents":250,"available":true}
                    """.formatted(itemId, merchantId));
        }
    }

    @Test
    void markingUnavailableThenAvailableRoundTrips() {
        long itemId = item("""
                {"name":"Horchata","priceCents":250}
                """);

        assertThat(patchItem(itemId, "{\"available\":false}"))
                .bodyJson()
                .extractingPath("$.available")
                .isEqualTo(false);
        assertThat(patchItem(itemId, "{\"available\":true}"))
                .bodyJson()
                .extractingPath("$.available")
                .isEqualTo(true);
    }

    @Test
    void rejectsABlankOrOver200CharacterNameOnUpdate() {
        long itemId = item("""
                {"name":"Horchata","priceCents":250}
                """);

        assertFieldError(patchItem(itemId, "{\"name\":\"  \"}"), "name");
        assertFieldError(patchItem(itemId, "{\"name\":\"%s\"}".formatted("x".repeat(201))), "name");
    }

    @Test
    void rejectsANegativePriceOnUpdate() {
        assertFieldError(patchItem(item("""
                {"name":"Horchata","priceCents":250}
                """), "{\"priceCents\":-1}"), "priceCents");
    }

    @Test
    void rejectsADecimalPriceOnUpdateInsteadOfTruncatingIt() {
        long itemId = item("""
                {"name":"Horchata","priceCents":250}
                """);

        assertFieldError(patchItem(itemId, "{\"priceCents\":9.99}"), "priceCents");
        assertThat(mvc.get().uri("/api/v1/menu-items/{id}", itemId).exchange())
                .bodyJson()
                .extractingPath("$.priceCents")
                .isEqualTo(250);
    }

    @Test
    void reportsAnUnknownMenuItemOnUpdateAsNotFound() {
        MvcTestResult result = patchItem(999_999, "{}");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void hasNoDeleteEndpoint() {
        MvcTestResult result = mvc.delete().uri("/api/v1/menu-items/{id}", 1).exchange();

        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }
}
