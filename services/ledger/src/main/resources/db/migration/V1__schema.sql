CREATE SCHEMA IF NOT EXISTS ledger;

-- Registro movimenti immutabile: nessun UPDATE/DELETE applicativo (ADR-010). Partizionare per member_id hash oltre i 100M righe.
CREATE TABLE ledger.movement (
    id           UUID PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    currency     VARCHAR(8)   NOT NULL CHECK (currency IN ('PREMIO','STATUS')),
    amount       BIGINT       NOT NULL,
    reason       VARCHAR(64)  NOT NULL,
    action_key   VARCHAR(240) NOT NULL,
    rule_version VARCHAR(64),
    reversal_of  UUID REFERENCES ledger.movement(id),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX movement_action_currency_uq ON ledger.movement (action_key, currency);
CREATE INDEX movement_member_created_idx ON ledger.movement (member_id, created_at DESC);

CREATE TABLE ledger.balance (
    member_id VARCHAR(128) NOT NULL,
    currency  VARCHAR(8)   NOT NULL,
    available BIGINT       NOT NULL DEFAULT 0,
    pending   BIGINT       NOT NULL DEFAULT 0,
    version   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (member_id, currency)
);

CREATE TABLE ledger.outbox (
    id           UUID PRIMARY KEY,
    topic        VARCHAR(128) NOT NULL,
    message_key  VARCHAR(128) NOT NULL,
    payload      BYTEA        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished_idx ON ledger.outbox (created_at) WHERE published_at IS NULL;

-- Vincolo di immutabilità a livello DB
CREATE OR REPLACE FUNCTION ledger.forbid_change() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'ledger.movement is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER movement_immutable BEFORE UPDATE OR DELETE ON ledger.movement FOR EACH ROW EXECUTE FUNCTION ledger.forbid_change();
