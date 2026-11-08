package io.github.danie1le.dropoff.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import io.github.danie1le.dropoff.contracts.MerchantChanged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/** Merchant endpoints end to end, against a real Postgres. Each test rolls back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
@RecordApplicationEvents
class MerchantApiIT {

    private static final String TACO_SHOP = """
            {"name":"Taco Shop","address":"100 Congress Ave","lat":30.2672,"lng":-97.7431}
            """;

    private final MockMvcTester mvc;

    MerchantApiIT(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    private MvcTestResult create(String json) {
        return mvc.post()
                .uri("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private long createdId(MvcTestResult result) {
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private MvcTestResult patch(long id, String json) {
        return mvc.patch()
                .uri("/api/v1/merchants/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    @Test
    void createsAnOpenMerchantAndPointsToIt() {
        MvcTestResult result = create(TACO_SHOP);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).matches(".*/api/v1/merchants/\\d+");
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Taco Shop");
        assertThat(result).bodyJson().extractingPath("$.address").isEqualTo("100 Congress Ave");
        assertThat(result).bodyJson().extractingPath("$.lat").isEqualTo(30.2672);
        assertThat(result).bodyJson().extractingPath("$.lng").isEqualTo(-97.7431);
        assertThat(result).bodyJson().extractingPath("$.open").isEqualTo(true);
    }

    /** The order module keeps its merchant copies from this event (ADR-0007), so it carries the whole merchant. */
    @Test
    void publishesTheNewMerchant(ApplicationEvents events) {
        long id = createdId(create(TACO_SHOP));

        assertThat(events.stream(MerchantChanged.class)).singleElement().satisfies(e -> {
            assertThat(e.merchantId()).isEqualTo(id);
            assertThat(e.name()).isEqualTo("Taco Shop");
            assertThat(e.address()).isEqualTo("100 Congress Ave");
            assertThat(e.open()).isTrue();
            assertThat(e.lat()).isEqualTo(30.2672);
            assertThat(e.lng()).isEqualTo(-97.7431);
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.eventId()).isNotNull();
            assertThat(e.occurredAt()).isNotNull();
        });
    }

    @Test
    void publishesTheWholeMerchantAfterAnUpdate(ApplicationEvents events) {
        long id = createdId(create(TACO_SHOP));

        assertThat(patch(id, "{\"open\":false}")).hasStatusOk();

        assertThat(events.stream(MerchantChanged.class).toList())
                .hasSize(2)
                .last()
                .satisfies(e -> {
                    assertThat(e.merchantId()).isEqualTo(id);
                    assertThat(e.open()).isFalse();
                    assertThat(e.name()).isEqualTo("Taco Shop");
                    assertThat(e.address()).isEqualTo("100 Congress Ave");
                    assertThat(e.version()).isEqualTo(2);
                });
    }

    @Test
    void returnsAMerchantById() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = mvc.get().uri("/api/v1/merchants/{id}", id).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo((int) id);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Taco Shop");
    }

    @Test
    void reportsAnUnknownMerchantAsNotFound() {
        MvcTestResult result = mvc.get().uri("/api/v1/merchants/{id}", 999_999).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MERCHANT_NOT_FOUND");
    }

    @Test
    void rejectsACreateRequestWithBlankNameAndOutOfRangeLatitude() {
        MvcTestResult result = create("""
                {"name":"  ","address":"100 Congress Ave","lat":91,"lng":-97.7431}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .contains("name", "lat");
    }

    @Test
    void rejectsAControlCharacterOnlyNameOnCreate() {
        MvcTestResult result = create("""
                {"name":"\\u0001","address":"100 Congress Ave","lat":30.2672,"lng":-97.7431}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void rejectsANulCharacterInANameOnCreate() {
        MvcTestResult result = create("""
                {"name":"Taco\\u0000Shop","address":"100 Congress Ave","lat":30.2672,"lng":-97.7431}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void rejectsANonFiniteLatitudeOnCreate() {
        MvcTestResult result = create("""
                {"name":"Taco Shop","address":"1 Main","lat":"NaN","lng":-97.7}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("lat");
    }

    @Test
    void listsMerchantsOnePageAtATime() {
        create(TACO_SHOP);
        create(TACO_SHOP.replace("Taco Shop", "Noodle Bar"));
        create(TACO_SHOP.replace("Taco Shop", "Pizza Place"));

        MvcTestResult result = mvc.get().uri("/api/v1/merchants?size=2").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.content").asArray().hasSize(2);
        assertThat(result).bodyJson().extractingPath("$.page.size").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(3);
    }

    @Test
    void closesAMerchantWithoutTouchingOtherFields() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"open\":false}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.open").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Taco Shop");
        assertThat(result).bodyJson().extractingPath("$.address").isEqualTo("100 Congress Ave");
    }

    @Test
    void renamesAMerchant() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"name\":\"Taco Palace\"}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Taco Palace");
        assertThat(result).bodyJson().extractingPath("$.open").isEqualTo(true);
    }

    @Test
    void movesAMerchantWhenAddressAndCoordinatesChangeTogether() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"address\":\"500 Lamar Blvd\",\"lat\":30.27,\"lng\":-97.75}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.address").isEqualTo("500 Lamar Blvd");
        assertThat(result).bodyJson().extractingPath("$.lat").isEqualTo(30.27);
        assertThat(result).bodyJson().extractingPath("$.lng").isEqualTo(-97.75);
    }

    @Test
    void rejectsAnAddressChangeWithoutCoordinates() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"address\":\"500 Lamar Blvd\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("lat", "lng");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].message")
                .asArray()
                .containsOnly("is required when changing the location");
    }

    @Test
    void rejectsALatitudeOnlyLocationChange() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"lat\":30.1}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("address", "lng");
    }

    @Test
    void rejectsALongitudeOnlyLocationChange() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"lng\":-97.7}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("address", "lat");
    }

    @Test
    void rejectsCoordinatesWithoutAnAddress() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"lat\":30.1,\"lng\":-97.7}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("address");
    }

    @Test
    void rejectsAnOutOfRangeLatitudeOnUpdate() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"address\":\"x\",\"lat\":95,\"lng\":-97.7}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("lat");
    }

    @Test
    void rejectsABlankNameOnUpdate() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"name\":\"   \"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void rejectsAControlCharacterOnlyNameOnUpdate() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"name\":\"\\u0001\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactly("name");
    }

    @Test
    void acceptsANameWithALineBreakOnUpdateLikeOnCreate() {
        long id = createdId(create(TACO_SHOP));

        MvcTestResult result = patch(id, "{\"name\":\"Taco\\nShop\"}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Taco\nShop");
    }

    @Test
    void reportsAnUpdateOfAnUnknownMerchantAsNotFound() {
        MvcTestResult result = patch(999_999, "{\"open\":false}");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("MERCHANT_NOT_FOUND");
    }
}
