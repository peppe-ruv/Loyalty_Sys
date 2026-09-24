-- V5 — webhook in uscita (docs/servizi/engagement-service.md §2, §5; F-WBH-01, BO-23, M7.2).
-- Nomi unici nel search_path condiviso dell'hub consolidato (ADR-023): nessun altro schema ha `webhook*`.
-- SPEC-GAP: Q-A1 — rispetto alla scheda servizio: `code` (docs/06 §2, come notification_rule per Q-69), colonne
-- standard di configurazione (`version`, `updated_*`) e, sulla consegna, i dati che BO-23 mostra nel dettaglio
-- ("stato HTTP, tentativi, durata, payload, firma", docs/08) più quelli che servono allo scheduler: `payload` (il corpo
-- firmato: la consegna parte dallo scheduler, non dal consumer), `signature`, `member_id`, `test`, `duration_ms`,
-- `error`, `last_attempt_at`, `claimed_until` (lease: un invio alla volta per consegna, senza tenere aperta una
-- transazione durante la chiamata HTTP).

CREATE TABLE IF NOT EXISTS webhook (
  id          text PRIMARY KEY,                      -- ULID
  code        text NOT NULL UNIQUE,                  -- WH-…
  name        text NOT NULL,
  url         text NOT NULL,
  secret      text NOT NULL,                         -- mai restituito dopo la creazione
  fact_types  text[] NOT NULL DEFAULT '{}',          -- forma breve, es. wallet.points.earned
  enabled     boolean NOT NULL DEFAULT true,
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by  text,
  updated_by  text
);

CREATE TABLE IF NOT EXISTS webhook_delivery (
  id               text PRIMARY KEY,                 -- ULID
  webhook_id       text NOT NULL REFERENCES webhook (id) ON DELETE CASCADE,
  event_id         text NOT NULL,
  fact_type        text NOT NULL,                    -- forma breve
  member_id        text,
  test             boolean NOT NULL DEFAULT false,   -- "Invia evento di prova" di BO-23
  payload          text NOT NULL,                    -- CloudEvent JSON: il corpo esatto che si firma e si invia
  signature        text NOT NULL,                    -- sha256=<HMAC(secret, payload)>
  attempt          int NOT NULL DEFAULT 0,           -- tentativi già eseguiti
  status           text NOT NULL DEFAULT 'PENDING',  -- PENDING | OK | FAILED | GAVE_UP
  http_status      int,
  response_excerpt text,
  error            text,                             -- TIMEOUT | CONNECTION_FAILED | BLOCKED_ADDRESS | HTTP_ERROR
  duration_ms      int,
  next_attempt_at  timestamptz,
  last_attempt_at  timestamptz,
  claimed_until    timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (webhook_id, event_id)
);
CREATE INDEX IF NOT EXISTS webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX IF NOT EXISTS webhook_delivery_by_webhook ON webhook_delivery (webhook_id, created_at DESC);
CREATE INDEX IF NOT EXISTS webhook_delivery_created ON webhook_delivery (created_at);
