-- RF-70 assegnazione manuale del tier con causale, autore e scadenza.
CREATE TABLE tierservice.tier_override (
    id        BIGSERIAL PRIMARY KEY,
    member_id VARCHAR(128) NOT NULL,
    tier      VARCHAR(16)  NOT NULL,
    reason    VARCHAR(240) NOT NULL,
    actor     VARCHAR(128) NOT NULL,
    from_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    until_at  TIMESTAMPTZ
);
CREATE INDEX tier_override_member_idx ON tierservice.tier_override (member_id, from_at DESC);
