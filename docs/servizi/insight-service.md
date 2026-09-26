# insight-service

**Porta** 8088 · **Schema** `insight` · **Feature** `F-INS-*`, `F-AUD-01` · **Milestone** M2 (event store, live, tracciati, KPI, audit, storico sintetico), M7 (DLQ con riprocessa)

## 1. Scopo e confini
È la **finestra sulla piattaforma**: consuma tutti i topic, conserva gli eventi recenti, ricostruisce i tracciati, calcola metriche giornaliere, espone audit e DLQ, e trasmette il flusso **live** via SSE.
Sola lettura rispetto al dominio: non produce fatti. Nel target è sostituito da ClickHouse + Superset + stack di osservabilità (`docs/04 §8`).

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `event_store` | `event_id` PK, `topic`, `family` (`ACTION, EFFECT, FACT, AUDIT, DLQ`), `type`, `short_type`, `source`, `member_id`, `correlation_id`, `causation_id`, `hop`, `actor`, `event_time`, `received_at`, `partition`, `offset`, `payload jsonb` · indici (`correlation_id`), (`member_id`,`event_time desc`), (`topic`,`received_at desc`) |
| `metric_daily` | (`day`,`metric`,`dimension`,`dim_value`) PK, `value numeric`, `synthetic bool` |
| `audit_entry` | `id`, `event_id` UQ, `at`, `actor_role`, `actor_name`, `service`, `entity_type`, `entity_id`, `action`, `summary`, `before jsonb`, `after jsonb`, `correlation_id` |
| `dlq_entry` | `id`, `event_id`, `original_topic`, `original_type`, `consumer`, `error_code`, `error_message`, `retryable`, `attempts`, `payload jsonb`, `first_seen_at`, `status` (`OPEN, REPROCESSED, DISCARDED`), `resolved_by`, `resolved_at` |
| `topic_stat` | `topic` PK, `last_event_at`, `count_1h`, `count_24h`, `last_offset_by_partition jsonb` |

Metriche (`metric`): `actions` (dim `source`, `type`), `points_earned`/`points_spent`/`points_expired` (dim `currency`), `points_by_campaign` (dim `campaign`), `members_new`, `members_active`, `redemptions` (dim `status`, `reward`), `plays`, `wins`, `tier_changes` (dim `direction`), `messages`, `dlq`.

## 3. API
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/stream/events` | **SSE** (`text/event-stream`); parametri `topics, memberId, types, correlationId`; evento `lh-event` con `{eventId, topic, family, shortType, memberId, correlationId, time, summary}`; `heartbeat` ogni 15 s; `Last-Event-ID` → rinvio degli ultimi ≤ 200 persi; CORS da `LH_CORS_ALLOWED_ORIGINS` |
| GET | `/v1/events` | filtri `topic, family, type, memberId, correlationId, source, from, to, q` (testo nel payload) |
| GET | `/v1/events/{eventId}` | payload completo |
| GET | `/v1/traces/{correlationId}` | `{correlationId, memberId, startedAt, durationMs, status (COMPLETE/IN_PROGRESS/FAILED), nodes[] {eventId, family, shortType, service, time, offsetMs, parentEventId, summary}, outcome: {points[] {currency, amount}, tierChange?, messages, coupons, plays, dlq}}` |
| GET | `/v1/traces?memberId=&from=&to=` | ultimi tracciati (uno per `correlationId`) |
| GET | `/v1/kpi/overview?from=&to=` | `{membersTotal, membersActive30d, actions, pointsEarned, pointsSpent, pointsExpired, liabilityPts?, redemptions, plays, wins, deltas{…vs periodo precedente}}` |
| GET | `/v1/kpi/timeseries?metric=&dimension=&from=&to=&granularity=day\|week` | |
| GET | `/v1/kpi/breakdown?metric=&dimension=&from=&to=&limit=` | top N (campagne, premi, fonti) |
| GET | `/v1/audit` | filtri `actor, role, service, entityType, entityId, action, from, to` · `GET /v1/audit/{id}` con diff |
| GET | `/v1/dlq` · `/v1/dlq/{id}` | filtri `status, consumer, errorCode` |
| POST | `/v1/dlq/{id}/reprocess` · `/discard` | ruolo `ADMIN`; vedi §5 |
| GET | `/v1/pipeline/status` | per topic: ultimo evento, volumi 1 h/24 h, ritardo stimato; per servizio: ultimo fatto prodotto |
| GET | `/v1/members/{memberId}/timeline` | eventi del membro raggruppati per tracciato (Scheda 360°) |

Nota: `liabilityPts` non è calcolabile qui con esattezza: la dashboard la legge da `wallet /v1/liability`.

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Consuma | tutti e 5 | tutto, gruppo `lh-insight` |
| Produce | — | **nessun evento su Kafka**. *Riprocessa* DLQ di un'azione = chiamata HTTP a `ingestion POST /v1/events` (ADR-003 resta valida: ingestion è l'unico produttore di azioni; è l'eccezione 1 di ADR-002) |

## 5. Regole
- **Ingest**: un consumer per topic, batch ≤ 200, `INSERT … ON CONFLICT (event_id) DO NOTHING`; poi aggiornamento `metric_daily` (`UPSERT` con incremento) e `topic_stat`; infine pubblicazione sul bus SSE in memoria (`Sinks.many().multicast()` o equivalente senza Reactor: lista di `SseEmitter` con coda limitata a 500 per client; client lento → disconnesso).
- **Tracciato**: albero per `causation_id`; `status = COMPLETE` se nessun nuovo evento da 5 s e nessuna voce DLQ; `FAILED` se esiste DLQ; `summary` generato per tipo (tabella in codice, es. `wallet.points.earned` → "+162 PTS · Acquisto").
- **Retention** (RNF-07): `event_store` 14 giorni o 200 000 righe (il minore); payload troncato a 8 KB; `audit_entry` 180 giorni; `metric_daily` illimitato. Job orario.
- **Storico sintetico** (F-INS-04): al seed genera 90 giorni di `metric_daily` con `synthetic=true` — curva con stagionalità settimanale (+35 % sab/dom per `points_earned`), rumore ±12 %, trend +0,4 %/giorno, picco al giorno −30 (campagna estiva). Generatore con seme fisso. I dati reali del giorno si sommano a quelli sintetici; BO-01 marca il periodo sintetico con nota.
- **DLQ riprocessa**: azione → re-invio a `ingestion POST /v1/events` con stesso `id` (unica chiamata HTTP tra servizi ammessa, *solo su comando umano*, ADR-002 eccezione 1); effetti/fatti → non riprocessabili da qui (`409 NOT_REPROCESSABLE`), solo `discard` con nota.
- **Avvio a freddo**: al risveglio recupera l'arretrato dai topic (`auto.offset.reset=earliest`, retention Kafka 3 giorni); nessun dato perso entro quella finestra.

## 6. Seed
`seed/insight-synthetic.json` (parametri del generatore: baseline per metrica, seme), nessun evento pre-caricato: l'event store si popola con i primi scenari. Il reset svuota `event_store`, `audit_entry`, `dlq_entry` e rigenera lo storico sintetico.

## 7. Accettazione minima
- Un acquisto dal simulatore → entro 3 s almeno 4 messaggi SSE (`action`, `campaign.evaluated`, `points.grant`, `wallet.points.earned`) con lo stesso `correlationId`.
- `GET /v1/traces/{id}` → albero con radice l'azione, `durationMs` > 0, `outcome.points` = [{PTS,162},{STS,130}] nello scenario SILVER 130 €.
- Evento duplicato sul topic → una sola riga in `event_store`, metriche non raddoppiate.
- `SCN-POISON` → voce in `/v1/dlq` con `errorCode`, tracciato `FAILED`.
- Dashboard appena dopo il reset → serie di 90 giorni non vuote, `synthetic=true`.
- Riconnessione SSE con `Last-Event-ID` → nessun evento perso tra i due collegamenti (test con 20 eventi).

## 8. Fase 2 (profilo `enterprise`)

Riferimento: `docs/18`. Le righe qui sotto sono segnaposto dell'adozione (M8.0): la fetta citata le rende normative aggiornando questa scheda.

- **Audit unificato** (ADR-043, M8.12): `audit_entry` riceve anche le modifiche fatte in Directus (`service=cms`) e in Keycloak (`service=idp`) con l'attore reale dal token; tabella sola-inserzione (nessun `GRANT UPDATE` al ruolo applicativo), **retention 400 giorni** (oggi 180 fino a M8.12), catena di hash con ancoraggio e `lh audit verify` (F2-GRC-07).
- **Dati personali** (ADR-032, M8.4): da `member.*:2` l'`event_store` non riceve più dati identificativi; il payload dell'audit è mascherato.
- **Economia del programma** (ADR-045, M13.4): `/v1/kpi/economics` (valore per livello e campagna) e metriche di costo.

**Classificazione `x-lh-class`** (`docs/18 §3.15`, F2-GRC-05; prima stesura M8.0, verificata e resa per colonna in M8.13). Tutto ciò che non è elencato è `INTERNAL`.
- `PERSONAL`: `event_store.payload` degli eventi `member.*:1` (fino alla dismissione della versione `:1`); `audit_entry.before`/`after` sui membri; `audit_entry.actor_name`.
- `CONFIDENTIAL`: `dlq_entry.payload`, `dlq_entry.error_message`, `audit_entry.*` (evidenza di sicurezza).
