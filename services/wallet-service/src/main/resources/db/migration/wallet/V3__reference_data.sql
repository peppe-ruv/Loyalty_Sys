-- V3 — dati di riferimento di sistema del wallet, in ogni profilo (Q-629, opzione B; ADR-049, F-WAL-01, F-TIER-01).
-- Solo aggiunte di righe (expand, regola 14, ADR-038): nessuna modifica di schema. Le valute PTS (spendibile, scade dopo
-- 12 mesi a rolling) e STS (non spendibile, vale per l'edizione) e la scala dei livelli di base BASE/SILVER/GOLD/PLATINUM
-- sono parte del prodotto, non dati fittizi e non membri: senza queste righe un programma avviato in `enterprise` non
-- potrebbe accreditare nulla (le PUT di /v1/currencies/{code} e /v1/tiers/{code} agiscono solo su righe esistenti e
-- `wallet.currency` ha una chiave esterna verso `currency`). Il divieto di docs/18 §3.15 (il profilo `demo` rifiuta un DB con
-- membri non di seed; ADR-049: niente seed di vetrina in `enterprise`) riguarda membri e dati di vetrina, non questi
-- riferimenti. Le edizioni NON sono qui: sono scelte del programma (BO-08), non di sistema.
--
-- Fonte dei valori: seed/currencies.json e seed/tiers.json. Devono restare uguali; lo verifica ReferenceDataIT.
-- Convivenza con la demo: il WalletSeeder fa `upsert` sulle stesse chiavi (ON CONFLICT DO UPDATE) e il reset della demo
-- cancella i livelli e li riscrive dal seed (le valute non si cancellano mai: i wallet le referenziano), quindi né la
-- migrazione fa fallire il seeder né il reset lascia la demo senza riferimenti. `ON CONFLICT DO NOTHING`: una valuta o un
-- livello già presente, magari modificato da un operatore, non viene toccato.

INSERT INTO currency (code, name, spendable, expiry_policy) VALUES
  ('PTS', 'Punti', true, '{"type": "ROLLING_MONTHS", "months": 12}'::jsonb),
  ('STS', 'Punti status', false, '{"type": "EDITION"}'::jsonb)
ON CONFLICT (code) DO NOTHING;

INSERT INTO tier (code, name, rank, threshold_sts, multiplier, benefits, color, icon) VALUES
  ('BASE', 'Base', 0, 0, 1.00,
   '["Accumulo punti su ogni acquisto", "Accesso al catalogo premi"]'::jsonb, '#94a3b8', 'circle'),
  ('SILVER', 'Silver', 1, 1000, 1.25,
   '["Punti ×1,25 sugli acquisti", "Anteprima delle promozioni"]'::jsonb, '#9ca3af', 'medal'),
  ('GOLD', 'Gold', 2, 3000, 1.50,
   '["Punti ×1,50 sugli acquisti", "Premi riservati", "1 giocata bonus sugli acquisti ≥ 50 €", "Assistenza prioritaria"]'::jsonb,
   '#f59e0b', 'award'),
  ('PLATINUM', 'Platinum', 3, 7000, 2.00,
   '["Punti ×2 sugli acquisti", "Premi ed eventi esclusivi", "Assistenza dedicata"]'::jsonb, '#6366f1', 'crown')
ON CONFLICT (code) DO NOTHING;
