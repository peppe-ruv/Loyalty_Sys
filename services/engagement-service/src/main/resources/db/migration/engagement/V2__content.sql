-- V2 — contenuti del CMS (docs/servizi/engagement-service.md §2), fetta M6.1: card, banner, pop-up e card vincita.
-- La registrazione delle viste dei pop-up (`popup_view`) arriva con M6.2.

CREATE TABLE IF NOT EXISTS content_item (
  id          text PRIMARY KEY,                     -- ULID
  code        text NOT NULL UNIQUE,
  kind        text NOT NULL,                        -- CARD | POPUP | BANNER
  placement   text,                                 -- HOME_HERO | HOME_GRID | CATALOG_TOP | CONTEST | WIN (null per i pop-up)
  title       text NOT NULL,
  body        text,
  image_url   text,
  cta_label   text,
  cta_target  text,                                 -- percorso del portale o URL https
  link_type   text NOT NULL DEFAULT 'NONE',         -- NONE | CONTEST | CAMPAIGN | REWARD | PRIZE
  link_code   text,
  audience    jsonb NOT NULL DEFAULT '{}'::jsonb,   -- {tiers[], segments[], statuses[]}; vuoto = tutti
  start_at    timestamptz,
  end_at      timestamptz,
  priority    integer NOT NULL DEFAULT 50,
  frequency   text,                                 -- ONCE | ONCE_PER_DAY | ALWAYS (solo pop-up)
  dismissible boolean NOT NULL DEFAULT true,
  style       jsonb NOT NULL DEFAULT '{}'::jsonb,   -- {tone: PRIMARY/SECONDARY/COIN/NIGHT, layout}
  status      text NOT NULL DEFAULT 'DRAFT',        -- DRAFT | LIVE | PAUSED | ENDED | ARCHIVED
  version     bigint NOT NULL DEFAULT 0,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  created_by  text,
  updated_by  text
);

CREATE INDEX IF NOT EXISTS content_item_placement ON content_item (placement, status, priority DESC);
