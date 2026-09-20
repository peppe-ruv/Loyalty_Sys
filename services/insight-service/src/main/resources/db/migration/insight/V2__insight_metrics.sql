-- V2 — metriche giornaliere (docs/servizi/insight-service.md §2, §5). Aggregati per giorno alimentati
-- dall'ingest (UPSERT incrementale) e dallo storico sintetico del seed (synthetic=true). `dimension`/`dim_value`
-- vuoti = totale della metrica; valorizzati = ripartizione (es. per fonte o valuta).

CREATE TABLE IF NOT EXISTS metric_daily (
  day        date NOT NULL,
  metric     text NOT NULL,
  dimension  text NOT NULL DEFAULT '',
  dim_value  text NOT NULL DEFAULT '',
  value      numeric NOT NULL DEFAULT 0,
  synthetic  boolean NOT NULL DEFAULT false,
  PRIMARY KEY (day, metric, dimension, dim_value)
);

CREATE INDEX IF NOT EXISTS idx_metric_daily_metric ON metric_daily (metric, day);
