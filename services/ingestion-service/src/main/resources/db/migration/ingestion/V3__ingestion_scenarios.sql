-- V3 — scenari demo (docs/servizi/ingestion-service.md §2, §5, F-DEMO-04). Uno scenario è una sequenza di
-- passi che l'esecutore invia nella pipeline (origine SIMULATOR) rispettando i ritardi. `scenario_run` traccia
-- l'avanzamento; `results` conserva l'esito passo per passo con il `correlationId` di ogni evento (BO-29).

CREATE TABLE IF NOT EXISTS scenario (
  code        text PRIMARY KEY,
  name        text NOT NULL,
  description text,
  protagonist text,
  watch       text,
  steps       jsonb NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS scenario_run (
  id            text PRIMARY KEY,
  scenario_code text NOT NULL,
  started_at    timestamptz NOT NULL,
  finished_at   timestamptz,
  status        text NOT NULL,          -- RUNNING, DONE, FAILED
  steps_total   int NOT NULL,
  steps_done    int NOT NULL DEFAULT 0,
  actor         text,
  results       jsonb NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS idx_scenario_run_code ON scenario_run (scenario_code, started_at DESC);
