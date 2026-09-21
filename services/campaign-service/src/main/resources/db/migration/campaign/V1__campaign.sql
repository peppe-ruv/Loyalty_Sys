-- V1 — schema `campaign` (docs/servizi/campaign-service.md §2). M1.3: motore regole, limiti, registro valutazioni.
-- Statistiche giornaliere (M2), esclusività (M3), segmenti (M6) e approvazione (M7) si aggiungono dopo.

CREATE TABLE IF NOT EXISTS campaign (
  id                    text PRIMARY KEY,                 -- ULID interno
  code                  text UNIQUE NOT NULL,             -- CMP-…
  name                  text NOT NULL,
  description           text,
  member_description    text,                             -- testo per il portale
  icon                  text,
  trigger_action_types  text[] NOT NULL DEFAULT '{}',
  audience              jsonb NOT NULL DEFAULT '{"all":true}'::jsonb,
  conditions            jsonb NOT NULL DEFAULT '{"op":"all","rules":[]}'::jsonb,
  effects               jsonb NOT NULL DEFAULT '[]'::jsonb,
  limits                jsonb NOT NULL DEFAULT '{}'::jsonb,
  schedule              jsonb NOT NULL DEFAULT '{}'::jsonb,
  priority              int NOT NULL DEFAULT 100,
  exclusive_group       text,
  visible_in_portal     boolean NOT NULL DEFAULT false,
  system                boolean NOT NULL DEFAULT false,
  requires_legal        boolean NOT NULL DEFAULT false,
  labels                text[] NOT NULL DEFAULT '{}',
  status                text NOT NULL DEFAULT 'DRAFT',     -- DRAFT | IN_REVIEW | LIVE | PAUSED | ENDED | ARCHIVED
  version               bigint NOT NULL DEFAULT 0,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS campaign_status ON campaign (status);

-- Contatori dei limiti per campagna/membro/periodo (docs §2, §5 punto 2.5).
CREATE TABLE IF NOT EXISTS campaign_counter (
  campaign_id text NOT NULL,
  member_id   text NOT NULL,
  period      text NOT NULL,                              -- ALWAYS | DAY | WEEK | MONTH | EDITION
  period_key  text NOT NULL,                              -- es. 2026-09-19 / 2026-W38 / 2026-09 / ALWAYS
  matches     int NOT NULL DEFAULT 0,
  points      bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (campaign_id, member_id, period, period_key)
);

CREATE TABLE IF NOT EXISTS campaign_totals (
  campaign_id     text PRIMARY KEY,
  matches         bigint NOT NULL DEFAULT 0,
  unique_members  bigint NOT NULL DEFAULT 0,
  points_decided  bigint NOT NULL DEFAULT 0,
  points_granted  bigint NOT NULL DEFAULT 0,              -- dai fatti wallet.points.earned
  last_match_at   timestamptz
);

-- Storico azioni per membro/tipo (spazio history.* delle condizioni).
CREATE TABLE IF NOT EXISTS member_action_counter (
  member_id   text NOT NULL,
  action_type text NOT NULL,
  count       bigint NOT NULL DEFAULT 0,
  first_at    timestamptz,
  last_at     timestamptz,
  PRIMARY KEY (member_id, action_type)
);

-- Snapshot locale del membro (dai fatti member.*/tier.*): il motore non fa chiamate sincrone.
CREATE TABLE IF NOT EXISTS member_snapshot (
  member_id     text PRIMARY KEY,
  status        text NOT NULL DEFAULT 'ACTIVE',
  tier_code     text NOT NULL DEFAULT 'BASE',
  segments      text[] NOT NULL DEFAULT '{}',
  labels        text[] NOT NULL DEFAULT '{}',
  attributes    jsonb NOT NULL DEFAULT '{}'::jsonb,
  registered_at timestamptz,
  birth_date    date
);

-- Registro valutazioni (spiegabilità, docs §2). Pulizia > 30 giorni.
CREATE TABLE IF NOT EXISTS evaluation_log (
  action_id      text PRIMARY KEY,
  member_id      text,
  action_type    text NOT NULL,
  action_time    timestamptz NOT NULL,
  evaluated_at   timestamptz NOT NULL DEFAULT now(),
  correlation_id text,
  outcome        text NOT NULL,                           -- MATCHED | NO_MATCH | NO_MEMBER
  results        jsonb NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX IF NOT EXISTS evaluation_log_member ON evaluation_log (member_id);
CREATE INDEX IF NOT EXISTS evaluation_log_evaluated ON evaluation_log (evaluated_at DESC);
