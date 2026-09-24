-- V1 — schema `gamification` (docs/servizi/gamification-service.md §2). Tutte le tabelle della scheda subito, così le
-- fette M5.2–M5.5 (giocata, obiettivi, badge, classifiche) non richiedono migrazioni distruttive.

CREATE TABLE IF NOT EXISTS contest (
  id                           text PRIMARY KEY,
  code                         text NOT NULL UNIQUE,
  name                         text NOT NULL,
  description                  text,
  rules_text                   text,
  mechanic                     text NOT NULL,          -- WHEEL | SCRATCH | BOX (SPEC-GAP Q-56)
  start_at                     timestamptz NOT NULL,
  end_at                       timestamptz NOT NULL,
  free_play_daily              boolean NOT NULL DEFAULT false,
  max_plays_per_member_per_day int,
  max_wins_per_member          int,
  distribution                 text NOT NULL DEFAULT 'UNIFORM', -- UNIFORM | BUSINESS_HOURS
  seed                         bigint NOT NULL,
  instants_generated_at        timestamptz,
  status                       text NOT NULL,          -- ciclo di vita comune (docs/03 §3.6)
  version                      bigint NOT NULL DEFAULT 0,
  created_by                   text,
  updated_at                   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS prize (
  id                 text PRIMARY KEY,
  contest_id         text NOT NULL REFERENCES contest (id) ON DELETE CASCADE,
  code               text NOT NULL,
  name               text NOT NULL,
  type               text NOT NULL,                    -- POINTS | COUPON | PHYSICAL
  points             bigint,
  reward_code        text,
  quantity_total     int NOT NULL,
  quantity_remaining int NOT NULL,
  image_url          text,
  wheel_color        text,
  sort_order         int NOT NULL DEFAULT 0,
  UNIQUE (contest_id, code)
);

CREATE TABLE IF NOT EXISTS winning_instant (
  id          text PRIMARY KEY,
  contest_id  text NOT NULL REFERENCES contest (id) ON DELETE CASCADE,
  prize_id    text NOT NULL REFERENCES prize (id) ON DELETE CASCADE,
  instant_at  timestamptz NOT NULL,
  status      text NOT NULL DEFAULT 'OPEN',            -- OPEN | CLAIMED | VOID
  claimed_by  text,
  claimed_at  timestamptz,
  play_id     text,
  planted     boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS winning_instant_claim_idx ON winning_instant (contest_id, status, instant_at);

CREATE TABLE IF NOT EXISTS play_grant (
  id            text PRIMARY KEY,
  member_id     text NOT NULL,
  contest_id    text NOT NULL REFERENCES contest (id) ON DELETE CASCADE,
  count         int NOT NULL,
  effect_id     text UNIQUE,
  campaign_code text,
  granted_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS play_grant_member_idx ON play_grant (member_id, contest_id);

CREATE TABLE IF NOT EXISTS play (
  id              text PRIMARY KEY,
  contest_id      text NOT NULL REFERENCES contest (id) ON DELETE CASCADE,
  member_id       text NOT NULL,
  kind            text NOT NULL,                       -- FREE_DAILY | CREDIT
  outcome         text NOT NULL,                       -- WIN | LOSE
  prize_id        text REFERENCES prize (id),
  played_at       timestamptz NOT NULL,
  play_date       date NOT NULL,                       -- Europe/Rome
  correlation_id  text,
  delivery_status text NOT NULL DEFAULT 'NA',          -- NA | PENDING | DELIVERED
  delivery_note   text
);
CREATE INDEX IF NOT EXISTS play_member_idx ON play (member_id, contest_id, play_date);
CREATE INDEX IF NOT EXISTS play_contest_idx ON play (contest_id, played_at);

CREATE TABLE IF NOT EXISTS achievement (
  id           text PRIMARY KEY,
  code         text NOT NULL UNIQUE,
  name         text NOT NULL,
  description  text,
  icon         text,
  action_types text[] NOT NULL DEFAULT '{}',
  filter       jsonb,
  metric       text NOT NULL,                          -- COUNT | SUM | STREAK | DISTINCT_TYPES
  sum_field    text,
  streak_unit  text,
  target       bigint NOT NULL,
  period       text NOT NULL,                          -- EVER | MONTH | EDITION
  repeatable   boolean NOT NULL DEFAULT false,
  badge_code   text,
  status       text NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS achievement_progress (
  achievement_id text NOT NULL REFERENCES achievement (id) ON DELETE CASCADE,
  member_id      text NOT NULL,
  period_key     text NOT NULL,
  value          bigint NOT NULL DEFAULT 0,
  distinct_seen  text[] NOT NULL DEFAULT '{}',
  last_unit_key  text,
  completed_at   timestamptz,
  PRIMARY KEY (achievement_id, member_id, period_key)
);

CREATE TABLE IF NOT EXISTS badge (
  code        text PRIMARY KEY,
  name        text NOT NULL,
  description text,
  icon        text,
  color       text
);

CREATE TABLE IF NOT EXISTS member_badge (
  member_id  text NOT NULL,
  badge_code text NOT NULL REFERENCES badge (code) ON DELETE CASCADE,
  origin     text,
  awarded_at timestamptz NOT NULL DEFAULT now(),
  effect_id  text UNIQUE,
  PRIMARY KEY (member_id, badge_code)
);

CREATE TABLE IF NOT EXISTS leaderboard (
  id           text PRIMARY KEY,
  code         text NOT NULL UNIQUE,
  name         text NOT NULL,
  metric       text NOT NULL,                          -- PTS_EARNED | STS_EARNED | ACTION_COUNT
  action_types text[] NOT NULL DEFAULT '{}',
  period       text NOT NULL,                          -- MONTH | EDITION | ALL_TIME
  top_n        int NOT NULL DEFAULT 10,
  status       text NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS leaderboard_score (
  leaderboard_id text NOT NULL REFERENCES leaderboard (id) ON DELETE CASCADE,
  period_key     text NOT NULL,
  member_id      text NOT NULL,
  score          bigint NOT NULL DEFAULT 0,
  reached_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (leaderboard_id, period_key, member_id)
);
CREATE INDEX IF NOT EXISTS leaderboard_rank_idx ON leaderboard_score (leaderboard_id, period_key, score DESC, reached_at);

-- Snapshot dei membri (nickname per le classifiche, stato). Prefisso `gamification_` come `reward_member_snapshot`:
-- nell'hub consolidato gli schemi condividono il search_path e `campaign.member_snapshot` esiste già (ADR-023).
CREATE TABLE IF NOT EXISTS gamification_member_snapshot (
  member_id  text PRIMARY KEY,
  nickname   text,
  status     text NOT NULL DEFAULT 'ACTIVE',
  updated_at timestamptz NOT NULL DEFAULT now()
);
