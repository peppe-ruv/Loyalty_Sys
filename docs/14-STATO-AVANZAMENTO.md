# 14 — Stato di avanzamento

Checklist **viva**: la aggiorna chi chiude una fetta (persona o agente), nello stesso commit del codice. È la prima cosa da leggere a inizio sessione insieme a `CLAUDE.md`.

**Come si usa** — `[ ]` da fare · `[~]` in corso (aggiungere data e nota) · `[x]` fatto (aggiungere l'hash breve del commit). Una feature si spunta solo se rispetta la *Definizione di fatto* (`CLAUDE.md §6`). Le feature che attraversano più milestone (es. `M1→M6`) si spuntano alla **prima** milestone e si annotano gli arricchimenti successivi nel registro in fondo.

## Quadro

| Milestone | Stato | Inizio | Fine | Demo online aggiornata | Note |
|---|---|---|---|---|---|
| M0 — Fondamenta | ✅ completata | 2026-09-19 | 2026-09-19 | ☐ | M0.1→M0.7 chiuse; CI in piedi |
| M1 — Core loop e primo deploy | ✅ completata | 2026-09-19 | 2026-09-19 | ☐ | M1.1→M1.8 chiuse; demo ospitata online |
| M2 — Visibilità | in corso | M2.2 | | ☐ | |
| M3 — Punti adulti | da iniziare | | | ☐ | |
| M4 — Premi | da iniziare | | | ☐ | |
| M5 — Gioco | da iniziare | | | ☐ | |
| M6 — Contenuti | da iniziare | | | ☐ | |
| M7 — Governance | da iniziare | | | ☐ | |

**Prossima fetta da lavorare:** `M2.3` (tracciati + BO-25 + `usePendingTrace` da polling a SSE)

**Ambiente demo** (ADR-023 + ADR-024: deployable consolidato `hub` senza broker)

| Risorsa | Stato | Riferimento (URL/ID, mai segreti) |
|---|---|---|
| Repository GitHub | ✅ | branch `claude/istruzioni-dwhe86` |
| Kafka locale (compose) | ✅ | `deploy/docker-compose.yml` (KRaft); topic dal profilo `local` |
| Hub online (Render, free) | ✅ | `srv-daneakoae00c73eg7j20` — https://loyalty-hub-6dc3.onrender.com (Docker `deploy/hub/Dockerfile`, Frankfurt, profilo `demo,inproc`). Include **insight** (event store + SSE) da M2.2: BO-24 live online |
| Postgres Neon | ✅ | progetto `odd-pine-62283646` (`Neon-Postgres-Loyalty`, eu-central-1, PG 18); DB `neondb`, schemi `ingestion/member/campaign/wallet` migrati e seminati (12 membri, 20 campagne, 24 wallet) |
| Broker Kafka gestito | — | non usato: demo senza broker (bus in-process, ADR-024). Redpanda/Confluent free restano opzione a fedeltà piena se servisse il protocollo online |
| Frontend Vercel | ✅ | progetto `loyalty-hub-web` (`prj_pO7cj7Q6iMKbcSXFPakB8WhilUUb`, team `poc-22b1`, Next.js, root `web/`, branch `claude/istruzioni-dwhe86`) — **https://loyalty-hub-web.vercel.app**. Env: `LH_SVC_{INGESTION,MEMBER,CAMPAIGN,WALLET,INSIGHT}_URL` + `NEXT_PUBLIC_LH_INSIGHT_URL` all'hub Render (SSE diretto per BO-24); deployment protection off (demo pubblica). NB: `loyalty-hub-playground` è un'altra app (Payload CMS), lasciata intatta |

## M0 — Fondamenta

**Fette** (`docs/12 §3`)

- [x] `M0.1` — parent POM (Java 25, Boot 4.1.0), wrapper, `.editorconfig`/`.gitignore`/`.dockerignore`, struttura cartelle `CLAUDE.md §3`; `./mvnw verify` verde (`86c6666`)
- [x] `M0.2` — `libs/lh-common`: envelope CloudEvents + factory, outbox (writer/relay/cleanup), inbox (idempotenza + router), Kafka (PLAINTEXT/SSL_PEM/SASL_SSL, error handler → DLQ), errori RFC 9457, actor `X-LH-Actor` + `@RequiresRole`, approvazioni, audit, SeedLoader + SeedDates, ids/time, `V0__lh_common.sql`, auto-config; test verdi (24 unit + 5 IT con EmbeddedKafka+Zonky). SPEC-GAP Q-40 (`4f9ddaa`)
- [x] `M0.3` — `contracts/events/`: `envelope.schema.json` + 10 schemi `data` degli eventi di M1 (action/effect/fact/audit, JSON Schema 2020-12) + un esempio valido per type; test di contratto `ContractsTest` (envelope + data + coerenza `dataschema` + audit con `lhactor`). SPEC-GAP Q-42 (`10db769`)
- [x] `M0.4` — `deploy/docker-compose.yml`: Kafka KRaft + Postgres 17 + Kafka UI (infra di default) e 8 servizi + web sotto `--profile all`; limiti mem/cpu. I 5 topic li crea il profilo `local` (bean `NewTopic`, 2 partizioni), verificato da `LocalTopicsIT` (Kafka in-JVM). Compose validato con `docker compose config`. (`8d4b60d`)
- [x] `M0.5` — servizio archetipo `ingestion-service`: `POST /v1/events` → dedup `(source,id)` → arricchimento `lh*` → outbox su `lh.actions.v1` (`202 ACCEPTED`/`DUPLICATE`), consumer di prova, Flyway (`V0`+`V1 inbound_event`), Actuator, Dockerfile multi-stage. IT su Spring Boot + EmbeddedKafka + Zonky (accettato/duplicato/400). **Migrazione a Jackson 3** (default di Boot 4) in lh-common + servizio; aggiunte le auto-config Boot 4 mancanti (`spring-boot-flyway`, `@EnableKafka`, `KafkaAdmin`). Q-43 (`ebb8dcb`)
- [x] `M0.6` — `web/` Next.js: token e font (`next/font`), shell delle tre aree (HUB-01, backoffice, portale), proxy `/api/lh/[service]/[...path]` (aggiunge `X-LH-Actor` + `X-Correlation-Id`), cookie persona, `/api/demo/status` e `/api/demo/wake`, keep-alive gentile; HUB-01 con pannello stato (10 tessere, wake, barra "pronti N/10", ingressi, percorso). `pnpm lint typecheck test build` verdi (9 test). (`ffba0df`)
- [x] `M0.7` — CI `.github/workflows/ci.yml` (job `backend` Temurin **25** + `./mvnw -B verify`, `web` pnpm lint/typecheck/test/build, `seed`, `contracts`, `docker` solo su main, `e2e` manuale); `scripts/check-seed.mjs` (scheletro) e `scripts/check-contracts.mjs`; `seed/_schemas/`. Script verdi in locale; YAML validato. Risolve Q-41 (JDK 25 in CI). (`80dad2f`)

**Feature** (`docs/02`)

- [ ] `F-DEMO-01` Demo Hub (P0) — _M0/M1_
- [x] `F-DEMO-02` Cambio persona (P0) — _cookie `lh_persona` via `/api/persona`; tray PT-14 + selettore backoffice_

**Accettazione M0** (`docs/12`)

- [x] criteri di accettazione verdi — accettato/duplicato/DLQ/no-loss coperti dagli IT; tessere `DOWN` senza errori nel Demo Hub. _Nota: RSS ≤ 450 MB dell'immagine archetipo va misurato con un run reale del container (Docker Hub non pull-abile qui; job `docker` in CI + deploy M1.8)._
- [x] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [x] stati *loading / empty / error / degraded* sulle schermate toccate — implementati in HUB-01/StatusPanel (docs/07 §6)

## M1 — Core loop e primo deploy

**Fette** (`docs/12 §3`)

- [x] `M1.1` — ingestion completo per M1: registri (`source`, `event_type` con JSON Schema 2020-12, `member_index`, `internal_mapping`), pipeline di accettazione a 8 passi (docs §5) con tutti gli esiti (`ACCEPTED/DUPLICATE/REJECTED/UNMATCHED` + `SOURCE_DISABLED/UNKNOWN_TYPE/TYPE_NOT_ALLOWED/INVALID_DATA/INVALID_TIME/MEMBER_NOT_ACTIVE`), seed canonici (`sources.json`, `event-types.json` 19 tipi, `members.json` 12 membri, `internal-mappings.json` 9), `DemoSeeder` (`DemoResettable`); IT a 11 casi su EmbeddedKafka + Zonky
- [x] `M1.2` — member-service: anagrafica (`member`), ricerca con filtri (`q`/`status`/`tier`), stati, proiezione saldi/tier (`member_projection`) e statistiche di attività (`member_stats`); produce i tre fatti `member.registered/updated/status.changed` (snapshot completo, lock ottimistico via `version`), consuma `lh.actions.v1` → stats e `lh.facts.v1` (`wallet.points.earned`→saldo, `tier.upgraded`→tier) → proiezione; `GET /v1/demo/personas`; `MemberSeeder` (12 membri dai seed). IT a 7 casi su EmbeddedKafka + Zonky
- [x] `M1.3` — campaign-service: **motore regole** (`CampaignEngine`, classe pura, algoritmo docs/03 §3.5) con condizioni `data`/`member`/`context`/`history`, effetti `GRANT_POINTS` (`FIXED`/`PER_AMOUNT`) e `MULTIPLIER`, limiti per membro/budget, calendario, pubblico per tier, `exclusiveGroup`; `evaluation_log` (spiegabilità) + fatto `campaign.evaluated`; effetti `points.grant` su `lh.effects.v1`; simulazione `POST /v1/campaigns/simulate` (senza scrivere, include bozze con `campaignIds`); portale "come guadagnare"; CRUD + transizioni (`DRAFT→LIVE` diretto, approvazione off fino a M7); consumo `lh.facts.v1`→`member_snapshot`; seed 20 campagne (quelle con effetti non supportati caricate ma scartate con `EFFECT_NOT_SUPPORTED_YET`). 5 unit motore + 8 IT su EmbeddedKafka+Zonky
- [x] `M1.4` — wallet-service: applica gli effetti `points.grant` (idempotenti su `effect_id`) con il **moltiplicatore di tier** letto da `tier`/`member_tier` (F-TIER-03 minima), scrive il ledger `EARN` e produce `wallet.points.earned`; crea i 2 wallet + `member_tier` BASE al `member.registered` (o "on the fly"); saldi/movimenti + API portale. Calcolo canonico chiuso: SILVER + PTS 130 → **EARN 162**, weekend 260 → **325**. Effetti emessi con `childSameBusinessTime` (data di business = azione). Tabelle `points_lot`/`tier_history`/`edition` create ma non usate (lotti/scadenze/salita/edizioni → M3; saga spesa → M4). 5 IT su EmbeddedKafka+Zonky
- [x] `M1.5` — backoffice (docs/08): shell con sidebar a milestone (`lib/nav.ts`), permessi `can(role,capability)`, client proxy TanStack (`useLhQuery`/`useLhMutation`), stati loading/empty/error/degraded (`QueryState`), componenti condivisi (DataTable, StatusPill, TierBadge, PointsAmount, CodeText, LifecycleBar, GeneratedSentence, SimulationPanel, Tabs, Can). Schermate: **BO-02** membri, **BO-03** scheda 360° (panoramica/movimenti/azioni), **BO-05** campagne, **BO-06** editor+simulazione (`describeCampaign()` con test), **BO-09** azioni/fonti (sola lettura), **BO-26** monitor ingressi, **BO-28** simulatore, **BO-30** console (stato + reset orchestrato). Endpoint ingestion aggiunti (GET event-types/sources/inbound-events, POST demo/simulator/fire). `pnpm lint typecheck test build` verdi (13 test)
- [x] `M1.6` — portale membri (docs/09): shell mobile-first «Club Aurora» con tab bar (Home/Guadagna/Attività, solo voci M1), tessera `MemberCard` col materiale del tier, membro attivo dal cookie + cambio persona (`/api/persona`). **PT-01** home (tessera, saldo, avanzamento livello, ultimi 3 movimenti, azioni rapide), **PT-02** guadagna (campagne leggibili con riepilogo premio), **PT-07** attività (movimenti per giorno, filtro valuta, scomposizione "130 base × 1,25 = 162"), **PT-14** tray demo (cambio membro + invio azioni reali). Schema "in elaborazione" a **polling** (`PendingContext`, 5 s; SSE → M2). Backend: wallet `GET /v1/portal/wallets/{id}/activity` (movimenti leggibili). `pnpm lint typecheck test build` verdi
- [x] `M1.7` — collante demo: `POST /v1/demo/reset` ora **realmente esposto** nei 4 servizi (registrato via `LhCommonAutoConfiguration` col profilo `demo` — prima il controller in `io.loyaltyhub.common` era fuori dal component-scan, quindi 404); `scripts/smoke.sh` (E2E: `POST /v1/events` acquisto 130 € → punti sul wallet entro 15 s, senza jq) e `scripts/wake.sh` (risveglio serverless con timeout); job CI `e2e` estende alla sintassi degli script. IT del reset in `IngestionPipelineIT` (12 casi)
- [x] `M1.8` — **primo deploy**: demo ospitata a costo zero (ADR-023 + ADR-024). Deployable consolidato `deploy/hub`: i 4 servizi del core loop (ingestion/member/campaign/wallet) in un solo JVM (gruppo consumer + `EventRouter` per servizio, migrazioni per schema in `db/migration/<servizio>/`, nomi bean qualificati). Broker sostituito da un **bus a eventi in-process** (`HubInProcessBus`, profilo `inproc`) — nessun Kafka a pagamento; fuori da `inproc` resta Redpanda/Kafka vero. Verificato senza broker da `HubInProcessEndToEndIT` (acquisto 130€ feriale → +162 PTS attraverso ingestion→campaign→wallet). **Online**: hub su Render (free, Docker) collegato a **Neon** (schemi migrati e seminati: 12 membri, 20 campagne, 24 wallet; MBR-000003 PTS 3240); avvio pulito, tutte le sottoscrizioni del bus registrate. Fix di deploy: search_path via `connection-init-sql` (il proxy Neon non inoltra `currentSchema`). Frontend Vercel da collegare (scope token fuori sessione). Reattore intero verde (`./mvnw verify`)

**Feature** (`docs/02`)

- [x] `F-ING-01` Ricezione CloudEvents (P0)
- [x] `F-ING-02` Deduplica (P0)
- [x] `F-ING-03` Risoluzione membro (P0)
- [x] `F-ING-05` Registro fonti (P0) — _motore: allow-list per fonte; CRUD backoffice in M1.6+_
- [x] `F-ING-06` Tipi azione e schemi (P0) — _M1 (custom: M6)_
- [x] `F-ING-09` Monitor ingressi (P0) — _API `/v1/inbound-events` (lettura) + BO-26; retry/match in M7_
- [x] `F-MBR-01` Anagrafica membro (P0)
- [x] `F-MBR-02` Scheda 360° (P0) — _BO-03 M1: panoramica/movimenti/azioni; altre schede in M4/M5/M6_
- [x] `F-MBR-04` Stati del membro (P0)
- [x] `F-CMP-01` CRUD campagne (P0)
- [x] `F-CMP-02` Ciclo di vita (P0) — _senza approvazione (M7)_
- [x] `F-CMP-03` Costruttore condizioni (P0) — _valutazione `data`/`member`/`context`/`history`; meta campi UI in M1.5+_
- [x] `F-CMP-04` Effetti (P0) — _M1: `GRANT_POINTS` FIXED/PER_AMOUNT + `MULTIPLIER`; altri effetti → M5_
- [x] `F-CMP-05` Limiti (P0)
- [x] `F-CMP-06` Pubblico (P0 tier · P1 segmenti) — _M1 tier / M6 segmenti_
- [x] `F-CMP-08` Simulazione (P0)
- [x] `F-CMP-09` Registro valutazioni (P0)
- [x] `F-CMP-11` "Come guadagnare" (P0)
- [x] `F-WAL-01` Doppia valuta (P0)
- [x] `F-WAL-02` Libro mastro (P0)
- [x] `F-DEMO-03` Simulatore eventi (P0) — _BO-28 + ingestion `POST /v1/demo/simulator/fire`_
- [x] `F-DEMO-05` Reset dati (P0) — _`POST /v1/demo/reset` (profilo demo) + Console demo BO-30_
- [ ] `F-DEMO-07` Keep-alive gentile (P0)

**Accettazione M1** (`docs/12`)

- [x] criteri di accettazione verdi — core loop azione→punti verde end-to-end (con e senza broker) negli IT `HubEndToEndIT`/`HubInProcessEndToEndIT`; demo online avviata e seminata su Neon
- [x] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [x] stati *loading / empty / error / degraded* sulle schermate toccate — coperti in M1.5/M1.6
- [x] demo online in piedi: **frontend** https://loyalty-hub-web.vercel.app (Vercel) → **hub** https://loyalty-hub-6dc3.onrender.com (Render) → **Neon** (seminato). _Nota: lo `smoke.sh` E2E sulla URL pubblica va lanciato da Giuseppe/browser (l'egress di questa sessione blocca `*.onrender.com`/`*.vercel.app`); il loop è provato dagli IT sullo stesso codice._

## M2 — Visibilità

**Fette** (`docs/12 §3`)

- [x] `M2.1` — insight-service: **event store** + ingest di tutti e 5 i topic (gruppo `lh-insight`) + retention. `event_store` (idempotente su `event_id`, `ON CONFLICT DO NOTHING`; indici per `correlation_id`, `member_id`, `topic`) e `topic_stat` (conteggio + ultimi offset, non gonfiati dai duplicati). 5 `@KafkaListener` (actions/effects/facts/audit/dlq) → `EventIngestService` (estrae gli attributi CloudEvents, famiglia dal topic, short type senza prefisso, `error_code` dall'header per la DLQ). `RetentionJob` orario (14 giorni o 200k righe). API di lettura `GET /v1/events` (filtri topic/family/type/member/correlation/source/from/to/q) + `GET /v1/events/{id}` (payload) + `GET /v1/pipeline/status`. Reset demo (`DemoResettable`) svuota lo store. IT su EmbeddedKafka(5 topic)+Zonky: 3 topic → 3 righe, duplicato → 1 riga, stat non raddoppiata. SSE/tracciati/KPI/audit/DLQ/sintetico → M2.2–M2.8
- [x] `M2.2` — **stream SSE + rail eventi live (BO-24)**. Backend insight: bus SSE in memoria (`LiveEventHub`, `SseEmitter` + heartbeat 15s + ring buffer 200 per `Last-Event-ID`, consegna serializzata su un thread, client rotto rimosso); `GET /v1/stream/events` (filtri topics/types/member/correlation) con evento `lh-event`; sintesi per tipo (`EventSummaries`, es. `wallet.points.earned` → "+162 PTS"); CORS del solo `/v1/stream/**` da `LH_CORS_ALLOWED_ORIGINS` (ADR-020). L'ingest pubblica sul bus ogni evento nuovo. Frontend: `lib/realtime/{sse.ts,useLiveEvents.ts}` (EventSource diretto a `NEXT_PUBLIC_LH_INSIGHT_URL`, dopo 3 errori → polling di `/v1/events` "live ridotto", buffer 500, pausa con contatore); **BO-24** `/observe/live` (striscia pipeline 5 topic + flusso a righe con TopicDot, filtri famiglia/membro, pausa, evidenzia stesso `correlationId`, indicatore live/ridotto/disconnesso); voce nav (REALIZED_MILESTONE→2). IT: lo stream consegna un `lh-event` via HTTP dopo un evento Kafka; unit sulle sintesi. `pnpm lint typecheck test build` verdi
- [ ] `M2.3`
- [ ] `M2.4`
- [ ] `M2.5`
- [ ] `M2.6`
- [ ] `M2.7`
- [ ] `M2.8`

**Feature** (`docs/02`)

- [ ] `F-CMP-10` Statistiche campagna (P1)
- [ ] `F-AUD-01` Audit log (P0)
- [x] `F-INS-01` Flusso eventi live (P0) — _M2.2: SSE `/v1/stream/events` + rail BO-24 con fallback a polling_
- [ ] `F-INS-02` Tracciato (P0)
- [ ] `F-INS-03` KPI e serie storiche (P0)
- [ ] `F-INS-04` Storico sintetico (P0)
- [x] `F-INS-06` Stato pipeline (P1) — _M2.1: `GET /v1/pipeline/status` da `topic_stat` (ultimo evento, conteggio, offset per partizione); ritardo stimato/ultimo fatto per servizio → M2.4_
- [ ] `F-DEMO-04` Scenari guidati (P0)

**Accettazione M2** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M3 — Punti adulti

**Fette** (`docs/12 §3`)

- [ ] `M3.1`
- [ ] `M3.2`
- [ ] `M3.3`
- [ ] `M3.4`
- [ ] `M3.5`
- [ ] `M3.6`
- [ ] `M3.7`
- [ ] `M3.8`
- [ ] `M3.9`

**Feature** (`docs/02`)

- [ ] `F-ING-07` Transazioni d'acquisto (P1)
- [ ] `F-ING-08` Ponte azioni interne (P0)
- [ ] `F-CMP-07` Cumulabilità (P0)
- [ ] `F-WAL-03` Lotti e scadenza (P0)
- [ ] `F-WAL-05` Punti in attesa (P1)
- [ ] `F-WAL-06` Scadenza (P0)
- [ ] `F-WAL-07` Rettifiche manuali (P0)
- [ ] `F-WAL-09` Passività (P1)
- [ ] `F-TIER-01` Definizione livelli (P0)
- [ ] `F-TIER-02` Salita immediata (P0)
- [x] `F-TIER-03` Moltiplicatore di livello (P0) — _M1.4 forma minima: letto da `tier`/`member_tier` da seed; definizione/salita/edizioni → M3_
- [ ] `F-TIER-04` Chiusura edizione con discesa morbida (P0)
- [ ] `F-TIER-05` Anteprima chiusura (P0)
- [ ] `F-TIER-06` Storico livelli (P1)
- [ ] `F-DEMO-06` Job su richiesta (P0)

**Accettazione M3** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M4 — Premi

**Fette** (`docs/12 §3`)

- [ ] `M4.1`
- [ ] `M4.2`
- [ ] `M4.3`
- [ ] `M4.4`
- [ ] `M4.5`
- [ ] `M4.6`

**Feature** (`docs/02`)

- [ ] `F-WAL-04` Spesa FIFO (P0)
- [ ] `F-WAL-08` Saga di spesa (P0)
- [ ] `F-RWD-01` Catalogo premi (P0)
- [ ] `F-RWD-02` Fasce premi (P0)
- [ ] `F-RWD-03` Disponibilità (P0)
- [ ] `F-RWD-04` Visibilità (P0 tier · P1 segmenti) — _M4 / M6_
- [ ] `F-RWD-05` Richiesta premio (P0)
- [ ] `F-RWD-06` Evasione (P0)
- [ ] `F-RWD-07` Annullamento con rimborso (P1)
- [ ] `F-RWD-08` Ciclo di vita premio (P0) — _M4 (M7 approv.)_
- [ ] `F-CPN-01` Pool di coupon (P0)
- [ ] `F-CPN-02` Emissione (P0) — _M4 / M5_
- [ ] `F-CPN-03` Utilizzo (P1)

**Accettazione M4** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M5 — Gioco

**Fette** (`docs/12 §3`)

- [ ] `M5.1`
- [ ] `M5.2`
- [ ] `M5.3`
- [ ] `M5.4`
- [ ] `M5.5`
- [ ] `M5.6`
- [ ] `M5.7`

**Feature** (`docs/02`)

- [ ] `F-MBR-06` Registrazione dal portale (P1)
- [ ] `F-MBR-07` Completamento profilo (P1)
- [ ] `F-CMP-12` Campagne di sistema (P0)
- [ ] `F-IW-01` Concorso (P0)
- [ ] `F-IW-02` Montepremi (P0)
- [ ] `F-IW-03` Istanti vincenti pre-generati (P0)
- [ ] `F-IW-04` Giocata (P0)
- [ ] `F-IW-05` Crediti di gioco (P0)
- [ ] `F-IW-06` Vincita come azione interna (P0)
- [ ] `F-IW-07` Vincitori e report (P0)
- [ ] `F-IW-08` Aiuto demo (P0)
- [ ] `F-ACH-01` Obiettivi (P0)
- [ ] `F-ACH-02` Progresso (P0)
- [ ] `F-ACH-03` Badge (P0)
- [ ] `F-LDB-01` Classifiche (P1)
- [ ] `F-REF-01` Codice amico (P1)
- [ ] `F-REF-02` Completamento referral (P1)

**Accettazione M5** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M6 — Contenuti

**Fette** (`docs/12 §3`)

- [ ] `M6.0`
- [ ] `M6.1`
- [ ] `M6.2`
- [ ] `M6.3`
- [ ] `M6.4`
- [ ] `M6.5`
- [ ] `M6.6`
- [ ] `M6.7`

**Feature** (`docs/02`)

- [ ] `F-MBR-03` Attributi custom ed etichette (P1)
- [ ] `F-SEG-01` Segmenti statici (P1)
- [ ] `F-SEG-02` Segmenti dinamici (P1)
- [ ] `F-SEG-03` Ricalcolo e fatti (P1)
- [ ] `F-CNT-01` Card (P0)
- [ ] `F-CNT-02` Pop-up (P0)
- [ ] `F-CNT-03` Card vincita (P0)
- [ ] `F-CNT-04` Anteprima (P1)
- [ ] `F-MSG-01` Inbox in-app (P0)
- [ ] `F-MSG-02` Template (P0)
- [ ] `F-THM-01` Tema del portale (P1)

**Accettazione M6** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M7 — Governance

**Fette** (`docs/12 §3`)

- [ ] `M7.1`
- [ ] `M7.2`
- [ ] `M7.3`
- [ ] `M7.4`
- [ ] `M7.5`
- [ ] `M7.6`

**Feature** (`docs/02`)

- [ ] `F-ING-04` Eventi non abbinati (P1)
- [ ] `F-MBR-05` Anonimizzazione (P1)
- [ ] `F-CMP-13` Duplica campagna (P1)
- [ ] `F-WBH-01` Webhook in uscita (P1)
- [ ] `F-APR-01` Workflow di approvazione (P0)
- [ ] `F-APR-02` Policy (P1)
- [ ] `F-APR-03` Casella approvazioni (P0)
- [ ] `F-INS-05` DLQ (P1)

**Accettazione M7** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## Fuori PoC (P2, solo predisposizione)

- `F-ING-10` Invio batch
- `F-MBR-08` Compleanno
- `F-CMP-14` Storno su reso

## Registro delle sessioni

| Data | Fetta | Esito | Commit | Domande aperte create | Note per la prossima sessione |
|---|---|---|---|---|---|
| 2026-09-20 | M2.2 | ✅ `./mvnw verify` verde (3 IT insight incl. SSE + unit sintesi); `pnpm lint typecheck test build` verdi | | — | **Stream SSE + rail eventi live (BO-24).** insight: `LiveEventHub` (SseEmitter, heartbeat 15s, ring buffer 200 per Last-Event-ID, consegna serializzata off-thread), `GET /v1/stream/events` con filtri, `EventSummaries`, CORS `/v1/stream/**` (ADR-020); l'ingest pubblica sul bus ogni evento nuovo. Frontend: `lib/realtime` (EventSource diretto a `NEXT_PUBLIC_LH_INSIGHT_URL`, fallback polling dopo 3 errori), BO-24 `/observe/live` (striscia pipeline + rail con filtri/pausa/evidenzia correlazione/indicatore connessione), nav `REALIZED_MILESTONE→2`, `.env.example`. **Non ancora online**: il rail live richiede insight raggiungibile dal browser con CORS; insight non è nell'hub consolidato → si attiva con M2.8 (deploy insight) o aggiungendolo all'hub. Prossima **M2.3**: tracciati (`/v1/traces`), BO-25, e passaggio del "in elaborazione" da polling a SSE (`usePendingTrace`). |
| 2026-09-19 | M2.1 | ✅ `./mvnw verify` (reattore intero) verde: 1 IT insight + tutti gli IT M1 | | — | **Inizio M2 (Visibilità) — insight-service: event store.** Modulo prima vuoto, ora prende corpo: consuma **tutti e 5** i topic (gruppo `lh-insight`), registra ogni evento in `event_store` idempotente su `event_id`, aggiorna `topic_stat`, retention oraria (14g/200k). API `GET /v1/events` (+ filtri), `GET /v1/events/{id}`, `GET /v1/pipeline/status`. Reset demo svuota lo store. Le tabelle `metric_daily`/`audit_entry`/`dlq_entry` NON sono ancora create: arrivano come migrazioni additive (V2/V3) nelle fette che le usano (M2.4/M2.5/M7). Prossima **M2.2**: SSE (`GET /v1/stream/events`) + `lib/realtime` FE + rail eventi BO-24 + CORS (ADR-020); poi il passaggio del "in elaborazione" da polling a SSE (M2.3). Nota deploy: quando l'SSE sarà pronto, insight va aggiunto all'hub consolidato (o deployato a sé, M2.8) e serve CORS da `LH_CORS_ALLOWED_ORIGINS`. |
| 2026-09-19 | M1.6 fix | ✅ diagnosi su Neon (`inbound_event=0`) + fix | `401bdc7` | — | **Bug demo online: le azioni del tray portale non accreditavano punti.** Causa: il tray (PT-14) chiamava `/v1/demo/simulator/fire` (`@RequiresRole ADMIN/MARKETING/LEGAL/CARE`) ma il portale ha attore MEMBER → `actorHeader`=`ANALYST:anonymous` → **403** prima di ingestion (confermato: `inbound_event=0`, tutti gli outbox/ledger a 0). Fix: il tray ora inietta da `POST /v1/events` (pubblico, senza ruolo) con la fonte reale per tipo (ecommerce/app/billing/partner) ed envelope CloudEvents completo — come una vera azione esterna. BO-28 (attore ADMIN) invariato. **Nota cold-start**: su Render free il primo colpo a hub addormentato (~100 s di boot) può superare il timeout proxy (25 s): svegliare l'hub prima (aprire `/actuator/health` o attendere il caricamento saldo), poi le azioni accreditano. |
| 2026-09-19 | M1.8 | ✅ reattore verde (hub IT broker + inproc); demo online **live** su Render+Neon (avviata, seminata: 12 membri/20 campagne/24 wallet) | `bfe295c`·`4ae3e51`·`a5120f5` | — | **Primo deploy — demo ospitata a costo zero.** ADR-023 (deployable consolidato `deploy/hub`: 4 servizi core in un JVM; gruppo consumer + `EventRouter` per servizio; migrazioni per schema; nomi bean qualificati) + **ADR-024** (bus a eventi **in-process** `HubInProcessBus`, profilo `inproc`: niente broker Kafka a pagamento — su Render un private service costa ~7$/mese e non esiste più Kafka gestito gratuito; deroga circoscritta a CLAUDE.md §1.3 dentro il solo hub; fuori resta Redpanda/Kafka vero). Nuovo IT `HubInProcessEndToEndIT` prova il loop senza broker (130€→+162 PTS). **Online**: hub `srv-daneakoae00c73eg7j20` (https://loyalty-hub-6dc3.onrender.com, Render free/Docker/Frankfurt, profilo `demo,inproc`) su **Neon** `odd-pine-62283646`. Fix deploy: search_path via `connection-init-sql` (Neon non inoltra `currentSchema`). **In sospeso**: (a) frontend Vercel `loyalty-hub-playground` da collegare — scope token `poc-22b1` 403 in sessione, env `LH_SVC_{INGESTION,MEMBER,CAMPAIGN,WALLET}_URL=<hub url>`; (b) smoke E2E sulla URL pubblica da lanciare da Giuseppe/browser (egress sessione blocca `*.onrender.com`). Prossima **M2.1** (visibilità). |
| 2026-09-19 | M0.1 | ✅ `./mvnw verify` verde (9 moduli) | `86c6666` | Q-01→Apache-2.0, Q-02→loyalty-hub (confermate dall'owner) | M0.2 `libs/lh-common`: test prima (Testcontainers Kafka+Postgres) per outbox/idempotenza/DLQ, poi implementazione. Nota ambiente: JDK locale 21; `verify` di M0.1 è verde perché i moduli sono vuoti, ma da M0.2 (codice reale) serve JDK 25 in CI/deploy. |
| 2026-09-19 | M0.2 | ✅ `./mvnw verify` verde (24 unit + 5 IT) | `4f9ddaa` | Q-40 (Testcontainers→EmbeddedKafka+Zonky, pull immagini Docker negato dal proxy), Q-41 (JDK 25 provvisto in ambiente; fissare 25 in CI) | M0.3 `contracts/events/`: envelope + schemi ed esempi degli eventi di M1 (docs/05) + test di contratto. Ambiente: JDK 25 in `/opt/jdk-25` (estratto da `mcr.microsoft.com/openjdk/jdk:25-ubuntu`); export in `~/.bashrc`. I test d'integrazione usano EmbeddedKafka + Zonky (niente Docker). |
| 2026-09-19 | M0.3 | ✅ `./mvnw verify` verde (26 unit + 5 IT) | `10db769` | Q-42 (esempi in `contracts/events/examples/` per `CLAUDE.md §3`, non `contracts/examples/` di docs/05 §9) | M0.4 `deploy/docker-compose.yml` + profilo `local` che crea i 5 topic (docs/11 §9). Nota: i contratti sono sul classpath di test di `lh-common` via `<testResource>`; da M1 ogni produttore aggiunge un test che valida l'evento realmente prodotto (docs/05 §9). |
| 2026-09-19 | M0.4 | ✅ `./mvnw verify` verde (26 unit + 6 IT); `docker compose config` OK | `8d4b60d` | — | M0.5 servizio **archetipo** = `ingestion-service` ridotto: `POST /v1/events` → outbox → `lh.actions.v1`, un consumer di prova, Flyway, Actuator, Dockerfile (docs/servizi/ingestion-service.md). Il compose ha già i riferimenti al Dockerfile dei servizi (profilo `all`). Immagini Docker non pull-abili qui: il boot dell'archetipo si verifica con Spring in-JVM (EmbeddedKafka+Zonky). |
| 2026-09-19 | M0.5 | ✅ `./mvnw verify` verde (reattore intero: 26 unit lh-common + 6 IT + 3 IT ingestion) | `ebb8dcb` | Q-43 (Jackson 3 come default runtime, Jackson 2 confinato ai test di contratto) | M0.6 `web/`: Next.js, token, shell delle tre aree, proxy `/api/lh`, cookie persona, `/api/demo/status|wake`, HUB-01 con pannello stato (docs/07). **Lezioni Boot 4**: auto-config modularizzate (serve `spring-boot-<tech>`); `@KafkaListener` via `@EnableKafka`; `TestRestTemplate` rimosso (usare `RestClient`); `@EmbeddedKafka` senza `kraft`; fat jar con classifier `boot` (i test trovano la @SpringBootConfiguration); Flyway usa il DataSource dell'app. Fix Vercel: placeholder statico `web/playground/` per sbloccare il progetto `loyalty-hub-playground` (il mio token è 403 sul team `poc-22b1`). |
| 2026-09-19 | M0.6 | ✅ `pnpm lint typecheck test build` verdi (9 test) | `ffba0df` | — | M0.7 CI (`.github/workflows/ci.yml`: backend, web, seed, contracts) + `scripts/check-seed.mjs` (scheletro) + `seed/_schemas/` (docs/11 §10). Nota web: Node 22 + pnpm 10; `next/font` scarica i font Google in build (raggiungibile); `formatPoints` usa `useGrouping:"always"` (l'italiano non raggruppa i 4 cifre di default). Rimosso `package-lock.json` spurio dell'hook di sessione (il PoC usa pnpm). |
| 2026-09-19 | M0.7 | ✅ script verdi in locale (`check-seed`, `check-contracts`); YAML CI valido | `80dad2f` | Q-41 → DECISA | **M0 completata.** M1.1 (docs/12): ingestion completo per M1 — fonti, tipi azione con JSON Schema, validazione, risoluzione membro da `member_index`, esiti, seed. La CI è ora l'arbitro: alla prima esecuzione su GitHub va verificato che i job (soprattutto `backend` su Temurin 25 e `web`) siano verdi. |
| 2026-09-19 | M1.1 | ✅ `./mvnw -pl services/ingestion-service -am verify` verde (11 IT pipeline); `check-seed` verde (4 file) | | — | **Pipeline di accettazione completa** (docs/servizi/ingestion-service.md §5): registri `source`/`event_type`/`member_index`/`internal_mapping` (V2), validazione JSON Schema 2020-12 (`JsonSchemaValidator`, networknt su stringhe → runtime resta Jackson 3), 8 esiti, `DemoSeeder` col profilo `demo` che carica i 4 seed. Seed ingestion sul classpath via `<resource>` del pom. `F-ING-09` (monitor `/v1/inbound-events` + BO-03) e le CRUD di `sources`/`event-types` restano ai backoffice (M1.6+). **Deploy**: l'owner ha collegato **Koyeb** a GitHub → candidato per i microservizi JVM in M1.8 (free tier con Docker/JVM, a differenza di Vercel che ospita solo il frontend). Prossima fetta **M1.2** (member-service). |
| 2026-09-19 | M1.2 | ✅ `./mvnw verify` (reattore intero) verde: 7 IT member + 11 IT ingestion + IT/unit lh-common; `check-seed` verde | | — | **member-service** (docs/servizi/member-service.md): anagrafica `member` + `member_projection` + `member_stats` (V1), CRUD (create con `referralCode` 8, PATCH con lock ottimistico `version`, cambio stato), ricerca `q`/`status`/`tier`, `GET /v1/demo/personas`. Produce i 3 fatti su `lh.facts.v1` con snapshot completo; consuma `lh.actions.v1`→`member_stats` (azioni interne escluse da ultima attività/acquisti) e `lh.facts.v1` `wallet.points.earned`/`tier.upgraded`→`member_projection` (nessun loop: i `member.*` non hanno handler). `MemberSeeder` (profilo demo, codici invito riproducibili) dai 12 membri di `seed/members.json` (arricchito con `story`/`avatarSeed`/`referredBy`). **Fix M1.1**: aggiunto `COPY seed/` al Dockerfile ingestion (la `<resource>` seed non era nel contesto di build). Referral/segmenti/attributi/anonimizzazione → M5/M6/M7. Prossima fetta **M1.3** (campaign-service). |
| 2026-09-19 | M1.7 | ✅ `./mvnw verify` (reattore intero) verde (12 IT ingestion incl. reset); `bash -n` script E2E OK | | — | **Collante demo.** Scoperto e corretto: `POST /v1/demo/reset` era **404** perché `DemoResetController` vive in `io.loyaltyhub.common`, fuori dal component-scan dei servizi (`io.loyaltyhub.<servizio>`); ora è un `@Bean @Profile("demo")` in `LhCommonAutoConfiguration`, quindi realmente esposto nei 4 servizi (idempotente, `@RequiresRole(ADMIN)`). `scripts/wake.sh` (risveglio serverless con health-poll, timeout configurabile) e `scripts/smoke.sh` (E2E: `POST /v1/events` acquisto 130 € → saldo wallet aumentato entro 15 s; niente jq, usa node). Job CI `e2e` valida ora anche la sintassi degli script; il run completo (`compose up` + wake + smoke) resta al deploy M1.8 (serve un runner con Docker Hub). Prossima **M1.8**: primo deploy end-to-end (Aiven Kafka, Neon, Render, Vercel) — richiede segreti/provisioning dell'owner. |
| 2026-09-19 | M1.6 | ✅ `pnpm lint typecheck test build` verdi (13 test, 3 route PT); `./mvnw -pl services/wallet-service verify` verde | | — | **Portale membri (docs/09)** — mobile-first «Club Aurora». Shell con tab bar (Home/Guadagna/Attività, solo voci M1), membro attivo dal cookie `lh_persona`, cambio persona via `POST /api/persona`. **PT-01** (tessera `MemberCard` col materiale del tier, saldo, avanzamento livello, ultimi 3 movimenti, azioni rapide), **PT-02** (campagne leggibili con riepilogo premio, da `campaign /v1/portal/campaigns`), **PT-07** (movimenti per giorno, filtro valuta, scomposizione "130 base × 1,25 livello SILVER = 162"), **PT-14** tray demo (cambio membro dai 12 personas + invio azioni reali via `ingestion /v1/demo/simulator/fire`). "Il saldo non mente": schema **in elaborazione a polling** (`PendingContext`, 5 s finché arriva il fatto wallet; SSE → M2). Linguaggio del portale ("punti"/"punti status", niente codici). **Backend**: wallet `GET /v1/portal/wallets/{id}/activity` (movimenti leggibili + breakdown dai metadata). Premi/Gioca/Io e contenuti/notifiche → M3/M4/M5/M6. Prossima **M1.7** (`/v1/demo/reset` end-to-end, `scripts/smoke.sh`, `wake.sh`). |
| 2026-09-19 | M1.5 | ✅ `pnpm lint typecheck test build` verdi (13 test, 8 route BO); `./mvnw -pl services/ingestion-service verify` verde | | — | **Backoffice (docs/08)** — primo lavoro sul frontend che consuma le API via proxy. Fondamenta: `lib/nav.ts` (voci a milestone, mai pagine "in arrivo"), `lib/persona/permissions.ts` `can(role,capability)`, `lib/api/client.ts` (`useLhQuery`/`useLhMutation`, distingue 503 SERVICE_ASLEEP), `QueryState` (loading/empty/error/degraded), primitive condivise, `describeCampaign()` (frase generata, 4 test), `LifecycleBar`, `SimulationPanel`. Schermate BO-02/03/05/06/09/26/28/30. **Backend**: aggiunti a ingestion i GET di lettura (`/v1/event-types`, `/v1/sources`, `/v1/inbound-events`) e `POST /v1/demo/simulator/fire` (F-DEMO-03) — servivano a BO-09/26/28. Reset orchestrato in BO-30 via `POST /v1/demo/reset` per servizio. **Semplificazioni PoC** (oneste): editor campagna esistente in sola lettura (le regole si cambiano duplicando, backend PUT in fetta successiva); condizioni/effetti in «Nuova campagna» via textarea JSON invece di ConditionBuilder/EffectEditor drag-drop; niente ricerca ⌘K, rail eventi live (SSE → M2), foglio laterale. Prossima **M1.6** (portale membri PT-01/02/07/14). |
| 2026-09-19 | M1.4 | ✅ `./mvnw verify` (reattore intero) verde: 5 IT wallet + 5 unit + 8 IT campaign + 7 IT member + 11 IT ingestion + lh-common; `check-seed` verde (9 file) | | — | **wallet-service** (docs/servizi/wallet-service.md): applica `points.grant` da `lh.effects.v1`, moltiplicatore di tier per il PTS (`floor(amount × tier.multiplier)`, letto da `tier`/`member_tier`), ledger `EARN`, fatto `wallet.points.earned`; idempotenza su `effect_id`; wallet + `member_tier` BASE al `member.registered` o "on the fly"; API saldi/movimenti/tier + portale. **Calcolo canonico chiuso end-to-end**: SILVER + PTS 130 (feriale) → EARN 162; weekend PTS 260 → 325. Modifica campaign: effetti emessi con `childSameBusinessTime` così `occurred_at` = data di business dell'azione (docs/05 §2). Schema completo (points_lot/tier_history/edition create) → nessuna migrazione distruttiva in M3. Seed `tiers`/`currencies`/`editions`/`wallets`. Lotti/scadenze/salita/edizioni → M3; saga di spesa/rettifiche → M4. Prossima **M1.5** (schermate backoffice BO-02/03/05/06/09 + reset orchestrato). |
| 2026-09-19 | M1.3 | ✅ `./mvnw verify` (reattore intero) verde: 5 unit motore + 8 IT campaign + 7 IT member + 11 IT ingestion + lh-common; `check-seed` verde (5 file) | | — | **campaign-service** = motore regole (docs/03 §3.5): `CampaignEngine` puro testabile senza Spring; condizioni `data`/`member`/`context`(Europe/Rome)/`history`; effetti `GRANT_POINTS` (FIXED/PER_AMOUNT) + `MULTIPLIER` (il tier NON è applicato qui: `amount=floor(base×campaignMult)`, il wallet applica il moltiplicatore di livello a valle); limiti per membro (`campaign_counter` per periodo) + budget; `effectId=sha256(actionId+code+i)[:26]`; `evaluation_log` + fatto `campaign.evaluated`; effetti su `lh.effects.v1`. `member_snapshot` da `lh.facts.v1` (member.*/tier.*), totali da `wallet.points.earned`. API: elenco+totali, CRUD, transizioni (approvazione off→`DRAFT→LIVE`), `validate`, `simulate` (no scritture, `campaignIds` include bozze), portale. Seed 20 campagne; quelle con effetti non ancora supportati (GRANT_PLAYS/ISSUE_COUPON/SEND_MESSAGE/LOOKUP/FROM_FIELD) restano caricate ma scartate con `EFFECT_NOT_SUPPORTED_YET`. Calcolo canonico verificato: feriale 130 PTS + 130 STS, weekend 260 PTS (×2). Statistiche giornaliere→M2, esclusività→M3, segmenti→M6, approvazione→M7. Prossima **M1.4** (wallet-service). |
