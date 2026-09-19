-- V1 — schema `ingestion` (docs/servizi/ingestion-service.md §2). Archetipo M0.5: tabella di dedup.
-- Le fonti, i tipi azione, member_index, ponte e scenari arrivano con M1.1+.

CREATE TABLE IF NOT EXISTS inbound_event (
  id             text PRIMARY KEY,                 -- ULID interno
  event_id       text NOT NULL,                    -- id del CloudEvent (fornito dalla fonte)
  source_code    text NOT NULL,
  type_code      text NOT NULL,
  subject        text NOT NULL,
  member_id      text,
  event_time     timestamptz NOT NULL,
  received_at    timestamptz NOT NULL DEFAULT now(),
  status         text NOT NULL,                    -- ACCEPTED | DUPLICATE | REJECTED | UNMATCHED
  reject_code    text,
  payload        jsonb NOT NULL,
  correlation_id text NOT NULL,
  origin         text NOT NULL DEFAULT 'EXTERNAL', -- EXTERNAL | INTERNAL | SIMULATOR
  UNIQUE (source_code, event_id)
);

CREATE INDEX IF NOT EXISTS inbound_event_received ON inbound_event (received_at DESC);
CREATE INDEX IF NOT EXISTS inbound_event_member ON inbound_event (member_id);
