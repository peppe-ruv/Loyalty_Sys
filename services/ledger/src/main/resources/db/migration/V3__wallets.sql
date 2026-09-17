-- RF-87/RF-88: wallet configurabili (codice libero), tipo di movimento, etichette, saldi cumulati e bloccati.
ALTER TABLE ledger.movement DROP CONSTRAINT IF EXISTS movement_currency_check;
ALTER TABLE ledger.movement ALTER COLUMN currency TYPE VARCHAR(32);
ALTER TABLE ledger.movement ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'EARN', ADD COLUMN labels TEXT;
ALTER TABLE ledger.balance ALTER COLUMN currency TYPE VARCHAR(32);
ALTER TABLE ledger.balance
    ADD COLUMN blocked       BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN earned_total  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN spent_total   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN expired_total BIGINT NOT NULL DEFAULT 0;
-- Il trigger di immutabilità confronta anche kind e labels.
CREATE OR REPLACE FUNCTION ledger.forbid_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.available_at IS NOT NULL AND NEW.available_at IS NULL
     AND NEW.id = OLD.id AND NEW.member_id = OLD.member_id AND NEW.currency = OLD.currency AND NEW.amount = OLD.amount
     AND NEW.reason = OLD.reason AND NEW.action_key = OLD.action_key AND NEW.rule_version IS NOT DISTINCT FROM OLD.rule_version
     AND NEW.reversal_of IS NOT DISTINCT FROM OLD.reversal_of AND NEW.expires_at IS NOT DISTINCT FROM OLD.expires_at
     AND NEW.kind = OLD.kind AND NEW.labels IS NOT DISTINCT FROM OLD.labels AND NEW.created_at = OLD.created_at THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'ledger.movement is append-only';
END;
$$ LANGUAGE plpgsql;
