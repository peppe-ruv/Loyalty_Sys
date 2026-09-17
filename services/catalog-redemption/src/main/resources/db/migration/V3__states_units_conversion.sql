-- RF-102/RF-103: stati a parità con Open Loyalty e buoni a conversione di unità.
ALTER TABLE catalogredemption.redemption DROP CONSTRAINT IF EXISTS redemption_status_check;
ALTER TABLE catalogredemption.redemption ADD CONSTRAINT redemption_status_check CHECK (status IN ('REQUESTED','CONFIRMED','PACKING','WAITING_FOR_SHIPPING','IN_DELIVERY','DELIVERED','USED','RETURNED','REJECTED','CANCELLED','EXPIRED','DONATED'));
ALTER TABLE catalogredemption.reward
    ADD COLUMN conversion_eur_per_unit NUMERIC(10,4),
    ADD COLUMN conversion_min_units BIGINT,
    ADD COLUMN conversion_max_units BIGINT,
    ADD COLUMN conversion_step_units BIGINT,
    ADD COLUMN dynamic_value_expression TEXT,   -- buono a valore dinamico (RF-102): formula sul contesto, es. #transaction['grossValue'] * 0.05
    ADD COLUMN cost_wallet VARCHAR(32) NOT NULL DEFAULT 'PREMIO';
CREATE TABLE catalogredemption.redemption_status_history (
    id BIGSERIAL PRIMARY KEY, redemption_id UUID NOT NULL REFERENCES catalogredemption.redemption(id),
    from_status VARCHAR(24), to_status VARCHAR(24) NOT NULL, actor VARCHAR(128), changed_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX redemption_status_history_idx ON catalogredemption.redemption_status_history (redemption_id, changed_at);
