-- V2 — registri di ingestion (docs/servizi/ingestion-service.md §2): fonti, tipi azione, indice membri, ponte.

CREATE TABLE IF NOT EXISTS source (
  code          text PRIMARY KEY,
  name          text NOT NULL,
  kind          text NOT NULL DEFAULT 'HTTP',          -- HTTP | INTERNAL
  enabled       boolean NOT NULL DEFAULT true,
  allowed_types text[] NOT NULL DEFAULT '{}',          -- vuoto = tutti i tipi
  description   text
);

CREATE TABLE IF NOT EXISTS event_type (
  code        text PRIMARY KEY,                          -- nome breve, es. purchase.completed
  name        text NOT NULL,
  description text,
  origin      text NOT NULL DEFAULT 'SYSTEM',            -- SYSTEM | CUSTOM
  category    text,                                      -- TRANSACTION | ENGAGEMENT | SERVICE | INTERNAL
  data_schema jsonb,                                     -- JSON Schema di data
  sample_data jsonb,
  enabled     boolean NOT NULL DEFAULT true,
  icon        text
);

CREATE TABLE IF NOT EXISTS member_index (
  member_id   text PRIMARY KEY,                          -- MBR-000123
  external_id text,
  email_lower text,
  status      text NOT NULL                              -- ACTIVE | SUSPENDED | BLOCKED | CLOSED | ANONYMIZED
);
CREATE INDEX IF NOT EXISTS member_index_external ON member_index (external_id);
CREATE INDEX IF NOT EXISTS member_index_email ON member_index (email_lower);

CREATE TABLE IF NOT EXISTS internal_mapping (
  fact_type   text PRIMARY KEY,
  action_type text NOT NULL,
  enabled     boolean NOT NULL DEFAULT true
);

-- Dettaglio dell'esito di rifiuto (docs §5 punto 4: "dettaglio = errori dello schema").
ALTER TABLE inbound_event ADD COLUMN IF NOT EXISTS reject_detail text;

-- La dedup vale per i soli eventi ACCETTATI (docs §2: "UNIQUE per i non duplicati"): un evento respinto
-- o non abbinato può essere reinviato dopo una correzione. Sostituisce il vincolo di V1.
ALTER TABLE inbound_event DROP CONSTRAINT IF EXISTS inbound_event_source_code_event_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS inbound_event_accepted_uniq
  ON inbound_event (source_code, event_id) WHERE status = 'ACCEPTED';

