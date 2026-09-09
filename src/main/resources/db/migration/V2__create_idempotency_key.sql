-- Idempotency ledger.
--
-- The row is claimed with INSERT ... ON CONFLICT DO NOTHING, which is a single
-- atomic statement: exactly one concurrent caller inserts, everyone else gets
-- zero rows affected and is treated as a replay/conflict. This is why we do not
-- need SELECT ... FOR UPDATE, and why there is no read-then-write race window.
CREATE TABLE idempotency_key (
    key           VARCHAR(64)  PRIMARY KEY,
    request_hash  CHAR(64)     NOT NULL,          -- SHA-256 hex of the canonical body
    response_body JSONB,                          -- NULL until the request completes
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT idempotency_key_status_check
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- Reaper / expiry sweep.
CREATE INDEX idx_idempotency_key_expires_at ON idempotency_key (expires_at);
