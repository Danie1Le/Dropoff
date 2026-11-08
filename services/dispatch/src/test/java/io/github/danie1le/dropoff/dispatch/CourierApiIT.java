package io.github.danie1le.dropoff.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.danie1le.dropoff.DispatchTestcontainers;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Courier endpoints end to end, against real Postgres and Redis (M3-1, tickets #12, #42). The Redis writes run after
 * commit, so these tests commit for real; each one makes its own courier, so they don't see each other's rows. Each
 * test deletes its couriers afterwards and takes them out of the geo set, so later IT classes never find them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DispatchTestcontainers.class)
class CourierApiIT {

    private static final String GEO_KEY = "couriers:available";

    private final MockMvcTester mvc;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final List<Long> createdIds = new ArrayList<>();

    CourierApiIT(@Autowired MockMvcTester mvc, @Autowired StringRedisTemplate redis, @Autowired JdbcClient jdbc) {
        this.mvc = mvc;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    @AfterEach
    void deleteCreatedCouriers() {
        if (createdIds.isEmpty()) {
            return;
        }
        redis.opsForGeo()
                .remove(GEO_KEY, createdIds.stream().map(String::valueOf).toArray(String[]::new));
        jdbc.sql("DELETE FROM couriers WHERE id IN (:ids)")
                .param("ids", createdIds)
                .update();
        createdIds.clear();
    }

    /** POSTs a courier and remembers its id for {@link #deleteCreatedCouriers()} when it is created. */
    private MvcTestResult create(String json) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/couriers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        if (location != null) {
            createdIds.add(Long.parseLong(location.substring(location.lastIndexOf('/') + 1)));
        }
        return result;
    }

    private long newCourier() {
        MvcTestResult result = create("""
                {"name":"Carla"}
                """);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return createdIds.get(createdIds.size() - 1);
    }

    private MvcTestResult status(long id, String status) {
        return mvc.patch()
                .uri("/api/v1/couriers/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}")
                .exchange();
    }

    private MvcTestResult location(long id, String json) {
        return mvc.put()
                .uri("/api/v1/couriers/{id}/location", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult location(long id, double lat, double lng) {
        return location(id, "{\"lat\":" + lat + ",\"lng\":" + lng + "}");
    }

    private long availableCourierAt(double lat, double lng) {
        long id = newCourier();
        assertThat(location(id, lat, lng)).hasStatusOk();
        assertThat(status(id, "AVAILABLE")).hasStatusOk();
        return id;
    }

    /** The courier's position in the geo set, or null when they are not in it. */
    private @Nullable Point geoPosition(long id) {
        List<@Nullable Point> positions = redis.opsForGeo().position(GEO_KEY, Long.toString(id));
        return positions == null || positions.isEmpty() ? null : positions.get(0);
    }

    private static void assertAt(@Nullable Point point, double lat, double lng) {
        assertThat(point).isNotNull();
        // Redis stores positions as a 52-bit geohash, so they come back slightly rounded.
        assertThat(point.getY()).isCloseTo(lat, within(1e-5));
        assertThat(point.getX()).isCloseTo(lng, within(1e-5));
    }

    private String dbStatus(long id) {
        return jdbc.sql("SELECT status FROM couriers WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    @Test
    void createsAnOfflineCourierWithNoLocationAndPointsToIt() {
        MvcTestResult result = create("""
                {"name":"  Carla  "}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).matches(".*/api/v1/couriers/\\d+");
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Carla");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("OFFLINE");
        assertThat(result).bodyJson().extractingPath("$.lat").isNull();
        assertThat(result).bodyJson().extractingPath("$.lng").isNull();
    }

    @Test
    void returnsACourierById() {
        long id = newCourier();

        MvcTestResult result = mvc.get().uri("/api/v1/couriers/{id}", id).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo((int) id);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("OFFLINE");
    }

    @Test
    void reportsAnUnknownCourierAsNotFoundOnEveryEndpoint() {
        for (MvcTestResult result : List.of(
                mvc.get().uri("/api/v1/couriers/{id}", 999_999).exchange(),
                status(999_999, "OFFLINE"),
                location(999_999, 30.27, -97.74))) {
            assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("COURIER_NOT_FOUND");
        }
    }

    @Test
    void rejectsABlankName() {
        MvcTestResult result = create("""
                {"name":"   "}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void rejectsBusyBecauseOnlyTheSystemSetsIt() {
        long id = availableCourierAt(30.27, -97.74);

        MvcTestResult result = status(id, "BUSY");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("status");
        assertThat(dbStatus(id)).isEqualTo("AVAILABLE");
    }

    @Test
    void refusesAvailableBeforeTheFirstLocation() {
        long id = newCourier();

        MvcTestResult result = status(id, "AVAILABLE");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("COURIER_LOCATION_UNKNOWN");
        assertThat(dbStatus(id)).isEqualTo("OFFLINE");
        assertThat(geoPosition(id)).isNull();
    }

    @Test
    void refusesAvailableWhileBusy() {
        long id = availableCourierAt(30.27, -97.74);
        // Nothing sets BUSY through the API until M3-2 claims couriers, so the test sets it directly.
        jdbc.sql("UPDATE couriers SET status = 'BUSY' WHERE id = ?").param(id).update();

        MvcTestResult result = status(id, "AVAILABLE");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("COURIER_BUSY");
        assertThat(dbStatus(id)).isEqualTo("BUSY");
    }

    @Test
    void savesALocationWhileOfflineWithoutAddingTheCourierToTheGeoSet() {
        long id = newCourier();

        MvcTestResult result = location(id, 30.2672, -97.7431);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("OFFLINE");
        assertThat(result).bodyJson().extractingPath("$.lat").isEqualTo(30.2672);
        assertThat(result).bodyJson().extractingPath("$.lng").isEqualTo(-97.7431);
        assertThat(geoPosition(id)).isNull();
    }

    @Test
    void goingAvailableAddsTheCourierToTheGeoSetAtTheLastLocation() {
        long id = newCourier();
        location(id, 30.2672, -97.7431);

        MvcTestResult result = status(id, "AVAILABLE");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AVAILABLE");
        assertAt(geoPosition(id), 30.2672, -97.7431);
    }

    @Test
    void repeatingAvailableIsANoOp() {
        long id = availableCourierAt(30.2672, -97.7431);

        MvcTestResult result = status(id, "AVAILABLE");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AVAILABLE");
        assertAt(geoPosition(id), 30.2672, -97.7431);
    }

    @Test
    void movesAnAvailableCourierInTheGeoSet() {
        long id = availableCourierAt(30.2672, -97.7431);

        MvcTestResult result = location(id, 30.2849, -97.7341);

        assertThat(result).hasStatusOk();
        assertAt(geoPosition(id), 30.2849, -97.7341);
    }

    @Test
    void goingOfflineRemovesTheCourierFromTheGeoSet() {
        long id = availableCourierAt(30.2672, -97.7431);

        MvcTestResult result = status(id, "OFFLINE");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("OFFLINE");
        assertThat(geoPosition(id)).isNull();
    }

    @Test
    void savesALocationWhileBusyWithoutAddingTheCourierToTheGeoSet() {
        long id = availableCourierAt(30.2672, -97.7431);
        // M3-2 claims a courier with this UPDATE and a ZREM; the test does both by hand.
        jdbc.sql("UPDATE couriers SET status = 'BUSY' WHERE id = ?").param(id).update();
        redis.opsForGeo().remove(GEO_KEY, Long.toString(id));

        MvcTestResult result = location(id, 30.2849, -97.7341);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.lat").isEqualTo(30.2849);
        assertThat(geoPosition(id)).isNull();
    }

    @Test
    void aLocationUpdateClearsAStaleGeoMemberWhileNotAvailable() {
        long id = newCourier();
        // A failed or reordered after-commit ZREM can leave an OFFLINE courier in the set; the test plants one.
        redis.opsForGeo().add(GEO_KEY, new Point(-97.7431, 30.2672), Long.toString(id));

        MvcTestResult result = location(id, 30.2849, -97.7341);

        assertThat(result).hasStatusOk();
        assertThat(geoPosition(id)).isNull();
    }

    @Test
    void rejectsAnOutOfRangeOrIncompleteLocation() {
        long id = newCourier();

        MvcTestResult result = location(id, """
                {"lat":91}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("lat", "lng");
    }
}
