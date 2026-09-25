-- V3 — istante di business dell'ultimo match per (campagna, membro, periodo): serve al limite
-- limits.cooldownMinutes (F-CMP-05, docs/03 §3.2). Nullable: le righe precedenti non lo conoscono.

ALTER TABLE campaign_counter ADD COLUMN IF NOT EXISTS last_match_at timestamptz;
