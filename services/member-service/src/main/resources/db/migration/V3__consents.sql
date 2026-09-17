-- Consensi con finalità, fonte, base giuridica, scadenza e versione (RF-135, privacy by design)
CREATE TABLE memberservice.consent (
    member_id    VARCHAR(128) NOT NULL REFERENCES memberservice.member(id),
    purpose      VARCHAR(64)  NOT NULL,
    granted      BOOLEAN      NOT NULL,
    source       VARCHAR(64)  NOT NULL,        -- app, web, call-center, sportello, import, api
    legal_basis  VARCHAR(32)  NOT NULL,        -- consent | contract | legitimate_interest | legal_obligation
    version      VARCHAR(32)  NOT NULL,        -- versione dell'informativa accettata
    granted_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ,
    evidence     VARCHAR(256),                  -- riferimento alla prova (id modulo, id sessione, registrazione)
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, purpose)
);
CREATE TABLE memberservice.consent_history (
    id           BIGSERIAL PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    purpose      VARCHAR(64)  NOT NULL,
    granted      BOOLEAN      NOT NULL,
    source       VARCHAR(64)  NOT NULL,
    legal_basis  VARCHAR(32)  NOT NULL,
    version      VARCHAR(32)  NOT NULL,
    granted_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ,
    evidence     VARCHAR(256),
    recorded_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX consent_history_member_idx ON memberservice.consent_history (member_id, recorded_at DESC);
CREATE INDEX consent_expiry_idx ON memberservice.consent (expires_at) WHERE granted AND expires_at IS NOT NULL;
