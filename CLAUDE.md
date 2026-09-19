# CLAUDE.md — Loyalty Hub

Piattaforma loyalty **open source ed event-driven**: microservizi Spring Boot + topic Kafka, frontend Next.js (backoffice + portale membri). Questo file è il contratto operativo per Claude Code.

> **Il codice segue le specifiche in `docs/`, non il contrario.** Se una specifica manca o è contraddittoria: non inventare, segui la procedura "Fermati e chiedi" in fondo.

## 1. Regole d'oro

1. **Spec-first.** Ogni modifica cita gli ID che implementa (`F-…`, `BO-…`, `PT-…`, `EVT-…`, `ADR-…`) nel messaggio di commit.
2. **Un solo proprietario per entità.** Ogni entità vive in un solo servizio (vedi `docs/04`). Il backoffice è un *client* delle API dei servizi: niente CMS o collezioni parallele. **Nessuna entità senza lettore**: se crei un'entità configurabile deve esistere chi la consuma (motore, portale o report).
3. **Nessuna chiamata sincrona tra servizi.** I servizi comunicano solo via Kafka (outbox + consumer idempotenti). Le chiamate HTTP arrivano solo dal frontend (via proxy Next.js) o da fonti esterne.
4. **Contratti evento immutabili.** I tipi evento sono quelli di `docs/05` e `contracts/events/`. Aggiungere campi opzionali è lecito; rinominare/rimuovere no (serve nuova versione + ADR).
5. **5 topic, non uno di più** (limite del Kafka gratuito, ADR-004). Nuovi flussi = nuovi `type` su topic esistenti.
6. **Niente login nel PoC.** Identità simulata con header `X-LH-Actor` (backoffice) e `memberId` esplicito (portale). Non introdurre Spring Security, sessioni o JWT.
7. **Neutralità.** Nessun riferimento ad aziende reali in codice, seed, testi, commit. Package `io.loyaltyhub`, URN `urn:loyaltyhub:*`, brand demo fittizio "Club Aurora".
8. **Costo zero.** Non aggiungere componenti infrastrutturali (Redis, registry, gateway, broker aggiuntivi…) senza ADR approvata. Ogni servizio deve girare in 512 MB RAM / 0.1 CPU.
9. **Dati demo = cittadini di prima classe.** `seed/` è l'unica fonte dei dati fittizi; i servizi la caricano con profilo `demo`. Ogni schermata deve essere "piena" a demo appena accesa.

## 2. Mappa di lettura (leggi solo ciò che serve al task)

| Se devi… | Leggi |
|---|---|
| Capire cosa costruire e cosa no | `docs/01-VISIONE-E-SCOPE.md`, `docs/02-CATALOGO-FUNZIONALE.md` |
| Capire regole di dominio (punti, tier, concorsi…) | `docs/03-MODELLO-DI-DOMINIO.md` |
| Toccare confini tra servizi, topic, pattern | `docs/04-ARCHITETTURA.md`, `docs/05-EVENTI-E-TOPIC.md` |
| Scrivere codice backend | `docs/06-CONVENZIONI-BACKEND.md` + `docs/servizi/<servizio>.md` |
| Scrivere codice frontend | `docs/07-FRONTEND-FONDAMENTA.md` + `docs/08` (backoffice) o `docs/09` (portale) |
| Creare/modificare dati fittizi | `docs/10-DATI-DEMO.md` |
| Deploy, env, Docker, CI | `docs/11-DEPLOY-COSTO-ZERO.md` |
| Sapere cosa fare adesso | `docs/12-PIANO-DI-SVILUPPO.md` + `docs/14-STATO-AVANZAMENTO.md` |
| Capire il perché di una scelta | `docs/13-REGISTRO-DECISIONI.md` |

## 3. Struttura del repo

```
CLAUDE.md  README.md  LICENSE  pom.xml (parent)  render.yaml
docs/                  specifiche (fonte di verità)
contracts/events/      JSON Schema degli eventi + examples/
seed/                  dati demo canonici (JSON), letti dai servizi
libs/lh-common/        starter condiviso (envelope, outbox, idempotenza, errori, actor, seed loader)
services/<nome>/       8 microservizi Spring Boot (un Dockerfile ciascuno)
web/                   Next.js: /(hub), /backoffice, /portal, /api
deploy/                docker-compose.yml, script, note Kafka
scripts/               smoke.sh, check-seed.mjs, wake.sh
.github/workflows/     ci.yml
```

## 4. Comandi

```bash
# infrastruttura locale (Kafka KRaft + Postgres + Kafka UI)
docker compose -f deploy/docker-compose.yml up -d kafka postgres kafka-ui
# build + test backend
./mvnw -q verify
# un servizio in locale (profili: demo = seed, local = endpoint locali)
./mvnw -pl services/wallet-service -am spring-boot:run -Dspring-boot.run.profiles=demo,local
# tutto lo stack in container
docker compose -f deploy/docker-compose.yml --profile all up --build
# frontend
cd web && pnpm i && pnpm dev          # http://localhost:3000
pnpm lint && pnpm typecheck && pnpm test
# verifiche trasversali
node scripts/check-seed.mjs           # coerenza dei seed tra servizi
bash scripts/smoke.sh                 # E2E: azione -> punti entro 15 s
```

## 5. Convenzioni in pillole

- **Java 25, Spring Boot 4.1.x, Maven.** Spring Data JDBC + `JdbcClient`, Flyway, Spring for Apache Kafka. Niente JPA/Hibernate, niente Lombok (usa `record`).
- **Package:** `io.loyaltyhub.<servizio>.{api,domain,application,infra,messaging,demo}`.
- **DB:** un solo database, **uno schema per servizio**; un servizio non legge mai lo schema di un altro.
- **API:** REST JSON, prefisso `/v1`, errori RFC 9457, paginazione `{items,page}`. Vedi `docs/06`.
- **Eventi:** CloudEvents 1.0 JSON strutturato; `type` = `io.loyaltyhub.<famiglia>.<nome>`; chiave Kafka = `memberId`.
- **Frontend:** TypeScript strict, App Router, Tailwind v4 + shadcn/ui, TanStack Query. `backoffice/` e `portal/` non si importano mai a vicenda.
- **Lingua:** UI e documenti in italiano; identificatori, API ed eventi in inglese.
- **Commit:** Conventional Commits + ID spec, es. `feat(wallet): FIFO lot spending [F-WAL-04]`.
- **Test:** ogni regola di dominio ha unit test; ogni consumer ha un test d'integrazione (Testcontainers). Vedi `docs/06 §9`.

## 6. Contratto di esecuzione (agente)

### Fonti autorevoli e precedenza
| # | Fonte | Decide su |
|---|---|---|
| 1 | `docs/13-REGISTRO-DECISIONI.md` | vincoli architetturali (vince su tutto) |
| 2 | `docs/05` + `contracts/events/` | forma degli eventi |
| 3 | `docs/servizi/*.md` | API, tabelle, regole del singolo servizio |
| 4 | `docs/02`, `docs/03` | comportamento funzionale |
| 5 | `docs/08`, `docs/09` | comportamento delle schermate |
| 6 | `docs/10` + `seed/` | dati demo |

Se due fonti confliggono vince quella col numero più basso; apri comunque una voce in `docs/15-DOMANDE-APERTE.md`.

### Non toccare
| Cosa | Perché |
|---|---|
| Nomi dei 5 topic e dei `type` evento | contratto tra servizi; il Kafka gratuito ha un tetto rigido |
| ID dei dati in `seed/` (`MBR-…`, `CMP-…`, `RWD-…`) | referenziati da più servizi, dagli scenari e dalle specifiche UI |
| Confini dei servizi (chi possiede cosa) | è l'ordine che questo repo esiste per garantire |
| `docs/13` (ADR accettate) | si superano con una nuova ADR, non si riscrivono |

### Definizione di "fatto" (per ogni slice)
1. Criteri di accettazione della milestone verdi (`docs/12`).
2. `./mvnw verify` e `pnpm lint typecheck test` verdi; `scripts/check-seed.mjs` verde.
3. Schermate coinvolte: stati *loading / empty / error / degraded* implementati (`docs/07 §6`).
4. `docs/14-STATO-AVANZAMENTO.md` aggiornato (spunta gli ID completati).
5. Nessun `TODO` senza ID; ogni scostamento dalla spec marcato `// SPEC-GAP: <id domanda>`.

### Fermati e chiedi
| Condizione | Azione |
|---|---|
| Serve un nuovo servizio, topic, dipendenza infrastrutturale o libreria "pesante" | STOP → proponi ADR in `docs/15-DOMANDE-APERTE.md`, attendi Giuseppe |
| Una feature richiede autenticazione reale | STOP → fuori scope PoC |
| Specifica ambigua su una regola di business (punti, tier, vincite) | Scegli l'opzione più conservativa, marca `SPEC-GAP`, registra la domanda |
| Un limite del free tier impedisce un requisito | STOP → descrivi il limite e 2 alternative a costo zero |
| Devi cambiare un contratto evento o un ID seed | STOP → è una decisione, non un refactor |

Referente per ogni escalation: **Giuseppe** (owner del progetto).
