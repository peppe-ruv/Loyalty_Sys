-- RF-69 codici promozionali e QR: definizioni (dal backoffice) e usi.
CREATE TABLE ingressadapters.promo_code (
    code                VARCHAR(64) PRIMARY KEY,
    campaign            VARCHAR(64) NOT NULL,
    kind                VARCHAR(16) NOT NULL CHECK (kind IN ('QR','PROMO','SINGLE_USE')),
    valid_from          TIMESTAMPTZ,
    valid_to            TIMESTAMPTZ,
    max_uses            INT NOT NULL DEFAULT 0,
    max_uses_per_member INT NOT NULL DEFAULT 1,
    active              BOOLEAN NOT NULL DEFAULT true
);
CREATE INDEX promo_code_campaign_idx ON ingressadapters.promo_code (campaign);
CREATE TABLE ingressadapters.code_use (
    code      VARCHAR(64)  NOT NULL REFERENCES ingressadapters.promo_code(code),
    member_id VARCHAR(128) NOT NULL,
    used_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (code, member_id, used_at)
);
CREATE INDEX code_use_member_idx ON ingressadapters.code_use (member_id);
