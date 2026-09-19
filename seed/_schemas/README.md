# seed/_schemas — JSON Schema dei file seed (docs/10 §11)

Uno schema `<file>.schema.json` per ogni `seed/<file>.json`. `scripts/check-seed.mjs` li usa per validare i
dati demo. Vuoto in M0.7: gli schemi nascono insieme ai rispettivi seed (M1+), a partire da `members.schema.json`.
