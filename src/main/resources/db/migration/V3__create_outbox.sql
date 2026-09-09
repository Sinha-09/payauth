-- Transactional outbox. Rows are written in the SAME transaction as the
-- authorization they describe, so "state changed" and "event will be published"
-- commit or roll back together. A relay drains the table asynchronously.
CREATE TABLE outbox (
    id           BIGSERIAL    PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- Partial index: the relay only ever scans unpublished rows, and this index
-- stays small no matter how large the outbox history grows.
CREATE INDEX idx_outbox_unpublished
    ON outbox (id)
    WHERE published_at IS NULL;
