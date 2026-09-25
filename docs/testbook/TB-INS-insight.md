# TB-INS — Osservabilità e audit (testbook funzionale)

Dominio **TB-INS** del testbook (`docs/16`, §10ter): event store, flusso live SSE, tracciati, KPI e storico sintetico, DLQ con *riprocessa* / *scarta*, audit delle scritture, stato della pipeline, conservazione. Servizio: `insight-service` (più il deployable consolidato `hub` per le righe tra servizi). Storie: `docs/17` E09 (US-E09-01…07), E08 (US-E08-07, US-E08-11, US-E08-12).

- **Oracolo** = specifica: `docs/servizi/insight-service.md` (qui «insight §n»), `docs/02` F-INS-01…06 e F-AUD-01, RNF-07, `docs/03` (fuso di business `Europe/Rome`), `docs/04 §5` (DLQ, correlazione), `docs/05` §1–§7 e `contracts/events/`, `docs/06` §2–§3 e §10, `docs/08` §2, BO-01, BO-22, BO-24, BO-25, BO-27, `docs/10 §9`, `docs/12` M2, le scelte di `docs/15` citate col loro `Q-nn` (Q-26, Q-104…Q-111, Q-126, Q-261). Mai «quello che il codice restituisce oggi».
- **AMBIGUO** = la specifica tace e `docs/15` non ha una scelta: la riga asserisce il comportamento attuale, marcata AMBIGUO nella descrizione e nel test (`// TESTBOOK: ambiguo, vedi <ID>`). Elenco e proposte di domanda in §15.
- **DIVERGENZA** = il codice non rispetta la specifica: il test asserisce la specifica; registro in §14. Le 55 righe divergenti (17 cause) sono state poi corrette nel codice di produzione (§14, «Correzioni»).
- **Q-Nn DECISA** = riga prima AMBIGUO: il proprietario ha scelto l'opzione conservativa (docs/15, §15); la riga asserisce la scelta.

## 0. Esecuzione

| Classe | Tipo | Aree | Dati |
|---|---|---|---|
| `TestbookInsLiveTest` (`insight.live`) | unit (surefire) | SFL, SUM | `sse-filtro.csv`, `@CsvSource` |
| `TestbookInsDlqRulesTest` (`insight.application`) | unit | DRP, DSH, DRS | `dlq-riprocessabile.csv`, `@CsvSource` |
| `TestbookInsConfigTest` | unit | CFG | `application.yml`, annotazioni |
| `TestbookInsDlqIT` | integrazione (failsafe) | DST, DRL, DNT, DIN, DAU, DCC, DLS, DIG | `dlq-stato-azione.csv`, `dlq-ruoli.csv`, `dlq-nota.csv`, `dlq-ingestion.csv`, `dlq-elenco.csv` |
| `TestbookInsTraceIT` | integrazione | TST, TTR, TOU, TLS | `tracciato-stato.csv` |
| `TestbookInsKpiIT` | integrazione | MET, DAY, KOV, KTS, KBR | `kpi-metriche.csv`, `kpi-giorno.csv` |
| `TestbookInsAuditIT` | integrazione | AIN, AFL, APG, ADT | `audit-ingest.csv`, `audit-filtri.csv` |
| `TestbookInsEventsIT` | integrazione | EVT, PIP, ING, ROL | `eventi-filtri.csv`, `ruoli-lettura.csv` |
| `TestbookInsStreamIT` | integrazione, contesto proprio | SRP, SSE | `@CsvSource` |
| `TestbookInsStoreIT` | integrazione, contesto e DB propri | RET, RST, SYN | `@CsvSource` |
| `TestbookInsHubIT` (`deploy/hub`) | integrazione nell'hub consolidato (`inproc`) | HUB | — |

- Test in `services/insight-service/src/test/java/io/loyaltyhub/insight/` (più `live/` e `application/` per la logica pura), CSV in `src/test/resources/testbook/ins/`; riga di tabella = un caso JUnit il cui nome inizia con `[<ID>]`.
- **Contesti**: le cinque classi `TestbookIns{Dlq,Trace,Kpi,Audit,Events}IT` estendono `TestbookInsBase` e condividono **un solo contesto Spring** (EmbeddedKafka + Postgres Zonky, profilo `demo`, job di retention spento). `TestbookInsStreamIT` ha un contesto proprio (il ring buffer SSE deve contenere solo gli eventi del caso); `TestbookInsStoreIT` ha contesto **e database** propri (svuota le tabelle, invoca il job).
- **Orologio**: il bean `Clock` è sostituito da `TestbookClock` (fermo quando il caso lo imposta). I tracciati si provano scrivendo gli eventi nell'event store con l'istante d'arrivo (`received_at`) del caso: i 5 s di quiete non dipendono dal tempo reale. Kafka rifiuta timestamp di record oltre 1 h nel futuro: i casi DLQ con data fissa usano giorni passati.
- **Ingestion** è uno stub HTTP nel contesto condiviso: ogni caso sceglie la risposta al re-invio (stato HTTP, corpo, connessione chiusa).
- **Attese**: sempre con timeout; l'arrivo di un messaggio precedente si prova con una **sentinella** pubblicata dopo sulla stessa partizione (partizione unica). Nessuna pausa usata come oracolo.
- Comando: `./mvnw -q -pl services/insight-service -am verify -Dtest='Testbook*' -Dit.test='Testbook*' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`; hub: stesso comando con `-pl deploy/hub -Dit.test=TestbookInsHubIT`; rapporto: `TESTBOOK_WEB=0 bash scripts/testbook.sh`.

## 1. Inventario delle regole

| Regola | Testo (sintesi) | Rif. spec | Aree |
|---|---|---|---|
| R-01 | Ingest: un consumer per topic (gruppo `lh-insight`, tutti e 5), famiglia dal topic, tipo breve senza prefisso, attributi CloudEvents conservati | insight §2, §4, §5; docs/05 §1–§2 | ING, CFG |
| R-02 | Idempotenza su `event_id`: un duplicato non crea righe, non gonfia metriche né statistiche, non torna nel flusso live | insight §5, §7; RNF-03 | ING, MET, PIP |
| R-03 | Ricerca eventi: filtri `topic, family, type, memberId, correlationId, source, from, to, q`; dettaglio col payload; 404 sconosciuto; 400 istante non valido | insight §3; docs/06 §2 | EVT |
| R-04 | Elenchi paginati `{items, page}` con `page/size`, `size` ≤ 100 | docs/06 §2; CLAUDE.md §5 | DLS, APG, EVT, TLS |
| R-05 | Letture aperte a tutte le personas (anche senza `X-LH-Actor`) | docs/08 §2; docs/06 §3 | ROL |
| R-06 | Flusso live SSE: evento `lh-event` `{eventId, topic, family, shortType, memberId, correlationId, time, summary}`; filtri `topics, types, memberId, correlationId`; CORS per il browser | insight §3; ADR-020 | SSE, SFL |
| R-07 | Ripresa con `Last-Event-ID`: rinvio degli ultimi ≤ 200 persi; nessun evento perso tra due collegamenti (20 eventi) | insight §3, §7 | SRP |
| R-08 | `heartbeat` ogni 15 s; client lento (coda > 500) o chiuso ⇒ disconnesso, gli altri continuano | insight §3, §5; Q-26 | SSE |
| R-09 | Sintesi per tipo («tabella in codice», es. «+162 PTS · Acquisto»), mai rotta da un payload inatteso | insight §5 | SUM, TTR |
| R-10 | Tracciato: albero per `causation_id`, nodi `{eventId, family, shortType, service, time, offsetMs, parentEventId, summary}`, `durationMs` | insight §3, §5; F-INS-02; BO-25 | TTR |
| R-11 | Stato del tracciato: `FAILED` se esiste una voce DLQ (OPEN o DISCARDED, non REPROCESSED — Q-106); `COMPLETE` se nessun nuovo evento da 5 s; altrimenti `IN_PROGRESS` | insight §3, §5; Q-106 | TST |
| R-12 | Esito del tracciato: `points[]` per valuta (dai fatti di accredito), `tierChange`, contatori `messages`, `coupons`, `plays`, `dlq` | insight §3, §7; BO-25; docs/05 §5 | TOU |
| R-13 | Elenco tracciati: uno per `correlationId`, filtri `memberId, from, to`, riga con esito sintetico «+162 PTS · +130 STS · tier GOLD» | insight §3; BO-25 | TLS |
| R-14 | Metriche giornaliere per tipo di evento: `actions` (dim `source`, `type`), `points_earned/spent/expired` (dim `currency`), `points_by_campaign` (dim `campaign`), `members_new`, `members_active`, `redemptions` (dim `status`, `reward`), `plays`, `wins`, `tier_changes` (dim `direction`), `messages`, `dlq` | insight §2, §5 | MET |
| R-15 | Giorno di business in `Europe/Rome` (mezzanotte, ora legale e solare, fine mese, 29 febbraio) per le metriche e per «oggi» | docs/03 (convenzioni); insight §2 | DAY |
| R-16 | Overview: totali nel periodo, `membersTotal`, `membersActive30d`, delta sul periodo precedente; periodo 7/30/90 (default 30) | insight §3; BO-01 | KOV |
| R-17 | Serie `day|week`, ripartizione top N; parametri errati ⇒ 400 | insight §3; BO-01; docs/06 §2 | KTS, KBR |
| R-18 | Storico sintetico: 90 giorni `synthetic=true`, stagionalità (+35 % sab/dom), rumore ±12 %, trend +0,4 %/g, picco al giorno −30, seme fisso; dati reali sommati; baseline di docs/10 §9 (anche richieste, giocate, vincite; membri 3 100 → 3 480 + 12 reali) | insight §5, §7; F-INS-04; docs/10 §9 | SYN, KOV-014/015 |
| R-19 | Audit: una voce per evento `io.loyaltyhub.audit.entry`, chi (`lhactor` → ruolo/nome), cosa (servizio, oggetto, azione ∈ 7 valori), quando, prima/dopo coi soli campi cambiati; idempotente | docs/05 §6; F-AUD-01; insight §2 | AIN, HUB |
| R-20 | Ogni scrittura da backoffice e ogni job/reset produce audit (CREATE, UPDATE, DELETE, TRANSITION, ADJUST, JOB, RESET), override ADMIN marcato; nessuna voce per una scrittura rifiutata | docs/06 §3, §10; docs/08 §2, BO-22; F-AUD-01 | HUB, DAU, RST |
| R-21 | Audit: filtri `actor, role, service, entityType, entityId, action, from, to`; dettaglio con diff; 404 | insight §3; BO-22 | AFL, ADT |
| R-22 | DLQ: record di `lh.dlq.v1` ⇒ voce con header `lh-*`; una sola voce aperta per (evento, consumer); record fuori dall'event store e nel flusso live | insight §2, §5; docs/04 §5; Q-111 | DIG |
| R-23 | DLQ *riprocessa*: solo azioni (effetti/fatti ⇒ 409 `NOT_REPROCESSABLE`), re-invio a ingestion con lo stesso `id` e `X-LH-Reprocess`, poi `REPROCESSED` | insight §5; ADR-002 ecc. 1; Q-104 | DST, DIN, DRP, DRS |
| R-24 | DLQ *scarta* con nota obbligatoria per ogni voce (Q-110); seconda chiusura ⇒ 409 `DLQ_NOT_OPEN` (Q-107); solo ADMIN (`dlq.handle`); audit della chiusura (Q-109) | insight §3, §5; docs/08 §2; Q-107, Q-109, Q-110 | DST, DRL, DNT, DAU, DCC |
| R-25 | DLQ elenco: filtri `status, consumer, errorCode`; stato `OPEN` in API («Nuova» in BO-27, Q-105); dettaglio con errore, stack, payload | insight §3; BO-27; Q-105 | DLS |
| R-26 | Stato pipeline: per topic ultimo evento, volumi 1 h/24 h, ritardo stimato; per servizio ultimo fatto | insight §2, §3; F-INS-06; BO-24 | PIP |
| R-27 | Conservazione (RNF-07): event store 14 giorni o 200 000 righe (il minore), payload troncato a 8 KB, audit 180 giorni, metriche illimitate, job orario | insight §5; RNF-07 | RET, CFG |
| R-28 | Reset demo: svuota event store, audit, DLQ e rigenera lo storico sintetico; solo ADMIN; audit `RESET` | insight §6; docs/06 §10 | RST |

## 2. Rami del codice → regola

| Punto di decisione (file) | Rami | Regola |
|---|---|---|
| `TopicListeners` | 5 listener, famiglia per topic; DLQ: eccezione su record nato dalla DLQ stessa ⇒ scartato (ciclo evitato) | R-01, R-22 (il ramo «ciclo» non è provato: richiede un guasto del DB durante l'ingest) |
| `EventIngestService#ingest` | nuovo / duplicato; famiglia AUDIT ⇒ voce di audit; FACT di anonimizzazione ⇒ redazione (F-MBR-05, TB-GOV) | R-01, R-02, R-19 |
| `EventIngestService#updateMetrics` | ACTION ⇒ `actions` (+ fonte); `DLQ` (irraggiungibile, Q-111); `wallet.points.earned/spent/refunded/expired`, `reward.redemption.confirmed`, `member.registered`, `tier.upgraded`/`tier.changed`; altri ⇒ nulla; giorno in UTC | R-14, R-15 (**divergenze**); `refunded` negativo = ramo senza specifica |
| `EventIngestService#recordAudit` | `data` nullo ⇒ nessuna voce (ramo senza specifica); attore `RUOLO:nome` / senza `:` / `:` in testa / in coda | R-19 |
| `EventIngestService#ingestDlq` | JSON o `{raw}`; id assente ⇒ `dlq-<topic>-<p>-<o>`; famiglia dal tipo o dal topic d'origine o `UNKNOWN`; header `lh-*` o `kafka_dlt-*` (ramo senza specifica); errorCode dalla classe; consumer `unknown`; tentativi non numerici; stack > 16 righe; voce nuova / già aperta | R-22 |
| `DlqService#reprocess` | 404; non OPEN ⇒ 409 `DLQ_NOT_OPEN`; non ACTION ⇒ 409 `NOT_REPROCESSABLE`; ingestion ≠ `ACCEPTED` ⇒ 409 `REPROCESS_REJECTED` (ramo senza specifica); chiusura concorrente ⇒ 409 | R-23, R-24 |
| `DlqService#discard` | nota assente/vuota ⇒ 422 `NOTE_REQUIRED` (prima di stato ed esistenza); 404; 409 | R-24 |
| `IngestionClient#resend` | corpo vuoto ⇒ 503; 4xx ⇒ stesso stato `INGESTION_REFUSED` (ramo senza specifica); 5xx ⇒ 503; irraggiungibile ⇒ 503 | R-23 |
| `DlqController` / `DlqRepository` | `size` in [1, 200], `page` ≥ 0; filtri, stato in maiuscolo | R-04 (**divergenza** sul massimo), R-25 |
| `DlqEntry#reprocessable`, `#shortType` | OPEN ∧ ACTION; prefisso per famiglia | R-23 |
| `TraceService#trace` | nessun dato ⇒ `IN_PROGRESS` vuoto (ramo senza specifica); famiglia DLQ in store; voce non REPROCESSED ⇒ FAILED; quiete ≥ 5 s ⇒ COMPLETE; memberId da eventi o DLQ; corsia da `source`/famiglia/consumer | R-10, R-11 |
| `TraceService#outcomeOf` | accrediti per valuta, cambio tier (con ripieghi `from/to/tier`), contatori messaggi/coupon/giocate fissi a 0 | R-12 (**divergenza**) |
| `TracesController` | `limit` in [1, 100]; istanti non ISO ⇒ 400 | R-13, R-04 |
| `LiveEventHub` | filtro (4 condizioni); replay: id assente/vuoto ⇒ nulla, trovato ⇒ successivi, sconosciuto ⇒ tutto il buffer (ramo senza specifica); buffer 200; heartbeat 15 s; invio fallito ⇒ rimozione; un solo thread d'invio (nessuna coda per client) | R-06…R-08 (**divergenza** R-08) |
| `EventSummaries` | tabella per tipo, ripieghi | R-09 |
| `KpiService` | finestra (`days` < 1 ⇒ 1); delta con precedente 0 ⇒ `pct` nullo; `membersTotal = 12 + registrati`; settimana ISO; `today()` in UTC | R-15, R-16, R-17, R-18 |
| `MetricRepository` | incremento, sintetico, totale, serie, top N, ultimo valore | R-14, R-16, R-17 |
| `KpiController` | `metric` obbligatorio (assente ⇒ 500 dal gestore comune), `granularity` diverso da `week` ⇒ day | R-17 (**divergenza**) |
| `AuditController` / `AuditRepository` | `limit` in [1, 200], `page`; filtri per uguaglianza; `from/to` inclusi; istanti non ISO ⇒ 400; 404 | R-21, R-04 (**divergenza**) |
| `EventsController` / `EventStoreRepository` | `limit` in [1, 200]; famiglia in maiuscolo; `q` ILIKE sul payload; 404; 400 | R-03, R-04 (**divergenza**) |
| `PipelineController` / `TopicStatRepository` | conteggio totale, `GREATEST` sull'ultimo evento, offset per partizione | R-26 (**divergenza**: volumi 1 h/24 h, ritardo, per servizio assenti) |
| `RetentionJob` + repository | per età (`<`), per numero (`OFFSET`), audit per età | R-27 (**divergenza**: troncamento 8 KB assente) |
| `InsightReset`, `InsightSyntheticSeeder` | svuotamento; generatore con seme; ripartizioni col resto al primo | R-18, R-28 (**divergenza**: metriche e RESET) |

Rami senza specifica (righe AMBIGUO): 13 voci — 404 vs tracciato vuoto, `REPROCESS_REJECTED`, errori HTTP di ingestion ripropagati, header `kafka_dlt-*`, `errorCode` dalla classe, consumer `unknown`, famiglia `AUDIT`/`UNKNOWN` non riprocessabile, rinvio dell'intero buffer per id sconosciuto, `refunded` negativo, `data` nullo dell'audit, attore senza ruolo, `days`/`limit`/`size` < 1 portati a 1, `granularity` sconosciuta.

## 3. DLQ — riprocessa e scarta

**Regole**: R-22…R-25. **Domini**: stato ∈ {`OPEN`, `REPROCESSED`, `DISCARDED`, inesistente}; azione ∈ {riprocessa, scarta}; famiglia ∈ {`ACTION`, `EFFECT`, `FACT`, `AUDIT`, `UNKNOWN`}; ruolo ∈ {ADMIN, MARKETING, LEGAL, CARE, ANALYST, assente, sconosciuto, minuscolo}; nota ∈ {assente, `{}`, `null`, `""`, spazi, 1 carattere, con spazi attorno, 2000 caratteri, unicode, JSON malformato}; risposta di ingestion ∈ {202/200 `ACCEPTED`, `DUPLICATE`, `REJECTED`, `UNMATCHED`, senza status, corpo vuoto, 400, 403, 500, 503, connessione chiusa}.

**Strategia**: macchina a stati **completa** stato × azione × famiglia con ADMIN e nota valida (3 × 2 × 5 = 30) + inesistente × 2 azioni + 2 righe sull'ordine dei controlli (DST, 34 righe). Ruoli: tabella completa ruolo × azione su un'azione aperta (7 × 2 + minuscolo = 15). Nota: tabella completa classe × famiglia per *scarta* (9 × 5 = 45 ≤ 64) + 5 righe su *riprocessa* e JSON malformato. Esito di ingestion: una riga per classe di risposta (12) + contenuto e intestazioni del re-invio + nuovo tentativo. Riprocessabilità: tabella completa famiglia × stato (15, unit). Su una voce già chiusa di effetto/fatto la specifica ammette entrambi i 409 (`DLQ_NOT_OPEN` per Q-107, `NOT_REPROCESSABLE` per §5): la riga accetta l'uno o l'altro.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DST-001 | riprocessa un'azione aperta | re-invio a ingestion e REPROCESSED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-002 | riprocessa una voce EFFECT aperta | 409 NOT_REPROCESSABLE e resta OPEN | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-003 | riprocessa una voce FACT aperta | 409 NOT_REPROCESSABLE e resta OPEN | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-004 | riprocessa una voce AUDIT aperta (AMBIGUO) | 409 NOT_REPROCESSABLE | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-005 | riprocessa una voce UNKNOWN aperta (AMBIGUO) | 409 NOT_REPROCESSABLE | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-006 | scarta con nota una voce ACTION aperta | DISCARDED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-007 | scarta con nota una voce EFFECT aperta | DISCARDED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-008 | scarta con nota una voce FACT aperta | DISCARDED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-009 | scarta con nota una voce AUDIT aperta | DISCARDED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-010 | scarta con nota una voce UNKNOWN aperta | DISCARDED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-011 | riprocessa una voce ACTION gia REPROCESSED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-012 | riprocessa una voce EFFECT gia REPROCESSED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-013 | riprocessa una voce FACT gia REPROCESSED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-014 | riprocessa una voce AUDIT gia REPROCESSED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-015 | riprocessa una voce UNKNOWN gia REPROCESSED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-016 | scarta una voce ACTION gia REPROCESSED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-017 | scarta una voce EFFECT gia REPROCESSED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-018 | scarta una voce FACT gia REPROCESSED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-019 | scarta una voce AUDIT gia REPROCESSED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-020 | scarta una voce UNKNOWN gia REPROCESSED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-021 | riprocessa una voce ACTION gia DISCARDED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-022 | riprocessa una voce EFFECT gia DISCARDED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-023 | riprocessa una voce FACT gia DISCARDED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-024 | riprocessa una voce AUDIT gia DISCARDED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-025 | riprocessa una voce UNKNOWN gia DISCARDED | 409 e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-026 | scarta una voce ACTION gia DISCARDED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-027 | scarta una voce EFFECT gia DISCARDED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-028 | scarta una voce FACT gia DISCARDED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-029 | scarta una voce AUDIT gia DISCARDED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-030 | scarta una voce UNKNOWN gia DISCARDED | 409 DLQ_NOT_OPEN e voce invariata | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-031 | riprocessa un id inesistente | 404 | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-032 | scarta con nota un id inesistente | 404 | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-033 | scarta senza nota una voce gia DISCARDED (AMBIGUO ordine dei controlli) | 422 NOTE_REQUIRED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |
| TB-INS-DST-034 | scarta senza nota un id inesistente (AMBIGUO ordine dei controlli) | 422 NOTE_REQUIRED | insight §5; Q-107; Q-110 | `TestbookInsDlqIT` · `dlq-stato-azione.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DRL-001 | riprocessa un'azione aperta come ADMIN | 200 | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-002 | riprocessa un'azione aperta come MARKETING | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-003 | riprocessa un'azione aperta come LEGAL | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-004 | riprocessa un'azione aperta come CARE | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-005 | riprocessa un'azione aperta come ANALYST | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-006 | riprocessa un'azione aperta come senza X-LH-Actor | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-007 | riprocessa un'azione aperta come GUEST:ospite (ruolo sconosciuto = ANALYST per Q-261) | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-008 | scarta un'azione aperta come ADMIN | 200 | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-009 | scarta un'azione aperta come MARKETING | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-010 | scarta un'azione aperta come LEGAL | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-011 | scarta un'azione aperta come CARE | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-012 | scarta un'azione aperta come ANALYST | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-013 | scarta un'azione aperta come senza X-LH-Actor | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-014 | scarta un'azione aperta come GUEST:ospite (ruolo sconosciuto = ANALYST per Q-261) | 403 e voce invariata senza re-invio | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |
| TB-INS-DRL-015 | scarta come admin minuscolo (AMBIGUO | ruolo letto senza maiuscole): 200 | docs/08 §2 `dlq.handle`; docs/06 §3; Q-261 | `TestbookInsDlqIT` · `dlq-ruoli.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DNT-001 | scarta una voce ACTION con senza corpo | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-002 | scarta una voce ACTION con corpo vuoto {} | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-003 | scarta una voce ACTION con note null | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-004 | scarta una voce ACTION con note vuota | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-005 | scarta una voce ACTION con note di soli spazi | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-006 | scarta una voce ACTION con note di 1 carattere | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-007 | scarta una voce ACTION con note con spazi attorno (salvata senza) | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-008 | scarta una voce ACTION con note di 2000 caratteri | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-009 | scarta una voce ACTION con note con accenti ed emoji | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-010 | scarta una voce EFFECT con senza corpo | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-011 | scarta una voce EFFECT con corpo vuoto {} | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-012 | scarta una voce EFFECT con note null | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-013 | scarta una voce EFFECT con note vuota | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-014 | scarta una voce EFFECT con note di soli spazi | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-015 | scarta una voce EFFECT con note di 1 carattere | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-016 | scarta una voce EFFECT con note con spazi attorno (salvata senza) | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-017 | scarta una voce EFFECT con note di 2000 caratteri | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-018 | scarta una voce EFFECT con note con accenti ed emoji | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-019 | scarta una voce FACT con senza corpo | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-020 | scarta una voce FACT con corpo vuoto {} | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-021 | scarta una voce FACT con note null | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-022 | scarta una voce FACT con note vuota | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-023 | scarta una voce FACT con note di soli spazi | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-024 | scarta una voce FACT con note di 1 carattere | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-025 | scarta una voce FACT con note con spazi attorno (salvata senza) | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-026 | scarta una voce FACT con note di 2000 caratteri | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-027 | scarta una voce FACT con note con accenti ed emoji | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-028 | scarta una voce AUDIT con senza corpo | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-029 | scarta una voce AUDIT con corpo vuoto {} | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-030 | scarta una voce AUDIT con note null | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-031 | scarta una voce AUDIT con note vuota | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-032 | scarta una voce AUDIT con note di soli spazi | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-033 | scarta una voce AUDIT con note di 1 carattere | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-034 | scarta una voce AUDIT con note con spazi attorno (salvata senza) | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-035 | scarta una voce AUDIT con note di 2000 caratteri | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-036 | scarta una voce AUDIT con note con accenti ed emoji | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-037 | scarta una voce UNKNOWN con senza corpo | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-038 | scarta una voce UNKNOWN con corpo vuoto {} | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-039 | scarta una voce UNKNOWN con note null | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-040 | scarta una voce UNKNOWN con note vuota | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-041 | scarta una voce UNKNOWN con note di soli spazi | 422 NOTE_REQUIRED e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-042 | scarta una voce UNKNOWN con note di 1 carattere | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-043 | scarta una voce UNKNOWN con note con spazi attorno (salvata senza) | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-044 | scarta una voce UNKNOWN con note di 2000 caratteri | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-045 | scarta una voce UNKNOWN con note con accenti ed emoji | 200 DISCARDED | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-046 | riprocessa senza corpo | 200 senza nota | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-047 | riprocessa con nota facoltativa | salvata senza spazi | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-048 | riprocessa con nota di soli spazi | nessuna nota salvata | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-049 | riprocessa con JSON malformato | 400 e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |
| TB-INS-DNT-050 | scarta con JSON malformato | 400 e resta OPEN | insight §5; Q-110; docs/06 §2 | `TestbookInsDlqIT` · `dlq-nota.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DIN-001 | ingestion 202 ACCEPTED | 200 REPROCESSED | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-002 | ingestion 202 DUPLICATE (AMBIGUO) | 409 REPROCESS_REJECTED e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-003 | ingestion 202 REJECTED con rejectCode (AMBIGUO) | 409 REPROCESS_REJECTED e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-004 | ingestion 202 UNMATCHED (AMBIGUO) | 409 REPROCESS_REJECTED e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-005 | ingestion 202 senza status (AMBIGUO) | 409 REPROCESS_REJECTED e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-006 | ingestion 200 ACCEPTED | 200 REPROCESSED | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-007 | ingestion 202 senza corpo (AMBIGUO) | 503 DEPENDENCY_UNAVAILABLE e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-008 | ingestion 400 (AMBIGUO) | 400 INGESTION_REFUSED e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-009 | ingestion 403 (AMBIGUO) | 403 INGESTION_REFUSED e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-010 | ingestion 500 | 503 DEPENDENCY_UNAVAILABLE e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-011 | ingestion 503 (addormentata) | 503 DEPENDENCY_UNAVAILABLE e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-012 | ingestion chiude la connessione (irraggiungibile) | 503 DEPENDENCY_UNAVAILABLE e resta OPEN | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` · `dlq-ingestion.csv` |
| TB-INS-DIN-013 | il re-invio porta lo stesso id e i soli attributi d'ingresso (niente lh*, dataschema) | come nella descrizione (asserito dal caso) | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` |
| TB-INS-DIN-014 | il re-invio è POST /v1/events con X-LH-Actor dell'ADMIN e X-LH-Reprocess = id della voce (Q-104) | come nella descrizione (asserito dal caso) | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` |
| TB-INS-DIN-015 | dopo un rifiuto di ingestion la voce resta aperta e un nuovo riprocessa riuscito la chiude | come nella descrizione (asserito dal caso) | insight §5; ADR-002 ecc. 1; Q-104; docs/06 §2 | `TestbookInsDlqIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DRP-001 | voce ACTION OPEN | riprocessabile = true | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-002 | voce ACTION REPROCESSED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-003 | voce ACTION DISCARDED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-004 | voce EFFECT OPEN | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-005 | voce EFFECT REPROCESSED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-006 | voce EFFECT DISCARDED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-007 | voce FACT OPEN | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-008 | voce FACT REPROCESSED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-009 | voce FACT DISCARDED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-010 | voce AUDIT OPEN | riprocessabile = false (AMBIGUO) | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-011 | voce AUDIT REPROCESSED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-012 | voce AUDIT DISCARDED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-013 | voce UNKNOWN OPEN | riprocessabile = false (AMBIGUO) | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-014 | voce UNKNOWN REPROCESSED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |
| TB-INS-DRP-015 | voce UNKNOWN DISCARDED | riprocessabile = false | insight §5; Q-106 | `TestbookInsDlqRulesTest` · `dlq-riprocessabile.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DSH-001 | azione | tipo breve senza prefisso | insight §2; docs/05 §2 | `TestbookInsDlqRulesTest` |
| TB-INS-DSH-002 | audit | tipo breve entry | insight §2; docs/05 §2 | `TestbookInsDlqRulesTest` |
| TB-INS-DSH-003 | famiglia UNKNOWN con tipo estraneo | tipo intero | insight §2; docs/05 §2 | `TestbookInsDlqRulesTest` |
| TB-INS-DSH-004 | senza tipo | tipo breve assente | insight §2; docs/05 §2 | `TestbookInsDlqRulesTest` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DRS-001 | envelope completo | re-inviati solo specversion, id, source, type, subject, time, data (stesso id) | insight §5; docs/05 §2; Q-104 | `TestbookInsDlqRulesTest` |
| TB-INS-DRS-002 | attributi nulli o assenti | non inviati | insight §5; docs/05 §2; Q-104 | `TestbookInsDlqRulesTest` |
| TB-INS-DRS-003 | payload assente | evento vuoto (nessun errore) | insight §5; docs/05 §2; Q-104 | `TestbookInsDlqRulesTest` |
| TB-INS-DRS-004 | payload grezzo non JSON ({raw}) | nessun attributo d'ingresso | insight §5; docs/05 §2; Q-104 | `TestbookInsDlqRulesTest` |

**Audit e concorrenza** (R-24, Q-109): l'assenza di una voce si prova con una barriera (una chiusura riuscita successiva: quando il suo audit è arrivato anche i precedenti lo sono).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DAU-001 | riprocessa riuscito | audit TRANSITION su DlqEntry con attore ADMIN e stato OPEN → REPROCESSED | Q-109; F-AUD-01; docs/05 §6 | `TestbookInsDlqIT` |
| TB-INS-DAU-002 | scarta riuscito | audit TRANSITION con la nota nel dopo | Q-109; F-AUD-01; docs/05 §6 | `TestbookInsDlqIT` |
| TB-INS-DAU-003 | scarta rifiutato per ruolo (403) | nessuna voce di audit | Q-109; F-AUD-01; docs/05 §6 | `TestbookInsDlqIT` |
| TB-INS-DAU-004 | seconda chiusura (409 DLQ_NOT_OPEN) | nessuna seconda voce di audit | Q-109; F-AUD-01; docs/05 §6 | `TestbookInsDlqIT` |
| TB-INS-DAU-005 | riprocessa rifiutato da ingestion (409 REPROCESS_REJECTED) | nessuna voce di audit | Q-109; F-AUD-01; docs/05 §6 | `TestbookInsDlqIT` |
| TB-INS-DAU-006 | scarta senza nota (422 NOTE_REQUIRED) | nessuna voce di audit | Q-109; F-AUD-01; docs/05 §6 | `TestbookInsDlqIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DCC-001 | due scarta simultanei della stessa voce | uno 200, l'altro 409 DLQ_NOT_OPEN | insight §5; Q-107 | `TestbookInsDlqIT` |
| TB-INS-DCC-002 | riprocessa e scarta simultanei della stessa azione | una sola chiusura riesce | insight §5; Q-107 | `TestbookInsDlqIT` |

**Elenco** (R-04, R-25): 5 voci per caso con consumer proprio (stati OPEN, OPEN, REPROCESSED, DISCARDED, OPEN; codici E_A/E_B), indici = ordine d'inserimento; `size` ai limiti 0, 1, 2, 100, 101, 200; `page` −1, 0…3, non numerica.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DLS-001 | solo consumer | voci #4, #3, #2, #1, #0 (dalla più recente); totalItems 5, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-002 | status=OPEN | voci #4, #1, #0 (dalla più recente); totalItems 3, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-003 | status=open minuscolo (Q-317 DECISA) | voci #4, #1, #0 (dalla più recente); totalItems 3, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-004 | status=REPROCESSED | voci #2 (dalla più recente); totalItems 1, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-005 | status=DISCARDED | voci #3 (dalla più recente); totalItems 1, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-006 | status=NEW (nome di BO-27; API con OPEN per Q-105) | voci nessuna (dalla più recente); totalItems 0, number 0, totalPages 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-007 | status sconosciuto (Q-317 DECISA, come `NEW` di Q-105) | voci nessuna (dalla più recente); totalItems 0, number 0, totalPages 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-008 | errorCode=E_A | voci #3, #2, #0 (dalla più recente); totalItems 3, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-009 | errorCode=E_B e status=OPEN | voci #4, #1 (dalla più recente); totalItems 2, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-010 | errorCode inesistente | voci nessuna (dalla più recente); totalItems 0, number 0, totalPages 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-011 | size=2 page=0 | voci #4, #3 (dalla più recente); totalItems 5, number 0, size 2, totalPages 3 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-012 | size=2 page=1 | voci #2, #1 (dalla più recente); totalItems 5, number 1, size 2, totalPages 3 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-013 | size=2 page=2 (ultima e parziale) | voci #0 (dalla più recente); totalItems 5, number 2, size 2, totalPages 3 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-014 | size=2 page=3 (oltre l'ultima) | voci nessuna (dalla più recente); totalItems 5, number 3, size 2, totalPages 3 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-015 | size=1 | voci #4 (dalla più recente); totalItems 5, number 0, size 1, totalPages 5 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-016 | size=0 (Q-317 DECISA) | 400 `BAD_REQUEST` | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-017 | size=100 (massimo di docs/06) | voci #4, #3, #2, #1, #0 (dalla più recente); totalItems 5, number 0, size 100, totalPages 1 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-018 | size=101 | **DIVERGENZA** — voci #4, #3, #2, #1, #0 (dalla più recente); totalItems 5, number 0, size 100, totalPages 1 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-019 | size=200 | **DIVERGENZA** — voci #4, #3, #2, #1, #0 (dalla più recente); totalItems 5, number 0, size 100, totalPages 1 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-020 | page=-1 (Q-317 DECISA) | 400 `BAD_REQUEST` | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-021 | page non numerica | HTTP 400 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-022 | size non numerica | HTTP 400 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-023 | filtri vuoti (status= errorCode=) ignorati | voci #4, #3, #2, #1, #0 (dalla più recente); totalItems 5, number 0 | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` · `dlq-elenco.csv` |
| TB-INS-DLS-024 | dettaglio GET /v1/dlq/{id} | messaggio d'errore, stack abbreviato e payload (BO-27) | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` |
| TB-INS-DLS-025 | dettaglio di un id inesistente | 404 NOT_FOUND | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` |
| TB-INS-DLS-026 | riga dell'elenco con i campi di BO-27 | quando, consumer, topic d'origine, tipo, errorCode, tentativi, stato | insight §3; docs/06 §2; BO-27; Q-105 | `TestbookInsDlqIT` |

**Ingest dei record DLQ** (R-22): header `lh-*` completi, solo `kafka_dlt-*`, parziali, valore non JSON o vuoto, tipo e topic d'origine in conflitto o assenti, subject non di membro, riletture, stack lungo.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DIG-001 | record con intestazioni lh-* complete | voce OPEN con topic, consumer, errore, tentativi, membro, correlazione | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-002 | solo intestazioni kafka_dlt-* del recoverer standard (AMBIGUO, ramo senza specifica) | campi letti da queste | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-003 | senza lh-error-code (AMBIGUO) | errorCode = nome semplice della classe d'errore | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-004 | senza consumer (AMBIGUO) | consumer = unknown | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-005 | tentativi non numerici e retryable assente (AMBIGUO) | campi nulli | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-006 | valore non JSON | payload {raw}, famiglia dal topic d'origine, id sintetico dlq-<topic>-<partizione>-<offset> | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-007 | valore vuoto | payload {raw: ""}, voce registrata | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-008 | tipo non riconoscibile con topic d'origine lh.facts.v1 | famiglia FACT | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-009 | né tipo né topic d'origine | famiglia UNKNOWN, non riprocessabile | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-010 | tipo d'azione arrivato da un topic diverso | la famiglia viene dal tipo (ACTION, riprocessabile) | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-011 | subject member:<id> ⇒ memberId; subject di configurazione ⇒ nessun membro | come nella descrizione (asserito dal caso) | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-012 | record DLQ riletto (stesso evento e consumer) | una sola voce aperta, metrica dlq e statistica del topic contate una volta | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-013 | stesso evento fallito in due consumer | due voci distinte | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-014 | nuovo fallimento dopo la chiusura della voce | si apre una seconda voce OPEN | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-015 | stack di 30 righe | conservate le prime 16 più il segno di troncamento | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-016 | il record DLQ non entra nell'event store (Q-111) | nessuna riga per il suo id, nessuna famiglia DLQ | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-017 | il record DLQ va nel flusso live | lh-event famiglia DLQ con id della voce e sintesi «DLQ · consumer · codice» | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |
| TB-INS-DIG-018 | nuova voce | metrica dlq +1 nel giorno della voce e statistica di lh.dlq.v1 +1 | insight §2, §5; docs/04 §5; Q-111 | `TestbookInsDlqIT` |

## 4. Tracciati

**Regole**: R-10…R-13. **Domini**: voci DLQ ∈ {nessuna, OPEN, DISCARDED, REPROCESSED, REPROCESSED+OPEN, REPROCESSED+DISCARDED}; età dell'ultimo arrivo ∈ {0, 4 999, 5 000, 5 001, 60 000 ms} (limiti del 5 s: −1, =, +1), più età negativa (orologio indietro) e `time` di business diverso dall'arrivo.

**Strategia**: tabella **completa** voci DLQ × età (6 × 5 = 30), più 6 righe di casi limite (tracciato sconosciuto, solo DLQ, riga di famiglia DLQ, quiete sull'arrivo, età negativa, DLQ più recente dell'ultimo evento). Albero, esito ed elenco: una riga per forma (catena, ramificazione, orfano, corsie, nodo DLQ) e per tipo di fatto dell'esito.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-TST-001 | nessuna voce DLQ e ultimo evento da 0 ms | IN_PROGRESS | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-002 | nessuna voce DLQ e ultimo evento da 4999 ms | IN_PROGRESS | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-003 | nessuna voce DLQ e ultimo evento da 5000 ms | COMPLETE | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-004 | nessuna voce DLQ e ultimo evento da 5001 ms | COMPLETE | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-005 | nessuna voce DLQ e ultimo evento da 60000 ms | COMPLETE | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-006 | voce DLQ OPEN e ultimo evento da 0 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-007 | voce DLQ OPEN e ultimo evento da 4999 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-008 | voce DLQ OPEN e ultimo evento da 5000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-009 | voce DLQ OPEN e ultimo evento da 5001 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-010 | voce DLQ OPEN e ultimo evento da 60000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-011 | voce DLQ DISCARDED e ultimo evento da 0 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-012 | voce DLQ DISCARDED e ultimo evento da 4999 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-013 | voce DLQ DISCARDED e ultimo evento da 5000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-014 | voce DLQ DISCARDED e ultimo evento da 5001 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-015 | voce DLQ DISCARDED e ultimo evento da 60000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-016 | voce DLQ REPROCESSED e ultimo evento da 0 ms | IN_PROGRESS | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-017 | voce DLQ REPROCESSED e ultimo evento da 4999 ms | IN_PROGRESS | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-018 | voce DLQ REPROCESSED e ultimo evento da 5000 ms | COMPLETE | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-019 | voce DLQ REPROCESSED e ultimo evento da 5001 ms | COMPLETE | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-020 | voce DLQ REPROCESSED e ultimo evento da 60000 ms | COMPLETE | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-021 | una REPROCESSED e una OPEN e ultimo evento da 0 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-022 | una REPROCESSED e una OPEN e ultimo evento da 4999 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-023 | una REPROCESSED e una OPEN e ultimo evento da 5000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-024 | una REPROCESSED e una OPEN e ultimo evento da 5001 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-025 | una REPROCESSED e una OPEN e ultimo evento da 60000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-026 | una REPROCESSED e una DISCARDED e ultimo evento da 0 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-027 | una REPROCESSED e una DISCARDED e ultimo evento da 4999 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-028 | una REPROCESSED e una DISCARDED e ultimo evento da 5000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-029 | una REPROCESSED e una DISCARDED e ultimo evento da 5001 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-030 | una REPROCESSED e una DISCARDED e ultimo evento da 60000 ms | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` · `tracciato-stato.csv` |
| TB-INS-TST-031 | correlationId sconosciuto (Q-318 DECISA) | 404 `NOT_FOUND` (nessun tracciato vuoto inventato) | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` |
| TB-INS-TST-032 | solo una voce DLQ aperta (eventi già potati) | FAILED, un nodo DLQ, membro dalla voce | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` |
| TB-INS-TST-033 | riga di famiglia DLQ nell'event store (ramo senza specifica, Q-111) | FAILED | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` |
| TB-INS-TST-034 | quiete misurata sull'arrivo | evento con time vecchio di 1 h ma arrivato 1 s fa ⇒ IN_PROGRESS | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` |
| TB-INS-TST-035 | orologio indietro rispetto all'ultimo arrivo (età negativa) | IN_PROGRESS | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` |
| TB-INS-TST-036 | voce DLQ REPROCESSED più recente dell'ultimo evento (AMBIGUO) | la quiete parte dalla voce | insight §3, §5; Q-106; US-E09-03 | `TestbookInsTraceIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-TTR-001 | catena azione → effetto → fatto | radice senza genitore, genitore = causationId, offsetMs e durationMs dagli arrivi | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-002 | ramificazione | azione → valutazione + 2 effetti → 2 fatti; una sola radice, 6 nodi | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-003 | un solo evento | durationMs 0, offsetMs 0 | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-004 | genitore assente dall'event store (AMBIGUO) | il nodo conserva parentEventId del genitore mancante | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-005 | corsia del nodo | servizio dal source urn:loyaltyhub:service:*, azioni in ingestion, altro source = famiglia (ramo senza specifica) | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-006 | nodo DLQ | famiglia DLQ, genitore = evento fallito, corsia = consumer senza lh-, sintesi con codice e stato | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-007 | memberId del tracciato | il primo evento che ne ha uno (il primo può non averlo) | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-008 | nessun evento con membro | memberId dalla voce DLQ | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-009 | eventi di altri tracciati esclusi (stesso membro, altro correlationId) | come nella descrizione (asserito dal caso) | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-010 | campi del nodo (insight §3) | eventId, family, shortType, service, time = time di business, offsetMs, parentEventId, summary | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |
| TB-INS-TTR-011 | sintesi per tipo (insight §5, AMBIGUO sulla forma) | wallet.points.earned contiene «+162 PTS» | insight §3, §5; F-INS-02; docs/04 §5 | `TestbookInsTraceIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-TOU-001 | scenario SILVER 130 € (insight §7) | outcome.points = [{PTS,162},{STS,130}] | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-002 | due accrediti PTS nello stesso tracciato (acquisto + bonus) | sommati per valuta | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-003 | nessun accredito | points vuoto, tierChange assente, contatori 0 | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-004 | accredito senza valuta (AMBIGUO) | contato come PTS | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-005 | tier.upgraded SILVER → GOLD (EVT-FACT-28) | tierChange {from SILVER, to GOLD} | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-006 | tier.downgraded GOLD → SILVER (EVT-FACT-29) | tierChange {from GOLD, to SILVER} | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-007 | l'effetto points.grant da solo non è un accredito | points vuoto (niente doppio conteggio con il fatto) | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-008 | due message.delivered (EVT-FACT-60) | **DIVERGENZA** — outcome.messages = 2 | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-009 | un coupon.issued (EVT-FACT-45) | **DIVERGENZA** — outcome.coupons = 1 | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-010 | tre contest.played (EVT-FACT-51) | **DIVERGENZA** — outcome.plays = 3 | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |
| TB-INS-TOU-011 | due voci DLQ (una OPEN, una REPROCESSED) (AMBIGUO) | outcome.dlq = 2 | insight §3, §7; BO-25; docs/05 §5 | `TestbookInsTraceIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-TLS-001 | filtro memberId | solo i tracciati del membro | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-002 | ordine | prima il tracciato con l'attività più recente | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-003 | uno per correlationId | tre eventi dello stesso tracciato ⇒ una riga | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-004 | limit=1 | solo il più recente | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-005 | limit=0, sinonimo di size (Q-323 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-006 | from/to sull'istante d'arrivo, estremi inclusi | dentro sì, un'ora dopo no | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-007 | from non ISO-8601 | 400 | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-008 | to non ISO-8601 | 400 | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-009 | riga di elenco (BO-25) | azione radice, inizio, durata, stato, esito «+162 PTS · +130 STS · tier GOLD» | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-010 | risposta paginata {items, page} (docs/06 §2) | **DIVERGENZA** — come nella descrizione (asserito dal caso) | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |
| TB-INS-TLS-011 | nessun parametro | tracciati recenti, al più 100 righe (docs/06 §2), uno per correlationId | insight §3; BO-25; docs/06 §2 | `TestbookInsTraceIT` |

## 5. KPI e metriche

**Regole**: R-14…R-17. **Domini**: tipo d'evento (ogni tipo del catalogo che alimenta una metrica + uno che non ne alimenta + effetto + audit); dimensione (totale, `source`, `type`, `currency`, `campaign`, `reward`, `status`, `direction`); consegna (una, duplicata, riconsegna con payload diverso); istante (vedi DAY); finestra (`from`/`to`, `days` 7/30/0/−5/non numerico, `from` > `to`, date non ISO); `granularity` (day, week, WEEK, month); `limit` (0, 2, default).

**Strategia**: MET = una riga per (tipo, metrica, dimensione) della tabella di insight §2 più le classi «nessuna metrica» e le tre forme di riconsegna (senza doppio conteggio); ogni caso usa un giorno proprio e misura la **variazione**. DAY = valori limite di mezzanotte a Roma (23:59:59 / 00:00:00 in ora solare e legale), i due salti d'ora del 2026 (29/3 e 25/10, con le due 02:30 del 25/10), fine mese, fine anno, 29 febbraio 2028, evento senza `time`, voce DLQ e «oggi» della finestra; ogni riga controlla il giorno atteso (+1) e il giorno sbagliato (+0). KOV/KTS/KBR scrivono le metriche direttamente in giorni lontani.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-MET-001 | azione purchase.completed | actions +1 (totale) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-002 | azione da ecommerce | actions per fonte ecommerce +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-003 | azione da app | actions per fonte app +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-004 | azione | **DIVERGENZA** — actions per tipo purchase.completed +1 (insight §2 dim type) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-005 | wallet.points.earned 162 PTS | points_earned +162 (totale) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-006 | wallet.points.earned 162 PTS | points_earned per valuta PTS +162 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-007 | wallet.points.earned 130 STS | points_earned per valuta STS +130 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-008 | wallet.points.earned 130 STS nel totale di points_earned (AMBIGUO | PTS e STS sommati) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-009 | wallet.points.earned con campaignCode | **DIVERGENZA** — points_by_campaign per campagna +162 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-010 | wallet.points.spent 500 | points_spent +500 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-011 | wallet.points.spent 500 PTS | **DIVERGENZA** — points_spent per valuta PTS +500 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-012 | wallet.points.refunded 500 (AMBIGUO) | points_spent -500 (spesa netta) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-013 | wallet.points.expired 1900 | points_expired +1900 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-014 | wallet.points.expired 1900 PTS | **DIVERGENZA** — points_expired per valuta PTS +1900 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-015 | member.registered | members_new +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-016 | reward.redemption.confirmed | redemptions +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-017 | reward.redemption.confirmed | **DIVERGENZA** — redemptions per premio RWD-TB +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-018 | reward.redemption.confirmed | **DIVERGENZA** — redemptions per stato (una ripartizione non vuota) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-019 | contest.played | **DIVERGENZA** — plays +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-020 | contest.won | **DIVERGENZA** — wins +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-021 | tier.upgraded | tier_changes +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-022 | tier.upgraded | **DIVERGENZA** — tier_changes per direzione (una ripartizione non vuota) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-023 | tier.downgraded | **DIVERGENZA** — tier_changes +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-024 | message.delivered | **DIVERGENZA** — messages +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-025 | fatto senza metrica (campaign.status.changed) | actions invariata | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-026 | voce di audit | actions invariata | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-027 | azione consegnata due volte (stesso id) | actions +1 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-028 | wallet.points.earned consegnato due volte | points_earned +162 | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-029 | riconsegna con payload diverso e stesso id | vale la prima (+162 non +999) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-030 | effetto points.grant 162 | points_earned invariata (conta solo il fatto) | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-031 | riconsegna di wallet.points.earned | points_earned per valuta PTS +162 una volta | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` · `kpi-metriche.csv` |
| TB-INS-MET-032 | voce DLQ nuova | metrica dlq +1; lo stesso record riletto non la raddoppia | insight §2, §5, §7; docs/05 §5 | `TestbookInsKpiIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-DAY-001 | 2026-03-10 22:59:59Z = 23:59:59 di Roma | giorno 2026-03-10 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-002 | 2026-03-10 23:00:00Z = mezzanotte di Roma | **DIVERGENZA** — giorno 2026-03-11 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-003 | 2026-03-11 00:00:00Z = 01:00 di Roma | giorno 2026-03-11 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-004 | ora legale | 2026-03-28 22:59:59Z = 23:59:59 CET: giorno 2026-03-28 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-005 | ora legale | **DIVERGENZA** — 2026-03-28 23:00:00Z = mezzanotte CET: giorno 2026-03-29 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-006 | ora legale | 2026-03-29 01:30:00Z = 03:30 CEST dopo il salto: giorno 2026-03-29 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-007 | ora legale | 2026-03-29 21:59:59Z = 23:59:59 CEST: giorno 2026-03-29 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-008 | ora legale | **DIVERGENZA** — 2026-03-29 22:00:00Z = mezzanotte CEST: giorno 2026-03-30 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-009 | ora solare | 2026-10-24 21:59:59Z = 23:59:59 CEST: giorno 2026-10-24 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-010 | ora solare | **DIVERGENZA** — 2026-10-24 22:00:00Z = mezzanotte CEST: giorno 2026-10-25 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-011 | ora solare | 2026-10-25 00:30:00Z = 02:30 CEST (prima delle due 02:30): giorno 2026-10-25 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-012 | ora solare | 2026-10-25 01:30:00Z = 02:30 CET (seconda 02:30): giorno 2026-10-25 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-013 | ora solare | 2026-10-25 22:59:59Z = 23:59:59 CET: giorno 2026-10-25 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-014 | ora solare | **DIVERGENZA** — 2026-10-25 23:00:00Z = mezzanotte CET: giorno 2026-10-26 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-015 | fine mese | **DIVERGENZA** — 2026-01-31 23:00:00Z = 1 febbraio a Roma | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-016 | fine anno | **DIVERGENZA** — 2026-12-31 23:00:00Z = 1 gennaio 2027 a Roma | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-017 | 29 febbraio | **DIVERGENZA** — 2028-02-28 23:30:00Z = 29/2 a Roma | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-018 | 29 febbraio | 2028-02-29 22:59:59Z = 23:59:59 del 29/2 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-019 | 29 febbraio | **DIVERGENZA** — 2028-02-29 23:00:00Z = 1 marzo a Roma | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-020 | evento senza time alle 22:30Z (orologio) | **DIVERGENZA** — giorno di Roma = il successivo | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-021 | evento senza time alle 12:00Z (orologio) | giorno 2031-05-12 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` · `kpi-giorno.csv` |
| TB-INS-DAY-022 | voce DLQ vista alle 23:30Z del 10/3/2020 (00:30 dell'11 a Roma) | **DIVERGENZA** — metrica dlq nel giorno 2020-03-11 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` |
| TB-INS-DAY-023 | finestra di default alle 22:30Z del 10/5/2031 (00:30 dell'11 a Roma) | **DIVERGENZA** — to = 2031-05-11 | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` |
| TB-INS-DAY-024 | finestra di default alle 12:00Z del 10/5/2031 | to = 2031-05-10, from = 2031-05-04 (7 giorni) | docs/03 (fuso Europe/Rome); insight §2 | `TestbookInsKpiIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-KOV-001 | totali nella finestra [from, to] con estremi inclusi, esclusi il giorno prima e il giorno dopo | come nella descrizione (asserito dal caso) | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-002 | delta sul periodo precedente della stessa lunghezza | abs 10, pct 50 | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-003 | periodo precedente a 0 | pct assente, abs = valore corrente | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-004 | periodo corrente a 0 e precedente 100 | abs -100, pct -100 | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-005 | finestra di un giorno (from = to) | il precedente è il giorno prima | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-006 | days=7 senza from/to (BO-01 7/30/90) | da oggi-6 a oggi | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-007 | senza parametri | default 30 giorni (BO-01) | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-008 | days=0 (Q-323 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-009 | days negativo (Q-323 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-010 | days non numerico | 400 | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-011 | from non è una data ISO (2034-13-01) | 400 | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-012 | from dopo to (Q-323 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-013 | campi di insight §3 | **DIVERGENZA** — membersTotal, membersActive30d, actions, pointsEarned/Spent/Expired, redemptions, plays, wins, deltas | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-014 | membri totali = storico sintetico (3 100 → 3 480) + i 12 reali (docs/10 §9) | **DIVERGENZA** — come nella descrizione (asserito dal caso) | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-015 | dato reale e sintetico dello stesso giorno sommati (insight §5) | come nella descrizione (asserito dal caso) | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |
| TB-INS-KOV-016 | membri attivi `membersActive30d` (gauge, Q-325 DECISA) | valore dell'ultimo giorno della finestra, non la somma | insight §3; BO-01; docs/06 §2; docs/10 §9 | `TestbookInsKpiIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-KTS-001 | granularità day (AMBIGUO sui giorni vuoti) | un punto per giorno con dati, in ordine crescente | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-002 | granularità week | somma per settimana ISO etichettata col lunedì | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-003 | settimana a cavallo d'anno (lun 31/12/2035 – dom 6/1/2036) | un solo punto etichettato 2035-12-31 | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-004 | marcatura synthetic per giorno | giorno sintetico con reale sommato = true, solo reale = false | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-005 | settimana con un giorno sintetico e uno reale (AMBIGUO) | synthetic = true | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-006 | granularity=WEEK maiuscolo (AMBIGUO) | accettato come week | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-007 | granularity=month fuori da day\|week (Q-326 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-008 | metric assente | **DIVERGENZA** — 400 (docs/06 §2 parametri errati) | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-009 | metrica sconosciuta (Q-326 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KTS-010 | from non è una data ISO | 400 | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-KBR-001 | top N (limit=2) in ordine decrescente | come nella descrizione (asserito dal caso) | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-002 | limit di default 5 (top 5 di BO-01) | 6 valori ⇒ 5 righe, escluso il minore | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-003 | limit=0 (Q-323 DECISA) | 400 `BAD_REQUEST` | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-004 | total (AMBIGUO) | somma delle sole righe restituite | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-005 | dimensione di default | source | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-006 | dimensione senza dati | nessuna riga e total 0 | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-007 | dimensione vuota | il totale della metrica non compare come riga | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-008 | metric assente | **DIVERGENZA** — 400 (docs/06 §2 parametri errati) | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |
| TB-INS-KBR-009 | più giorni nella finestra | valori della stessa voce sommati | insight §3; BO-01; docs/06 §2 | `TestbookInsKpiIT` |

## 6. Flusso live SSE

**Regole**: R-06…R-09. **Domini**: `Last-Event-ID` ∈ {assente, spazi, a metà, ultimo, sconosciuto, sfrattato dal buffer}; eventi persi ∈ {1, 199, 200, 201, 250} (limite 200: −1, =, +1 e oltre); filtri `topics`/`types`/`memberId`/`correlationId` ciascuno ∈ {assente, uguale, diverso} + vuoto, più valori, maiuscole, tipo completo, evento senza membro/correlazione.

**Strategia**: filtro con **all-pairs** L9 sui 4 fattori a 3 livelli (81 → 9) + i 4 guasti singoli + tutti uguali + 10 casi speciali (SFL, unit). Ripresa: una riga per classe e per limite della finestra; la riconnessione di insight §7 (20 eventi) come scenario.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-SFL-001 | L9 topics assente types assente memberId assente correlationId assente | passa | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-002 | L9 topics assente types uguale memberId uguale correlationId uguale | passa | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-003 | L9 topics assente types diverso memberId diverso correlationId diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-004 | L9 topics uguale types assente memberId uguale correlationId diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-005 | L9 topics uguale types uguale memberId diverso correlationId assente | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-006 | L9 topics uguale types diverso memberId assente correlationId uguale | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-007 | L9 topics diverso types assente memberId diverso correlationId uguale | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-008 | L9 topics diverso types uguale memberId assente correlationId diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-009 | L9 topics diverso types diverso memberId uguale correlationId assente | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-010 | tutti i filtri uguali | passa | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-011 | solo topics diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-012 | solo types diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-013 | solo memberId diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-014 | solo correlationId diverso | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-015 | topics con due valori di cui uno uguale | passa | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-016 | insieme topics vuoto | nessun filtro | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-017 | memberId vuoto | nessun filtro | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-018 | correlationId vuoto | nessun filtro | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-019 | types col tipo completo (AMBIGUO) | scartato perche si confronta il tipo breve | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-020 | topics in maiuscolo (AMBIGUO) | scartato (confronto esatto) | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-021 | filtro memberId su evento senza membro | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-022 | filtro correlationId su evento senza correlazione | scartato | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |
| TB-INS-SFL-023 | nessun filtro su evento senza membro ne correlazione | passa | insight §3 | `TestbookInsLiveTest` · `sse-filtro.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-SUM-001 | acquisto 130 € | sintesi con l'importo | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-002 | accredito 162 PTS (esempio di insight §5, AMBIGUO sulla forma) | contiene +162 PTS | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-003 | accredito STS | contiene la valuta STS | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-004 | cambio di stato del membro | precedente → nuovo | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-005 | tier.upgraded | livello nuovo | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-006 | voce di audit | l'azione | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-007 | tipo senza voce nella tabella | il tipo breve | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-008 | acquisto senza importo | nessun errore | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-009 | dati nulli | nessun errore | insight §5 | `TestbookInsLiveTest` |
| TB-INS-SUM-010 | tipo nullo | testo generico | insight §5 | `TestbookInsLiveTest` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-SRP-001 | senza Last-Event-ID | nessun rinvio degli eventi passati, arrivano solo i nuovi | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-002 | Last-Event-ID a metà | rinviati in ordine gli eventi successivi | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-003 | Last-Event-ID = ultimo evento | nulla da rinviare | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-004 | Last-Event-ID sconosciuto (Q-322 DECISA) | nessun rinvio (niente duplicati); gli eventi successivi arrivano dal vivo | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-005 | Last-Event-ID di soli spazi | come assente, nessun rinvio | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-006 | 1 evento perso | rinviato | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-007 | 199 eventi persi | rinviati tutti | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-008 | 200 eventi persi (limite) | rinviati tutti | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-009 | 201 eventi persi | rinviati gli ultimi 200 (buco di 1) | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-010 | 250 eventi persi | rinviati gli ultimi 200 (buco di 50) | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-011 | il rinvio rispetta il filtro | solo gli eventi del correlationId richiesto | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-012 | dopo il rinvio il flusso continua dal vivo, senza duplicati | come nella descrizione (asserito dal caso) | insight §3, §7 | `TestbookInsStreamIT` |
| TB-INS-SRP-013 | riconnessione con Last-Event-ID (insight §7, 20 eventi) | nessun evento perso né doppio | insight §3, §7 | `TestbookInsStreamIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-SSE-001 | acquisto dal topic | evento lh-event con id = eventId e data {eventId, topic, family, shortType, memberId, correlationId, time, summary} | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-002 | filtro topics con due valori separati da virgola e spazi | arrivano azioni e fatti, non gli effetti | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-003 | filtro memberId | solo gli eventi del membro | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-004 | filtro types sul tipo breve | solo wallet.points.earned | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-005 | due client con filtri diversi | ognuno riceve solo i suoi eventi | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-006 | heartbeat ogni 15 s (AMBIGUO sulla forma | commento SSE): arriva entro 16 s su un canale muto | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-007 | client che chiude | rimosso dagli iscritti senza errori per gli altri | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-008 | CORS per l'EventSource del browser (ADR-020) | Access-Control-Allow-Origin sulla risposta | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |
| TB-INS-SSE-009 | client lento che non legge (coda oltre 500) | **DIVERGENZA** — disconnesso, gli altri client continuano a ricevere | insight §3, §5; ADR-020; Q-26 | `TestbookInsStreamIT` |

## 7. Audit

**Regole**: R-19…R-21. **Domini**: `action` ∈ 7 valori + uno sconosciuto; `lhactor` ∈ {`RUOLO:nome`, `system`, `member:<id>`, assente, `RUOLO:`, `:nome`}; `before/after` presenti/assenti; `data` nullo; filtri ciascuno uguale/diverso/vuoto; istanti `from`/`to` all'istante della voce e ±1 ms, non ISO, solo data; paginazione `page`/`size`.

**Strategia**: una riga per valore di `action` e per forma dell'attore (AIN, via Kafka); filtri con 5 voci per caso e servizio proprio, una riga per filtro, per limite di tempo e per combinazione significativa (AFL); paginazione di docs/06 (APG). Le scritture reali di ogni famiglia sono nelle righe HUB (§13).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-AIN-001 | voce di audit con action CREATE | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-002 | voce di audit con action UPDATE | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-003 | voce di audit con action DELETE | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-004 | voce di audit con action TRANSITION | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-005 | voce di audit con action ADJUST | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-006 | voce di audit con action JOB | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-007 | voce di audit con action RESET | registrata e filtrabile per azione | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-008 | action fuori elenco PURGE (AMBIGUO) | registrata cosi com'e | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-009 | attore MARKETING:luca.marketing | ruolo MARKETING e nome luca.marketing | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-010 | attore system dei job (AMBIGUO) | ruolo system senza nome | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-011 | attore member:MBR-000003 dal portale | ruolo member e nome MBR-000003 | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-012 | lhactor assente (AMBIGUO | obbligatorio per l'audit): voce registrata senza attore | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-013 | attore ADMIN | senza nome: ruolo ADMIN e nome assente | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-014 | attore :anonimo senza ruolo (AMBIGUO) | ruolo = valore intero e nome anonimo | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` · `audit-ingest.csv` |
| TB-INS-AIN-015 | before/after presenti | conservati come oggetti con i soli campi cambiati | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` |
| TB-INS-AIN-016 | before/after assenti (creazione, job) | campi nulli | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` |
| TB-INS-AIN-017 | evento di audit senza data (AMBIGUO) | nessuna voce, l'evento resta nell'event store | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` |
| TB-INS-AIN-018 | stesso evento di audit consegnato due volte | una sola voce | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` |
| TB-INS-AIN-019 | chi/cosa/quando | at = time dell'evento, servizio, tipo e id dell'oggetto, sintesi, correlationId | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` |
| TB-INS-AIN-020 | voce senza entityId (AMBIGUO | il contratto lo richiede): registrata con id vuoto | docs/05 §6; contracts/events/audit; F-AUD-01 | `TestbookInsAuditIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-AFL-001 | filtro attore = nome utente | voci #3, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-002 | filtro attore nella forma RUOLO:nome (AMBIGUO) | voci nessuna (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-003 | filtro ruolo ADMIN | voci #3, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-004 | filtro ruolo minuscolo admin (AMBIGUO) | voci nessuna (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-005 | filtro ruolo system (AMBIGUO) | voci #4 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-006 | solo servizio | voci #4, #3, #2, #1, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-007 | filtro tipo oggetto CAMPAIGN | voci #1, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-008 | filtro oggetto MBR-000004 | voci #2 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-009 | filtro azione UPDATE | voci #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-010 | filtro azione JOB | voci #4 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-011 | from uguale all'istante della voce | voci #4, #3, #2 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-012 | from 1 ms dopo la voce | voci #4, #3 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-013 | to uguale all'istante della voce | voci #2, #1, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-014 | to 1 ms prima della voce | voci #1, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-015 | finestra from e to | voci #3, #2, #1 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-016 | ruolo ADMIN e azione TRANSITION | voci #3 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-017 | filtri vuoti ignorati | voci #4, #3, #2, #1, #0 (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-018 | from non ISO-8601 | HTTP 400 | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-019 | to con mese 13 | HTTP 400 | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-020 | from solo data (AMBIGUO) | HTTP 400 | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |
| TB-INS-AFL-021 | tipo oggetto inesistente | voci nessuna (dalla più recente) | insight §3; BO-22; docs/06 §2 | `TestbookInsAuditIT` · `audit-filtri.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-APG-001 | page=0&size=2 | **DIVERGENZA** — due voci e page {number 0, size 2, totalItems 5, totalPages 3} | docs/06 §2 | `TestbookInsAuditIT` |
| TB-INS-APG-002 | page=2&size=2 | **DIVERGENZA** — l'ultima voce | docs/06 §2 | `TestbookInsAuditIT` |
| TB-INS-APG-003 | size=101 | **DIVERGENZA** — limitata a 100 (docs/06 §2) | docs/06 §2 | `TestbookInsAuditIT` |
| TB-INS-APG-004 | senza parametri | **DIVERGENZA** — risposta {items, page} con totalItems | docs/06 §2 | `TestbookInsAuditIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-ADT-001 | GET /v1/audit/{id} | voce completa con before/after per il DiffView | insight §3; BO-22 | `TestbookInsAuditIT` |
| TB-INS-ADT-002 | id inesistente | 404 NOT_FOUND | insight §3; BO-22 | `TestbookInsAuditIT` |

## 8. Ricerca eventi

**Regole**: R-03, R-04. **Strategia**: 4 eventi per caso (azione, effetto, fatto dello stesso tracciato + un fatto di altro tracciato) di un membro proprio; una riga per filtro, per limite di `from`/`to`, per `q` senza maiuscole, per valore non valido.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-EVT-001 | solo memberId | voci #3, #2, #1, #0 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-002 | topic lh.facts.v1 | voci #3, #2 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-003 | family=action minuscolo | voci #0 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-004 | family=FACT | voci #3, #2 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-005 | family=DLQ | voci nessuna (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-006 | type breve wallet.points.earned | voci #2 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-007 | type completo io.loyaltyhub.fact.wallet.points.earned (AMBIGUO) | voci nessuna (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-008 | correlationId del tracciato | voci #2, #1, #0 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-009 | source urn:loyaltyhub:service:wallet | voci #3, #2 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-010 | from uguale all'arrivo del secondo evento | voci #3, #2, #1 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-011 | to uguale all'arrivo del secondo evento | voci #1, #0 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-012 | q testo del payload in minuscolo | voci #0 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-013 | q assente dai payload | voci nessuna (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-014 | from non ISO-8601 | HTTP 400 | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-015 | page=0&size=2 (docs/06 §2) | **DIVERGENZA** — voci #3, #2 (dalla più recente) | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` · `eventi-filtri.csv` |
| TB-INS-EVT-016 | dettaglio GET /v1/events/{id} | payload completo, tipo completo, causazione, partizione e offset | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` |
| TB-INS-EVT-017 | evento inesistente | 404 NOT_FOUND | insight §3; docs/06 §2; Q-111 | `TestbookInsEventsIT` |

## 9. Ingest

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-ING-001 | azione su lh.actions.v1 | famiglia ACTION, tipo breve senza prefisso, membro dal subject | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-002 | effetto su lh.effects.v1 | famiglia EFFECT | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-003 | fatto su lh.facts.v1 | famiglia FACT | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-004 | audit su lh.audit.v1 | famiglia AUDIT, tipo breve «entry» | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-005 | tipo fuori da io.loyaltyhub.* (ramo senza specifica) | tipo breve = tipo intero | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-006 | subject non di membro (fatto di configurazione) | nessun memberId | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-007 | attributi conservati | source, correlationId, causationId, hop, actor, time di business | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |
| TB-INS-ING-008 | evento duplicato sul topic | una sola riga e un solo messaggio nel flusso live | insight §2, §4, §5; docs/05 §1–§2 | `TestbookInsEventsIT` |

## 10. Stato della pipeline

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-PIP-001 | nuovo evento | countTotal del topic +1 | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-002 | evento duplicato | la statistica non conta la seconda consegna | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-003 | evento con time più vecchio dell'ultimo | lastEventAt non torna indietro | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-004 | evento più recente dell'ultimo | lastEventAt avanza al suo time | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-005 | offset per partizione | la partizione 0 riporta l'offset dell'ultimo record | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-006 | volumi 1 h e 24 h per topic (insight §2 topic_stat count_1h/count_24h, BO-24) | **DIVERGENZA** — come nella descrizione (asserito dal caso) | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-007 | ritardo stimato per topic (insight §3, BO-24) | **DIVERGENZA** — come nella descrizione (asserito dal caso) | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-008 | per servizio | **DIVERGENZA** — ultimo fatto prodotto (insight §3) | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |
| TB-INS-PIP-009 | un evento su ognuno dei 5 topic | tutti e 5 presenti nello stato pipeline | insight §2, §3; F-INS-06; BO-24 | `TestbookInsEventsIT` |

## 11. Letture per ruolo e configurazione

**Strategia**: tabella **completa** endpoint di lettura × ruolo (6 × 7 = 42).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-ROL-001 | lettura eventi come ADMIN | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-002 | lettura eventi come MARKETING | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-003 | lettura eventi come LEGAL | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-004 | lettura eventi come CARE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-005 | lettura eventi come ANALYST | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-006 | lettura eventi come NONE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-007 | lettura eventi come INVALID | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-008 | lettura tracciati come ADMIN | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-009 | lettura tracciati come MARKETING | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-010 | lettura tracciati come LEGAL | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-011 | lettura tracciati come CARE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-012 | lettura tracciati come ANALYST | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-013 | lettura tracciati come NONE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-014 | lettura tracciati come INVALID | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-015 | lettura audit come ADMIN | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-016 | lettura audit come MARKETING | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-017 | lettura audit come LEGAL | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-018 | lettura audit come CARE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-019 | lettura audit come ANALYST | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-020 | lettura audit come NONE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-021 | lettura audit come INVALID | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-022 | lettura DLQ come ADMIN | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-023 | lettura DLQ come MARKETING | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-024 | lettura DLQ come LEGAL | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-025 | lettura DLQ come CARE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-026 | lettura DLQ come ANALYST | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-027 | lettura DLQ come NONE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-028 | lettura DLQ come INVALID | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-029 | lettura KPI come ADMIN | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-030 | lettura KPI come MARKETING | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-031 | lettura KPI come LEGAL | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-032 | lettura KPI come CARE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-033 | lettura KPI come ANALYST | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-034 | lettura KPI come NONE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-035 | lettura KPI come INVALID | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-036 | lettura stato pipeline come ADMIN | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-037 | lettura stato pipeline come MARKETING | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-038 | lettura stato pipeline come LEGAL | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-039 | lettura stato pipeline come CARE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-040 | lettura stato pipeline come ANALYST | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-041 | lettura stato pipeline come NONE | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |
| TB-INS-ROL-042 | lettura stato pipeline come INVALID | 200 (docs/08 §2 tutte le personas leggono tutto) | docs/08 §2; docs/06 §3; Q-261 | `TestbookInsEventsIT` · `ruoli-lettura.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-CFG-001 | event store | 14 giorni | insight §4, §5; RNF-07 | `TestbookInsConfigTest` |
| TB-INS-CFG-002 | event store | 200 000 righe | insight §4, §5; RNF-07 | `TestbookInsConfigTest` |
| TB-INS-CFG-003 | audit | 180 giorni | insight §4, §5; RNF-07 | `TestbookInsConfigTest` |
| TB-INS-CFG-004 | payload troncato a 8 KB | soglia 8192 byte | insight §4, §5; RNF-07 | `TestbookInsConfigTest` |
| TB-INS-CFG-005 | job di conservazione orario | 24 esecuzioni al giorno, una all'ora | insight §4, §5; RNF-07 | `TestbookInsConfigTest` |
| TB-INS-CFG-006 | consumer | tutti e 5 i topic col gruppo lh-insight | insight §4, §5; RNF-07 | `TestbookInsConfigTest` |

## 12. Conservazione, reset e storico sintetico

**Domini**: età di un evento 13 g, 14 g − 1 min, 14 g + 1 min, 15 g, 0; soglia di righe 5 con 4/5/6 eventi (−1, =, +1) e combinata con l'età (vince il minore, in entrambi i sensi); età dell'audit 179 g, 180 g ± 1 min, 181 g, 20 g; metrica di 10 anni fa; payload di 20 KB.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-RET-001 | evento arrivato 13 giorni fa | conservato | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-002 | evento arrivato 14 giorni meno 1 minuto fa | conservato | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-003 | evento arrivato 14 giorni più 1 minuto fa | eliminato | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-004 | evento arrivato 15 giorni fa | eliminato | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-005 | evento appena arrivato | conservato | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-006 | soglia 5 righe e 4 eventi recenti | tutti conservati | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-007 | soglia 5 righe e 5 eventi recenti (limite) | tutti conservati | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-008 | soglia 5 righe e 6 eventi recenti | eliminato il più vecchio | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-009 | 8 eventi (3 oltre 14 giorni) e soglia 6 | vince l'età e restano 5 | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-010 | 8 eventi (3 oltre 14 giorni) e soglia 3 | vince la soglia e restano i 3 più recenti | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-011 | voce di audit di 179 giorni | conservata | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-012 | voce di audit di 180 giorni meno 1 minuto | conservata | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-013 | voce di audit di 180 giorni più 1 minuto | eliminata | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-014 | voce di audit di 181 giorni | eliminata | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-015 | voce di audit di 20 giorni (oltre i 14 dell'event store) | conservata | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-016 | metric_daily illimitata | una metrica di 10 anni fa resta dopo la pulizia | insight §5; RNF-07 | `TestbookInsStoreIT` |
| TB-INS-RET-017 | payload di 20 KB | **DIVERGENZA** — conservato troncato a 8 KB (insight §5) | insight §5; RNF-07 | `TestbookInsStoreIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-RST-001 | reset da ADMIN | event store, audit, DLQ e statistiche per topic svuotati | insight §6; docs/06 §10; docs/10 §1.3 | `TestbookInsStoreIT` |
| TB-INS-RST-002 | reset | storico sintetico rigenerato identico (seme fisso, docs/10 §1.3) | insight §6; docs/06 §10; docs/10 §1.3 | `TestbookInsStoreIT` |
| TB-INS-RST-003 | reset da MARKETING | 403, nulla svuotato | insight §6; docs/06 §10; docs/10 §1.3 | `TestbookInsStoreIT` |
| TB-INS-RST-004 | reset | **DIVERGENZA** — voce di audit RESET con l'attore ADMIN (docs/06 §10) | insight §6; docs/06 §10; docs/10 §1.3 | `TestbookInsStoreIT` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-SYN-001 | actions | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-002 | points_earned | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-003 | points_spent | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-004 | points_expired | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-005 | members_new | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-006 | members_active | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-007 | tier_changes | 90 giorni sintetici con valore positivo | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-008 | redemptions (docs/10 §9 richieste 14/giorno) | **DIVERGENZA** — 90 giorni sintetici | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-009 | plays (docs/10 §9 giocate 95/giorno) | **DIVERGENZA** — 90 giorni sintetici | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-010 | wins (docs/10 §9 vincite 11/giorno) | **DIVERGENZA** — 90 giorni sintetici | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-011 | stagionalità | points_earned del sabato e della domenica ≈ +35 % sui feriali (tra +20 % e +50 %) | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-012 | picco della campagna estiva al giorno −30 | actions a −30 oltre 1,3 volte la media dei giorni −50…−40 | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-013 | trend +0,4 %/giorno | media degli ultimi 14 giorni oltre 1,15 volte quella dei primi 14 (points_spent) | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-014 | ripartizioni coerenti | per ogni giorno la somma per valuta e per fonte è uguale al totale | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-015 | dato reale in un giorno sintetico | sommato e il giorno resta marcato synthetic | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |
| TB-INS-SYN-016 | BO-01 a 90 giorni subito dopo il reset (insight §7) | nessun giorno passato vuoto | insight §5, §7; F-INS-04; docs/10 §9 | `TestbookInsStoreIT` |

## 13. Righe tra servizi (hub)

Nel deployable consolidato (`TestbookInsHubIT`, profilo `inproc`): traffico reale dalle API pubbliche; ogni famiglia di scrittura da backoffice produce la sua voce di audit (chi/cosa/quando).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-INS-HUB-001 | acquisto SILVER 130 € (insight §7) | una radice = l'azione, durationMs > 0, esito [{PTS,162},{STS,130}] | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-002 | dopo 5 s senza nuovi eventi il tracciato dell'acquisto è COMPLETE | come nella descrizione (asserito dal caso) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-003 | flusso live di un acquisto (insight §7) | entro 3 s ≥ 4 messaggi SSE con lo stesso correlationId (azione, valutazione, effetto punti, accredito) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-004 | SCN-POISON (insight §7) | voce DLQ DEMO_POISON del consumer lh-campaign, azione riprocessabile, tracciato FAILED | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-005 | scarta della voce avvelenata | **DIVERGENZA** — DISCARDED, audit TRANSITION dell'ADMIN, tracciato ancora FAILED (Q-106) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-006 | CREATE | **DIVERGENZA** — nuova campagna da MARKETING ⇒ voce di audit con servizio, oggetto, attore e istante | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-007 | UPDATE | **DIVERGENZA** — nome cambiato ⇒ diff con il solo campo cambiato (prima/dopo) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-008 | TRANSITION | SUBMIT della campagna ⇒ voce TRANSITION dell'attore | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-009 | override | ADMIN approva al posto di LEGAL ⇒ audit TRANSITION marcato «override» (docs/08 §2, BO-22) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-010 | ADJUST | **DIVERGENZA** — rettifica manuale da CARE ⇒ voce ADJUST del wallet con saldo prima/dopo | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-011 | UPDATE del membro da CARE ⇒ voce del servizio member con attore CARE | **DIVERGENZA** — come nella descrizione (asserito dal caso) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-012 | DELETE | **DIVERGENZA** — eliminazione di un webhook da ADMIN ⇒ voce DELETE di engagement | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-013 | JOB | **DIVERGENZA** — job scadenze ⇒ voce JOB con attore system (docs/05 §2 lhactor dei job) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-014 | scrittura rifiutata (ANALYST crea una campagna | 403) ⇒ nessuna voce di audit | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-015 | SCN-WEEKEND-BURST (docs/12 M2) | le 12 azioni in event store su lh.actions.v1 e il topic conta almeno +12 | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |
| TB-INS-HUB-016 | RESET | **DIVERGENZA** — reset demo da ADMIN ⇒ voce di audit RESET con l'attore (docs/06 §10) | insight §3, §5, §7; docs/05 §6; docs/06 §3, §10; docs/08 §2, BO-22, BO-24, BO-25, BO-27; docs/12 M2 | `TestbookInsHubIT` |

## 14. Registro delle divergenze

| Righe (prefisso `TB-INS-`) | Specifica | Comportamento osservato | Causa (file:riga) |
|---|---|---|---|
| DAY-002, 005, 008, 010, 014, 015, 016, 017, 019, 020, 022, 023 | docs/03: «fuso orario di business `Europe/Rome`»; il `day` di `metric_daily` e «oggi» della finestra sono giorni di business | giorno calcolato in UTC: un evento alle 00:30 di Roma finisce nel giorno prima; a mezzanotte di Roma `to` è ancora ieri | `EventIngestService.java:117` e `:162` (`ZoneOffset.UTC`), `KpiService.java:39`, anche `InsightSyntheticSeeder.java:75` |
| MET-004, 009, 011, 014, 017, 018, 019, 020, 022, 023, 024 | insight §2: `actions` (dim `type`), `points_spent/expired` (dim `currency`), `points_by_campaign`, `redemptions` (dim `status`, `reward`), `plays`, `wins`, `tier_changes` (dim `direction`), `messages` | metriche o dimensioni mai scritte; `tier.downgraded` non conta come cambio di livello | `EventIngestService.java:161-198` (switch limitato a M2) |
| KOV-013 | insight §3: overview con `membersActive30d` | il campo si chiama `membersActive` | `KpiService.java:137` |
| KOV-014 | docs/10 §9: «membri totali» = storico sintetico (3 100 → 3 480) + i 12 reali | 12 + registrati: 12 | `KpiService.java:26`, `:63`; nessuna metrica dei membri totali in `seed/insight-synthetic.json` |
| SYN-008, 009, 010 | docs/10 §9: baseline sintetiche anche per richieste (14), giocate (95), vincite (11); CLAUDE.md §9 (schermate piene) | nessun giorno sintetico per `redemptions`, `plays`, `wins`: le tessere di BO-01 sono a 0 | `seed/insight-synthetic.json` (sezione `metrics`) |
| KTS-008, KBR-008 | docs/06 §2: parametri errati ⇒ 400 | `metric` assente ⇒ 500 `INTERNAL_ERROR` | `libs/lh-common/.../web/GlobalExceptionHandler.java:88` (nessun gestore per `MissingServletRequestParameterException`) |
| TOU-008, 009, 010 | insight §3: `outcome {…, messages, coupons, plays, dlq}`; BO-25 pannello esito | contatori sempre 0 | `TraceService.java:138` |
| DLS-018, 019 | docs/06 §2: `size` massimo 100 | `size` fino a 200 | `DlqController.java:27` |
| APG-001…004 | docs/06 §2: elenchi `?page&size`, risposta `{items, page}` | `/v1/audit` usa `limit`, risponde `{items, count, total}` | `AuditController.java:31-54` |
| EVT-015 | docs/06 §2 | `/v1/events` usa `limit`, risponde `{items, count}` | `EventsController.java:46`, `:60` |
| TLS-010 | docs/06 §2 | `/v1/traces` risponde `{items, count}` | `TracesController.java:32` |
| PIP-006, 007, 008 | insight §2 (`count_1h`, `count_24h`), §3 («volumi 1 h/24 h, ritardo stimato; per servizio: ultimo fatto prodotto»), BO-24 | solo `countTotal`, `lastEventAt`, offset; nessun elenco per servizio | `TopicStatRepository.java:46`, `PipelineController.java:26`, migrazione `V1__insight.sql:31-36` |
| SSE-009 | insight §5: «coda limitata a 500 per client; client lento → disconnesso» | un client che non legge blocca l'unico thread d'invio: nessun altro client riceve finché il socket non si chiude | `LiveEventHub.java:37-42` (un solo thread), `:118-127` (invio bloccante, nessuna coda) |
| RET-017 | insight §5 (RNF-07): payload troncato a 8 KB | payload di 20 KB conservato intero; `payload-max-bytes` non letto da nessuno | `application.yml:40`; `EventIngestService.java:139` |
| RST-004, HUB-016 | docs/06 §10: il reset produce audit `RESET` | nessuna voce `RESET` | `libs/lh-common/.../demo/DemoResetController.java:19` (nessuna chiamata ad `AuditPublisher`) |
| HUB-005, 006, 010, 011, 012, 013 | docs/05 §6: `data.service` = servizio che scrive (es. `wallet`); BO-22 filtra per servizio | nell'hub consolidato tutte le voci hanno `service = hub` | `AuditPublisher.java:41` (`props.getService()`), `deploy/hub/src/main/resources/hub.yml:42` |
| HUB-007 | docs/05 §6: `before/after` contengono solo i campi cambiati | l'UPDATE di una campagna riporta sempre `name`, `priority`, `schedule` | `services/campaign-service/.../CampaignAdminService.java:223-225` |

Totale: **55 righe divergenti**, 17 cause. **Tutte corrette** nel codice di produzione (vedi «Correzioni» sotto): le righe asseriscono la specifica e sono verdi.

**Correzioni** (per causa):

| Causa | Correzione |
|---|---|
| Giorno in UTC | `EventIngestService.businessDay` (`BusinessCalendar.ZONE` = `Europe/Rome`) per metriche e DLQ; `KpiService.today()` e `InsightSyntheticSeeder` sul giorno di Roma |
| Metriche e dimensioni di insight §2 | `EventIngestService#updateMetrics`: `actions` per `type`; `points_spent`/`points_expired` per `currency`; `points_by_campaign` per `campaign`; `redemptions` per `status` (ogni passaggio del ciclo di vita) e `reward`; `plays`, `wins`, `messages`; `tier_changes` per `direction` con `tier.downgraded` (DOWN); solo i fatti contano per le metriche di valore |
| `membersActive30d` | campo rinominato nell'overview e nei delta (web BO-01 aggiornato) |
| Membri totali | `seed/insight-synthetic.json` `members_total` lineare 3 100 → 3 480 (senza rumore); `membersTotal` = ultimo valore sintetico + 12 + registrati |
| Storico di richieste, giocate, vincite | `seed/insight-synthetic.json`: `redemptions` 14, `plays` 95, `wins` 11 (docs/10 §9), in coda per non cambiare le sequenze esistenti |
| `metric` assente ⇒ 500 | `GlobalExceptionHandler#onMissingParameter` (lh-common): `MissingServletRequestParameterException` ⇒ 400 `BAD_REQUEST` |
| Contatori dell'esito | `TraceService#outcomeOf`: `message.delivered`, `coupon.issued`, `contest.played` |
| `size` fino a 200 | `Paging` comune agli elenchi: `size` oltre 100 ⇒ 100 |
| Paginazione di audit, eventi, tracciati | `/v1/audit`, `/v1/events`, `/v1/traces` con `?page&size` e `{items, page}` (`PageResponse`), `limit` sinonimo di `size`; web (BO-22, BO-24 polling, BO-25) aggiornato |
| Stato pipeline | `/v1/pipeline/status`: per topic `count1h`, `count24h` (dall'event store, DLQ da `dlq_entry`), `lagMs` (arrivo − timestamp del record), `lastReceivedAt`; `services[]` con l'ultimo fatto (`V5__insight_pipeline.sql`: `topic_stat.last_lag_ms`, `last_received_at`, tabella `service_stat`); striscia BO-24 aggiornata |
| SSE senza coda per client | `LiveEventHub`: coda limitata a 500 e thread (virtuale) d'invio per client; coda piena ⇒ client disconnesso, gli altri continuano |
| Payload non troncato | `EventIngestService#truncate`: payload oltre `payload-max-bytes` accorciato (testi più lunghi, poi `data` segnaposto), `lhtruncatedbytes` = dimensione originale |
| Nessun audit `RESET` | `DemoResetController` (lh-common) pubblica `RESET` con l'attore dopo i reset |
| `service = hub` nell'hub | `AuditPublisher.callerService()`: servizio logico dal package del chiamante (`io.loyaltyhub.<servizio>`) |
| UPDATE di campagna con tutti i campi | `CampaignAdminService.changedFields`: solo i campi cambiati in `before/after` |

TB-INS-SRP-013 (duplicato occasionale alla riconnessione): **riprodotto in modo deterministico** da `LiveEventHubTest` (`insight.live`): con un solo thread d'invio condiviso, la consegna di un evento restava in coda dietro un client bloccato e, quando ripartiva, trovava già iscritto il nuovo client, che lo riceveva dal vivo e dal rinvio (il buffer era copiato dopo l'iscrizione). Corretto in `LiveEventHub`: iscrizione (copia del buffer + registrazione) e pubblicazione passano dallo stesso lock e ogni client ha la propria coda, quindi il testimone tra rinvio e vivo passa senza duplicati né buchi. `TestbookInsStreamIT` verde 5 esecuzioni su 5.

## 15. Ambiguità (decise: opzione conservativa, docs/15 Q-312…Q-331)

Il proprietario ha deciso di applicare a ogni ambiguità l'opzione più conservativa. Dove il comportamento attuale lo era già resta (e diventa la scelta registrata); dove non lo era è stato cambiato e le righe asseriscono la scelta (`// Q-Nn DECISA`).

| Righe | Domanda (docs/15) | Scelta |
|---|---|---|
| DST-004, DST-005, DRP-010, DRP-013 | Q-312 — *Riprocessa* di una voce `AUDIT` o di famiglia sconosciuta | 409 `NOT_REPROCESSABLE` (già così) |
| DST-033, 034 | Q-313 — ordine dei controlli di *scarta* | 422 `NOTE_REQUIRED` prima di 409/404 (già così) |
| DRL-015 | Q-314 — ruolo in minuscolo in `X-LH-Actor` | segue Q-298 (lh-common, comune a tutti i servizi): nessun cambio locale |
| DIN-002…005, 007…009 | Q-315 — risposte di ingestion diverse da `ACCEPTED` | voce resta `OPEN`; 409 `REPROCESS_REJECTED`; 4xx `INGESTION_REFUSED`; vuoto 503 (già così) |
| DIG-002…005 | Q-316 — header `kafka_dlt-*`, `errorCode` dalla classe, consumer `unknown`, tentativi non numerici | lettura tollerante (già così: un errore qui rimanderebbe il record in DLQ) |
| DLS-003, 007, 016, 020 | Q-317 — `status` minuscolo o sconosciuto, `size=0`, `page` negativa | minuscolo accettato; sconosciuto = nessuna voce (come `NEW`, Q-105); `size` < 1 e `page` < 0 ⇒ **400** (cambiato) |
| TST-031 | Q-318 — tracciato sconosciuto | **404** `NOT_FOUND` (cambiato) |
| TST-036 | Q-319 — la quiete di 5 s conta le voci DLQ | sì (già così) |
| TTR-004, 005, 011 | Q-320 — nodo orfano, corsia di un `source` estraneo, forma della sintesi | genitore mancante conservato; corsia = famiglia; «Punti accreditati · +162 PTS» (già così) |
| TOU-004, TOU-011 | Q-321 — accredito senza valuta; `dlq` conta anche le riprocessate | PTS; sì (già così) |
| SRP-004 | Q-322 — `Last-Event-ID` mai visto da questa istanza | **nessun rinvio** (cambiato: niente duplicati); un id sfrattato da poco dal buffer riceve gli ultimi 200 (SRP-008…010) |
| TLS-005, KOV-008, 009, 012, KBR-003 | Q-323 — valori < 1 di `limit`/`size`/`days`, `from` dopo `to` | **400** (cambiato) |
| MET-008, MET-012 | Q-324 — totale di `points_earned` su PTS e STS; rimborso sottratto da `points_spent` | sì; sì (già così; la dimensione `currency` separa le valute) |
| KOV-016 | Q-325 — «membri attivi» come ultimo valore della finestra | ultimo valore (già così) |
| KTS-001, 005, 006, 007, 009, KBR-004 | Q-326 — giorni vuoti, settimana mista, `WEEK`, `granularity` o metrica sconosciute, `total` | omessi; synthetic; accettato; granularità e metrica sconosciute ⇒ **400** (cambiato); somma delle righe restituite |
| SSE-006 | Q-327 — forma del heartbeat | commento SSE `:hb` (già così) |
| SFL-019, 020, EVT-007 | Q-328 — tipo completo nei filtri, topic in maiuscolo | nessuna corrispondenza (già così) |
| AIN-008, 010, 012, 014, 017, 020; AFL-002, 004, 005, 020 | Q-329 — audit: azione fuori elenco, attore anomalo, `data` nullo, `entityId` assente, filtri | come oggi (ingest tollerante, filtri esatti, `from` solo data ⇒ 400) |
| ING-005 | Q-330 — tipo fuori da `io.loyaltyhub.*` | tipo breve = tipo intero (già così) |
| SYN (baseline), SYN-011 | Q-331 — conflitto docs/10 §9 ↔ `seed/insight-synthetic.json` e «acquisti» ↔ `points_earned` | baseline del seed per le metriche esistenti, docs/10 §9 per le nuove; stagionalità su `points_earned` (insight §5, fonte più autorevole) |

## 16. Storie di docs/17 coperte

US-E08-07 (AIN, AFL, APG, ADT, HUB-006…016), US-E08-11 (DST, DIN, DRP, DRS, DAU, HUB-004), US-E08-12 (DST, DNT, DAU, HUB-005), US-E09-01 (SRP, SSE, SFL, HUB-003; lato client in TB-WEB), US-E09-02 (PIP, HUB-015), US-E09-03 (TST, TTR, TOU, TLS, HUB-001/002), US-E09-04 (MET, DAY, KOV, KTS, KBR, SYN), US-E09-06 (RET, CFG), US-E09-07 (EVT). US-E09-05 (linea del tempo del membro) resta scoperta: l'endpoint `GET /v1/members/{id}/timeline` non esiste (Q-167, **regola non implementata**, nessuna riga).

## 17. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate | 28 (R-01…R-28) |
| Rami del codice mappati | 23 punti di decisione della tabella §2 (≈ 110 rami) |
| Righe di testbook | **586** (DST 34 · DRL 15 · DNT 50 · DIN 15 · DRP 15 · DSH 4 · DRS 4 · DAU 6 · DCC 2 · DLS 26 · DIG 18 · TST 36 · TTR 11 · TOU 11 · TLS 11 · MET 32 · DAY 24 · KOV 16 · KTS 10 · KBR 9 · SFL 23 · SUM 10 · SRP 13 · SSE 9 · AIN 20 · AFL 21 · APG 4 · ADT 2 · EVT 17 · ING 8 · PIP 9 · ROL 42 · CFG 6 · RET 17 · RST 4 · SYN 16 · HUB 16) |
| Combinazioni ridotte | SFL: 81 → 9 all-pairs (L9) + 4 guasti singoli + 1 tutti uguali + 10 speciali; DLQ stato × azione × famiglia × ruolo × nota = 4 × 2 × 5 × 8 × 10 = 3 200 → 34 (macchina a stati completa con ADMIN e nota valida) + 15 (ruolo × azione) + 50 (nota × famiglia completa per scarta + riprocessa) = 99; tabelle complete: TST 6 × 5 = 30, DRP 5 × 3 = 15, DNT 9 × 5 = 45, ROL 6 × 7 = 42 |
| Rami senza specifica | 13 voci (§2), righe marcate AMBIGUO (§15) |
| Regole non implementate | `GET /v1/members/{id}/timeline` (Q-167); dimensione della serie (`timeseries?dimension=`) |
| Divergenze aperte | nessuna (55 righe, 17 cause corrette, §14) |
| Verifica a mutazione | 11 mutazioni, tutte rilevate (vedi sotto) |

**Mutazioni** (codice di produzione rotto temporaneamente, poi ripristinato con `git checkout`; nessuna modifica residua):

| Mutazione | Classi | Righe che la rilevano |
|---|---|---|
| `DlqService`: *riprocessa* senza controllo di famiglia | `TestbookInsDlqIT` | DST-002…005 |
| `DlqEntry.reprocessable` ignora la famiglia | `TestbookInsDlqRulesTest`, `TestbookInsDlqIT` | DRP-004, 007, 010, 013; DST-007…010; DIG-008, 009 |
| `TraceService`: quiete di 4 s | `TestbookInsTraceIT` | TST-002, TST-017 |
| `EventIngestService`: azioni non contate | `TestbookInsKpiIT` | MET-001, MET-027, DAY-001, 003, 004, 006, 007, 009, 011…013, 018, 021 |
| `AuditRepository`: filtro ruolo ignorato | `TestbookInsAuditIT` | AFL-003, 004, 005 |
| `EventStoreRepository`: famiglia non normalizzata | `TestbookInsEventsIT` | EVT-003 |
| `LiveEventHub`: `Last-Event-ID` ignorato | `TestbookInsStreamIT` | SRP-002, 003, 006, 007, 011, 012 |
| `LiveEventHub.Filter`: `types` ignorato | `TestbookInsLiveTest`, `TestbookInsStreamIT` | SFL-006, 012, 019; SSE-004 |
| `RetentionJob`: soglia di righe ignorata | `TestbookInsStoreIT` | RET-008, RET-010 |
| `application.yml`: 15 giorni invece di 14 | `TestbookInsConfigTest`, `TestbookInsStoreIT` | CFG-001, RET-003 |
| `TraceService`: mai `COMPLETE` | `TestbookInsHubIT` | HUB-002 |
