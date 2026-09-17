-- RF-95 ruota della fortuna: registro dei giri (stesse garanzie del registro giocate quando la ruota è agganciata all'instant win).
CREATE TABLE contestservice.wheel_spin (
    id                 UUID PRIMARY KEY,
    wheel_id           VARCHAR(64)  NOT NULL,
    member_id          VARCHAR(128) NOT NULL,
    slot_id            VARCHAR(64)  NOT NULL,
    won                BOOLEAN      NOT NULL,
    reward_id          VARCHAR(64),
    wallet             VARCHAR(32),
    units              BIGINT       NOT NULL DEFAULT 0,
    spun_at            TIMESTAMPTZ  NOT NULL,
    device_fingerprint VARCHAR(128),
    ip                 VARCHAR(64)
);
CREATE INDEX wheel_spin_member_idx ON contestservice.wheel_spin (wheel_id, member_id, spun_at DESC);
CREATE INDEX wheel_spin_slot_idx ON contestservice.wheel_spin (wheel_id, slot_id) WHERE won;
