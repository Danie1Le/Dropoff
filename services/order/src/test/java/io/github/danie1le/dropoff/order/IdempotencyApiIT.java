package io.github.danie1le.dropoff.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.danie1le.dropoff.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code Idempotency-Key} header on place order (M2-4). Not {@code @Transactional}: the concurrent cases need real
 * commits and rollbacks, so each test deletes what it created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyApiIT {

    private static final String HEADER = "Idempotency-Key";

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final List<String> keys = new ArrayList<>();
    private final long customerId;
    private final long merchantId;
    private final long taco;
    private final long soda;

    IdempotencyApiIT(@Autowired MockMvcTester mvc, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate tx) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.tx = tx;
        this.customerId = OrderFixtures.customer(jdbc);
        this.merchantId = OrderFixtures.merchant(jdbc, true);
        this.taco = OrderFixtures.item(jdbc, merchantId, 399, true);
        this.soda = OrderFixtures.item(jdbc, merchantId, 150, true);
    }

    @AfterEach
    void cleanUp() {
        for (String key : keys) {
            jdbc.sql("DELETE FROM order_events WHERE order_id IN (SELECT id FROM orders WHERE idempotency_key = ?)")
                    .param(key)
                    .update();
            jdbc.sql("DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE idempotency_key = ?)")
                    .param(key)
                    .update();
            jdbc.sql("DELETE FROM orders WHERE idempotency_key = ?").param(key).update();
        }
        OrderFixtures.deleteMerchants(jdbc, List.of(merchantId));
        jdbc.sql("DELETE FROM customers WHERE id = ?").param(customerId).update();
    }

    private String newKey() {
        String key = UUID.randomUUID().toString();
        keys.add(key);
        return key;
    }

    private String body(String lines) {
        return "{\"customerId\":%d,\"merchantId\":%d,\"lines\":%s}".formatted(customerId, merchantId, lines);
    }

    private MvcTestResult place(String key, String body) {
        return mvc.post()
                .uri("/api/v1/orders")
                .header(HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static long id(MvcTestResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long orderCount(String key) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE idempotency_key = ?")
                .param(key)
                .query(Long.class)
                .single();
    }

    @Test
    void sameKeyAndBodyReplaysTheOriginalOrder() throws Exception {
        String key = newKey();
        String lines = "[{\"menuItemId\":%d,\"quantity\":2},{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco, soda);
        // Same lines in another order: the canonical request sorts them, so the hash matches.
        String reordered =
                "[{\"menuItemId\":%d,\"quantity\":1},{\"menuItemId\":%d,\"quantity\":2}]".formatted(soda, taco);

        MvcTestResult first = place(key, body(lines));
        MvcTestResult replay = place(key, body(reordered));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(replay).hasStatus(HttpStatus.CREATED);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(orderCount(key)).isEqualTo(1);
    }

    @Test
    void replayIgnoresStateThatChangedSinceTheFirstRequest() {
        String key = newKey();
        String body = body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco));
        assertThat(place(key, body)).hasStatus(HttpStatus.CREATED);
        jdbc.sql("UPDATE merchant_copies SET open = false WHERE merchant_id = ?")
                .param(merchantId)
                .update();

        MvcTestResult replay = place(key, body);

        assertThat(replay).hasStatus(HttpStatus.CREATED);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void sameKeyWithAChangedBodyIsRejected() {
        String key = newKey();
        assertThat(place(key, body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco))))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult changed = place(key, body("[{\"menuItemId\":%d,\"quantity\":2}]".formatted(taco)));

        assertThat(changed).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(changed).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(orderCount(key)).isEqualTo(1);
    }

    @Test
    void missingHeaderIsRejected() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco)))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void blankHeaderIsRejected() {
        MvcTestResult result = place("  ", body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco)));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void missingHeaderIsReportedBeforeAnInvalidBody() {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("[]"))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void overLongHeaderFailsValidation() {
        MvcTestResult result = place("k".repeat(256), body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco)));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo(HEADER);
    }

    @Test
    void headerOfExactly255CharactersIsAccepted() {
        String key = "k".repeat(255);
        keys.add(key);

        assertThat(place(key, body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco))))
                .hasStatus(HttpStatus.CREATED);
    }

    /** Starts the duplicate while the first request's transaction is still open, so it waits on the unique index. */
    private CompletableFuture<MvcTestResult> duplicateDuringOpenTransaction(
            String key, String firstBody, String secondBody, boolean commitFirst) {
        AtomicReference<CompletableFuture<MvcTestResult>> duplicate = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            assertThat(place(key, firstBody)).hasStatus(HttpStatus.CREATED);
            duplicate.set(CompletableFuture.supplyAsync(() -> place(key, secondBody)));
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(duplicate.get())
                    .as("duplicate waits for the first transaction")
                    .isNotDone();
            if (!commitFirst) {
                status.setRollbackOnly();
            }
        });
        return duplicate.get();
    }

    @Test
    void concurrentDuplicateWaitsThenReplays() throws Exception {
        String key = newKey();
        String body = body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco));

        MvcTestResult second =
                duplicateDuringOpenTransaction(key, body, body, true).get(10, TimeUnit.SECONDS);

        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(orderCount(key)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateWithAChangedBodyWaitsThenIsRejected() throws Exception {
        String key = newKey();

        MvcTestResult second = duplicateDuringOpenTransaction(
                        key,
                        body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco)),
                        body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(soda)),
                        true)
                .get(10, TimeUnit.SECONDS);

        assertThat(second).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(second).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void concurrentDuplicateProceedsWhenTheFirstRollsBack() throws Exception {
        String key = newKey();
        String body = body("[{\"menuItemId\":%d,\"quantity\":1}]".formatted(taco));

        MvcTestResult second =
                duplicateDuringOpenTransaction(key, body, body, false).get(10, TimeUnit.SECONDS);

        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(orderCount(key)).isEqualTo(1);
        assertThat(id(second)).isPositive();
    }
}
