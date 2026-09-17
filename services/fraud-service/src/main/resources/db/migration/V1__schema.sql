CREATE SCHEMA IF NOT EXISTS fraudservice;

-- Eventi osservati (finestra scorrevole, nessun dato personale: id membro pseudonimo, id dispositivo hash, coordinate)
CREATE TABLE fraudservice.observed_event (
    id           BIGSERIAL PRIMARY KEY,
    event_id     VARCHAR(256) NOT NULL UNIQUE,
    member_id    VARCHAR(128) NOT NULL,
    kind         VARCHAR(32) NOT NULL,  -- ACTION | EARNING | REDEMPTION | RETURN | CODE_FAILED | CHECK_IN | PLAY | ENROLLED
    action_type  VARCHAR(64),
    units        BIGINT NOT NULL DEFAULT 0,
    device_id    VARCHAR(128),
    lat          DOUBLE PRECISION,
    lon          DOUBLE PRECISION,
    occurred_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX observed_event_member_idx ON fraudservice.observed_event (member_id, occurred_at DESC);
CREATE INDEX observed_event_device_idx ON fraudservice.observed_event (device_id, occurred_at DESC) WHERE device_id IS NOT NULL;
CREATE INDEX observed_event_time_idx ON fraudservice.observed_event (occurred_at);

-- Valutazione corrente per membro
CREATE TABLE fraudservice.member_risk (
    member_id      VARCHAR(128) PRIMARY KEY,
    score          INT NOT NULL DEFAULT 0,
    level          VARCHAR(16) NOT NULL DEFAULT 'LOW',
    reason_codes   JSONB NOT NULL DEFAULT '[]',
    assessment     JSONB NOT NULL DEFAULT '{}',
    blocked        BOOLEAN NOT NULL DEFAULT false,
    policy_version VARCHAR(64),
    assessed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX member_risk_level_idx ON fraudservice.member_risk (level, assessed_at DESC);

-- Storico delle valutazioni (audit, RF-135)
CREATE TABLE fraudservice.risk_history (
    id           BIGSERIAL PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    from_level   VARCHAR(16),
    to_level     VARCHAR(16) NOT NULL,
    score        INT NOT NULL,
    reason_codes JSONB NOT NULL,
    trigger_event VARCHAR(256),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX risk_history_member_idx ON fraudservice.risk_history (member_id, changed_at DESC);
