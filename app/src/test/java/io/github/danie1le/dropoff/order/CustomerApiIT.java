package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.danie1le.dropoff.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/** Customer endpoints end to end, against a real Postgres. Each test rolls back. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerApiIT {

    private static final String ALICE = """
            {"name":"Alice","address":"200 Lavaca St","lat":30.2669,"lng":-97.7466}
            """;

    private final MockMvcTester mvc;

    CustomerApiIT(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    private MvcTestResult create(String json) {
        return mvc.post()
                .uri("/api/v1/customers")
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
                .uri("/api/v1/customers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    @Test
    void createsACustomerAndPointsToIt() {
        MvcTestResult result = create(ALICE);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).matches(".*/api/v1/customers/\\d+");
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Alice");
        assertThat(result).bodyJson().extractingPath("$.address").isEqualTo("200 Lavaca St");
        assertThat(result).bodyJson().extractingPath("$.lat").isEqualTo(30.2669);
        assertThat(result).bodyJson().extractingPath("$.lng").isEqualTo(-97.7466);
    }

    @Test
    void returnsACustomerById() {
        long id = createdId(create(ALICE));

        MvcTestResult result = mvc.get().uri("/api/v1/customers/{id}", id).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo((int) id);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Alice");
    }

    @Test
    void reportsAnUnknownCustomerAsNotFound() {
        MvcTestResult result = mvc.get().uri("/api/v1/customers/{id}", 999_999).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @Test
    void rejectsACreateRequestWithBlankNameAndOutOfRangeLongitude() {
        MvcTestResult result = create("""
                {"name":"  ","address":"200 Lavaca St","lat":30.2669,"lng":181}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "lng");
    }

    @Test
    void rejectsACreateRequestWithoutCoordinates() {
        MvcTestResult result = create("""
                {"name":"Alice","address":"200 Lavaca St"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("lat", "lng");
    }

    @Test
    void renamesACustomerWithoutTouchingTheAddress() {
        long id = createdId(create(ALICE));

        MvcTestResult result = patch(id, "{\"name\":\"Alice B\"}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Alice B");
        assertThat(result).bodyJson().extractingPath("$.address").isEqualTo("200 Lavaca St");
    }

    @Test
    void movesACustomerWhenAddressAndCoordinatesChangeTogether() {
        long id = createdId(create(ALICE));

        MvcTestResult result = patch(id, "{\"address\":\"500 Lamar Blvd\",\"lat\":30.27,\"lng\":-97.75}");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.address").isEqualTo("500 Lamar Blvd");
        assertThat(result).bodyJson().extractingPath("$.lat").isEqualTo(30.27);
        assertThat(result).bodyJson().extractingPath("$.lng").isEqualTo(-97.75);
    }

    @Test
    void rejectsAnAddressChangeWithoutCoordinates() {
        long id = createdId(create(ALICE));

        MvcTestResult result = patch(id, "{\"address\":\"500 Lamar Blvd\"}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("lat", "lng");
    }

    @Test
    void rejectsCoordinatesWithoutAnAddress() {
        long id = createdId(create(ALICE));

        MvcTestResult result = patch(id, "{\"lat\":30.1,\"lng\":-97.7}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactly("address");
    }

    @Test
    void rejectsAnOutOfRangeLatitudeOnUpdate() {
        long id = createdId(create(ALICE));

        MvcTestResult result = patch(id, "{\"address\":\"x\",\"lat\":-91,\"lng\":-97.7}");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactly("lat");
    }

    @Test
    void reportsAnUpdateOfAnUnknownCustomerAsNotFound() {
        MvcTestResult result = patch(999_999, "{\"name\":\"Bob\"}");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CUSTOMER_NOT_FOUND");
    }
}
