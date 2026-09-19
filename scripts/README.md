# scripts — verifiche trasversali (docs/11 §10, docs/10 §11)

| Script | Cosa fa |
|---|---|
| `check-seed.mjs` | Valida i seed: JSON, espressioni di data, stringhe vietate (scheletro M0.7; coerenza incrociata da M1) |
| `check-contracts.mjs` | Gate strutturale sui contratti eventi (envelope, schema per type, dataschema, audit con lhactor) |

`smoke.sh` e `wake.sh` (E2E e risveglio) arrivano con M1.7.
