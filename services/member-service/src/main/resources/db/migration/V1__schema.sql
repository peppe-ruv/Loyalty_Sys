CREATE SCHEMA IF NOT EXISTS memberservice;
CREATE TABLE memberservice.member (
    id                    VARCHAR(128) PRIMARY KEY,          -- sub OIDC (ADR-012)
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED','ANONYMIZED')),
    enrolled_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    channel               VARCHAR(32),
    labels                JSONB        NOT NULL DEFAULT '{}'::jsonb,
    consents              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    referral_code         VARCHAR(16),
    referred_by           VARCHAR(128) REFERENCES memberservice.member(id),
    referral_completed_at TIMESTAMPTZ,
    first_action_at       TIMESTAMPTZ,
    profile_completed_at  TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX member_referral_code_uq ON memberservice.member (referral_code) WHERE referral_code IS NOT NULL;
CREATE INDEX member_referred_by_idx ON memberservice.member (referred_by) WHERE referred_by IS NOT NULL;
CREATE INDEX member_labels_gin ON memberservice.member USING gin (labels);
CREATE INDEX member_enrolled_day_idx ON memberservice.member ((extract(month FROM enrolled_at AT TIME ZONE 'Europe/Rome')), (extract(day FROM enrolled_at AT TIME ZONE 'Europe/Rome')));
