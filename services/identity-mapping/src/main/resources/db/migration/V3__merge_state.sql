-- Merge ripartibile (RF-136): il merge tocca tre domini (grafo locale, registro punti, anagrafica membri) e
-- non può essere una transazione sola. Ogni passo lascia traccia nello stato, così un merge interrotto si
-- riprende da dove si era fermato invece di restare a metà.
ALTER TABLE identitymapping.merge_history
    ADD COLUMN status         VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED',  -- PENDING | LINKS_MOVED | UNITS_MOVED | ALIASED | COMPLETED | UNMERGING | UNMERGED
    ADD COLUMN failure        VARCHAR(512),                               -- ultimo errore del passo non riuscito, per la console operatore
    ADD COLUMN attempts       INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ADD COLUMN units_restored JSONB        NOT NULL DEFAULT '{}';         -- unità davvero ritrasferite dall'unmerge

-- I merge storici erano scritti solo a fine corsa: sono completi per costruzione.
UPDATE identitymapping.merge_history SET status = 'UNMERGED' WHERE unmerged_at IS NOT NULL;

-- Coda di ripresa: solo i merge a metà, che sono pochi e transitori.
CREATE INDEX merge_history_pending_idx ON identitymapping.merge_history (updated_at)
    WHERE status NOT IN ('COMPLETED', 'UNMERGED');
