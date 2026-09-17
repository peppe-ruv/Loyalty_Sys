CREATE SCHEMA IF NOT EXISTS engagementservice;
CREATE TABLE engagementservice.achievement_progress (
    member_id VARCHAR(128) NOT NULL, definition_id VARCHAR(64) NOT NULL,
    state JSONB NOT NULL, completed_count INT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, definition_id));
CREATE INDEX achievement_progress_def_idx ON engagementservice.achievement_progress (definition_id, completed_count);
CREATE TABLE engagementservice.challenge_state (
    member_id VARCHAR(128) NOT NULL, definition_id VARCHAR(64) NOT NULL,
    state JSONB NOT NULL, completed_count INT NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, definition_id));
CREATE TABLE engagementservice.member_badge (
    member_id VARCHAR(128) NOT NULL, badge_code VARCHAR(64) NOT NULL, completed_count INT NOT NULL DEFAULT 1,
    first_granted_at TIMESTAMPTZ NOT NULL, last_granted_at TIMESTAMPTZ NOT NULL, source VARCHAR(240),
    PRIMARY KEY (member_id, badge_code));
CREATE TABLE engagementservice.badge_grant_key (grant_key VARCHAR(240) PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE engagementservice.leaderboard_score (leaderboard_id VARCHAR(64) NOT NULL, member_id VARCHAR(128) NOT NULL, value DOUBLE PRECISION NOT NULL DEFAULT 0, updated_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (leaderboard_id, member_id));
CREATE TABLE engagementservice.leaderboard_rank (leaderboard_id VARCHAR(64) NOT NULL, group_value VARCHAR(128) NOT NULL DEFAULT '', rank INT NOT NULL, member_id VARCHAR(128) NOT NULL, value DOUBLE PRECISION NOT NULL, computed_at TIMESTAMPTZ NOT NULL);
CREATE INDEX leaderboard_rank_idx ON engagementservice.leaderboard_rank (leaderboard_id, group_value, rank);
CREATE INDEX leaderboard_rank_member_idx ON engagementservice.leaderboard_rank (leaderboard_id, member_id);
CREATE TABLE engagementservice.leaderboard_cycle (leaderboard_id VARCHAR(64) NOT NULL, cycle_key VARCHAR(32) NOT NULL, closed_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (leaderboard_id, cycle_key));
-- Proiezioni dal topic membri: presentatore (per le milestone REFERRAL) e valore di raggruppamento (per le classifiche per gruppo)
CREATE TABLE engagementservice.member_referrer (member_id VARCHAR(128) PRIMARY KEY, referrer_id VARCHAR(128) NOT NULL);
CREATE TABLE engagementservice.member_group (member_id VARCHAR(128) NOT NULL, group_key VARCHAR(64) NOT NULL, group_value VARCHAR(128) NOT NULL, PRIMARY KEY (member_id, group_key));
