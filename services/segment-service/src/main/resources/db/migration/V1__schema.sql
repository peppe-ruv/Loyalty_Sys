CREATE SCHEMA IF NOT EXISTS segmentservice;
-- Appartenenza corrente: una riga per (membro, segmento); la storia è negli eventi SEGMENT_MEMBERSHIP.
CREATE TABLE segmentservice.membership (
    member_id  VARCHAR(128) NOT NULL,
    segment_id VARCHAR(64)  NOT NULL,
    entered_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (member_id, segment_id)
);
CREATE INDEX membership_segment_idx ON segmentservice.membership (segment_id, entered_at);
-- Liste statiche caricate dal backoffice (criterio STATIC_LIST oltre i limiti di un parametro inline)
CREATE TABLE segmentservice.static_list (
    segment_id VARCHAR(64)  NOT NULL,
    member_id  VARCHAR(128) NOT NULL,
    PRIMARY KEY (segment_id, member_id)
);
