-- V2 — segmenti statici e dinamici (docs/servizi/member-service.md §2, §5; docs/03 §10). M6.6.
-- Attributi personalizzati (`attribute_definition`) arrivano con M6.7.

CREATE TABLE IF NOT EXISTS segment (
  id            text PRIMARY KEY,                    -- ULID (docs/06 §2)
  code          text NOT NULL UNIQUE,                -- SEG-DIGITAL; immutabile, citato da campagne/premi/contenuti
  name          text NOT NULL,
  description   text,
  type          text NOT NULL,                       -- STATIC | DYNAMIC
  criteria      jsonb,                               -- solo DYNAMIC: condizioni docs/03 §3.3 sullo spazio member.*
  status        text NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE | ARCHIVED
  member_count  integer NOT NULL DEFAULT 0,
  refreshed_at  timestamptz,
  version       integer NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  created_by    text,
  updated_by    text
);

CREATE TABLE IF NOT EXISTS segment_member (
  segment_id  text NOT NULL REFERENCES segment (id) ON DELETE CASCADE,
  member_id   text NOT NULL REFERENCES member (id),
  entered_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (segment_id, member_id)
);

CREATE INDEX IF NOT EXISTS segment_member_member ON segment_member (member_id);

-- Contatori giornalieri per le finestre mobili dei criteri (actions.<type>.count30d, purchases.amount90d):
-- un contatore cumulativo non "dimentica" le azioni uscite dalla finestra.
-- SPEC-GAP: Q-82 — tabella di supporto interna allo schema member, non elencata nella scheda servizio §2.
CREATE TABLE IF NOT EXISTS member_activity_day (
  member_id        text NOT NULL REFERENCES member (id),
  day              date NOT NULL,                    -- giorno di business in Europe/Rome
  action_type      text NOT NULL,                    -- forma breve (purchase.completed)
  count            integer NOT NULL DEFAULT 0,
  purchase_amount  numeric(14,2) NOT NULL DEFAULT 0,  -- solo acquisti esterni
  PRIMARY KEY (member_id, day, action_type)
);

-- actions_by_type nella forma della scheda servizio §2: {type: {count30d, total, lastAt}} (prima: {type: n}).
UPDATE member_stats SET actions_by_type = coalesce((
    SELECT jsonb_object_agg(e.key, CASE WHEN jsonb_typeof(e.value) = 'number'
                                        THEN jsonb_build_object('total', e.value, 'count30d', 0)
                                        ELSE e.value END)
    FROM jsonb_each(member_stats.actions_by_type) e), '{}'::jsonb);
