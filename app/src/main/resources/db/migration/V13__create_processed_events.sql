-- Kafka delivers at least once, so a consumer records each event it handled and skips a repeat (ticket #15,
-- ADR-0006). The row is written in the same transaction as the handler's work: a rolled-back handler leaves no row,
-- and the redelivery runs it again. Keyed per consumer group, since every group handles every event once.
-- Each service keeps its own table after the split (ticket #42).
CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       UUID         NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);
