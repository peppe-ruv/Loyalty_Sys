CREATE SCHEMA IF NOT EXISTS readmodel;
-- Vista per il sito (CQRS): aggiornata dai topic movimenti, tier, riscatti, concorsi. Nessuna scrittura dal BFF.
CREATE TABLE readmodel.member_summary (
    member_id        VARCHAR(128) PRIMARY KEY,
    reward_available BIGINT NOT NULL DEFAULT 0,
    status_year      BIGINT NOT NULL DEFAULT 0,
    tier             VARCHAR(16) NOT NULL DEFAULT 'BASE',
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()  -- mostrato come "aggiornato alle hh:mm" (RF-50)
);
