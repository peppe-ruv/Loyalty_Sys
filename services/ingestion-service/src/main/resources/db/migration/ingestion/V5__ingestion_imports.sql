-- V5 — import file asincrono con rapporto (F2-ING-02, BO-32, M8.7; docs/18 §3.6, docs/servizi/ingestion-service.md §2).
-- Solo espansione (ADR-038, regola 14): due tabelle nuove, nessuna colonna esistente toccata.
-- Il file resta in `content` solo finché il lavoro non è concluso (poi NULL): il rapporto vive nei contatori e in
-- `import_row`. Ogni riga passa dalla stessa pipeline di POST /v1/events (origine IMPORT su inbound_event).

CREATE TABLE IF NOT EXISTS import_job (
  id               text PRIMARY KEY,                    -- ULID
  kind             text NOT NULL,                       -- EVENTS (ATTRIBUTES in M13.5, Q-370)
  format           text NOT NULL,                       -- CSV | NDJSON | JSON
  file_name        text NOT NULL,                       -- nome ripulito (niente percorso), solo per la vista
  size_bytes       integer NOT NULL,
  sha256           text NOT NULL,                       -- impronta del file caricato (evidenza, niente contenuto)
  default_source   text,                                -- codice fonte per le righe senza `source`
  status           text NOT NULL,                       -- QUEUED | RUNNING | DONE | FAILED
  rows_total       integer NOT NULL,
  rows_done        integer NOT NULL DEFAULT 0,          -- punto di ripresa: righe già elaborate
  accepted         integer NOT NULL DEFAULT 0,
  duplicate        integer NOT NULL DEFAULT 0,
  rejected         integer NOT NULL DEFAULT 0,
  unmatched        integer NOT NULL DEFAULT 0,
  invalid          integer NOT NULL DEFAULT 0,          -- righe non leggibili come evento (errore di forma)
  attempts         integer NOT NULL DEFAULT 0,          -- prese in carico dal lavoratore (ripresa dopo un arresto)
  error_detail     text,                                -- motivo di FAILED
  content          text,                                -- file UTF-8; NULL a lavoro concluso
  idempotency_key  text,                                -- header Idempotency-Key facoltativo, per autore (Q-353, M8.10)
  created_by       text NOT NULL,                       -- RUOLO:username
  created_at       timestamptz NOT NULL DEFAULT now(),
  started_at       timestamptz,
  finished_at      timestamptz,
  heartbeat_at     timestamptz                          -- ultimo avanzamento del lavoratore
);

CREATE UNIQUE INDEX IF NOT EXISTS import_job_idempotency ON import_job (created_by, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS import_job_created ON import_job (created_at DESC);
CREATE INDEX IF NOT EXISTS import_job_pending ON import_job (created_at) WHERE status IN ('QUEUED', 'RUNNING');

-- Esito per riga delle righe non accettate (gli ACCEPTED sono solo contati): il rapporto di BO-32.
CREATE TABLE IF NOT EXISTS import_row (
  import_id         text NOT NULL REFERENCES import_job (id) ON DELETE CASCADE,
  row_number        integer NOT NULL,                   -- 1 = primo record dopo l'intestazione
  line_number       integer,                            -- linea fisica del file dove comincia il record
  event_id          text,
  outcome           text NOT NULL,                      -- DUPLICATE | REJECTED | UNMATCHED | INVALID
  reject_code       text,
  detail            text,                               -- mai il soggetto: per UNMATCHED si legge da inbound_event
  inbound_event_id  text,                               -- riga di inbound_event (BO-26); NULL per INVALID
  PRIMARY KEY (import_id, row_number)
);

CREATE INDEX IF NOT EXISTS import_row_outcome ON import_row (import_id, outcome);
