-- V2 — storico delle transizioni degli oggetti governati (docs/06 §1, §7; docs/03 §3.6; F-APR-01, M7.1).
-- Una riga per transizione: chi, quando, da/verso, azione, commento. Letta da BO-21 (storico e «Inviate da me»).

CREATE TABLE IF NOT EXISTS approval_history (
  id           uuid PRIMARY KEY,
  entity_type  text NOT NULL,                 -- CAMPAIGN | REWARD | CONTEST
  entity_id    text NOT NULL,                 -- id (ULID) dell'oggetto
  from_status  text,
  to_status    text NOT NULL,
  action       text NOT NULL,                 -- SUBMIT | APPROVE | REJECT | PUBLISH | PAUSE | RESUME | END | ARCHIVE
  actor        text NOT NULL,                 -- RUOLO:username (lhactor)
  comment      text,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS approval_history_entity ON approval_history (entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS approval_history_submitter ON approval_history (entity_type, action, actor);
