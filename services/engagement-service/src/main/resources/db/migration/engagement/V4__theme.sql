-- V4 — tema del portale (docs/servizi/engagement-service.md §2, F-THM-01, M6.5): una riga `default`.
-- `currency_names` in più rispetto alla scheda servizio: BO-20 (docs/08) chiede i nomi delle valute nel portale (Q-79).

CREATE TABLE IF NOT EXISTS theme (
  id             text PRIMARY KEY,                 -- 'default'
  program_name   text NOT NULL,
  tagline        text,
  logo_url       text,
  colors         jsonb NOT NULL,                   -- {primary, secondary, coin, night, bg}
  hero_title     text,
  hero_subtitle  text,
  font_display   text,
  currency_names jsonb NOT NULL DEFAULT '{}'::jsonb, -- {PTS: "punti", STS: "punti status"}
  version        bigint NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  updated_by     text
);
