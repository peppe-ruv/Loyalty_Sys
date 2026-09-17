CREATE SCHEMA IF NOT EXISTS catalogredemption;
CREATE TABLE catalogredemption.reward (
    id             UUID PRIMARY KEY,
    name           VARCHAR(160) NOT NULL,
    type           VARCHAR(16) NOT NULL CHECK (type IN ('VOUCHER','PHYSICAL','SERVICE')),
    value_eur      NUMERIC(10,2) NOT NULL,
    points_cost    BIGINT NOT NULL,
    min_tier_order INT NOT NULL DEFAULT 0,
    stock          BIGINT NOT NULL DEFAULT 0,
    supplier       VARCHAR(120),
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);
CREATE TABLE catalogredemption.redemption (
    id           UUID PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    reward_id    UUID NOT NULL REFERENCES catalogredemption.reward(id),
    points       BIGINT NOT NULL,
    status       VARCHAR(16) NOT NULL CHECK (status IN ('REQUESTED','CONFIRMED','CANCELLED','IN_DELIVERY','DELIVERED','EXPIRED','DONATED')),
    code         VARCHAR(128),
    requested_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    -- RC-07: validità del riscatto, 180 giorni dalla richiesta. L'aritmetica su timestamptz dipende dal
    -- fuso della sessione, quindi Postgres la considera non immutabile e rifiuta la colonna generata:
    -- il calcolo passa per UTC, che è immutabile ed è anche la durata fissa che serve qui.
    expires_at   TIMESTAMPTZ GENERATED ALWAYS AS
                 (((requested_at AT TIME ZONE 'UTC') + INTERVAL '180 days') AT TIME ZONE 'UTC') STORED
);
CREATE INDEX redemption_member_idx ON catalogredemption.redemption (member_id, requested_at DESC);
