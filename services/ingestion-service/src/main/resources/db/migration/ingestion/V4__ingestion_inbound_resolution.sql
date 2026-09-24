-- V4 — risoluzione degli eventi non abbinati / respinti (F-ING-04, F-ING-09, BO-26, M7.4;
-- docs/servizi/ingestion-service.md §3 POST /v1/inbound-events/{id}/retry e /match).
-- Una riga REJECTED/UNMATCHED rivalutata con successo diventa ACCEPTED *in place* (l'ingresso resta uno solo);
-- si registra come (RETRY / MANUAL_MATCH / AUTO_MATCH), da chi (attore X-LH-Actor o `system`) e quando.

ALTER TABLE inbound_event ADD COLUMN IF NOT EXISTS resolution text;          -- RETRY | MANUAL_MATCH | AUTO_MATCH
ALTER TABLE inbound_event ADD COLUMN IF NOT EXISTS resolved_by text;         -- RUOLO:username | system
ALTER TABLE inbound_event ADD COLUMN IF NOT EXISTS resolved_at timestamptz;

-- Abbinamento automatico alla registrazione del membro: ricerca dei parcheggiati per subject.
CREATE INDEX IF NOT EXISTS inbound_event_unmatched_subject
  ON inbound_event (lower(subject)) WHERE status = 'UNMATCHED';
