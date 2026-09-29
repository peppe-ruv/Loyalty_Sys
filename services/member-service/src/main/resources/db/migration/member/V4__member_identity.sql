-- V4 — legame account↔membro (docs/servizi/member-service.md §2, M8.2, ADR-048, Q-551). Solo espansione (ADR-038, regola 14):
-- una tabella nuova, nessuna colonna toccata. `member.external_id` resta l'id del CRM e non è il `sub`.
-- `subject` è un dato personale e non lascia mai il servizio; sul bus viaggia solo `subject_ref` (HMAC-SHA256, Q-552).
-- Il legame si cancella con l'anonimizzazione (D11): una nuova registrazione dello stesso account crea un nuovo membro.

CREATE TABLE IF NOT EXISTS member_identity (
  member_id   text PRIMARY KEY REFERENCES member (id),
  issuer      text NOT NULL,
  subject     text NOT NULL,                    -- dato personale: mai sul bus, mai nei log
  subject_ref text NOT NULL UNIQUE CHECK (subject_ref ~ '^[0-9a-f]{64}$'),
  linked_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (issuer, subject)
);
