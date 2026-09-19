-- V1 — schema `member` (docs/servizi/member-service.md §2). M1.2: anagrafica, proiezione saldi/tier, statistiche.
-- Segmenti, attributi e referral avanzati arrivano con M5/M6.

CREATE TABLE IF NOT EXISTS member (
  id                     text PRIMARY KEY,                 -- MBR-000123
  external_id            text UNIQUE,
  first_name             text,
  last_name              text,
  nickname               text,
  email                  text UNIQUE,
  phone                  text,
  birth_date             date,
  gender                 text,
  city                   text,
  status                 text NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | INACTIVE | BLOCKED | CLOSED | ANONYMIZED
  channel                text,                             -- PORTAL | APP | STORE | IMPORT
  registered_at          timestamptz NOT NULL DEFAULT now(),
  referral_code          text UNIQUE,
  referred_by            text,
  referral_completed_at  timestamptz,
  consents               jsonb NOT NULL DEFAULT '{}'::jsonb,
  attributes             jsonb NOT NULL DEFAULT '{}'::jsonb,
  labels                 text[] NOT NULL DEFAULT '{}',
  avatar_seed            text,
  profile_completed_at   timestamptz,
  version                bigint NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS member_status ON member (status);
CREATE INDEX IF NOT EXISTS member_referred_by ON member (referred_by);

-- Nuovi id generati (portale/backoffice): MBR-001000+ , senza collidere coi 12 membri seed (000001–000012).
CREATE SEQUENCE IF NOT EXISTS member_id_seq START 1000;

-- Proiezione di saldi/tier posseduti dal wallet (docs §1): member li riflette, non li possiede.
CREATE TABLE IF NOT EXISTS member_projection (
  member_id            text PRIMARY KEY REFERENCES member (id),
  tier_code            text NOT NULL DEFAULT 'BASE',
  period_sts           bigint NOT NULL DEFAULT 0,
  balance_pts          bigint NOT NULL DEFAULT 0,
  pending_pts          bigint NOT NULL DEFAULT 0,
  lifetime_earned_pts  bigint NOT NULL DEFAULT 0,
  updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS member_projection_tier ON member_projection (tier_code);

-- Statistiche di attività alimentate dalle azioni (docs §4, §5).
CREATE TABLE IF NOT EXISTS member_stats (
  member_id              text PRIMARY KEY REFERENCES member (id),
  last_activity_at       timestamptz,
  actions_total          bigint NOT NULL DEFAULT 0,
  actions_by_type        jsonb NOT NULL DEFAULT '{}'::jsonb,
  purchases_count        bigint NOT NULL DEFAULT 0,
  purchases_amount_90d   numeric(14,2) NOT NULL DEFAULT 0,
  purchases_amount_total numeric(14,2) NOT NULL DEFAULT 0
);
