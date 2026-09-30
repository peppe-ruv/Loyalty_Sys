# seed/_schemas — JSON Schema dei file seed (docs/10 §11)

Uno schema `<file>.schema.json` per ogni `seed/<file>.json`. `scripts/check-seed.mjs` li usa per validare i
dati demo (docs/10 §11 regola 1): ogni seed con lo schema omonimo deve conformarvisi, altrimenti il job `seed` fallisce.

- **Dialetto:** JSON Schema 2020-12 (`"$schema": "https://json-schema.org/draft/2020-12/schema"`), come i contratti
  di `contracts/events/` (docs/05 §9, ADR-009). Le annotazioni `x-lh-*` (es. `x-lh-pii`) sono ammesse.
- **`format`:** asserito sui seed (`email`, `date-time`, `uri`…): scelta più rigida dei servizi, dove è solo
  annotazione (Q-43).
- **`$ref`:** tra file di questa cartella si risolvono per nome file (es. `{"$ref": "members.schema.json"}`).
- Vuoto in M0.7: gli schemi nascono insieme ai rispettivi seed (M1+), a partire da `members.schema.json`.
- Dipendenze: `npm --prefix scripts ci --omit=dev`; senza schemi lo script non le richiede.
