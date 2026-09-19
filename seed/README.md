# seed — dati demo canonici (docs/10)

Un'unica cartella letta da più servizi (coerenza per costruzione). I servizi la caricano dal classpath col
profilo `demo` (`SeedLoader` di lh-common). Le date sono espressioni relative risolte al caricamento
(`SeedDates`, grammatica in docs/10 §1). `_schemas/` contiene i JSON Schema; `scripts/check-seed.mjs` valida.

Vuoto in M0: i file (`members.json`, `campaigns.json`, `wallets.json`, …) arrivano con le fette di dominio di M1+.
