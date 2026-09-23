-- V1 — schema `reward` (docs/servizi/reward-service.md §2). Tutte le tabelle della scheda subito, così le fette
-- M4.2–M4.4 (pool coupon, saga di richiesta, evasione) non richiedono migrazioni distruttive.

CREATE TABLE IF NOT EXISTS reward_category (
  code       text PRIMARY KEY,
  name       text NOT NULL,
  icon       text,
  sort_order int  NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS reward_band (
  code             text PRIMARY KEY,               -- F1 … F5
  name             text NOT NULL,
  points_threshold bigint NOT NULL UNIQUE,         -- costo di ogni premio della fascia (docs/03 §5)
  color            text,
  sort_order       int NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS coupon_pool (
  id            text PRIMARY KEY,
  code          text NOT NULL UNIQUE,
  name          text NOT NULL,
  prefix        text NOT NULL,
  validity_days int  NOT NULL DEFAULT 90,
  total         int  NOT NULL DEFAULT 0,
  available     int  NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS reward (
  id                text PRIMARY KEY,
  code              text NOT NULL UNIQUE,
  name              text NOT NULL,
  description       text,
  terms             text,
  image_url         text,
  type              text NOT NULL,               -- PHYSICAL | COUPON | DIGITAL | DONATION | EXPERIENCE
  category_code     text REFERENCES reward_category (code),
  band_code         text NOT NULL REFERENCES reward_band (code),
  fulfilment        text NOT NULL,               -- AUTO_COUPON | MANUAL | INSTANT
  coupon_pool_id    text REFERENCES coupon_pool (id),
  stock_total       int,                         -- null = illimitato
  stock_remaining   int,
  per_member_limit  int,
  eligible_tiers    text[] NOT NULL DEFAULT '{}',
  eligible_segments text[] NOT NULL DEFAULT '{}',
  valid_from        timestamptz,
  valid_to          timestamptz,
  status            text NOT NULL,               -- ciclo di vita comune (docs/03 §3.6)
  version           bigint NOT NULL DEFAULT 0,
  created_by        text,
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS reward_band_idx ON reward (band_code);

CREATE TABLE IF NOT EXISTS coupon (
  code          text PRIMARY KEY,
  pool_id       text NOT NULL REFERENCES coupon_pool (id),
  status        text NOT NULL,                   -- AVAILABLE | ISSUED | USED | EXPIRED | VOID
  member_id     text,
  reward_code   text,
  origin        text,                            -- REDEMPTION | CAMPAIGN
  redemption_id text,
  effect_id     text UNIQUE,
  issued_at     timestamptz,
  expires_at    timestamptz,
  used_at       timestamptz
);
CREATE INDEX IF NOT EXISTS coupon_pool_status_idx ON coupon (pool_id, status);
CREATE INDEX IF NOT EXISTS coupon_member_idx ON coupon (member_id);

CREATE TABLE IF NOT EXISTS redemption (
  id              text PRIMARY KEY,
  member_id       text NOT NULL,
  reward_code     text NOT NULL,
  reward_name     text NOT NULL,
  points_cost     bigint NOT NULL,
  status          text NOT NULL,                 -- PENDING | CONFIRMED | FULFILLED | REJECTED | CANCELLED
  reject_reason   text,
  needs_attention boolean NOT NULL DEFAULT false,
  coupon_code     text,
  fulfilment_note text,
  shipping        jsonb,
  correlation_id  text,
  requested_at    timestamptz NOT NULL DEFAULT now(),
  confirmed_at    timestamptz,
  closed_at       timestamptz,
  actor           text
);
CREATE INDEX IF NOT EXISTS redemption_member_idx ON redemption (member_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS redemption_status_idx ON redemption (status, requested_at);

-- Cronologia degli stati di una richiesta (GET /v1/redemptions/{id} "con cronologia stati").
CREATE TABLE IF NOT EXISTS redemption_history (
  id            text PRIMARY KEY,
  redemption_id text NOT NULL REFERENCES redemption (id) ON DELETE CASCADE,
  status        text NOT NULL,
  note          text,
  actor         text,
  at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS redemption_history_idx ON redemption_history (redemption_id, at);

CREATE TABLE IF NOT EXISTS reward_member_snapshot (
  member_id  text PRIMARY KEY,
  status     text NOT NULL DEFAULT 'ACTIVE',
  tier_code  text NOT NULL DEFAULT 'BASE',
  segments   text[] NOT NULL DEFAULT '{}',
  first_name text,
  last_name  text,
  updated_at timestamptz NOT NULL DEFAULT now()
);
