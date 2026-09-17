-- RF-66 punti in sospeso (finestra di reso/ripensamento) e RF-09 scadenza in batch.
ALTER TABLE ledger.movement ADD COLUMN available_at TIMESTAMPTZ;
CREATE INDEX movement_pending_idx ON ledger.movement (available_at) WHERE available_at IS NOT NULL;
CREATE INDEX movement_expiry_idx ON ledger.movement (expires_at) WHERE expires_at IS NOT NULL AND amount > 0;

-- Il registro resta append-only: l'unica modifica ammessa è il rilascio del sospeso (available_at -> NULL), a parità di ogni altro campo.
CREATE OR REPLACE FUNCTION ledger.forbid_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND OLD.available_at IS NOT NULL AND NEW.available_at IS NULL
     AND NEW.id = OLD.id AND NEW.member_id = OLD.member_id AND NEW.currency = OLD.currency AND NEW.amount = OLD.amount
     AND NEW.reason = OLD.reason AND NEW.action_key = OLD.action_key AND NEW.rule_version IS NOT DISTINCT FROM OLD.rule_version
     AND NEW.reversal_of IS NOT DISTINCT FROM OLD.reversal_of AND NEW.expires_at IS NOT DISTINCT FROM OLD.expires_at
     AND NEW.created_at = OLD.created_at THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'ledger.movement is append-only';
END;
$$ LANGUAGE plpgsql;
