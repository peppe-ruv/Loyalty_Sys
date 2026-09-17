-- Reinvio manuale delle consegne fallite (RF-132): per rispedire una consegna servono i parametri con cui era stata
-- resa, altrimenti l'operatore può solo constatare il fallimento. Il testo non si duplica qui: si ri-renderizza dal
-- modello, che resta l'unica versione buona.
ALTER TABLE notifier.delivery_log
    ADD COLUMN params         JSONB,
    ADD COLUMN expires_at     TIMESTAMPTZ,
    ADD COLUMN correlation_id VARCHAR(256),
    ADD COLUMN resent_from    VARCHAR(64),     -- consegna di origine, quando questa riga è un reinvio
    ADD COLUMN resent_at      TIMESTAMPTZ;     -- sulla riga di origine: quando è stata rispedita

-- Coda del reinvio per la console: le consegne fallite non ancora rispedite, più recenti prima.
CREATE INDEX delivery_log_failed_idx ON notifier.delivery_log (created_at DESC)
    WHERE status = 'FAILED' AND resent_at IS NULL;
