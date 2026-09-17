-- RF-99 campi custom per gruppo (righe ripetibili con row_index >= 0, gruppo singolo = -1); RF-108 identificatori.
CREATE TABLE memberservice.custom_field (
    member_id  VARCHAR(128) NOT NULL REFERENCES memberservice.member(id),
    group_name VARCHAR(64)  NOT NULL,
    row_index  INT          NOT NULL DEFAULT -1,
    values     JSONB        NOT NULL,
    PRIMARY KEY (member_id, group_name, row_index)
);
CREATE INDEX custom_field_values_gin ON memberservice.custom_field USING gin (values);
CREATE TABLE memberservice.member_identifier (
    member_id VARCHAR(128) NOT NULL REFERENCES memberservice.member(id),
    kind      VARCHAR(16)  NOT NULL,
    value     VARCHAR(128) NOT NULL,
    PRIMARY KEY (member_id, kind)
);
CREATE UNIQUE INDEX member_identifier_unique_idx ON memberservice.member_identifier (kind, value) WHERE kind IN ('OIDC_SUB','CRM_ID','SAP_BP','EMAIL','LOYALTY_CARD');
