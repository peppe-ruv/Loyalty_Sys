# 14 — Stato di avanzamento

Checklist **viva**: la aggiorna chi chiude una fetta (persona o agente), nello stesso commit del codice. È la prima cosa da leggere a inizio sessione insieme a `CLAUDE.md`.

**Come si usa** — `[ ]` da fare · `[~]` in corso (aggiungere data e nota) · `[x]` fatto (aggiungere l'hash breve del commit). Una feature si spunta solo se rispetta la *Definizione di fatto* (`CLAUDE.md §6`). Le feature che attraversano più milestone (es. `M1→M6`) si spuntano alla **prima** milestone e si annotano gli arricchimenti successivi nel registro in fondo.

## Quadro

| Milestone | Stato | Inizio | Fine | Demo online aggiornata | Note |
|---|---|---|---|---|---|
| M0 — Fondamenta | ✅ completata | 2026-09-19 | 2026-09-19 | ☐ | M0.1→M0.7 chiuse; CI in piedi |
| M1 — Core loop e primo deploy | ✅ completata | 2026-09-19 | 2026-09-19 | ☐ | M1.1→M1.8 chiuse; demo ospitata online |
| M2 — Visibilità | completata | M2.8 | | ☑ | |
| M3 — Punti adulti | chiusa (codice) | 2026-09-21 | 2026-09-23 | ☑ | M3.1–M3.9 implementate; criteri di accettazione verdi con test automatici; resta `smoke.sh` sulla demo online |
| M4 — Premi | chiusa (codice) | 2026-09-23 | 2026-09-24 | ☐ | M4.1–M4.6 implementate; criteri di accettazione verdi con test automatici (E2E portale su API simulate); resta `smoke.sh` sulla demo online |
| M5 — Gioco | da iniziare | | | ☐ | |
| M6 — Contenuti | da iniziare | | | ☐ | |
| M7 — Governance | da iniziare | | | ☐ | |

**Prossima fetta da lavorare:** M5 (gioco: instant win, obiettivi, badge, classifiche, referral); resta `smoke.sh` sulla demo online per M3–M4

**Ambiente demo** (ADR-023 + ADR-024: deployable consolidato `hub` senza broker)

| Risorsa | Stato | Riferimento (URL/ID, mai segreti) |
|---|---|---|
| Repository GitHub | ✅ | branch `claude/istruzioni-dwhe86` |
| Kafka locale (compose) | ✅ | `deploy/docker-compose.yml` (KRaft); topic dal profilo `local` |
| Hub online (Render, free) | ✅ | `srv-daneakoae00c73eg7j20` — https://loyalty-hub-6dc3.onrender.com (Docker `deploy/hub/Dockerfile`, Frankfurt). Include **insight** (event store + SSE) da M2.2. **Profilo `demo` (broker reale, ADR-025)**: consuma i 5 topic da **Aiven Kafka** (SSL client cert). Fallback a costo zero `demo,inproc` (bus in-process) sempre disponibile cambiando `SPRING_PROFILES_ACTIVE` |
| Postgres Neon | ✅ | progetto `damp-leaf-89930909` (`loyalty-hub`, eu-central-1, PG 18); DB `neondb`, schemi `ingestion/member/campaign/wallet/insight/reward` migrati e seminati all'avvio. **Nota**: il progetto Neon precedente (`odd-pine-62283646`) è stato ricreato il 2026-09-20; le credenziali dell'hub su Render (`DB_URL/DB_USERNAME/DB_PASSWORD`) puntano al nuovo endpoint pooler `ep-late-smoke-b11gfp2h-pooler`. Il DB nuovo nasce vuoto: hub esegue migrazioni + seed al boot (profilo `demo`) |
| Broker Kafka gestito | ✅ | **Aiven Kafka** `kafka-3df3ed2e-…aivencloud.com:19612` (cluster multi-broker, rack ams3), 5 topic pre-creati, auth **SSL client cert** (ADR-025). L'hub in profilo `demo` consuma/produce sul broker reale; verificato live (consumer group per servizio, tutti i topic assegnati). Non è free tier: deroga consapevole a "costo zero", reversibile col profilo `demo,inproc` |
| Frontend Vercel | ✅ | progetto `loyalty-hub-web` (`prj_pO7cj7Q6iMKbcSXFPakB8WhilUUb`, team `poc-22b1`, Next.js, root `web/`, branch `claude/istruzioni-dwhe86`) — **https://loyalty-hub-web.vercel.app**. Env: `LH_SVC_{INGESTION,MEMBER,CAMPAIGN,WALLET,INSIGHT,REWARD}_URL` + `NEXT_PUBLIC_LH_INSIGHT_URL` all'hub Render (SSE diretto per BO-24); deployment protection off (demo pubblica). NB: `loyalty-hub-playground` è un'altra app (Payload CMS), lasciata intatta |

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
- [x] `M2.3` — **tracciati (BO-25) + pending via SSE**. Backend insight: `TraceService` ricostruisce l'albero di un giro dall'event store (nodi per `causationId`, servizio dedotto dal `source`, `offsetMs`, sintesi per tipo), stato COMPLETE/IN_PROGRESS/FAILED (5s di quiete; FAILED se c'è DLQ) ed esito (punti per valuta, cambio tier, DLQ). API `GET /v1/traces/{correlationId}` + `GET /v1/traces?memberId=&from=&to=`. Frontend: **BO-25** `/observe/traces` (elenco + `TraceWaterfall` a corsie per servizio con nodi per offset ed esito), voce nav. **usePendingTrace**: l'attesa "punti in arrivo" del portale ora ascolta l'SSE filtrato per `correlationId` e si chiude al fatto `wallet.points.earned` (il tray cattura il `correlationId` dalla risposta `/v1/events`); polling del wallet come fallback. IT: catena azione→effetto→fatto → albero a 3 nodi, radice = azione, esito +162 PTS. `./mvnw verify` + `pnpm lint typecheck test build` verdi
- [x] `M2.4` — **`metric_daily` + storico sintetico + dashboard BO-01**. Backend insight: migrazione `V2__insight_metrics.sql` (`metric_daily` (day,metric,dimension,dim_value) PK, `value numeric`, `synthetic bool`); `MetricRepository` (UPSERT incrementale `increment`, `putSynthetic`, `total`, `latestValue` (gauge), `timeseries` con `bool_or(synthetic)`, `breakdown` top-N); l'ingest aggiorna le metriche solo su evento nuovo (idempotenza ⇒ niente doppio conteggio): `actions` (tot+`source`), `points_earned` (tot+`currency`), `members_new`, `tier_changes`, `dlq`. `InsightSyntheticSeeder` (`@Profile("demo")`, `seed/insight-synthetic.json`): 90 giorni con seme fisso — trend +0,4%/g, +35% sab/dom su `points_earned`, picco "estivo" al giorno −30 (gaussiana), rumore ±12%; possiede interamente `metric_daily` (azzera+rigenera, ordine di reset irrilevante). `KpiService`/`KpiController`: `GET /v1/kpi/overview` (con delta sul periodo precedente), `/v1/kpi/timeseries` (day/week), `/v1/kpi/breakdown`. Frontend: **BO-01** `/backoffice` (selettore 7/30/90g, 6 `KpiTile` con delta+sparkline, area impilata punti emessi/spesi/scaduti con storico sintetico ombreggiato, barre azioni per fonte con palette validata, "Da guardare") — grafici inline-SVG con hover + tabella alternativa (RNF-08); helper `lib/charts/scale.ts` (unit test); voce nav Panoramica. IT: storico riempie 90 giorni `synthetic=true`, overview con baseline+delta, breakdown per fonte. `./mvnw -pl services/insight-service -am verify` verde (7 IT); `pnpm lint typecheck test build` verdi. Distribuzione tier/passività/top campagne-premi (righe 3-4 di BO-01) → M3/M4
- [x] `M2.5` — **audit end-to-end (`lh.audit.v1`) + BO-22**. `AuditPublisher` di lh-common (già presente) ora usato dai servizi con scritture da backoffice: **member** (create→CREATE, update→UPDATE coi soli campi cambiati, cambio stato→TRANSITION) e **campaign** (create→CREATE, transizioni→TRANSITION), ciascuno nella stessa transazione della scrittura, con `lhactor` dall'attore corrente. insight: `V3__insight_audit.sql` (`audit_entry` con `event_id` UQ, attore, prima/dopo jsonb, correlation); l'ingest, sul topic audit, estrae la voce e la registra idempotente su `event_id`; `AuditRepository` (ricerca con filtri attore/ruolo/servizio/tipo/oggetto/azione/periodo + dettaglio); API `GET /v1/audit` + `GET /v1/audit/{id}`; retention 180 giorni; reset svuota `audit_entry`. Frontend: **BO-22** `/backoffice/governance/audit` (tabella quando·attore·servizio·azione·oggetto, filtri, dettaglio con `DiffView` campo per campo + JSON grezzo, link al tracciato via `?c=`; RESET/JOB marcati), voce nav Governance. IT insight: voce audit end-to-end con before/after e idempotenza. `./mvnw verify` + `pnpm lint typecheck test build` verdi. Gli altri servizi (wallet/ingestion) emetteranno audit quando avranno scritture da backoffice (rettifiche M4, CRUD fonti M6).
- [x] `M2.6` — **statistiche campagna (F-CMP-10) in BO-05/BO-06**. Backend campaign: `GET /v1/campaigns/{id}/stats` → totali (attivazioni, membri unici, punti decisi, punti erogati), **budget residuo** (da `limits.global.maxPoints/maxMatches` − consumato) e **serie giornaliera 30 giorni** ricavata da `evaluation_log` (parsing jsonb dei `results` per `campaignCode` matched: attivazioni + somma `amount` effetti). `CounterRepository.uniqueMembers` (distinct da `campaign_counter`); `EvaluationLogRepository.dailyForCampaign`. `CampaignSummary` arricchito col `budget` per l'elenco. Frontend: **BO-05** con colonne *Membri unici* e barra *Budget*; pagina campagna (**BO-06**) con pannello **Statistiche** (stat tile + budget bar + due grafici a barre 30g inline-SVG con hover e tabella alternativa, `DailyBars`). IT campaign: un acquisto attiva `CMP-PURCHASE-BASE` → `/stats` con `matches≥1`, `uniqueMembers≥1`, `pointsDecided>0`, serie giornaliera non vuota. `./mvnw verify` + `pnpm lint typecheck test build` verdi.
- [x] `M2.7` — **scenari demo (F-DEMO-04) + BO-29**. Backend ingestion: `V3` tabelle `scenario` + `scenario_run` (con `results jsonb` per l'esito passo-passo con `correlationId`); `seed/scenarios.json` (3 scenari: weekend positivo, giornata mista con UNMATCHED, ingressi REJECTED); `IngestionService.ingest` con **origine esplicita** (overload, `SIMULATOR` per simulatore e scenari); `ScenarioService` esecutore **asincrono** (thread dedicato, ritardi max 10 s/passo, un passo può dichiarare `expect` REJECTED/DUPLICATE/UNMATCHED, opzionale `eventId` per i duplicati); API `GET /v1/demo/scenarios`, `POST /v1/demo/scenarios/{code}/run` (202 `{runId}`, `demo.simulate`), `GET /v1/demo/scenario-runs/{runId}`; `DemoSeeder` carica gli scenari e il reset svuota le esecuzioni. Frontend: **BO-29** `/backoffice/demo/scenarios` (schede scenario + vista esecuzione passo-passo con stato in attesa/in corso/elaborato, badge esito, `TraceLink` per passo, suggerimento "apri il portale come…"), voce nav Demo. IT ingestion: esecuzione di `SCN-MIXED-DAY` → 2 ACCEPTED + 1 UNMATCHED atteso, `correlationId` presenti. `./mvnw verify` + `pnpm lint typecheck test build` verdi; `check-seed` verde (11 file).
- [x] `M2.8` — **chiusura M2: deploy insight + CORS SSE, robustezza stream**. insight è servito dall'**hub consolidato** (ADR-023/024): un solo deployable Render, schema `insight` incluso nel `search_path`, CORS del solo `/v1/stream/**` da `LH_CORS_ALLOWED_ORIGINS` (default `*`, valido perché `allowCredentials=false`: stream pubblico in sola lettura, ADR-020). Verificato il cablaggio live: Vercel `NEXT_PUBLIC_LH_INSIGHT_URL` → hub Render; migrazioni insight (V1–V3) applicate su Neon; storico sintetico rigenerato all'avvio. **Fix robustezza SSE**: alla disconnessione del client il container sollevava `AsyncRequestNotUsableException` e il `GlobalExceptionHandler` provava a scrivere un `problem+json` su una risposta `text/event-stream` (errore `HttpMessageNotWritableException`, rumore a livello ERROR); aggiunto un handler dedicato che tratta la disconnessione come evento normale (nessun corpo, log a DEBUG). `./mvnw verify` verde. **M2 completa**: flusso live (BO-24), tracciati (BO-25), dashboard KPI + storico (BO-01), audit (BO-22), statistiche campagna (BO-05/06), scenari (BO-29).

**Feature** (`docs/02`)

- [x] `F-CMP-10` Statistiche campagna (P1) — _M2.6: `GET /v1/campaigns/{id}/stats` (totali, budget residuo, serie 30g da evaluation_log) + BO-05 (membri unici, budget) + BO-06 pannello Statistiche_
- [x] `F-AUD-01` Audit log (P0) — _M2.5: `AuditPublisher` in member/campaign → `lh.audit.v1`; insight `audit_entry` + `GET /v1/audit(/{id})` + BO-22 con DiffView_
- [x] `F-INS-01` Flusso eventi live (P0) — _M2.2: SSE `/v1/stream/events` + rail BO-24 con fallback a polling_
- [x] `F-INS-02` Tracciato (P0) — _M2.3: `/v1/traces/{correlationId}` (albero + esito) + BO-25 waterfall; il payload per nodo e il riquadro "Perché" per campagna arrivano con M2.6_
- [x] `F-INS-03` KPI e serie storiche (P0) — _M2.4: `metric_daily` + `/v1/kpi/overview|timeseries|breakdown` + BO-01 (tiles, area punti, barre per fonte)_
- [x] `F-INS-04` Storico sintetico (P0) — _M2.4: `InsightSyntheticSeeder` 90 giorni con seme (`seed/insight-synthetic.json`), `synthetic=true`, ombreggiato in BO-01_
- [x] `F-INS-06` Stato pipeline (P1) — _M2.1: `GET /v1/pipeline/status` da `topic_stat` (ultimo evento, conteggio, offset per partizione); ritardo stimato/ultimo fatto per servizio → M2.4_
- [x] `F-DEMO-04` Scenari guidati (P0) — _M2.7: `scenario`/`scenario_run` + `ScenarioService` (esecutore async, origine SIMULATOR) + API `/v1/demo/scenarios(/{code}/run)`, `/scenario-runs/{id}` + BO-29_

**Accettazione M2** (`docs/12`)

- [ ] criteri di accettazione verdi
- [ ] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [ ] stati *loading / empty / error / degraded* sulle schermate toccate
- [ ] demo online aggiornata e `smoke.sh` verde

## M3 — Punti adulti

**Fette** (`docs/12 §3`)

- [x] `M3.1` — _2026-09-21: lotti su ogni accredito, scadenza `ROLLING_MONTHS` (fine mese +n, Europe/Rome), `pendingDays` → lotto `PENDING` e rilascio (`RELEASE` + `wallet.points.released`), `GET /v1/wallets/{id}/lots`, `expiringSoon` nella vista wallet, seed lotti (Σ attivi = saldo). Job schedulato/endpoint demo `asOf` e BO-30 → M3.2_
- [x] `M3.3` — _2026-09-21: salita immediata di livello dopo accredito/rilascio STS (member_tier + tier_history UPGRADE + fatto `tier.upgraded` EVT-FACT-28, nello stesso commit), moltiplicatore da tabella; `GET /v1/tiers/distribution`, `PUT /v1/tiers/{code}` (monotonìa `TIER_THRESHOLDS_NOT_MONOTONIC` + BASE=0 + audit, ADMIN), `GET /v1/members/{id}/tier-history`; BO-07 Livelli (scala+modifica+discesa morbida), PT-08 carta livello + tab "Io"_
- [x] `M3.2` — _2026-09-21: job wallet scadenze/preavvisi/rilascio con `asOf` (`EXPIRE`+`wallet.points.expired`, `wallet.points.expiring` una volta per lotto via flag `warned`, rilascio pending), `WalletJobs` schedulati gated da `loyaltyhub.jobs.enabled`, endpoint demo `POST /v1/demo/jobs/{expire-points,expiry-warnings,release-pending}?asOf=` (ADMIN), BO-30 "Macchina del tempo"_
- [x] `M3.4` — _2026-09-23: backend (PR #7 + fix review f899b7f: chiusura in un'unica transazione con lock sulla riga edizione e `FOR UPDATE` sui `member_tier`, policy `END_OF_EDITION_PLUS_GRACE` attiva sui nuovi lotti, Q-47/Q-48) e BO-08 (PR #9): schede Valute (policy + esempio di scadenza) ed Edizioni (linea del tempo, anteprima chiusura con filtro per esito, applicazione ADMIN con digitazione del codice). Scheda `liability` → M3.9_
- [x] `M3.5` — _2026-09-23: backend su main: `FactsListener` su `lh.facts.v1` → `FactsHandler` (indice membri da `member.*`; ponte per i 9 fatti di docs/05 §7 con mappatura abilitata → azione `source=internal`, stessa correlazione, `lhhop+1`; oltre 3 → DLQ `LOOP_GUARD`), `GET/PUT /v1/internal-mappings` (ADMIN, audit, famiglie vietate → 422). `InternalBridgeIT` 7 casi. BO-09 scheda *Ponte interno* (2c99326); resta la verifica live di `SCN-TIER-UP`_
- [x] `M3.6` — _2026-09-23 (PR #10): `POST /v1/wallets/{id}/adjustments` (CARE/ADMIN; motivi della scheda servizio, Q-45; solo PTS, Q-46; accredito = nuovo lotto, addebito tutto-o-niente con consumo FIFO + `lot_consumption`), fatto `wallet.points.adjusted` (EVT-FACT-26) e audit `ADJUST`. Dialogo *Rettifica punti* in BO-03 (2c99326)_
- [x] `M3.7` — _2026-09-23 (PR #8): modi `GRANT_POINTS` `FROM_FIELD` (intero dal campo; decimali scartati, Q-44) e `LOOKUP` (tabella per valore del campo; chiave assente → scartato); priorità, gruppo esclusivo, `MULTIPLIER` e `history.*` coperti da test; `CMP-TIER-UP-BONUS` GOLD → 500 PTS_
- [x] `M3.8` — _2026-09-23: `POST /v1/transactions` → `purchase.completed` con `id = txn-<orderId>` attraverso la stessa pipeline di `/v1/events` (fonte, tipo, schema, finestra, dedup, membro); reso con `kind=RETURN` → `purchase.returned` (Q-49)_
- [x] `M3.9` — _2026-09-23: `GET /v1/liability?currency=` → `{currency, outstanding, pending, byExpiryMonth[{month, amount}], asOf}` (saldi dei wallet + lotti attivi per mese di scadenza Europe/Rome, stesso snapshot `REPEATABLE_READ`: Σ mesi = in circolazione); grafico a colonne 12 mesi + "oltre"/"mai" (teal validato dataviz, tooltip, tabella) in BO-08 scheda *Passività* e in BO-01 riga 3_

**Feature** (`docs/02`)

- [x] `F-ING-07` Transazioni d'acquisto (P1) — _M3.8: `POST /v1/transactions`; reso via `kind=RETURN` (Q-49)_
- [x] `F-ING-08` Ponte azioni interne (P0) — _M3.5: backend con anti-loop + API mappature + BO-09 *Ponte interno*_
- [x] `F-CMP-07` Cumulabilità (P0) — _M3.7: priorità + `exclusiveGroup` (vince la prima) + `MULTIPLIER` sulle altre campagne; modi `LOOKUP`/`FROM_FIELD`_
- [x] `F-WAL-03` Lotti e scadenza (P0) — _M3.1: lotto per ogni `EARN` con `expires_at` da policy `ROLLING_MONTHS`; consumo FIFO → M4_
- [x] `F-WAL-05` Punti in attesa (P1) — _M3.1: `pendingDays` → lotto `PENDING`, `balance_pending`, rilascio ad `available_at`; job schedulato → M3.2_
- [x] `F-WAL-06` Scadenza (P0) — _M3.2: job scadenza (`asOf`) azzera i lotti scaduti, movimento `EXPIRE` + `wallet.points.expired`; preavvisi `wallet.points.expiring` una volta per lotto_
- [x] `F-WAL-07` Rettifiche manuali (P0) — _M3.6: API + fatto + audit + dialogo BO-03_
- [x] `F-WAL-09` Passività (P1) — _M3.9: API + BO-08 + BO-01_
- [x] `F-TIER-01` Definizione livelli (P0) — _M3.3: `PUT /v1/tiers/{code}` (nome, soglia, moltiplicatore, vantaggi, colore) con monotonìa e BASE=0; BO-07_
- [x] `F-TIER-02` Salita immediata (P0) — _M3.3: dopo ogni accredito/rilascio STS, al più alto livello raggiunto, `tier.upgraded` nello stesso commit_
- [x] `F-TIER-03` Moltiplicatore di livello (P0) — _M1.4 forma minima: letto da `tier`/`member_tier` da seed; definizione/salita/edizioni → M3_
- [x] `F-TIER-04` Chiusura edizione con discesa morbida (P0) — _M3.4: API atomica e serializzata + BO-08_
- [x] `F-TIER-05` Anteprima chiusura (P0) — _M3.4: `dryRun=true` + tabella con filtro in BO-08_
- [x] `F-TIER-06` Storico livelli (P1) — _M3.3: tabella `tier_history` (INITIAL/UPGRADE) + `GET /v1/members/{id}/tier-history`_
- [x] `F-DEMO-06` Job su richiesta (P0) — _M3.2: endpoint demo `POST /v1/demo/jobs/*?asOf=` (ADMIN) e BO-30 "Macchina del tempo" per lanciare i job del wallet; altri servizi nelle rispettive fette_

**Accettazione M3** (`docs/12`)

- [x] criteri di accettazione verdi — _2026-09-23, verificati con test automatici (la sandbox non raggiunge la demo online):_
  - _`SCN-TIER-UP` → `HubEndToEndIT` sull'hub consolidato: Giulia +162 +500 PTS, GOLD, azione `tier.upgraded` interna nello stesso tracciato (una sola radice), esito SILVER → GOLD_
  - _catena con `lhhop` > 3 → DLQ `LOOP_GUARD` → `InternalBridgeIT`_
  - _job scadenze `asOf` +31 giorni → Chiara −1 900 PTS con movimento `EXPIRE` → `HubEndToEndIT` (seed allineato a docs/10: `expiringSoon` 1 900)_
  - _chiusura edizione `dryRun` (Stefano → SILVER) → `EditionCloseDryRunIT`, `EditionCloseRuleTest`_
  - _gruppo esclusivo → `CampaignEngineTest` (motivo `EXCLUSIVE`, non `EXCLUSIVE_GROUP`: Q-50)_
  - _campagna `LIVE`, campi bloccati → `409 CAMPAIGN_LIVE_LOCKED` → `CampaignServiceIT` (API `PUT` aggiunta ora; lato UI la modifica di BO-06 è in M6)_
- [x] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [x] stati *loading / empty / error / degraded* sulle schermate toccate (BO-03, BO-07, BO-08, BO-09, BO-01 passività, PT-08 via `QueryState`)
- [ ] demo online aggiornata e `smoke.sh` verde — _deploy Render/Vercel automatici da `main` e `live`; `smoke.sh` da lanciare da una macchina che raggiunge la demo_

## M4 — Premi

**Fette** (`docs/12 §3`)

- [x] `M4.1` — _reward-service nell'hub (schema `reward`, catalogo/fasce/categorie, ciclo di vita, catalogo portale, `/v1/rewards/stats`) + BO-10 e BO-11_
- [x] `M4.2` — _pool coupon con seme stabile, generazione ≤ 5 000 e import, cassa simulata (uso/annullo/scadenza), effetto `coupon.issue` idempotente, BO-12_
- [x] `M4.3` — _saga reward ↔ wallet: richiesta 202 con stock atomico e validazioni 422, spesa FIFO, conferma ed evasione (coupon/immediata/manuale), rifiuto, timeout 10 min, compensazione della spesa tardiva, rimborso_
- [x] `M4.4` — _evasione manuale, annullo con rimborso (coupon `VOID`, stock +1), `retry-fulfilment`, filtri per schede, seed `redemptions.json` (+ spese storiche nel wallet), BO-13 con contatore in sidebar, job reward in BO-30_
- [x] `M4.5` — _PT-03 catalogo a fasce (raggiunta / ti mancano, filtri), PT-04 richiesta con saldo prima → dopo, spedizione, attesa ed esito (codice grande), PT-13 richieste e coupon; tab «Premi»; avviso scadenza in home_
- [x] `M4.6` — _ponte `reward.redemption.confirmed` → azione interna `reward.redeemed` (mappatura già nel seed, ora provata nell'hub: `lhhop 1`, stesso albero), contratto `action.reward.redeemed` (EVT-ACT-28)_

**Feature** (`docs/02`)

- [x] `F-WAL-04` Spesa FIFO (P0) — _M4.3: lotti per scadenza poi anzianità, `lot_consumption`; la rettifica in addebito usa lo stesso consumo_
- [x] `F-WAL-08` Saga di spesa (P0) — _M4.3: `wallet.points.spent` / `wallet.spend.rejected` idempotenti su `redemption_id`, rimborso con `wallet.points.refunded` (Q-54)_
- [x] `F-RWD-01` Catalogo premi (P0) — _M4.1: API + BO-10 (griglia/tabella, editor); PT-03 in M4.5_
- [x] `F-RWD-02` Fasce premi (P0) — _M4.1: soglie uniche e crescenti, `BAND_IN_USE`, BO-11 con impatto sui premi LIVE_
- [x] `F-RWD-03` Disponibilità (P0) — _M4.1 stock/limite/`stockState`; M4.3 prenotazione atomica (`UPDATE … WHERE stock_remaining > 0`), limite per membro sotto lock, ripristino su rifiuto/timeout/annullo_
- [x] `F-RWD-04` Visibilità (P0 tier · P1 segmenti) — _M4.1: tier (lucchetto) e segmenti (esclusione) sul catalogo portale da snapshot dei fatti; `AudiencePicker` in M6_
- [x] `F-RWD-05` Richiesta premio (P0) — _M4.3 backend (saga, timeout, compensazione); M4.4 BO-13; M4.5 PT-04 (conferma, attesa, esito, timeout 20 s → «I miei premi») e PT-13 (annullo in conferma)_
- [x] `F-RWD-06` Evasione (P0) — _M4.3 automatica (coupon, pool vuoto → `needsAttention`) e immediata; M4.4 manuale da BO-13 (nota + tracking, CARE/ADMIN) e nuovo tentativo dopo una generazione di codici_
- [x] `F-RWD-07` Annullamento con rimborso (P1) — _M4.4: da `CONFIRMED`, motivo obbligatorio, stock ripristinato, coupon `VOID`, rimborso nei lotti d'origine (Q-54); BO-13 mostra "in elaborazione" finché il wallet non rimborsa_
- [x] `F-RWD-08` Ciclo di vita premio (P0) — _M4.1: macchina a stati comune, blocco campi LIVE (`REWARD_LIVE_LOCKED`), duplica; approvazione in M7_
- [x] `F-CPN-01` Pool di coupon (P0) — _M4.2: `prefix-XXXX-XXXX` da seme (stessi codici a ogni reset), import con scartati, stato per codice, BO-12_
- [x] `F-CPN-02` Emissione (P0) — _M4.2 da effetto `coupon.issue` (idempotente su `effectId`, pool vuoto → DLQ `COUPON_POOL_EMPTY`); M4.3 da richiesta premio (un solo coupon per richiesta anche se la spesa è rielaborata). Emissione da concorso in M5_
- [x] `F-CPN-03` Utilizzo (P1) — _M4.2: `POST /v1/coupons/{code}/use` (409 già usato / 410 scaduto), fatto `coupon.used`, cassa simulata di BO-12_

**Accettazione M4** (`docs/12`)

- [x] criteri di accettazione verdi — _2026-09-24, verificati con test automatici (la sandbox non raggiunge la demo online):_
  - _reward-service §7.1 Davide `RWD-SHOP-10` → 202, `FULFILLED` con coupon entro 10 s, saldo −1 500, stock −1 → `HubEndToEndIT` (hub consolidato)_
  - _§7.2 Anna `RWD-COFFEE-5` → `REJECTED (INSUFFICIENT_BALANCE)`, stock invariato → `HubEndToEndIT`_
  - _§7.3 ultimo pezzo, due richieste concorrenti → una `PENDING`, l'altra `422 REWARD_SOLD_OUT` → `RedemptionIT`_
  - _§7.4 `RWD-WEEKEND` da SILVER → `422 TIER_NOT_ELIGIBLE`, nel catalogo con `lockedByTier` → `RedemptionIT`, `RewardServiceIT`_
  - _§7.5 annullo di una `CONFIRMED` → `wallet.points.refunded`, stock +1 → `HubEndToEndIT` (Sofia, Σ lotti = saldo); coupon `VOID` coperto dal codice ma non da un test (una `CONFIRMED` con coupon non nasce dal flusso normale)_
  - _§7.6 `use` due volte → seconda `409` → `CouponIT`_
  - _§7.7 rielaborazione di `wallet.points.spent` → nessun secondo coupon → `RedemptionIT`_
  - _spesa FIFO [500 ott, 800 dic, 900 mar] → 500 + 800 + 200, `lot_consumption` coerente, rimborso nei lotti d'origine → `WalletRedemptionIT`_
  - _E2E n. 2 di `docs/09 §4` (Davide, PT-03 → PT-04 `RWD-COFFEE-5` → codice → PT-13 → saldo −500) → Playwright su API simulate a stato, 7/7 passi (script fuori dal repo: la CI non esegue Playwright)_
  - _tracciato della saga in BO-25: richiesta → spesa → conferma → coupon → evasa + azione `reward.redeemed` dal ponte, una sola radice → `HubEndToEndIT`; il "messaggio" arriverà con engagement (M6)_
  - _wallet fermo → `PENDING`, poi `CONFIRMED` al suo ritorno → `RedemptionIT`; oltre 10 min → `REJECTED (TIMEOUT)` con stock ripristinato, e la spesa tardiva è compensata con rimborso → `RedemptionIT`_
- [x] `./mvnw verify` · `pnpm lint typecheck test` · `check-seed` verdi
- [x] stati *loading / empty / error / degraded* sulle schermate toccate (BO-10/11/12/13, PT-03/04/13 via `QueryState`)
- [ ] demo online aggiornata e `smoke.sh` verde — _deploy Render/Vercel automatici da `main` (env `LH_SVC_REWARD_URL` impostata); `smoke.sh` da lanciare da una macchina che raggiunge la demo_

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
| 2026-09-24 | M4.6 · accettazione M4 | ✅ `./mvnw verify` verde (`HubEndToEndIT` 8 con il ponte `reward.redeemed` nel tracciato e lo stock +1 all'annullo, `RedemptionIT` 10 con "wallet fermo → PENDING"), `check-seed` 16, `check-contracts` 31 | (questo commit) | — | Ponte già attivo dal seed di M3.5: ora provato end-to-end e con contratto. Il test d'accettazione ha trovato un'incoerenza nel seed: i residui di stock non tenevano conto delle richieste attive dello storico (la borraccia era 150/150 pur con due richieste attive, quindi all'annullo di Sofia lo stock non poteva risalire): residui allineati (es. `RWD-BORRACCIA` 148) e nuova regola in `check-seed`. |
| 2026-09-23 | M4.5 | ✅ `pnpm lint typecheck test build` verdi (42 test, nuovi su `lib/reward/portal`), `./mvnw verify` wallet verde, `check-seed` 16; percorso E2E n. 2 di docs/09 §4 eseguito con Playwright su API simulate a stato (Davide: PT-03 → PT-04 `RWD-COFFEE-5` → codice → PT-13 → saldo −500): 7/7 passi | (questo commit) | — | Portale: PT-03, PT-04, PT-13, tab «Premi» (attiva anche su «I miei premi»), collegamenti da home e profilo, avviso «N punti scadono il … — usali» in PT-01 ora che PT-03 esiste. Icone di categoria in `lib/reward/icons` (condivise, backoffice e portale non si importano). Wallet: titolo «Rettifica punti» anche per `ADJUST_CREDIT/DEBIT` nell'attività del portale. Banner `CATALOG_TOP` rinviato a M6 (engagement). Script E2E fuori dal repo (la CI non esegue ancora Playwright). |
| 2026-09-23 | M4.4 | ✅ `./mvnw verify` verde (`RedemptionIT` 10, `CouponIT` 5, `HubEndToEndIT` 8 con il rimborso di Sofia e Σ lotti = saldo), `pnpm lint typecheck test build` verdi (36 test), `check-seed` 16 (nuovi controlli su `redemptions.json`), `check-contracts` 30; BO-13 renderizzata e controllata (Playwright) | 7061712, (questo commit) | — | CI rossa su M4.3 per un test mio (`WalletRedemptionIT` leggeva il saldo prima che il wallet del membro nuovo esistesse): corretto in 7061712. Storico demo: 24 richieste (niente `PENDING`, che il timeout respingerebbe), coupon presi dai pool con seme, spese/rimborsi storici nel ledger del wallet legati a `redemption_id` così gli annulli da BO-13 rimborsano davvero; rimborso di spese senza consumi registrati → lotto nuovo. BO-30: aggiunti reward al reset orchestrato (mancava) e i job scadenza coupon / timeout richieste. |
| 2026-09-23 | M4.3 | ✅ `./mvnw verify` verde (`RedemptionIT` 6, `WalletRedemptionIT` 3, `HubEndToEndIT` 7 con Davide → coupon e Anna → rifiuto sul hub consolidato, tracciato della saga a radice unica), `check-contracts` 30, `check-seed` 15 | (questo commit) | Q-54, Q-55 | Env Vercel `LH_SVC_REWARD_URL` impostata (conferma dell'owner) e redeploy di produzione. Contratti degli 8 fatti della saga. Trovato e corretto: il seed del wallet non riportava lo stato dei membri (Roberto `BLOCKED` avrebbe potuto spendere). insight ora alimenta `points_spent` (netto dei rimborsi), `redemptions` e `points_expired` per BO-01. |
| 2026-09-23 | M4.2 | ✅ `./mvnw verify` verde (`CouponIT` 5, `RewardServiceIT` 5, hub), `pnpm lint typecheck test build` verdi (32 test), `check-contracts` 22, `check-seed` 15 (nuovo controllo premi ↔ fasce/categorie/pool); BO-12 renderizzata e controllata (Playwright), palette della barra per stato validata con dataviz | (questo commit) | Q-52, Q-53 | `seed/coupon-pools.json` (5 pool, SHP25 con 141 codici già usati → 9 disponibili come lo stock). V2: via i contatori `total/available` (si contano da `coupon`), `seed` per pool. lh-common: `NonRetryableEventException` (DLQ immediata con codice) e `LhException.gone` (410). Contratti nuovi: `effect.coupon.issue`, `fact.coupon.issued`, `fact.coupon.used`, `fact.reward.status.changed`. Editor premio: pool scelto da elenco. |
| 2026-09-23 | M4.1 | ✅ `./mvnw verify` verde (`RewardServiceIT` 5, `HubEndToEndIT` 5), `pnpm lint typecheck test build` verdi (29 test), `check-contracts` 18, `check-seed` 14; BO-10/BO-11 renderizzate e controllate (Playwright, dati del seed) | 3613556, (questo commit) | — | reward-service nell'hub: tabella snapshot rinominata `reward_member_snapshot` (collideva con `campaign.member_snapshot` nel search_path condiviso). Fasce modificabili da ADMIN e MARKETING (`object.edit`, docs/08 §2). `LifecycleBar` ora generica (servizio + percorso). Fix sidebar: evidenziata solo la voce più specifica. **Da fare fuori sessione**: env Vercel `LH_SVC_REWARD_URL` → hub Render, senza la quale BO-10/11 mostrano lo stato *degraded*. |
| 2026-09-23 | Accettazione M3 | ✅ `./mvnw verify` verde (HubEndToEndIT 4, CampaignServiceIT 11, InsightServiceIT 9, …), `check-contracts` 18, `check-seed` 11 | b3a44fe, (questo commit) | Q-50, Q-51 | Verifica dei criteri M3 con test sull'hub consolidato. Trovati e corretti: `PUT /v1/campaigns/{id}` mancante (F-CMP-01, blocco campi `LIVE`); `SCN-TIER-UP` assente dal seed (aggiunto, con segnaposto `at: @lastWeekdayT10:30` ora supportato dall'esecutore scenari); lotto in scadenza di Chiara 720 invece dei 1 900 di docs/10 (`expiringSoon` nel seed); esito del tracciato che leggeva `from/to` invece di `previousTier/newTier` (EVT-FACT-28) → cambio livello mai mostrato in BO-25; test E2E dell'hub su Giulia diventato instabile con il ponte attivo e con data fissa destinata a uscire dalla finestra dei 30 giorni. |
| 2026-09-23 | M3.5-FE · M3.6-FE · M3.9 | ✅ `./mvnw verify` verde (13 IT `WalletServiceIT` con passività), `pnpm lint typecheck test build` verdi (22 test), `check-contracts` 18, `check-seed` 11; grafico passività renderizzato e controllato (Playwright, dati fittizi) | 2c99326, (questo commit) | — | BO-03 *Rettifica punti* e BO-09 *Ponte interno*. M3.9: `/v1/liability` + colonne per mese di scadenza (dataviz: accento `#0b7a75` sotto la soglia di croma → teal `#0d9488` validato); per gli STS, che non scadono a lotto, frase al posto di un grafico vuoto. Deploy Render di M3.5/M3.8 `live`. |
| 2026-09-23 | M3.4 · M3.5-BE · M3.6-BE · M3.8 | ✅ `./mvnw verify` verde (locale + CI su PR #9/#10), `pnpm lint typecheck test build` verdi, `check-contracts` 18, `check-seed` 11 | f899b7f, c8acbd2, 4b8c8e6, 15e59d0 (#9), 7334607 (#10), 5fee9a0 | Q-45…Q-49 | Fix review Codex su PR #7 (chiusura atomica/serializzata, lock STS, scadenza di edizione). M3.5 ripresa da Claude dal lavoro di Jules: tipi gestiti fissi (il router li registra prima del seed), nomi brevi/lunghi normalizzati, un solo handler per `member.registered`, controller mancante. Font self-hosted (`next/font/local`): la build non dipende più da Google Fonts. Da qui in poi niente Jules. |
| 2026-09-23 | M3.4-BE · M3.7 | ✅ `./mvnw verify` verde in locale e CI (PR #7, #8); `check-contracts` 17 esempi; `check-seed` 11 file | 52dd9c0, 91c0d3e | Q-44 | Orchestrazione Claude → Jules (skill): M3.4-A, M3.5, M3.7 in parallelo su servizi diversi. PR #7 corretta in un ciclo (finalizzazione chiusura transazionale, RETAINED azzera solo `period_sts`, ADMIN su POST/PUT edizioni, 422 campi obbligatori). M3.7: `GET /v1/meta/condition-fields` non esiste ancora (costruttore condizioni, milestone successiva). In corso: BO-08 (Jules), M3.5 ponte (Jules). |
| 2026-09-20 | M2.8 | ✅ `./mvnw verify` (reattore) verde | | — | **Chiusura M2.** insight nell'hub consolidato (deploy unico Render, CORS `/v1/stream/**` default `*` con `allowCredentials=false`, ADR-020). Verificato live: Vercel `NEXT_PUBLIC_LH_INSIGHT_URL`/`LH_SVC_*_URL` → hub; migrazioni insight V1–V3 su Neon; seed sintetico rigenerato. **Fix SSE**: disconnessione client → `AsyncRequestNotUsableException` non è più un 500 né tenta un `problem+json` su `text/event-stream` (handler dedicato, log DEBUG). **M2 completa** (BO-01/22/24/25/29 + rail). Prossima **M3.1** (livelli/tier). |
| 2026-09-20 | M2.7 | ✅ `./mvnw verify` (reattore) verde: 13 IT ingestion (incl. scenario) + resto; `pnpm lint typecheck test build` verdi; `check-seed` 11 file | | — | **Scenari demo (F-DEMO-04) + BO-29.** ingestion: `V3` `scenario`/`scenario_run` (results jsonb passo-passo con correlationId); `seed/scenarios.json` (3 storie: positiva, mista con UNMATCHED, REJECTED); `IngestionService.ingest` con origine esplicita (overload SIMULATOR); `ScenarioService` esecutore async (thread dedicato, ritardi max 10 s/passo, `expect`, `eventId` opzionale); API `/v1/demo/scenarios`, `POST …/{code}/run` (202 runId, demo.simulate), `/scenario-runs/{id}`; DemoSeeder carica scenari, reset svuota le run. FE: **BO-29** `/backoffice/demo/scenarios` (schede + esecuzione passo-passo con TraceLink e suggerimento portale). IT: SCN-MIXED-DAY → 2 ACCEPTED + 1 UNMATCHED atteso. **Da distribuire**: redeploy hub (migrazione scenari + endpoint) + frontend (BO-29). Prossima **M2.8**: verifica finale deploy insight/CORS (già nell'hub). |
| 2026-09-20 | M2.6 | ✅ `./mvnw verify` (reattore) verde: 9 IT campaign (incl. stats) + resto; `pnpm lint typecheck test build` verdi | | — | **Statistiche campagna (F-CMP-10) in BO-05/06.** campaign: `GET /v1/campaigns/{id}/stats` (totali, membri unici, budget residuo da `limits.global`, serie giornaliera 30g da `evaluation_log` via jsonb); `CounterRepository.uniqueMembers`, `EvaluationLogRepository.dailyForCampaign`; `CampaignSummary` col budget. FE: BO-05 colonne *Membri unici* + barra *Budget*; BO-06 pannello **Statistiche** (stat tile + budget bar + `DailyBars` 30g inline-SVG con hover/tabella). IT: acquisto → `/stats` con matches/uniqueMembers/pointsDecided e serie non vuota. **Da distribuire**: redeploy hub (endpoint stats) + frontend. Prossima **M2.7**: scenari (`seed/scenarios.json`, esecutore in ingestion) + BO-29. |
| 2026-09-20 | M2.5 | ✅ `./mvnw verify` (reattore) verde: 8 IT insight (incl. audit) + 7 member + 8 campaign + hub inproc; `pnpm lint typecheck test build` verdi; `check-seed` verde | | — | **Audit end-to-end + BO-22.** `AuditPublisher` (lh-common) ora usato da **member** (create/update/status) e **campaign** (create/transizioni), stessa transazione, `lhactor` = attore corrente. insight: `V3` `audit_entry` (idempotente su `event_id`, before/after jsonb), l'ingest sul topic audit registra la voce; `AuditRepository` + `GET /v1/audit(/{id})`; retention 180g; reset svuota. FE: **BO-22** `/backoffice/governance/audit` (tabella + filtri + `DiffView` + JSON grezzo + link tracciato `?c=`), voce nav Governance. Fix: bind `Instant`→`Timestamp` per le colonne timestamptz dell'audit. **Da distribuire**: redeploy hub (migrazione `audit_entry` + audit da member/campaign) + frontend (BO-22). Prossima **M2.6**: statistiche campagna in BO-05 (F-CMP-10). |
| 2026-09-20 | M2.4 | ✅ `./mvnw -pl services/insight-service -am verify` verde (7 IT insight); `pnpm lint typecheck test build` verdi; `check-seed` verde (10 file) | | — | **`metric_daily` + storico sintetico + dashboard BO-01.** insight: `V2` `metric_daily`, `MetricRepository` (UPSERT `increment`/`putSynthetic`, `total`/`latestValue`/`timeseries`/`breakdown`), l'ingest aggiorna le metriche solo su evento nuovo (no doppio conteggio). `InsightSyntheticSeeder` (`seed/insight-synthetic.json`, 90g, seme fisso, +35% weekend su punti, picco −30, ±12%). `KpiService`/`KpiController` `/v1/kpi/overview|timeseries|breakdown` (delta vs periodo precedente). FE: **BO-01** `/backoffice` (7/30/90g, 6 KpiTile con delta+sparkline, area impilata punti con storico ombreggiato, barre azioni per fonte con palette validata, "Da guardare"), grafici inline-SVG con hover + tabella alternativa, `lib/charts/scale.ts` (unit), voce nav Panoramica. Righe 3-4 di BO-01 (distribuzione tier, passività, top campagne/premi) restano a M3/M4 (endpoint wallet/reward non ancora esistenti). **Da distribuire**: redeploy hub (KPI API + `metric_daily`) + frontend (BO-01). Prossima **M2.5**: audit end-to-end (`lh.audit.v1` da tutti i servizi) + BO-22. |
| 2026-09-20 | M2.3 | ✅ `./mvnw verify` verde (4 IT insight, incl. traccia); `pnpm lint typecheck test build` verdi | | — | **Tracciati (BO-25) + pending via SSE.** insight: `TraceService` (albero per causationId, stato, esito) + `GET /v1/traces/{id}` e `/v1/traces`. FE: BO-25 `/observe/traces` (elenco + `TraceWaterfall` a corsie per servizio) + `usePendingTrace` (il portale chiude l'attesa al fatto wallet via SSE per correlationId; polling fallback). Da distribuire: redeploy hub (traces API) + frontend (BO-25). Prossima **M2.4**: `metric_daily` (UPSERT nell'ingest), storico sintetico 90g con seme (`seed/insight-synthetic.json`), KPI `/v1/kpi/*`, BO-01 dashboard. Nota: le tabelle `metric_daily`/`audit_entry`/`dlq_entry` vanno create con migrazioni additive V2/V3 di insight. |
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
