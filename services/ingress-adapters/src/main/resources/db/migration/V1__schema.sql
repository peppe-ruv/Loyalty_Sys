CREATE SCHEMA IF NOT EXISTS ingressadapters;

-- Chiavi di idempotenza viste (RI-01): retention 24 mesi tramite job di pulizia.
CREATE TABLE ingressadapters.seen_keys (
    idempotency_key VARCHAR(230) PRIMARY KEY,
    seen_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX seen_keys_seen_at_idx ON ingressadapters.seen_keys (seen_at);
