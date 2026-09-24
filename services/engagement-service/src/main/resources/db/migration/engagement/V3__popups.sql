-- V3 — pop-up (docs/servizi/engagement-service.md §2, §5; M6.2): viste per membro e giorno, data d'iscrizione nello
-- snapshot per il pubblico "iscritti da meno di N giorni" (SPEC-GAP Q-71).

CREATE TABLE IF NOT EXISTS popup_view (
  content_id   text NOT NULL,
  member_id    text NOT NULL,
  view_date    date NOT NULL,                        -- giorno (Europe/Rome) della vista
  seen_at      timestamptz NOT NULL DEFAULT now(),
  dismissed_at timestamptz,
  PRIMARY KEY (content_id, member_id, view_date)
);
CREATE INDEX IF NOT EXISTS popup_view_member ON popup_view (member_id, content_id, view_date DESC);

ALTER TABLE engagement_member_snapshot ADD COLUMN IF NOT EXISTS registered_at timestamptz;
