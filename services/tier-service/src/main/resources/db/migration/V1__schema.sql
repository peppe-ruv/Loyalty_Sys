CREATE SCHEMA IF NOT EXISTS tierservice;
CREATE TABLE tierservice.member_tier (
    member_id          VARCHAR(128) PRIMARY KEY,
    tier               VARCHAR(16) NOT NULL DEFAULT 'BASE',
    status_points_year BIGINT NOT NULL DEFAULT 0,
    program_year       INT NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE tierservice.tier_history (
    id           BIGSERIAL PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    from_tier    VARCHAR(16),
    to_tier      VARCHAR(16) NOT NULL,
    reason       VARCHAR(32) NOT NULL, -- UPGRADE | YEAR_END_CONFIRMED | YEAR_END_DOWNGRADE
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
