-- V4 — voci DLQ (docs/servizi/insight-service.md §2, §5; docs/04 §5; M7.3, F-INS-05, BO-27).
-- insight consuma `lh.dlq.v1` (gruppo `lh-insight`): ogni record in DLQ diventa una voce con i dati degli header
-- `lh-*` (topic d'origine, consumer, errore, tentativi). Stato OPEN → REPROCESSED | DISCARDED (azione umana ADMIN).
-- Colonne oltre a quelle "principali" della scheda: famiglia e tipo breve d'origine, classe/stack dell'errore,
-- membro e correlazione (tracciato FAILED), coordinate del record DLQ, nota di chiusura.

CREATE TABLE IF NOT EXISTS dlq_entry (
  id               text PRIMARY KEY,                 -- ULID
  event_id         text NOT NULL,                    -- id CloudEvents del messaggio fallito
  original_topic   text,
  original_type    text,
  original_family  text NOT NULL,                    -- ACTION | EFFECT | FACT | AUDIT | UNKNOWN
  consumer         text NOT NULL,                    -- gruppo consumer che ha fallito (es. lh-campaign)
  error_code       text,
  error_class      text,
  error_message    text,
  error_stack      text,
  retryable        boolean,
  attempts         int,
  member_id        text,
  correlation_id   text,
  payload          jsonb NOT NULL,
  dlq_partition    int,
  dlq_offset       bigint,
  first_seen_at    timestamptz NOT NULL DEFAULT now(),
  status           text NOT NULL DEFAULT 'OPEN',     -- OPEN | REPROCESSED | DISCARDED
  resolved_by      text,
  resolved_at      timestamptz,
  resolution_note  text
);

-- Idempotenza: un solo record aperto per (evento, consumer). Un nuovo fallimento dopo la chiusura ne apre un altro.
CREATE UNIQUE INDEX IF NOT EXISTS ux_dlq_entry_open ON dlq_entry (event_id, consumer) WHERE status = 'OPEN';
CREATE INDEX IF NOT EXISTS idx_dlq_entry_seen ON dlq_entry (first_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_dlq_entry_status ON dlq_entry (status, first_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_dlq_entry_correlation ON dlq_entry (correlation_id);
