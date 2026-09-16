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
    expires_at   TIMESTAMPTZ GENERATED ALWAYS AS (requested_at + INTERVAL '180 days') STORED  -- RC-07
);
CREATE INDEX redemption_member_idx ON catalogredemption.redemption (member_id, requested_at DESC);
