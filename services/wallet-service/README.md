# wallet-service — porta 8084, schema `wallet` (docs/04 §3, docs/servizi/wallet-service.md).

M1.4: accrediti, saldi, movimenti. Applica gli effetti `points.grant` (idempotenti su `effect_id`),
applica il **moltiplicatore di tier** letto da `tier`/`member_tier` (F-TIER-03 in forma minima),
scrive il ledger e produce `wallet.points.earned`. Crea i due wallet + `member_tier` BASE al fatto
`member.registered` (o "on the fly" se l'accredito precede la registrazione). Le tabelle `points_lot`,
`tier_history`, `edition` sono create secondo la scheda ma **non ancora usate**: lotti, scadenze, salita di
livello, edizioni e rettifiche arrivano con M3; la saga di spesa e i rimborsi con M4.
