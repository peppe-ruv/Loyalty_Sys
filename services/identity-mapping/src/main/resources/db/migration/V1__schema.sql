CREATE SCHEMA IF NOT EXISTS identitymapping;
CREATE TABLE identitymapping.member_identity (
    member_id  VARCHAR(128) PRIMARY KEY,   -- sub OIDC dell'IAM aziendale (ADR-012)
    crm_id     VARCHAR(64) UNIQUE,
    sap_bp_id  VARCHAR(64) UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
