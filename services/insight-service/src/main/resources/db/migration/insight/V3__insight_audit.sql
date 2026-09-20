-- V3 — voci di audit (docs/servizi/insight-service.md §2, docs/05 §6). insight consuma `lh.audit.v1`
-- (tipo unico `io.loyaltyhub.audit.entry`, gruppo `lh-insight`) e ne conserva una riga per evento,
-- idempotente su `event_id`. `before`/`after` contengono solo i campi cambiati. Retention 180 giorni (§5).

CREATE TABLE IF NOT EXISTS audit_entry (
  id             text PRIMARY KEY,
  event_id       text NOT NULL UNIQUE,
  at             timestamptz NOT NULL,
  actor_role     text,
  actor_name     text,
  service        text NOT NULL,
  entity_type    text NOT NULL,
  entity_id      text NOT NULL,
  action         text NOT NULL,
  summary        text,
  before         jsonb,
  after          jsonb,
  correlation_id text
);

CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_entry (at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_entry (entity_type, entity_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_service ON audit_entry (service, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_entry (actor_name, at DESC);
