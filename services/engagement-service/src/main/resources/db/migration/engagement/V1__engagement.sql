-- V1 — schema `engagement`, fetta M6.0 (docs/servizi/engagement-service.md §2): template, regole di notifica, inbox,
-- snapshot del membro. Contenuti, pop-up, tema e webhook arrivano con le fette M6.1–M7 in migrazioni separate.
-- Nomi unici nel search_path condiviso dell'hub consolidato (ADR-023): `member_snapshot` esiste già in campaign, quindi
-- qui è `engagement_member_snapshot` (come `reward_member_snapshot` e `gamification_member_snapshot`).

CREATE TABLE IF NOT EXISTS message_template (
  code        text PRIMARY KEY,
  name        text NOT NULL,
  channel     text NOT NULL DEFAULT 'INAPP',          -- INAPP | EMAIL_FAKE
  title_tpl   text NOT NULL,
  body_tpl    text NOT NULL,
  icon        text,
  link_target text,
  category    text NOT NULL,                          -- POINTS | TIER | REWARD | GAME | PROGRAM
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by  text,
  updated_by  text
);

-- Regola fatto → template. `code` leggibile oltre all'id (docs/06 §2: le entità di configurazione hanno id e code).
-- SPEC-GAP: Q-69 — la scheda servizio elenca solo `id`; `code` serve ai seed stabili e ai path per codice.
CREATE TABLE IF NOT EXISTS notification_rule (
  id            text PRIMARY KEY,
  code          text NOT NULL UNIQUE,
  fact_type     text NOT NULL,                        -- forma breve, es. wallet.points.earned
  condition     jsonb,                                -- formato condizioni di docs/03 §3.3, solo spazio data.*
  template_code text NOT NULL REFERENCES message_template (code),
  enabled       boolean NOT NULL DEFAULT true,
  version       bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  created_by    text,
  updated_by    text
);
CREATE INDEX IF NOT EXISTS notification_rule_fact ON notification_rule (fact_type) WHERE enabled;

-- Messaggio consegnato. Deduplica per (membro, evento sorgente, template) (docs/03 §9).
CREATE TABLE IF NOT EXISTS inbox_message (
  id              text PRIMARY KEY,                   -- ULID
  member_id       text NOT NULL,
  template_code   text NOT NULL,
  channel         text NOT NULL,
  title           text NOT NULL,
  body            text NOT NULL,
  icon            text,
  link_target     text,
  category        text NOT NULL,
  source_event_id text NOT NULL,
  source_type     text,                               -- tipo breve dell'evento sorgente (registro BO-19)
  correlation_id  text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  read_at         timestamptz,
  UNIQUE (member_id, source_event_id, template_code)
);
CREATE INDEX IF NOT EXISTS inbox_message_member ON inbox_message (member_id, created_at DESC);
CREATE INDEX IF NOT EXISTS inbox_message_unread ON inbox_message (member_id) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS inbox_message_created ON inbox_message (created_at DESC);

CREATE TABLE IF NOT EXISTS engagement_member_snapshot (
  member_id  text PRIMARY KEY,
  first_name text,
  status     text NOT NULL DEFAULT 'ACTIVE',
  tier_code  text,
  segments   text[] NOT NULL DEFAULT '{}',
  updated_at timestamptz NOT NULL DEFAULT now()
);
