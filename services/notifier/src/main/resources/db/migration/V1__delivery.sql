CREATE SCHEMA IF NOT EXISTS notifier;

-- Inbox in app (canale APP): messaggi e offerte mostrati nell'area riservata / app
CREATE TABLE notifier.inbox_message (
    id           VARCHAR(64) PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    decision_id  VARCHAR(64),
    action       VARCHAR(32) NOT NULL,   -- SEND_MESSAGE | SHOW_OFFER | ASK_FOR_FEEDBACK
    reference    VARCHAR(256),
    subject      VARCHAR(256),
    body         TEXT,
    params       JSONB NOT NULL DEFAULT '{}',
    status       VARCHAR(16) NOT NULL DEFAULT 'NEW',  -- NEW | READ | ACCEPTED | DISMISSED | EXPIRED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    read_at      TIMESTAMPTZ,
    acted_at     TIMESTAMPTZ
);
CREATE INDEX inbox_member_idx ON notifier.inbox_message (member_id, status, created_at DESC);

-- Coda operatore (canale OPERATOR): contatti da gestire a mano (call center, sportello)
CREATE TABLE notifier.operator_queue (
    id           VARCHAR(64) PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    decision_id  VARCHAR(64),
    action       VARCHAR(32) NOT NULL,
    reference    VARCHAR(256),
    note         TEXT,
    status       VARCHAR(16) NOT NULL DEFAULT 'OPEN', -- OPEN | DONE | SKIPPED
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    handled_by   VARCHAR(128),
    handled_at   TIMESTAMPTZ
);
CREATE INDEX operator_queue_status_idx ON notifier.operator_queue (status, created_at);

-- Registro consegne (RF-132): ogni tentativo, canale, esito; base della pressione commerciale
CREATE TABLE notifier.delivery_log (
    delivery_id  VARCHAR(64) PRIMARY KEY,
    member_id    VARCHAR(128) NOT NULL,
    decision_id  VARCHAR(64),
    action       VARCHAR(32) NOT NULL,
    reference    VARCHAR(256),
    channel      VARCHAR(32) NOT NULL,
    status       VARCHAR(16) NOT NULL,  -- SENT | PRESENTED | QUEUED | FAILED | SKIPPED
    detail       VARCHAR(512),
    provider_ref VARCHAR(256),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX delivery_log_member_idx ON notifier.delivery_log (member_id, created_at DESC);
CREATE UNIQUE INDEX delivery_log_decision_action_idx ON notifier.delivery_log (decision_id, action, reference) WHERE decision_id IS NOT NULL AND status IN ('SENT','PRESENTED','QUEUED');
