# ingestion-service

**Porta** 8081 · **Schema** `ingestion` · **Feature** `F-ING-*`, `F-DEMO-03/04` · **Milestone** M1 (core), M2 (scenari), M3 (ponte), M6 (tipi custom), M7 (non abbinati)

## 1. Scopo e confini
Unica porta d'ingresso delle azioni premianti e **unico produttore** di `lh.actions.v1`. Valida, deduplica, risolve il membro, normalizza in CloudEvent canonico. Ospita il **ponte interno** (fatti → azioni) e il **simulatore** della demo.
Non valuta regole, non conosce punti o premi.

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `source` | `code` PK (`crm, app, ecommerce, billing, partner, internal, simulator`), `name`, `kind` (`HTTP`/`INTERNAL`), `enabled`, `allowed_types text[]` (vuoto = tutti), `description` |
| `event_type` | `code` PK (nome breve, es. `purchase.completed`), `name`, `description`, `origin` (`SYSTEM`/`CUSTOM`), `category` (`TRANSACTION, ENGAGEMENT, SERVICE, INTERNAL`), `data_schema jsonb` (JSON Schema), `sample_data jsonb`, `enabled`, `icon` |
| `inbound_event` | `id` PK (ULID interno), `event_id`, `source_code`, `type_code`, `subject`, `member_id`, `event_time`, `received_at`, `status` (`ACCEPTED, DUPLICATE, REJECTED, UNMATCHED`), `reject_code`, `reject_detail`, `payload jsonb`, `correlation_id`, `origin` (`EXTERNAL, INTERNAL, SIMULATOR`) · UNIQUE (`source_code`,`event_id`) per i non duplicati |
| `member_index` | `member_id` PK, `external_id`, `email_lower`, `status` |
| `internal_mapping` | `fact_type` PK, `action_type`, `enabled` |
| `scenario` | `code` PK, `name`, `description`, `steps jsonb` |
| `scenario_run` | `id`, `scenario_code`, `started_at`, `finished_at`, `status`, `steps_total`, `steps_done`, `actor` |

Pulizia: `inbound_event` > 7 giorni (o oltre 20 000 righe: si eliminano le più vecchie).

## 3. API
### Ingresso (fonti esterne)
| Metodo | Path | Note |
|---|---|---|
| POST | `/v1/events` | corpo = CloudEvent. `202 {eventId, status, memberId?, rejectCode?}`. Errori di forma → `400`; rifiuti di business → `202` con `status=REJECTED` (la fonte non deve ritentare) |
| POST | `/v1/transactions` | `{source, orderId, memberRef, amount, currency, channel, items[], occurredAt}` → azione `purchase.completed` con `id = "txn-" + orderId` (P1) |

### Gestione (backoffice)
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/inbound-events` | filtri `status, source, type, memberId, from, to, q` |
| GET | `/v1/inbound-events/{id}` | payload completo + `correlationId` per aprire il tracciato |
| POST | `/v1/inbound-events/{id}/retry` | solo `REJECTED`/`UNMATCHED`: rivaluta e, se valido, pubblica |
| POST | `/v1/inbound-events/{id}/match` | `{memberId}` abbina un `UNMATCHED` (P1) |
| GET/POST/PUT | `/v1/sources`, `/v1/sources/{code}` | abilitazione, tipi ammessi |
| GET/POST/PUT | `/v1/event-types`, `/v1/event-types/{code}` | i tipi `SYSTEM` permettono di modificare solo `name, description, enabled, icon`; i `CUSTOM` tutto tranne `code` |
| GET | `/v1/event-types/{code}/fields` | elenco piatto dei campi `data.*` dedotti dallo schema `{path, type, enum?}` → alimenta il costruttore di condizioni |
| GET/PUT | `/v1/internal-mappings` | ponte: abilita/disabilita |

### Demo
| Metodo | Path | Note |
|---|---|---|
| POST | `/v1/demo/simulator/fire` | `{memberId, type, data?, source?, occurredAt?, count?=1}`; `data` assente → `sample_data` del tipo con piccole variazioni casuali. Risposta: `[{eventId, correlationId, status}]` |
| GET | `/v1/demo/scenarios` · POST `/v1/demo/scenarios/{code}/run` | esecuzione asincrona; `202 {runId}` |
| GET | `/v1/demo/scenario-runs/{runId}` | avanzamento passo per passo con `correlationId` di ogni evento |

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Produce | `lh.actions.v1` | tutte le `action.*` |
| Produce | `lh.audit.v1` | modifiche a fonti, tipi, mapping |
| Consuma | `lh.facts.v1` | `member.registered/updated/status.changed` → `member_index`; tipi abilitati in `internal_mapping` → ponte |

## 5. Regole
Pipeline di accettazione, in ordine; al primo fallimento si salva `inbound_event` con l'esito e ci si ferma:
1. forma envelope valida (altrimenti `400`, nulla salvato);
2. fonte esistente e abilitata → altrimenti `REJECTED/SOURCE_DISABLED`;
3. tipo noto e abilitato, ammesso per la fonte → `UNKNOWN_TYPE` / `TYPE_NOT_ALLOWED`;
4. `data` valido contro lo schema → `INVALID_DATA` (dettaglio = errori dello schema);
5. `time` non nel futuro (> 5 min) né più vecchio di 30 giorni → `INVALID_TIME`;
6. dedup `(source, id)` → `DUPLICATE`;
7. membro: `member:<id>` / `external:<x>` / `email:<x>` → non trovato → `UNMATCHED`; stato ≠ `ACTIVE` → `REJECTED/MEMBER_NOT_ACTIVE`;
8. arricchimento (`lh*`, `type` completo, `subject` normalizzato a `member:<id>`) → outbox → `ACCEPTED`.

Ponte: per ogni fatto mappato crea l'azione secondo `docs/05 §7`; salva `inbound_event` con `origin=INTERNAL`; `lhhop > 3` → DLQ.
Scenari: i passi sono `{delayMs, memberId, type, data, source, note}`; l'esecutore rispetta i ritardi (max 10 s per passo) e passa dalla stessa pipeline (origine `SIMULATOR`). Un passo può dichiarare `expect: REJECTED|DUPLICATE|UNMATCHED` per gli scenari negativi.

## 6. Seed
`seed/sources.json`, `seed/event-types.json`, `seed/internal-mappings.json`, `seed/scenarios.json`, più `seed/members.json` per popolare `member_index`. Storico: 40 `inbound_event` degli ultimi 3 giorni con tutti gli esiti.

## 7. Accettazione minima
- Dato un evento valido per `MBR-000002`, quando lo invio, allora `202 ACCEPTED` e un record su `lh.actions.v1` con chiave `MBR-000002` e `lhhop=0`.
- Dato lo stesso `source`+`id` inviato due volte, allora il secondo è `DUPLICATE` e su Kafka c'è un solo record.
- Dato `data.amount` mancante in `purchase.completed`, allora `REJECTED/INVALID_DATA` con il campo indicato.
- Dato il fatto `tier.upgraded`, allora compare `action.tier.upgraded` con `source=…:internal`, stesso `lhcorrelationid`, `lhhop=1`.
- Dato un fatto con `lhhop=3` mappato, allora l'azione non viene pubblicata e c'è un record in DLQ con `LOOP_GUARD`.
