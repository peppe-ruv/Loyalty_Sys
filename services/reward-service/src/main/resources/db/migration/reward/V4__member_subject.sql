-- V4 — proiezione locale del legame account↔membro (F2-SEC-09, ADR-048, Q-550, Q-552). Solo aggiunte (expand, ADR-038):
-- colonne nullable o con default sulla tabella snapshot `reward_member_snapshot` e un indice parziale unico. `subject_ref`
-- è lo pseudonimo HMAC(iss, sub) che member-service pubblica in member.registered/updated; qui serve a risolvere il membro
-- del token senza chiamate sincrone (regola 3). `subject_erased` è la lapide dell'anonimizzazione: un replay vecchio non
-- ri-lega mai il membro. `subject_ref_at` è l'istante (business time) dell'ultimo aggiornamento del legame.
ALTER TABLE reward_member_snapshot ADD COLUMN IF NOT EXISTS subject_ref text;
ALTER TABLE reward_member_snapshot ADD COLUMN IF NOT EXISTS subject_ref_at timestamptz;
ALTER TABLE reward_member_snapshot ADD COLUMN IF NOT EXISTS subject_erased boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX IF NOT EXISTS reward_member_subject_ref_uq ON reward_member_snapshot (subject_ref) WHERE subject_ref IS NOT NULL;
