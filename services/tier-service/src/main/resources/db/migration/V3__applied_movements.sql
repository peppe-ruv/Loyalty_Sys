-- I punti STATUS dell'anno si accumulavano sommando ogni movimento letto dal topic, senza memoria di
-- quali fossero già stati applicati: l'outbox del ledger è at-least-once (RI-08) e un replay gonfiava
-- i punti, quindi il tier. Qui si registra ogni movimento applicato: il secondo passaggio non conta.
CREATE TABLE tierservice.applied_movement (
    movement_id UUID PRIMARY KEY,
    member_id   VARCHAR(128) NOT NULL,
    applied_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX applied_movement_member_idx ON tierservice.applied_movement (member_id);
