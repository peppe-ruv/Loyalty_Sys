-- RF-74..RF-76: tipi di premio, limiti per membro, finestre, target, lotti di codici, stato USED.
ALTER TABLE catalogredemption.reward DROP CONSTRAINT IF EXISTS reward_type_check;
ALTER TABLE catalogredemption.reward ADD CONSTRAINT reward_type_check CHECK (type IN ('VOUCHER','PHYSICAL','SERVICE','CASHBACK','DISCOUNT_PERCENT','DISCOUNT_VALUE','FREE_SERVICE','EVENT_INVITATION','GIFT','DONATION'));
ALTER TABLE catalogredemption.reward
    ADD COLUMN limit_per_member         INT NOT NULL DEFAULT 0,
    ADD COLUMN limit_per_member_per_day INT NOT NULL DEFAULT 0,
    ADD COLUMN visible_from   TIMESTAMPTZ,
    ADD COLUMN visible_to     TIMESTAMPTZ,
    ADD COLUMN active_from    TIMESTAMPTZ,
    ADD COLUMN active_to      TIMESTAMPTZ,
    ADD COLUMN target_segments TEXT,                  -- csv di id segmento; vuoto = tutti
    ADD COLUMN category       VARCHAR(64),
    ADD COLUMN coupon_pool_id VARCHAR(64),
    ADD COLUMN code_validity_days INT NOT NULL DEFAULT 0,
    ADD COLUMN discount_percent NUMERIC(5,2),         -- DISCOUNT_PERCENT
    ADD COLUMN cashback_eur   NUMERIC(10,2);          -- CASHBACK: importo accreditato in bolletta
-- stock = -1: illimitato (premi digitali con solo tetto economico)
ALTER TABLE catalogredemption.reward ALTER COLUMN stock SET DEFAULT -1;

ALTER TABLE catalogredemption.redemption DROP CONSTRAINT IF EXISTS redemption_status_check;
ALTER TABLE catalogredemption.redemption ADD CONSTRAINT redemption_status_check CHECK (status IN ('REQUESTED','CONFIRMED','CANCELLED','IN_DELIVERY','DELIVERED','USED','EXPIRED','DONATED'));
ALTER TABLE catalogredemption.redemption
    ADD COLUMN code_expires_at TIMESTAMPTZ,
    ADD COLUMN used_at         TIMESTAMPTZ,
    ADD COLUMN last_actor      VARCHAR(128),
    ADD COLUMN grant_key       VARCHAR(240);
CREATE UNIQUE INDEX redemption_grant_key_uq ON catalogredemption.redemption (grant_key) WHERE grant_key IS NOT NULL;
CREATE INDEX redemption_reward_member_idx ON catalogredemption.redemption (reward_id, member_id, requested_at);

CREATE TABLE catalogredemption.coupon (
    pool_id       VARCHAR(64)  NOT NULL,
    code          VARCHAR(128) NOT NULL,
    redemption_id UUID REFERENCES catalogredemption.redemption(id),
    assigned_at   TIMESTAMPTZ,
    PRIMARY KEY (pool_id, code)
);
CREATE INDEX coupon_free_idx ON catalogredemption.coupon (pool_id) WHERE redemption_id IS NULL;
