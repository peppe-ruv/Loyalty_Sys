# insight-service

**Porta** 8088 · **Schema** `insight` · **Feature** `F-INS-*`, `F-AUD-01` · **Milestone** M2 (event store, live, tracciati, KPI, audit, storico sintetico), M7 (DLQ con riprocessa)

## 1. Scopo e confini
È la **finestra sulla piattaforma**: consuma tutti i topic, conserva gli eventi recenti, ricostruisce i tracciati, calcola metriche giornaliere, espone audit e DLQ, e trasmette il flusso **live** via SSE.
Sola lettura rispetto al dominio: non produce fatti. Nel target è sostituito da ClickHouse + Superset + stack di osservabilità (`docs/04 §8`).

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `event_store` | `event_id` PK, `topic`, `family` (`ACTION, EFFECT, FACT, AUDIT, DLQ`), `type`, `short_type`, `source`, `member_id`, `correlation_id`, `causation_id`, `hop`, `actor`, `error_code` (solo eventi del topic DLQ), `event_time`, `received_at`, `kafka_partition`, `kafka_offset`, `payload jsonb` · indici (`correlation_id`), (`member_id`,`event_time desc`), (`topic`,`received_at desc`) |
| `metric_daily` | (`day`,`metric`,`dimension`,`dim_value`) PK, `value numeric`, `synthetic bool` |
| `audit_entry` | `id`, `event_id` UQ, `at`, `actor_role`, `actor_name`, `service`, `entity_type`, `entity_id`, `action`, `summary`, `before jsonb`, `after jsonb`, `correlation_id`; catena di hash (V6, F2-GRC-07): `seq` (UQ con `service`), `prev_hash`, `content_hash`, `entry_hash`, `redacted_at` · **sola inserzione** (trigger, §5) |
| `audit_chain_head` | `service` PK, `seq`, `entry_hash`, `updated_at`: testa di ogni catena, riga bloccata per serializzare gli accodamenti del servizio (V6) |
| `audit_anchor` | `id` PK, `service`, `seq`, `entry_hash`, `kind` (`DAILY, PURGE, BACKFILL`), `anchored_at` (fissato dal database), `entry_at` (solo `PURGE`: istante della voce più recente cancellata) · UQ (`service`,`seq`,`kind`) · sola inserzione (V6) |
| `dlq_entry` | `id`, `event_id`, `original_topic`, `original_type`, `consumer`, `error_code`, `error_message`, `retryable`, `attempts`, `payload jsonb`, `first_seen_at`, `status` (`OPEN, REPROCESSED, DISCARDED`), `resolved_by`, `resolved_at`; in più `original_family`, `error_class`, `error_stack`, `member_id`, `correlation_id`, `dlq_partition`, `dlq_offset`, `resolution_note` · UQ parziale (`event_id`,`consumer`) sulle voci `OPEN` |
| `topic_stat` | `topic` PK, `last_event_at`, `count_total`, `last_offset_by_partition jsonb`, `last_lag_ms`, `last_received_at` (V5). I volumi a 1 h e 24 h non sono colonne: si contano da `event_store` sull'istante d'arrivo |
| `service_stat` | `service` PK (dal `source` del fatto), `last_fact_at`, `last_fact_type`, `last_event_id`, `facts_total` (V5, stato della pipeline per servizio) |

Tracciato delle tabelle (docs/18 §3.12-bis, verificato sulle migrazioni `V1`–`V6`). insight non ha vincoli `FOREIGN KEY`: le tabelle sono registri e aggregati legati per `event_id`, topic o servizio (linee tratteggiate). `metric_daily` è un aggregato giornaliero senza relazioni. Le voci di audit di uno stesso servizio formano una catena (`prev_hash` della voce `seq` = `entry_hash` della voce `seq - 1`); testa e ancore puntano a una posizione della catena per servizio e `seq`.

```mermaid
erDiagram
  accTitle: Tabelle dello schema insight
  accDescr: L'event store conserva gli eventi dei cinque topic; le statistiche per topic e per servizio ne riassumono l'ultimo arrivo; audit e DLQ hanno una voce per evento; le voci di audit di un servizio sono in catena, con una testa per servizio e ancore registrate a parte; le metriche giornaliere sono aggregati indipendenti.
  event_store {
    text event_id PK
    text topic
    text family
    text member_id
    text correlation_id
  }
  topic_stat {
    text topic PK
  }
  service_stat {
    text service PK
    text last_event_id "rif. event_store.event_id"
  }
  audit_entry {
    text id PK
    text event_id UK "evento lh.audit.v1"
    text service UK "UQ con seq"
    bigint seq UK "posizione nella catena"
    text prev_hash "entry_hash della voce seq - 1"
    text content_hash
    text entry_hash
    timestamptz redacted_at
    text entity_type
    text entity_id
  }
  audit_chain_head {
    text service PK
    bigint seq "ultima voce accodata"
    text entry_hash
  }
  audit_anchor {
    bigint id PK
    text service UK "UQ con seq e kind"
    bigint seq UK
    text entry_hash
    text kind UK "DAILY, PURGE, BACKFILL"
    timestamptz entry_at "solo PURGE"
  }
  dlq_entry {
    text id PK
    text event_id "UQ con consumer se OPEN"
    text consumer
    text status
  }
  metric_daily {
    date day PK
    text metric PK
    text dimension PK
    text dim_value PK
  }
  topic_stat ||..o{ event_store : "per topic"
  service_stat |o..o| event_store : "ultimo fatto"
  event_store |o..o| audit_entry : "stesso event_id"
  event_store |o..o{ dlq_entry : "evento fallito"
  audit_entry |o..o| audit_entry : "prev_hash, voce precedente"
  audit_chain_head |o..o| audit_entry : "ultima voce del servizio"
  audit_anchor }o..o| audit_entry : "stessa service e seq"
```

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
| GET | `/v1/audit/verify?service=&seq=&hash=` | ruolo `ADMIN` (`SPEC-GAP: Q-399`); ricalcola la catena di hash (§5) di tutti i servizi o di `service` (404 se il servizio non ha lasciato traccia, né voci né testa né ancore): `{verifiedAt, status (OK/BROKEN), services[] {service, status, checked, firstSeq, lastSeq, headSeq, redacted, anchorsChecked, brokenSeq?, reason?, detail?, expectedAnchor?}}`; 200 anche con catena interrotta. `seq` e `hash` (con `service`, altrimenti 400) confrontano la catena con un'ancora copiata dai log: `expectedAnchor {seq, entryHash, result (MATCH/MISMATCH/MISSING/PURGED/UNCHECKED), purgedAt?}`. `reason`: `GENESIS_MISMATCH, UNANCHORED_START, PURGE_TOO_RECENT, MISSING_ENTRY, PREV_HASH_MISMATCH, CONTENT_ALTERED, REDACTION_UNRECORDED, ENTRY_ALTERED, ANCHOR_MISMATCH, TAIL_MISSING, HEAD_MISMATCH`. Base di `lh audit verify` (M12.2) |
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

insight è solo consumatore: legge i cinque topic con il gruppo `lh-insight` e non pubblica su Kafka. L'unica uscita è il *Riprocessa* di un'azione in DLQ, una chiamata HTTP a ingestion su comando umano.

```mermaid
flowchart LR
  accTitle: Consumi e produzioni di insight-service
  accDescr: insight consuma tutti e cinque i topic, azioni, effetti, fatti, audit e DLQ, e non produce eventi; su comando umano riprocessa un'azione in DLQ chiamando ingestion via HTTP.
  TA(["lh.actions.v1"]) --> INS["insight-service"]
  TE(["lh.effects.v1"]) --> INS
  TF(["lh.facts.v1"]) --> INS
  TU(["lh.audit.v1"]) --> INS
  TD(["lh.dlq.v1"]) --> INS
  INS -.->|"Riprocessa: POST /v1/events, solo azioni"| ING["ingestion-service"]
  classDef svc fill:#EFF6FF,stroke:#2563EB,color:#1E3A8A
  classDef topic fill:#FEF3C7,stroke:#D97706,color:#78350F
  class INS,ING svc
  class TA,TE,TF,TU,TD topic
```

## 5. Regole
- **Ingest**: un consumer per topic, batch ≤ 200, `INSERT … ON CONFLICT (event_id) DO NOTHING`; poi aggiornamento `metric_daily` (`UPSERT` con incremento) e `topic_stat`; infine pubblicazione sul bus SSE in memoria (`Sinks.many().multicast()` o equivalente senza Reactor: lista di `SseEmitter` con coda limitata a 500 per client; client lento → disconnesso).
- **Tracciato**: albero per `causation_id`; `status = COMPLETE` se nessun nuovo evento da 5 s e nessuna voce DLQ; `FAILED` se esiste DLQ; `summary` generato per tipo (tabella in codice, es. `wallet.points.earned` → "+162 PTS · Acquisto").
- **Retention** (RNF-07): `event_store` 14 giorni o 200 000 righe (il minore); payload troncato a 8 KB; `audit_entry` 180 giorni, cancellando per ogni catena solo la parte iniziale scaduta (sotto, *Audit a catena di hash*); `metric_daily` illimitato. Job orario.
- **Storico sintetico** (F-INS-04): al seed genera 90 giorni di `metric_daily` con `synthetic=true` — curva con stagionalità settimanale (+35 % sab/dom per `points_earned`), rumore ±12 %, trend +0,4 %/giorno, picco al giorno −30 (campagna estiva). Generatore con seme fisso. I dati reali del giorno si sommano a quelli sintetici; BO-01 marca il periodo sintetico con nota.
- **DLQ riprocessa**: azione → re-invio a `ingestion POST /v1/events` con stesso `id` (unica chiamata HTTP tra servizi ammessa, *solo su comando umano*, ADR-002 eccezione 1); effetti/fatti → non riprocessabili da qui (`409 NOT_REPROCESSABLE`), solo `discard` con nota.

Ciclo di vita di una voce DLQ (`dlq_entry.status`): un record aperto per coppia evento e consumer; chiuderlo è un'azione umana ADMIN. Un nuovo fallimento dello stesso evento dopo la chiusura apre una voce nuova.

```mermaid
stateDiagram-v2
  accTitle: Ciclo di vita di una voce DLQ
  accDescr: Una voce nasce aperta quando un record arriva in DLQ; un'azione si può riprocessare re-inviandola a ingestion, mentre effetti e fatti si possono solo scartare con una nota; entrambe le chiusure sono definitive.
  [*] --> OPEN: record su lh.dlq.v1
  OPEN --> REPROCESSED: Riprocessa, solo azioni, re-invio a ingestion
  OPEN --> DISCARDED: Scarta con nota
  REPROCESSED --> [*]
  DISCARDED --> [*]
```

- **Avvio a freddo**: al risveglio recupera l'arretrato dai topic (`auto.offset.reset=earliest`, retention Kafka 3 giorni); nessun dato perso entro quella finestra.

### Audit a catena di hash (F2-GRC-07 parte 1, ADR-043, ADR-044, ADR-038, docs/18 §3.15 punto 5, M8.12a)

**Cosa garantisce.** Ricalcolando gli hash, la verifica rileva, per errore dell'applicazione, difetto della versione precedente o alterazione diretta nel database:
- una voce modificata o tolta in mezzo alla catena, con la sua posizione;
- voci iniziali tolte senza un'ancora `PURGE`, oppure con un'ancora `PURGE` che registra la cancellazione di voci più giovani dell'età minima (`PURGE_TOO_RECENT`);
- un contenuto riscritto senza la sua prova `REDACT`, o diverso da quello registrato dalla prova;
- una riscrittura coerente della catena fatta dopo un'ancora, se l'ancora è ancora nel database oppure se chi verifica la ricopia dai log (`GET /v1/audit/verify?service=&seq=&hash=`).

Non basta invece contro chi ha le credenziali applicative del database e conosce il meccanismo: i controlli del database usano impostazioni di sessione che quelle credenziali possono alzare, e nel database tutto si può riscrivere, ancore comprese. Contro questo avversario l'unica prova è l'ancora nei log della piattaforma, finché firma ed esportazione immutabile non arrivano (Q-400, TOBE-002). I limiti residui sono elencati in fondo alla sezione.

- **Una catena per servizio** (`audit_entry.service`): servizi diversi non si contendono nulla. La voce `seq` porta `prev_hash` = `entry_hash` della voce `seq - 1`; la prima parte dalla genesi (64 zeri).
- **Chi la calcola**: il database, all'inserimento (trigger `audit_entry_chain`, migrazione V6), ignorando i valori proposti da chi inserisce. Vale per ogni scrittore, anche per la versione precedente di insight durante un aggiornamento senza fermo (ADR-038). La verifica ricalcola tutto in Java (`AuditHashChain`), indipendentemente dalle funzioni del database.
- **Serializzazione**: il trigger blocca la riga del servizio in `audit_chain_head` (`SELECT … FOR UPDATE`) fino al commit; due accodamenti dello stesso servizio non leggono mai la stessa testa. **Idempotenza**: un `event_id` già registrato non crea un secondo anello (controllo dopo il blocco, quindi anche tra riconsegne concorrenti).
- **Forma canonica, versione 1** (normativa). Ogni campo è una netstring `<byte UTF-8 in decimale>:<valore>,`; `NULL` è il solo carattere `~`. `content_hash` = SHA-256 di `ns("lh.audit.content.v1") ns(summary) ns(before) ns(after)`; `entry_hash` = SHA-256 di `ns("lh.audit.entry.v1") ns(service) ns(seq) ns(prev_hash) ns(id) ns(event_id) ns(at) ns(actor_role) ns(actor_name) ns(entity_type) ns(entity_id) ns(action) ns(correlation_id) ns(content_hash)`. `before`/`after` sono la resa testuale di jsonb di PostgreSQL (`jsonb::text`): chiavi senza duplicati, ordinate per lunghezza e poi byte per byte (`{"b": 1, "aa": 2}`), separatori `", "` e `": "`, numeri come memorizzati (`1.50` resta `1.50`). `at` è in UTC con sei cifre di microsecondi (`2026-09-28T10:15:30.000000Z`); `seq` in decimale; hash in esadecimale minuscolo. Vettori di riferimento in `AuditHashChainTest`.
  > **Nota:** la verifica dipende dalla resa `jsonb::text`, stabile da PostgreSQL 9.4 (test su PostgreSQL 14, compose di riferimento su 17). Il formato dell'anno di Java (`uuuu`) e quello di PostgreSQL (`YYYY`) coincidono fino all'anno 9999.
- **Scritture ammesse (fase expand, `SPEC-GAP: Q-403`)**. `TRUNCATE` è sempre rifiutato; `UPDATE` e `DELETE` sono rifiutati (`42501`) con tre eccezioni, ammesse anche senza flag perché la versione precedente di insight le esegue ancora durante il rilascio:
  - `UPDATE` dei soli `summary`, `before`, `after` (anonimizzazione, F-MBR-05): ogni altro campo e la catena sono congelati, la voce è marcata `redacted_at` e il database accoda una **prova `REDACT`** nella catena riservata **`audit.redaction`** (`entity_type = AUDIT_ENTRY`, `entity_id` = voce riscritta, hash del nuovo contenuto, `memberId` ed `eventId` del fatto di anonimizzazione se la riscrittura passa da `audit_redact`, nessun dato personale). Quella catena la scrive solo il database: un evento del bus che rivendica il servizio `audit.redaction` o l'azione `REDACT` è rifiutato. Il codice nuovo usa `audit_redact(memberId, eventId, id, …, tokens, correlationId)`, che prima prende il blocco delle anonimizzazioni (due anonimizzazioni concorrenti si mettono in fila) e poi esige che `eventId` sia nell'event store come fatto di member-service di quel membro e che la voce riguardi il membro (sua, oppure la sintesi o un valore testuale di `before`/`after` ne cita l'id o uno dei valori personali di almeno 3 caratteri come parola intera, `audit_mentions`: «ata» non è in «Modificata»). `SPEC-GAP: Q-401`.
  - `DELETE` della sola parte iniziale di ogni catena: un trigger sul risultato dell'istruzione rifiuta i buchi e lascia un'**ancora `PURGE`** sull'ultima voce cancellata di ogni servizio, con l'istante della voce più recente cancellata (`entry_at`), qualunque codice abbia cancellato. Una posizione si cancella una volta sola: se esiste già un'ancora `PURGE` per l'ultima voce cancellata, è falsificata e il `DELETE` è rifiutato; la verifica segnala un'ancora `PURGE` su una voce ancora conservata (`ANCHOR_MISMATCH`). Il codice nuovo passa da `audit_purge_before`, che non va mai oltre `now() - audit_min_retention_days()` (180 giorni; il profilo `enterprise` rifiuta di partire con `loyaltyhub.insight.retention.audit-max-age-days` più corta, `INSECURE_CONFIG`). Il `DELETE` per età della versione precedente è rifiutato quando una voce scaduta segue una più recente (aprirebbe un buco, Q-402): nessuna voce si perde, il job della versione precedente riprova al giro successivo e la retention nuova arriva con il rilascio. Durante il rilascio un'anonimizzazione della versione precedente (che blocca prima la voce e poi la testa di `audit.redaction`) e una della versione nuova (prima la testa) possono bloccarsi a vicenda: PostgreSQL ne annulla una e il consumer la riprova.
  - Reset: il `DELETE` che svuota tutta la tabella nella stessa transazione che ha svuotato l'event store (il reset della demo della versione precedente, `InsightReset`) cancella anche teste e ancore, e le catene ripartono dalla genesi. Un `DELETE` che svuota la tabella in altro modo, per esempio la retention oraria della versione precedente su un'installazione ferma, lascia le sue ancore `PURGE` come ogni cancellazione: le ancore dei log restano confrontabili (`PURGED`). `audit_reset()` fa lo stesso e oggi è invocabile con le credenziali applicative in ogni profilo, non solo nel `demo` (la contract di Q-403 la toglie al ruolo applicativo in `enterprise`): una verifica completa vuota va sempre confrontata con le ancore nei log.

  `audit_chain_head` la scrive solo il trigger di accodamento e la cancella solo il reset. `audit_anchor` non si modifica e si cancella solo con il reset; l'istante `anchored_at` lo fissa sempre il database e le ancore `PURGE` le scrive solo il trigger dei `DELETE` (flag e `INSERT` annidato in un trigger, `pg_trigger_depth()`). Il codice nuovo alza già i flag (`loyaltyhub.audit_write`) che la migrazione di contract (Q-403, con i ruoli owner/app di M8.5) renderà obbligatori: allora il ruolo applicativo avrà solo `SELECT, INSERT` e le funzioni diventeranno `SECURITY DEFINER` del ruolo owner.
- **Retention**: per ogni catena si cancella la parte iniziale scaduta, cioè le voci fino alla prima con `at` dentro la finestra, esclusa. Una voce scaduta che segue una più recente (per esempio un evento datato nel futuro) resta finché non scade anche quella, e il job lo segnala con un WARN al più una volta al giorno per servizio (`SPEC-GAP: Q-402`).
- **Ancore** (`audit_anchor`, una per servizio, `seq` e tipo): `DAILY` dal job giornaliero (`loyaltyhub.insight.audit.anchor-cron`, default 00:05 UTC), che per ogni servizio cresciuto dall'ultima ancora verifica il tratto nuovo e solo se regge registra la testa; `PURGE` dalle cancellazioni; `BACKFILL` dalla migrazione V6. Ogni ancora `DAILY` e ogni `PURGE` della retention finiscono anche nei **log della piattaforma**, sul logger dedicato `io.loyaltyhub.audit.anchor`, una riga INFO senza dati personali. Il profilo `enterprise` rifiuta di partire (`INSECURE_CONFIG`) con il job spento (`anchor-cron=-`) o con quel logger sotto INFO; chi installa lo instrada verso un archivio di log durevole (deploy/README.md).

  ```text
  audit-anchor service=campaign seq=128 entryHash=3f0c…e91a kind=DAILY anchoredAt=2026-09-28T00:05:00.412Z
  ```
- **Verifica** (`GET /v1/audit/verify`, in una sola fotografia `REPEATABLE READ`, voci lette a pagine): nessuna ancora `PURGE` registra la cancellazione di voci più giovani dell'età minima; la prima voce conservata parte dalla genesi o si aggancia a un'ancora **`PURGE`** di `seq - 1`; ogni voce ha numerazione contigua, `prev_hash` giusto, hash della voce uguale a quello memorizzato e contenuto uguale a quello dell'inserimento oppure, se anonimizzata, a quello registrato dalla sua prova `REDACT` (le voci anonimizzate contano in `redacted`; una prova può mancare solo per un'anonimizzazione più vecchia dell'età minima, che la retention può aver cancellato); ogni ancora della stessa posizione coincide; la testa coincide con l'ultima voce e nessuna ancora punta oltre. Un servizio di cui restano solo le ancore compare e risulta `BROKEN`. Si ferma al primo errore del servizio e ne dà `brokenSeq` e `reason`.
- **Confronto con un'ancora dei log** (costo zero, workaround di TOBE-002). Copia `service`, `seq` ed `entryHash` da una riga `audit-anchor` e chiedi `GET /v1/audit/verify?service=campaign&seq=128&hash=<entryHash>`. `expectedAnchor.result` vale:
  - `MATCH`: la catena contiene ancora quella voce, con quell'hash;
  - `MISMATCH` (catena `BROKEN`, `ANCHOR_MISMATCH`): la voce, o il `prev_hash` della voce rimasta successiva, o la testa ha un altro hash: la catena è stata riscritta dopo l'ancora;
  - `MISSING` (catena `BROKEN`, `TAIL_MISSING`): le voci ancorate non ci sono più, anche se il servizio è sparito del tutto;
  - `PURGED`: la voce non c'è più, cancellata con la parte iniziale della catena il giorno `purgedAt` (mai `MATCH` per una voce cancellata). Se `purgedAt` è a meno di 180 giorni dalla data della riga di log, le voci sono state cancellate prima del tempo; se `purgedAt` è **precedente** alla riga di log, le ancore sono state alterate;
  - `UNCHECKED`: la catena si interrompe prima.

**Limiti residui** (fino alla contract di Q-403 e alla parte 2 di Q-400):
- chi ha le credenziali applicative può riscrivere il contenuto di una voce: resta una prova `REDACT` e la verifica la accetta. Può anche falsificarne l'attribuzione, alzando a mano l'impostazione del membro o inserendo nell'event store un fatto di anonimizzazione inventato; per questo le prove si rivedono (`GET /v1/audit?action=REDACT`, Q-401). Cancellare la prova di una riscrittura recente invece si vede (`REDACTION_UNRECORDED` e `PURGE_TOO_RECENT`);
- chi ha le credenziali applicative può cancellare la parte iniziale di una catena alzando i flag e il suo trigger, oppure svuotare tutto l'audit con `audit_reset()`: nel primo caso resta un'ancora `PURGE` con `PURGE_TOO_RECENT`, che però può cancellare a sua volta, o sostituire con un'ancora `PURGE` falsificata dopo la cancellazione: un `INSERT` diretto è rifiutato anche con il flag alzato (una `PURGE` nasce solo dentro il trigger dei `DELETE`), quindi serve poter creare o spegnere trigger, diritto che il ruolo applicativo perde con la contract; lo rivela in ogni caso un'ancora dei log (`PURGED` con `purgedAt` troppo vicino o `MISSING`) (Q-403);
- chi ha le credenziali applicative può alzare a mano i flag e riscrivere testa, catena e ancore in modo coerente: lo rivela solo un'ancora dei log (`MISMATCH` o `MISSING`); i log stessi non sono firmati né immutabili (Q-400, TOBE-002);
- l'hash del contenuto originale (`content_hash`) resta nella voce anche dopo l'anonimizzazione ed è uno SHA-256 senza chiave di testi poco vari: chi indovina il contenuto (per esempio «Aggiornato » + un nome) può confermarlo. La catena ne ha bisogno; un hash con chiave gestita è TOBE-006 (Q-401).

**Costo della migrazione V6.** Tutto in una transazione: `ADD COLUMN` prende un lock `ACCESS EXCLUSIVE` su `audit_entry` (colonne senza default, nessuna riscrittura), il riempimento è un ciclo per riga (un `UPDATE` e due SHA-256 per voce), i `SET NOT NULL` scandiscono la tabella una volta e l'indice unico `(service, seq)` una volta. Misurato su PostgreSQL 14 in-process sotto carico elevato: 20 000 voci ≈ 12 s, 50 000 ≈ 27 s, dentro `migrations.activeDeadlineSeconds` (1200 s) del chart; intanto gli inserimenti nell'audit attendono.

```mermaid
flowchart LR
  accTitle: Catena di hash dell'audit per servizio
  accDescr: Ogni voce di audit di un servizio porta l'hash della precedente; la prima parte dalla genesi o si aggancia all'ancora PURGE lasciata dalla cancellazione; ogni riscrittura del contenuto accoda una prova REDACT nella catena riservata audit.redaction; il job giornaliero verifica il tratto nuovo, registra un'ancora e la scrive nei log; la verifica ricalcola la catena e la confronta con testa, ancore, prove e un'ancora copiata dai log.
  GEN(["genesi: 64 zeri"]) --> E1["voce 1, cancellata dalla retention"]
  E1 -->|entry_hash| E2["voce 2"]
  E2 -->|entry_hash| E3["voce 3, anonimizzata"]
  E3 -->|entry_hash| E4["voce 4"]
  PURGE[("ancora PURGE: hash della voce 1 e istante della voce cancellata")] -.->|la voce 2 vi si aggancia| E2
  E3 -.->|prova| RED["REDACT nella catena audit.redaction"]
  E4 --- HEAD[("testa: voce 4")]
  JOB["job giornaliero: verifica il tratto nuovo"] -->|solo se regge| DAILY[("ancora DAILY: voce 4")]
  JOB -->|riga audit-anchor| LOG(["log della piattaforma"])
  VER["GET /v1/audit/verify"] -.->|ricalcola e confronta| E4
  VER -.-> HEAD
  VER -.-> DAILY
  VER -.-> RED
  LOG -.->|"service, seq, hash"| VER
  classDef svc fill:#EFF6FF,stroke:#2563EB,color:#1E3A8A
  classDef store fill:#F1F5F9,stroke:#475569,color:#0F172A
  classDef ext fill:#FFFFFF,stroke:#94A3B8,stroke-dasharray:4 2,color:#334155
  class JOB,VER svc
  class PURGE,HEAD,DAILY,RED store
  class GEN,E1,LOG ext
```

## 6. Seed
`seed/insight-synthetic.json` (parametri del generatore: baseline per metrica, seme), nessun evento pre-caricato: l'event store si popola con i primi scenari. Il reset svuota `event_store`, `audit_entry`, `dlq_entry` e rigenera lo storico sintetico; per l'audit passa da `audit_reset` (§5), che svuota anche teste e ancore: nella demo le catene ripartono dalla genesi.

## 7. Accettazione minima
- Un acquisto dal simulatore → entro 3 s almeno 4 messaggi SSE (`action`, `campaign.evaluated`, `points.grant`, `wallet.points.earned`) con lo stesso `correlationId`.
- `GET /v1/traces/{id}` → albero con radice l'azione, `durationMs` > 0, `outcome.points` = [{PTS,162},{STS,130}] nello scenario SILVER 130 €.
- Evento duplicato sul topic → una sola riga in `event_store`, metriche non raddoppiate.
- `SCN-POISON` → voce in `/v1/dlq` con `errorCode`, tracciato `FAILED`.
- Dashboard appena dopo il reset → serie di 90 giorni non vuote, `synthetic=true`.
- Riconnessione SSE con `Last-Event-ID` → nessun evento perso tra i due collegamenti (test con 20 eventi).

## 8. Fase 2 (profilo `enterprise`)

Riferimento: `docs/18`. Le righe qui sotto sono segnaposto dell'adozione (M8.0): la fetta citata le rende normative aggiornando questa scheda.

- **Audit unificato** (ADR-043, M8.12): `audit_entry` riceve anche le modifiche fatte in Directus (`service=cms`) e in Keycloak (`service=idp`) con l'attore reale dal token; tabella sola-inserzione (nessun `GRANT UPDATE` al ruolo applicativo), **retention 400 giorni** (oggi 180 fino a M8.12), catena di hash con ancoraggio e `lh audit verify` (F2-GRC-07). *Fatto in M8.12a*: catena per servizio, scritture limitate dal database in fase expand, prove `REDACT` delle anonimizzazioni nella catena riservata `audit.redaction`, ancore giornaliere anche nei log (obbligatorie in `enterprise`), età minima della retention e `PURGE_TOO_RECENT`, pulizia delle copie per parole intere senza toccare i campi strutturali, verifica `GET /v1/audit/verify` anche contro un'ancora dei log (§5 «Audit a catena di hash»); restano la migrazione di contract con i ruoli owner/app (Q-403, M8.5), la firma e l'esportazione delle ancore su archivio immutabile (Q-400, TOBE-002), la retention a 400 giorni e i bridge Directus/Keycloak.
- **Dati personali** (ADR-032, M8.4): da `member.*:2` l'`event_store` non riceve più dati identificativi; il payload dell'audit è mascherato.
  - *Doppia lettura `:1`/`:2`* (Q-346, fino a M10; M8.4f): insight smista per `type`, non per `dataschema`, quindi le due versioni seguono le stesse regole: stessa sintesi nel rail e nel tracciato («Nuovo membro», «Membro aggiornato»: nessuna delle due usa campi personali), stesso conteggio in `members_new` e in «membri totali». L'`event_store` conserva il payload intero di entrambe; le copie `:1` restano `PERSONAL` finché dura la finestra, con la retention di §5 invariata.
  - *Anonimizzazione*: il fatto che porta il membro in `ANONYMIZED` (anche `member.updated:2`) ripulisce le copie `:1` come prima (F-MBR-05, Q-126: chiavi di `PersonalData.KEYS` tolte, valori noti sostituiti con «Membro anonimo») e sulle copie `:2`, che non hanno i campi personali, toglie `emailHash` (pseudonimo reversibile con `LH_PSEUDONYM_KEY`, Q-367) e lo tratta come valore inequivocabile sulle righe di altre entità; `locale`, `birthYear` e `province` (`x-lh-pii: false`) restano. `SPEC-GAP: Q-122`: `emailHash` non è in `PersonalData.KEYS`, insight lo toglie da sé. Da M8.12a i valori noti si sostituiscono solo come parole intere (forma NFC, senza distinguere maiuscole; il trattino basso separa) e si saltano solo i valori sicuri: identificativi, istanti e campi dell'envelope sempre, i campi di stato, tipo e codice quando il valore ha la forma di un codice, e nel testo libero le costanti con trattino basso (`MEMBER_REQUEST`). Ogni altro testo si ripulisce, compresi `reason` (cambi di stato, rettifiche, premi annullati, diff dell'audit) e il `subject` `email:…`/`external:…` delle azioni. Un soprannome «Anon» o «Active» non corrompe più `ANONYMIZED` o uno stato conservato (`PersonalTextScrubber` di lh-common, che da Q-404 applica la stessa regola anche in engagement e ingestion).
- **Economia del programma** (ADR-045, M13.4): `/v1/kpi/economics` (valore per livello e campagna) e metriche di costo.

**Classificazione `x-lh-class`** (`docs/18 §3.15`, F2-GRC-05; prima stesura M8.0, verificata e resa per colonna in M8.13). Tutto ciò che non è elencato è `INTERNAL`.
- `PERSONAL`: `event_store.payload` degli eventi `member.*:1` (fino alla dismissione della versione `:1`); `audit_entry.before`/`after` sui membri; `audit_entry.actor_name`.
- `CONFIDENTIAL`: `dlq_entry.payload`, `dlq_entry.error_message`, `audit_entry.*` (evidenza di sicurezza), `audit_chain_head.*` e `audit_anchor.*` (evidenza d'integrità dell'audit, M8.12a).
