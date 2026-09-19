-- V1 — schema `insight` (docs/servizi/insight-service.md §2). M2.1: event store + statistiche per topic.
-- metric_daily, audit_entry, dlq_entry arrivano nelle fette successive (M2.4/M2.5/M7) come migrazioni additive.

-- Copia recente di tutti gli eventi dei 5 topic (finestra + retention in §5). `event_id` = idempotenza.
CREATE TABLE IF NOT EXISTS event_store (
  event_id       text PRIMARY KEY,                       -- id CloudEvents dell'evento
  topic          text NOT NULL,
  family         text NOT NULL,                           -- ACTION | EFFECT | FACT | AUDIT | DLQ
  type           text NOT NULL,                           -- type completo (io.loyaltyhub.<fam>.<nome>)
  short_type     text NOT NULL,                           -- type senza prefisso di famiglia
  source         text,
  member_id      text,
  correlation_id text,
  causation_id   text,
  hop            int,
  actor          text,
  error_code     text,                                    -- solo per gli eventi del topic DLQ
  event_time     timestamptz,                             -- `time` dell'envelope (tempo di business)
  received_at    timestamptz NOT NULL DEFAULT now(),      -- quando insight l'ha registrato
  kafka_partition int NOT NULL,
  kafka_offset    bigint NOT NULL,
  payload        jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_store_correlation ON event_store (correlation_id);
CREATE INDEX IF NOT EXISTS idx_event_store_member ON event_store (member_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_event_store_topic ON event_store (topic, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_event_store_received ON event_store (received_at DESC);

-- Statistiche per topic (docs/servizi/insight-service.md §2, API /v1/pipeline/status).
CREATE TABLE IF NOT EXISTS topic_stat (
  topic          text PRIMARY KEY,
  last_event_at  timestamptz,
  count_total    bigint NOT NULL DEFAULT 0,
  last_offset_by_partition jsonb NOT NULL DEFAULT '{}'::jsonb
);
