-- Card authorization aggregate.
-- NOTE: AUTHORIZATION is a reserved word in PostgreSQL, so the table is named
-- card_authorization. The JPA entity is still called Authorization.
-- amount_minor is BIGINT: monetary values are integer minor units (paise/cents).
-- We never store money in a floating point type.
CREATE TABLE card_authorization (
    id            UUID         PRIMARY KEY,
    card_token    VARCHAR(64)  NOT NULL,
    amount_minor  BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency      CHAR(3)      NOT NULL,
    merchant_id   VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    response_code CHAR(2)      NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT card_authorization_status_check
        CHECK (status IN ('APPROVED', 'DECLINED', 'CAPTURED', 'VOIDED'))
);

-- Velocity rules and support tooling both read "recent authorizations for a card".
CREATE INDEX idx_card_authorization_card_token_created_at
    ON card_authorization (card_token, created_at DESC);

CREATE INDEX idx_card_authorization_merchant_id
    ON card_authorization (merchant_id);
