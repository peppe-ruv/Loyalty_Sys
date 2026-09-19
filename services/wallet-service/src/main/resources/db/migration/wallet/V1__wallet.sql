-- V1 — schema `wallet` (docs/servizi/wallet-service.md §2). M1.4 usa currency/wallet/ledger_entry/tier/member_tier;
-- points_lot, lot_consumption, tier_history, edition sono create ora secondo la scheda così M3 (lotti, scadenze,
-- salita di livello, edizioni) non richiede migrazioni distruttive.

CREATE TABLE IF NOT EXISTS currency (
  code          text PRIMARY KEY,                 -- PTS | STS
  name          text NOT NULL,
  spendable     boolean NOT NULL DEFAULT true,
  expiry_policy jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS wallet (
  member_id        text NOT NULL,
  currency         text NOT NULL REFERENCES currency (code),
  balance_active   bigint NOT NULL DEFAULT 0,
  balance_pending  bigint NOT NULL DEFAULT 0,
  lifetime_earned  bigint NOT NULL DEFAULT 0,
  lifetime_spent   bigint NOT NULL DEFAULT 0,
  lifetime_expired bigint NOT NULL DEFAULT 0,
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (member_id, currency)
);

CREATE TABLE IF NOT EXISTS ledger_entry (
  id             text PRIMARY KEY,                 -- ULID
  member_id      text NOT NULL,
  currency       text NOT NULL,
  type           text NOT NULL,                    -- EARN | SPEND | ADJUST | EXPIRE | REFUND | RELEASE
  amount         bigint NOT NULL,                  -- sempre positivo
  direction      char(1) NOT NULL,                 -- + | -
  balance_after  bigint NOT NULL,
  occurred_at    timestamptz NOT NULL,             -- data di business
  created_at     timestamptz NOT NULL DEFAULT now(),
  source_type    text NOT NULL,                    -- CAMPAIGN | REDEMPTION | MANUAL | SYSTEM
  effect_id      text UNIQUE,                      -- idempotenza di dominio degli accrediti
  redemption_id  text,
  campaign_code  text,
  action_id      text,
  correlation_id text,
  description    text,
  actor          text,
  metadata       jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS ledger_member ON ledger_entry (member_id, currency, occurred_at DESC);

CREATE TABLE IF NOT EXISTS points_lot (
  id              text PRIMARY KEY,
  member_id       text NOT NULL,
  currency        text NOT NULL,
  amount          bigint NOT NULL,
  remaining       bigint NOT NULL,
  status          text NOT NULL DEFAULT 'ACTIVE',  -- PENDING | ACTIVE | EXHAUSTED | EXPIRED
  earned_at       timestamptz NOT NULL,
  available_at    timestamptz,
  expires_at      timestamptz,
  warned          boolean NOT NULL DEFAULT false,
  ledger_entry_id text
);

CREATE INDEX IF NOT EXISTS points_lot_member ON points_lot (member_id, currency, status, expires_at);

CREATE TABLE IF NOT EXISTS lot_consumption (
  ledger_entry_id text NOT NULL,
  lot_id          text NOT NULL,
  amount          bigint NOT NULL,
  PRIMARY KEY (ledger_entry_id, lot_id)
);

CREATE TABLE IF NOT EXISTS tier (
  code          text PRIMARY KEY,
  name          text NOT NULL,
  rank          int NOT NULL,
  threshold_sts bigint NOT NULL,
  multiplier    numeric(4,2) NOT NULL DEFAULT 1.00,
  benefits      jsonb NOT NULL DEFAULT '[]'::jsonb,
  color         text,
  icon          text
);

CREATE TABLE IF NOT EXISTS member_tier (
  member_id     text PRIMARY KEY,
  tier_code     text NOT NULL DEFAULT 'BASE',
  since         timestamptz NOT NULL DEFAULT now(),
  period_sts    bigint NOT NULL DEFAULT 0,
  previous_tier text,
  member_status text NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS tier_history (
  id           text PRIMARY KEY,
  member_id    text NOT NULL,
  from_tier    text,
  to_tier      text NOT NULL,
  kind         text NOT NULL,                      -- UPGRADE | DOWNGRADE | RETAIN | INITIAL
  edition_code text,
  at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS edition (
  code                   text PRIMARY KEY,
  name                   text NOT NULL,
  start_date             date NOT NULL,
  end_date               date NOT NULL,
  redemption_grace_until date,
  status                 text NOT NULL DEFAULT 'PLANNED'  -- PLANNED | ACTIVE | CLOSED
);
