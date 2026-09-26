# scripts — verifiche trasversali (docs/11 §10, docs/10 §11)

| Script | Cosa fa |
|---|---|
| `check-seed.mjs` | Valida i seed: JSON, espressioni di data, stringhe vietate (scheletro M0.7; coerenza incrociata da M1) |
| `check-contracts.mjs` | Gate strutturale sui contratti eventi (envelope, schema per type, dataschema, audit con lhactor) |
| `wake.sh` | Risveglia i servizi serverless: interroga l'health finché tutti rispondono (timeout `TIMEOUT`, default 180 s). URL override con `BASE_<SERVIZIO>` |
| `smoke.sh` | Prova E2E del core loop: `POST /v1/events` (acquisto 130 €) → punti sul wallet entro 15 s. `MEMBER`, `AMOUNT`, `BASE_INGESTION`, `BASE_WALLET` configurabili |
| `check-adr-append-only.mjs` | Job CI `guard` (Fase 2, ADR-041): `node scripts/check-adr-append-only.mjs <base> <head> [--pr-labels=a,b]`; fallisce se una riga esistente di `docs/13` cambia o sparisce, o se un ID di `seed/` cambia senza la label `decisione` |
| `check-mermaid.mjs` | Diagrammi Mermaid validi e con `accTitle`/`accDescr` (docs/18 §3.12): `npm --prefix scripts ci` una volta, poi `node scripts/check-mermaid.mjs docs site` (cartelle). Job `docs` da M8.9 |
| `setup-branch-protection.sh` | Ruleset `main-protetto` e impostazioni del repo (ADR-041): **solo il proprietario**, dopo il merge di M8.0; prima `--dry-run`; controlli obbligatori in `LH_REQUIRED_CHECKS` |
| `testbook.sh`, `testbook-report.mjs` | Esecuzione del testbook funzionale e rapporto riga per riga (docs/16 §1bis) |

Esecuzione locale completa (richiede Docker con accesso ai registri):

```bash
docker compose -f deploy/docker-compose.yml --profile all up --build   # stack + web
scripts/wake.sh        # attende che i servizi rispondano
scripts/smoke.sh       # azione → punti entro 15 s
```

Reset dei dati demo (profilo `demo`): `curl -X POST -H 'X-LH-Actor: ADMIN:cli' http://localhost:8081/v1/demo/reset`
(idem su 8082/8083/8084), oppure dalla Console demo del backoffice (BO-30).
