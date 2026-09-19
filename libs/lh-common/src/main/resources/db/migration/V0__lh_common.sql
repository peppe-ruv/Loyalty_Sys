-- V0 — tabelle comuni di lh-common (docs/06 §1): outbox, processed_event, approval_history.
-- Ogni servizio include questa migrazione (dalla libreria, sul classpath) prima delle proprie V1+.

CREATE TABLE IF NOT EXISTS outbox (
  id           uuid PRIMARY KEY,
  topic        text NOT NULL,
  msg_key      text NOT NULL,
  type         text NOT NULL,
  payload      jsonb NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
-- Indice parziale sulle sole righe da pubblicare (relay efficiente anche con tabella grande).
CREATE INDEX IF NOT EXISTS outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processed_event (
  consumer     text NOT NULL,
  event_id     text NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE IF NOT EXISTS approval_history (
  id          uuid PRIMARY KEY,
  entity_type text NOT NULL,
  entity_id   text NOT NULL,
  from_status text,
  to_status   text NOT NULL,
  action      text NOT NULL,
  actor       text NOT NULL,
  comment     text,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS approval_history_entity ON approval_history (entity_type, entity_id, created_at);
