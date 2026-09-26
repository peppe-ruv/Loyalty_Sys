-- Dati personali fuori dal bus (ADR-032, docs/18 §3.4, M8.4): lo snapshot passa da data di nascita e città ad anno di
-- nascita e sigla di provincia (member.*:2). Fase expand: birth_date resta finché i fatti :1 sono letti (Q-346) e si
-- rimuove nella fase contract, con la :1.
ALTER TABLE member_snapshot ADD COLUMN IF NOT EXISTS birth_year int;
ALTER TABLE member_snapshot ADD COLUMN IF NOT EXISTS province text;
UPDATE member_snapshot SET birth_year = extract(year FROM birth_date)::int WHERE birth_date IS NOT NULL AND birth_year IS NULL;
