-- RF-125 Customer 360: una riga JSONB per membro, aggiornata dai consumer con lock di riga.
CREATE TABLE readmodel.customer_context (
    member_id  VARCHAR(128) PRIMARY KEY,
    context    JSONB        NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX customer_context_status_idx ON readmodel.customer_context ((context->'identity'->>'status'));
CREATE INDEX customer_context_tier_idx ON readmodel.customer_context ((context->'loyalty'->>'tier'));
CREATE INDEX customer_context_updated_idx ON readmodel.customer_context (updated_at);
