-- V5 — stato della pipeline (docs/servizi/insight-service.md §3 `GET /v1/pipeline/status`, F-INS-06, BO-24).
-- Per topic: ritardo stimato dell'ultimo record (arrivo in insight − timestamp del record Kafka). I volumi 1 h / 24 h
-- si contano dall'event store sull'istante d'arrivo (`idx_event_store_topic`), sempre esatti sulla finestra mobile.
-- Per servizio: l'ultimo fatto prodotto (tabella propria, aggiornata dall'ingest: niente scansione dell'event store).

ALTER TABLE topic_stat ADD COLUMN IF NOT EXISTS last_lag_ms bigint;           -- ritardo stimato dell'ultimo record
ALTER TABLE topic_stat ADD COLUMN IF NOT EXISTS last_received_at timestamptz; -- arrivo in insight dell'ultimo record

CREATE TABLE IF NOT EXISTS service_stat (
  service        text PRIMARY KEY,                  -- dal `source` urn:loyaltyhub:service:<servizio>
  last_fact_at   timestamptz NOT NULL,              -- arrivo in insight dell'ultimo fatto del servizio
  last_fact_type text NOT NULL,                     -- tipo breve (es. wallet.points.earned)
  last_event_id  text NOT NULL,
  facts_total    bigint NOT NULL DEFAULT 0
);
