-- Grafo delle identità (RF-136): ogni identificatore (tipo, valore) punta a un solo membro; un membro ha molti identificatori
CREATE TABLE identitymapping.identity_link (
    kind        VARCHAR(32)  NOT NULL,   -- oidc_sub | crm_id | sap_bp_id | device_id | app_id | pos_card | ecommerce_id | email_hash | phone_hash | external
    value       VARCHAR(256) NOT NULL,
    member_id   VARCHAR(128) NOT NULL,
    source      VARCHAR(64)  NOT NULL,   -- sistema che ha creato il collegamento
    confidence  SMALLINT     NOT NULL DEFAULT 100,  -- 100 = deterministico (login), <100 = probabilistico (da confermare)
    linked_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, value)
);
CREATE INDEX identity_link_member_idx ON identitymapping.identity_link (member_id);

-- Migrazione della tabella storica (sub OIDC → CRM/SAP) nel grafo
INSERT INTO identitymapping.identity_link(kind, value, member_id, source, confidence)
SELECT 'oidc_sub', member_id, member_id, 'legacy', 100 FROM identitymapping.member_identity ON CONFLICT DO NOTHING;
INSERT INTO identitymapping.identity_link(kind, value, member_id, source, confidence)
SELECT 'crm_id', crm_id, member_id, 'legacy', 100 FROM identitymapping.member_identity WHERE crm_id IS NOT NULL ON CONFLICT DO NOTHING;
INSERT INTO identitymapping.identity_link(kind, value, member_id, source, confidence)
SELECT 'sap_bp_id', sap_bp_id, member_id, 'legacy', 100 FROM identitymapping.member_identity WHERE sap_bp_id IS NOT NULL ON CONFLICT DO NOTHING;

-- Merge/unmerge con snapshot per ripristino (identità separabili)
CREATE TABLE identitymapping.merge_history (
    merge_id        VARCHAR(64) PRIMARY KEY,
    from_member_id  VARCHAR(128) NOT NULL,   -- assorbito
    into_member_id  VARCHAR(128) NOT NULL,   -- sopravvissuto
    reason          VARCHAR(256),
    actor           VARCHAR(128),
    moved_links     JSONB NOT NULL,          -- [{kind,value,source,confidence}]
    units_moved     JSONB NOT NULL DEFAULT '{}',
    merged_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    unmerged_at     TIMESTAMPTZ,
    unmerge_reason  VARCHAR(256)
);
CREATE INDEX merge_history_members_idx ON identitymapping.merge_history (from_member_id, into_member_id);

-- Alias: un membro assorbito continua a risolvere verso il sopravvissuto (retro-compatibilità degli id nei sistemi esterni)
CREATE TABLE identitymapping.member_alias (
    old_member_id  VARCHAR(128) PRIMARY KEY,
    member_id      VARCHAR(128) NOT NULL,
    merge_id       VARCHAR(64) NOT NULL REFERENCES identitymapping.merge_history(merge_id)
);
