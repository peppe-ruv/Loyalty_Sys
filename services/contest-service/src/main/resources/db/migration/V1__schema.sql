CREATE SCHEMA IF NOT EXISTS contestservice;

CREATE TABLE contestservice.contest (
    id                           UUID PRIMARY KEY,
    name                         VARCHAR(160) NOT NULL,
    starts_at                    TIMESTAMPTZ NOT NULL,
    ends_at                      TIMESTAMPTZ NOT NULL,
    status                       VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','APPROVED','RUNNING','CLOSED')),
    max_plays_per_member_per_day INT NOT NULL DEFAULT 1,
    prema_protocol               VARCHAR(64),          -- RC-03: obbligatorio per passare a RUNNING
    prema_notified_at            DATE,
    bond_reference               VARCHAR(128),         -- RC-04: fideiussione 100% montepremi
    onlus                        VARCHAR(200),         -- RC-06
    regulation_object_key        VARCHAR(256),         -- RC-08: PDF del regolamento su object storage
    CHECK (ends_at > starts_at),
    CHECK (ends_at <= starts_at + INTERVAL '1 year')   -- RC-05
);

-- Istanti vincenti: "at" è cifrato a livello di colonna dall'applicazione in produzione (RF-31); qui in chiaro per lo scaffold.
CREATE TABLE contestservice.winning_instant (
    id               UUID PRIMARY KEY,
    contest_id       UUID NOT NULL REFERENCES contestservice.contest(id),
    at               TIMESTAMPTZ NOT NULL,
    prize_code       VARCHAR(64) NOT NULL,
    assigned_play_id UUID,
    assigned_at      TIMESTAMPTZ
);
CREATE INDEX winning_instant_open_idx ON contestservice.winning_instant (contest_id, at) WHERE assigned_play_id IS NULL;

-- Registro giocate append-only con hash concatenato (RF-33)
CREATE TABLE contestservice.play (
    seq                BIGSERIAL PRIMARY KEY,
    id                 UUID NOT NULL UNIQUE,
    contest_id         UUID NOT NULL REFERENCES contestservice.contest(id),
    member_id          VARCHAR(128) NOT NULL,
    played_at          TIMESTAMPTZ NOT NULL,
    outcome            VARCHAR(8) NOT NULL CHECK (outcome IN ('WON','LOST')),
    matched_instant_id UUID REFERENCES contestservice.winning_instant(id),
    device_fingerprint VARCHAR(128),
    ip                 VARCHAR(64),
    prev_hash          CHAR(64),
    hash               CHAR(64) NOT NULL
);
CREATE INDEX play_member_day_idx ON contestservice.play (contest_id, member_id, played_at);

CREATE OR REPLACE FUNCTION contestservice.forbid_change() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'contestservice.play is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER play_immutable BEFORE UPDATE OR DELETE ON contestservice.play FOR EACH ROW EXECUTE FUNCTION contestservice.forbid_change();
