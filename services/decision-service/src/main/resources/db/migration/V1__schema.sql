CREATE SCHEMA IF NOT EXISTS decisionservice;

-- Decision log (RF-129, RF-135): una riga per decisione, documento completo in JSONB per spiegabilità e audit
CREATE TABLE decisionservice.decision_log (
    decision_id     VARCHAR(64) PRIMARY KEY,
    member_id       VARCHAR(128) NOT NULL,
    event_id        VARCHAR(256),
    event_type      VARCHAR(64),
    correlation_id  VARCHAR(256),
    policy_version  VARCHAR(64) NOT NULL,
    experiment_id   VARCHAR(64),
    variant         VARCHAR(64),
    primary_action  VARCHAR(32) NOT NULL,
    risk_level      VARCHAR(16),
    executed        BOOLEAN NOT NULL DEFAULT false,
    decision        JSONB NOT NULL,
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX decision_log_member_idx ON decisionservice.decision_log (member_id, decided_at DESC);
CREATE INDEX decision_log_correlation_idx ON decisionservice.decision_log (correlation_id);
CREATE INDEX decision_log_experiment_idx ON decisionservice.decision_log (experiment_id, variant, decided_at DESC) WHERE experiment_id IS NOT NULL;

-- Azioni scelte, denormalizzate per limiti per membro (cooldown, periodo) e per la BI
CREATE TABLE decisionservice.decision_action (
    id           BIGSERIAL PRIMARY KEY,
    decision_id  VARCHAR(64) NOT NULL REFERENCES decisionservice.decision_log(decision_id),
    member_id    VARCHAR(128) NOT NULL,
    action       VARCHAR(32) NOT NULL,
    reference    VARCHAR(256),
    channel      VARCHAR(32),
    score        DOUBLE PRECISION,
    source       VARCHAR(32),
    source_id    VARCHAR(128),
    decided_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX decision_action_member_idx ON decisionservice.decision_action (member_id, decided_at DESC);

-- Esposizioni agli esperimenti (RF-134): una per membro ed esperimento, stabile nel tempo
CREATE TABLE decisionservice.experiment_exposure (
    experiment_id VARCHAR(64) NOT NULL,
    member_id     VARCHAR(128) NOT NULL,
    variant       VARCHAR(64) NOT NULL,
    exposed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (experiment_id, member_id)
);

-- Il decision log è append-only: nessun aggiornamento né cancellazione (audit)
CREATE OR REPLACE FUNCTION decisionservice.deny_change() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'decision log is append-only'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER decision_log_immutable BEFORE UPDATE OR DELETE ON decisionservice.decision_log FOR EACH ROW EXECUTE FUNCTION decisionservice.deny_change();
