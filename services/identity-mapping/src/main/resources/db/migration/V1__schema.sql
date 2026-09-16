CREATE SCHEMA IF NOT EXISTS identitymapping;
CREATE TABLE identitymapping.member_identity (
    member_id  VARCHAR(128) PRIMARY KEY,   -- sub OIDC dello IAM Iren (D12)
    crm_id     VARCHAR(64) UNIQUE,
    sap_bp_id  VARCHAR(64) UNIQUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
