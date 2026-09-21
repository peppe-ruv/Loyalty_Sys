# scripts — verifiche trasversali (docs/11 §10, docs/10 §11)

| Script | Cosa fa |
|---|---|
| `check-seed.mjs` | Valida i seed: JSON, espressioni di data, stringhe vietate (scheletro M0.7; coerenza incrociata da M1) |
| `check-contracts.mjs` | Gate strutturale sui contratti eventi (envelope, schema per type, dataschema, audit con lhactor) |
| `wake.sh` | Risveglia i servizi serverless: interroga l'health finché tutti rispondono (timeout `TIMEOUT`, default 180 s). URL override con `BASE_<SERVIZIO>` |
| `smoke.sh` | Prova E2E del core loop: `POST /v1/events` (acquisto 130 €) → punti sul wallet entro 15 s. `MEMBER`, `AMOUNT`, `BASE_INGESTION`, `BASE_WALLET` configurabili |

Esecuzione locale completa (richiede Docker con accesso ai registri):

```bash
docker compose -f deploy/docker-compose.yml --profile all up --build   # stack + web
scripts/wake.sh        # attende che i servizi rispondano
scripts/smoke.sh       # azione → punti entro 15 s
```

Reset dei dati demo (profilo `demo`): `curl -X POST -H 'X-LH-Actor: ADMIN:cli' http://localhost:8081/v1/demo/reset`
(idem su 8082/8083/8084), oppure dalla Console demo del backoffice (BO-30).
