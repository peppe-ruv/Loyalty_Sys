-- V2 — pool coupon (M4.2, docs/servizi/reward-service.md §2, §6). I contatori `total`/`available` di V1 duplicavano
-- ciò che si ricava da `coupon` (conteggio per stato): li togliamo per non avere due fonti. `seed` rende la
-- generazione dei codici deterministica (stessi codici a ogni reset, docs/10 §1).
ALTER TABLE coupon_pool DROP COLUMN IF EXISTS total;
ALTER TABLE coupon_pool DROP COLUMN IF EXISTS available;
ALTER TABLE coupon_pool ADD COLUMN IF NOT EXISTS seed bigint NOT NULL DEFAULT 0;
ALTER TABLE coupon_pool ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS voided_at timestamptz;
CREATE INDEX IF NOT EXISTS coupon_pool_created_idx ON coupon (pool_id, created_at, code);
