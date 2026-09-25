# TB-ING — Ingresso eventi

Testbook funzionale del dominio **Ingresso eventi** (docs/16 §3): pipeline di accettazione di `POST /v1/events`,
risoluzione del membro, deduplica, eventi non abbinati (riprova, abbina, abbinamento automatico), tipi azione SYSTEM/CUSTOM,
`POST /v1/transactions`, simulatore e scenari. Metodo e convenzioni: docs/16 §1, §1bis.

- **Oracolo** = la specifica: `docs/servizi/ingestion-service.md` (§2, §3, §5, §6, §7), docs/02 F-ING-01…09, docs/05 §1-§3,
  `contracts/events/` (envelope e `action/*.schema.json`), docs/03 §2, docs/08 §2 e BO-09/26/28/29, docs/10 §1-§3 e §8, e
  le scelte registrate in docs/15 (Q-49, Q-89, Q-114…Q-119, Q-128…Q-130). Mai «quello che il codice restituisce oggi».
- **Q-In** nella colonna *atteso* (prima «AMBIGUO»): la specifica tace; la scelta in uso è registrata in docs/15 con quell'id
  (numerazione provvisoria `Q-255…Q-272`, la rinumera l'orchestratore) e il test asserisce il comportamento attuale con il
  commento `// TESTBOOK: ambiguo, vedi <ID>`.
- **DIVERGENZA** nella colonna *atteso*: il codice non rispetta la specifica; il test asserisce la specifica e fallisce
  (registro in §5).
- **Esecuzione.** CSV in `services/ingestion-service/src/test/resources/testbook/ing/` (separatore `|`, prima colonna `id`),
  un caso dinamico `[<ID>] <descrizione>` per riga (`TestbookIngRows`); classi `TestbookIngPipelineIT`,
  `TestbookIngResolutionIT`, `TestbookIngConfigIT` (un solo contesto Spring condiviso, `TestbookIngHarness`: Postgres
  in-process, EmbeddedKafka, orologio del servizio regolabile) e `TestbookIngScenarioTimeTest` (unit). Id freschi per ogni
  caso (eventi, membri, fonti, tipi); pubblicazioni contate sulle righe `outbox` di `lh.actions.v1`, in modo sincrono.
  `bash scripts/testbook.sh` → `target/testbook/rapporto.md`.

## 1. Inventario delle regole

| Regola | Testo (sintesi) | Specifica | Rami del codice | Aree |
|---|---|---|---|---|
| R-01 | forma dell'envelope ⇒ 400, nulla salvato | ingestion §3, §5.1; docs/05 §2; envelope.schema.json | `IngestionService` 227-254 (specversion, 6 campi, data nullo, time) | FRM, PIP |
| R-02 | fonte esistente e abilitata ⇒ `SOURCE_DISABLED` | §5.2; F-ING-05; Q-129 | 139-141; `#sourceCodeOf` 297-300 | SRC, PIP, FON |
| R-03 | tipo noto e abilitato ⇒ `UNKNOWN_TYPE`; breve ≡ completo | §5.3; docs/05 §2 | 145-147; `#normalizeType`/`#shortType` 283-291 | TYP, PIP, ETY |
| R-04 | tipo ammesso dalla fonte, elenco vuoto = tutti ⇒ `TYPE_NOT_ALLOWED` | §2, §5.3; docs/05 §3; docs/10 §3 | 148-150; `Source#allows` | SRC, PIP |
| R-05 | `data` valido contro lo schema ⇒ `INVALID_DATA` con gli errori | §5.4, §7; contracts/events/action | 152-158 (`hasSchema`, validatore) | SCH, PIP, MON |
| R-06 | `time` in [adesso − 30 g, adesso + 5 min] ⇒ `INVALID_TIME` | §5.5 | 160-164 | TIM, PIP |
| R-07 | dedup (fonte, id) ⇒ `DUPLICATE`, riga salvata, nulla pubblicato | §5.6, §7; F-ING-02 | 166-170; 102-110 (gara); `acceptedExists`, `insertAccepted` | DUP, PIP |
| R-08 | subject `member:`/`external:`/`email:` risolto sull'indice | F-ING-03; §5.7 | `#resolveMember` 257-269; `MemberIndexRepository` | MBR |
| R-09 | membro non trovato ⇒ `UNMATCHED` (parcheggiato) | F-ING-04; §5.7 | 176-179 | MBR, PIP |
| R-10 | membro ≠ ACTIVE ⇒ `REJECTED/MEMBER_NOT_ACTIVE`; anonimizzato (Q-128) | F-ING-03; docs/03 §2; Q-128 | 180-183; `MemberErasureRepository#erase` | MBR, PIP |
| R-11 | arricchimento canonico, outbox, chiave memberId | §5.8, §7; docs/05 §1-§2 | 185-188; `#enrich` 272-278; 111 | ACC |
| R-12 | risposta `202 {eventId, status, memberId?, rejectCode?}` | §3 | `EventsController`; `Evaluation#toResult` | ACC, PIP |
| R-13 | monitor: filtri, conteggi, dettaglio | §3; F-ING-09; BO-26 | `InboundEventsController`; `InboundEventRepository#search/#countByStatus` | MON |
| R-14 | storico demo: 40 `inbound_event` di 3 giorni, tutti gli esiti | §6 | — | MON |
| R-15 | Riprova solo `REJECTED`/`UNMATCHED`, rivaluta e pubblica | §3; F-ING-09; Q-114; Q-118 | `InboundResolutionService#retry` 91-100, `#apply` 188-203; `markAccepted`/`updateOutcome` | RES |
| R-16 | Abbina un `UNMATCHED` a `{memberId}` | §3; F-ING-04; Q-118 | `#match` 104-126 | RES |
| R-17 | abbinamento automatico alla registrazione (7 g, 100, solo external/email, solo ACCEPTED) | F-ING-04; Q-115; Q-116; Q-119 | `#autoMatch` 140-163; `FactsHandler#updateMemberIndex` | AUT |
| R-18 | audit di riprova/abbina (`TRANSITION`) e dell'automatico (job) | Q-117; F-AUD-01 | `#auditResolution` 211-233 | RES, AUT |
| R-19 | guardie di ruolo (inbound.handle, actiontype.custom, SYSTEM solo ADMIN, demo.simulate; ingresso senza guardia) | docs/08 §2; docs/06 §3; Q-89 | `@RequiresRole`; `EventTypeService` 101-103 | RES, ETY, SIM, SCN, ACC, TXN |
| R-20 | tipi CUSTOM: tutto tranne `code`; formato del codice e categorie | §3; F-ING-06; Q-89 | `EventTypeService#create/#customDraft` 58-160 | ETY |
| R-21 | tipi SYSTEM: solo name, description, enabled, icon | §3 | `EventTypeService#update` 84-124 | ETY |
| R-22 | campi `data.*` dello schema per il costruttore di condizioni | §3; BO-06 | `SchemaFields` | ETY |
| R-23 | registro fonti gestibile (`POST`/`PUT /v1/sources`) | §3; F-ING-05 | solo `GET` (`RegistryController`) | FON |
| R-24 | transazione ⇒ `purchase.completed` `txn-<orderId>`; reso ⇒ `purchase.returned` | §3; F-ING-07; Q-49 | `TransactionsController` 44-90 | TXN |
| R-25 | simulatore: default, sample con variazioni, origine `SIMULATOR`, 1–20 | §2-§3; F-DEMO-03; BO-28 | `SimulatorController` 49-67 | SIM |
| R-26 | esecutore scenari: ritardo ≤ 10 s, expect, avanzamento, origine, `{run}` | §3, §5; docs/10 §8; Q-129; Q-130 | `ScenarioService` 71-139 | SCN |
| R-27 | espressioni `at` di docs/10 §1 su Europe/Rome | docs/10 §1, §8 | `ScenarioTime#resolve` 24-47 | SCT |
| R-28 | pulizia di `inbound_event` oltre 7 giorni / 20 000 righe | §2 | — | — |

## 2. Rami del codice senza specifica e regole non implementate

**Rami senza specifica** (mappati su righe Q-In che asseriscono il comportamento attuale, §6):

| Ramo | Codice | Righe |
|---|---|---|
| subject senza prefisso trattato come id membro | `IngestionService` 267-268 | MBR (forma «senza prefisso», 6 righe) |
| prefisso sconosciuto, `member:` vuoto, `external:` con maiuscole diverse ⇒ `UNMATCHED` | `IngestionService` 257-268; `MemberIndexRepository` | MBR (3 righe) |
| tipo senza `data_schema` ⇒ nessuna validazione | `IngestionService` 153 | TYP |
| dedup solo sugli `ACCEPTED` (un `REJECTED`/`UNMATCHED` precedente non conta) | `IngestionService` 167; `InboundEventRepository#acceptedExists` | DUP (3 righe) |
| fonte in forma breve accettata e normalizzata | `IngestionService#normalizeSource` 293-295 | SRC |
| URN di fonte con codice vuoto ⇒ `SOURCE_DISABLED` invece di 400 | `IngestionService` 297-300 | SRC |
| riga `ACCEPTED` col subject normalizzato | `IngestionService` 102-103 | ACC |
| finestra di 720 ore (non 30 giorni di calendario) al cambio dell'ora | `IngestionService` 53, 162 | TIM (2 righe) |
| custom da fonte con elenco che non lo contiene | `Source#allows` | TYP |
| Abbina: codici `MEMBER_REQUIRED`/`MEMBER_NOT_FOUND`, `memberId` ripulito dagli spazi | `InboundResolutionService` 106-119 | RES (5 righe) |
| intestazione `X-LH-Actor` non valida trattata come ANALYST | lh-common `ActorContext#parse` | RES, ETY |
| limiti di 60 caratteri per codice e nome, categoria di default ENGAGEMENT, abilitato di default, `sampleData` di default `{}` | `EventTypeService` 65, 131-160 | ETY (6 righe) |
| tipo di sistema: nome vuoto ⇒ nome invariato | `EventTypeService` 118 | ETY |
| transazioni: `kind` senza maiuscole; `amount`/`currency` mancanti ⇒ 400 (non `INVALID_DATA`) | `TransactionsController` 74-86 | TXN (3 righe) |
| simulatore: `count` limitato a 1…20 invece di rifiutato | `SimulatorController` 51 | SIM (3 righe) |
| scenari: fonte di default `simulator`, `expect` non rispettato ⇒ esecuzione comunque `DONE`, `at` non valido ⇒ `FAILED` | `ScenarioService` 94-103, 114 | SCN (3 righe) |
| `@last<Giorno>` per ogni giorno della settimana; ora inesistente/doppia al cambio dell'ora | `ScenarioTime` 36-44 | SCT (3 righe) |

**Regole non implementate** alla stesura (righe DIVERGENZA): R-14 storico demo (MON-010), R-23
`POST`/`PUT /v1/sources` (FON-002…004), filtri `from`/`to`/`q` di R-13 (MON-008, MON-009), variazioni casuali e origine
`SIMULATOR` di R-25 (SIM-008, SIM-015), `@now`/`@today`/`@lastWeekday` senza orario di R-27 (SCN-024, SCT-021…023).
Tutte implementate in seguito (§5, colonna *Stato*). R-28 (pulizia di `inbound_event`) non ha codice né un punto d'ingresso da provare: nessuna riga eseguibile finché non esiste
il job.

## 3. Casi per area

### 3.1 FRM — Forma dell'envelope (passo 1)

**Regola.** `POST /v1/events` accetta un CloudEvent JSON; obbligatori in ingresso `specversion` (= `"1.0"`), `id`, `source`,
`type`, `subject`, `time` (RFC 3339), `data` (oggetto). Errore di forma ⇒ `400` RFC 9457 e **nulla salvato**; i rifiuti di
business sono invece `202` con `status=REJECTED` (ingestion §3, §5.1; docs/05 §2; `contracts/events/envelope.schema.json`;
F-ING-01). Codice: `IngestionService#validateForm` e `#parseTime` (righe 227-254), `EventsController`.

| Ingresso | Classi valide | Classi non valide |
|---|---|---|
| `specversion` | `"1.0"` | assente, `""`, `"1"`, `"0.3"` |
| `id`, `source`, `type`, `subject` | stringa non vuota | assente, `""`, solo spazi |
| `time` | RFC 3339 con offset `Z`/`+02:00`, `t`/`z` minuscole, frazioni di secondo | assente, `24/09/2026`, solo data, senza secondi, senza offset, 29/2 di anno non bisestile, ora 25, spazio al posto di `T` |
| `data` | oggetto | assente, `null`, array, stringa |
| corpo | JSON, `application/json` o `application/cloudevents+json`, attributi di estensione | non JSON, vuoto |

**Strategia.** Guasto singolo: ogni classe non valida da sola su un evento altrimenti valido (i campi sono indipendenti:
il primo campo mancante basta per il `400`); le classi valide non banali (content-type CloudEvents, estensioni) una riga
ciascuna; le varianti valide di `time` sono in TIM. L'interazione con gli altri passi è in PIP.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-FRM-001 | specversion assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-002 | specversion 0.3 | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-003 | specversion 1 (senza .0) | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-004 | specversion vuoto | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-005 | id assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-006 | id vuoto | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-007 | id di soli spazi | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-008 | source assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-009 | source vuoto | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-010 | type assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-011 | type di soli spazi | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-012 | subject assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-013 | subject di soli spazi | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-014 | time assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-015 | time 24/09/2026 | 400, nulla salvato | ingestion §5.1; docs/05 §2; RFC 3339 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-016 | time solo data 2026-09-24 | 400 | ingestion §5.1; docs/05 §2; RFC 3339 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-017 | time senza secondi 2026-09-24T10:00Z | 400 | ingestion §5.1; docs/05 §2; RFC 3339 (secondi obbligatori) | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-018 | time senza fuso 2026-09-24T10:00:00 | 400 | ingestion §5.1; docs/05 §2; RFC 3339 (offset obbligatorio) | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-019 | time 29 febbraio di anno non bisestile (2026-02-29T10:00:00Z) | 400 | ingestion §5.1; docs/05 §2; RFC 3339 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-020 | time con ora 25 | 400 | ingestion §5.1; docs/05 §2; RFC 3339 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-021 | time con spazio al posto di T | 400 | ingestion §5.1; docs/05 §2; RFC 3339 §5.6 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-022 | data assente | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-023 | data null | 400, nulla salvato | ingestion §5.1; docs/05 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-024 | data array [] | 400: data deve essere un oggetto (DIVERGENZA) | ingestion §5.1; docs/05 §2; contracts/events/envelope.schema.json (data: object) | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-025 | data stringa | 400: data deve essere un oggetto (DIVERGENZA) | ingestion §5.1; docs/05 §2; contracts/events/envelope.schema.json (data: object) | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-026 | corpo non JSON | 400 RFC 9457 (DIVERGENZA: oggi 500) | ingestion §3 (errori di forma → 400); docs/06 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-027 | corpo vuoto | 400 RFC 9457 (DIVERGENZA: oggi 500) | ingestion §3; docs/06 §2 | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-028 | content-type application/cloudevents+json | 202 ACCEPTED | docs/05 §2 (content-type application/cloudevents+json) | `TestbookIngPipelineIT#frm` |
| TB-ING-FRM-029 | attributo di estensione sconosciuto (foo) | 202 ACCEPTED (estensioni tollerate) | docs/05 §2; contracts/events/envelope.schema.json (additionalProperties) | `TestbookIngPipelineIT#frm` |

### 3.2 PIP — Ordine della pipeline (primo fallimento vince)

**Regola.** Pipeline di accettazione in ordine; al primo fallimento si salva `inbound_event` con l'esito e ci si ferma:
1 forma (`400`) → 2 fonte (`SOURCE_DISABLED`) → 3 tipo (`UNKNOWN_TYPE`, poi `TYPE_NOT_ALLOWED`) → 4 `data`
(`INVALID_DATA`) → 5 `time` (`INVALID_TIME`) → 6 dedup (`DUPLICATE`) → 7 membro (`UNMATCHED` / `MEMBER_NOT_ACTIVE`) →
8 `ACCEPTED` (ingestion §5; docs/17 US-E01-03). Codice: `IngestionService#evaluate` (righe 124-189).

| Guasto (flag) | Come è provocato |
|---|---|
| FORM | `specversion: "0.3"` |
| SOURCE | fonte nuova disabilitata dopo l'eventuale primo invio |
| UNKNOWN | `type: tb.unknown.type` |
| NOTALLOWED | fonte con `allowed_types = [survey.completed]` |
| DATA | `amount: -1` |
| TIME | `time` = adesso + 10 min |
| DUP | stesso (fonte, id) già `ACCEPTED` da un invio precedente valido |
| UNMATCHED | `member:<id>` non indicizzato |
| NOTACTIVE | `member:<id>` `BLOCKED` |

**Strategia.** 9 flag booleani ⇒ 2⁹ = 512 combinazioni (> 64): riduzione a **nessun guasto** (1) + **ogni guasto da
solo** (9) + **tutte le coppie** di guasti (36 − 1 coppia impossibile UNMATCHED×NOTACTIVE = 35) + **catene** «tutti i guasti
dal passo k in poi» per k = 1…6 (6). Le coppie provano l'ordine per ogni coppia di passi (vince sempre il passo precedente),
le catene che nessun passo successivo «ruba» l'esito. Per ogni riga: HTTP, `status`/`rejectCode`, numero di righe salvate
(`400` ⇒ nessuna nuova riga) e pubblicazioni su `lh.actions.v1` (solo `ACCEPTED`).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-PIP-001 | nessun guasto | 202 ACCEPTED, 1 pubblicazione | ingestion §5.8, §7 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-002 | solo specversion 0.3 | 400, nulla salvato | ingestion §5 §5.1 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-003 | solo fonte disabilitata | REJECTED/SOURCE_DISABLED | ingestion §5 §5.2 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-004 | solo tipo sconosciuto | REJECTED/UNKNOWN_TYPE | ingestion §5 §5.3 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-005 | solo tipo non ammesso dalla fonte | REJECTED/TYPE_NOT_ALLOWED | ingestion §5 §5.3 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-006 | solo amount -1 | REJECTED/INVALID_DATA | ingestion §5 §5.4 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-007 | solo time = adesso + 10 min | REJECTED/INVALID_TIME | ingestion §5 §5.5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-008 | solo (fonte, id) già ACCEPTED | DUPLICATE | ingestion §5 §5.6 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-009 | solo membro non in indice | UNMATCHED | ingestion §5 §5.7 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-010 | solo membro BLOCKED | REJECTED/MEMBER_NOT_ACTIVE | ingestion §5 §5.7 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-011 | specversion 0.3 + fonte disabilitata | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-012 | specversion 0.3 + tipo sconosciuto | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-013 | specversion 0.3 + tipo non ammesso dalla fonte | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-014 | specversion 0.3 + amount -1 | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-015 | specversion 0.3 + time = adesso + 10 min | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-016 | specversion 0.3 + (fonte, id) già ACCEPTED | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-017 | specversion 0.3 + membro non in indice | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-018 | specversion 0.3 + membro BLOCKED | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-019 | fonte disabilitata + tipo sconosciuto | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-020 | fonte disabilitata + tipo non ammesso dalla fonte | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-021 | fonte disabilitata + amount -1 | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-022 | fonte disabilitata + time = adesso + 10 min | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-023 | fonte disabilitata + (fonte, id) già ACCEPTED | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-024 | fonte disabilitata + membro non in indice | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-025 | fonte disabilitata + membro BLOCKED | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-026 | tipo sconosciuto + tipo non ammesso dalla fonte | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-027 | tipo sconosciuto + amount -1 | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-028 | tipo sconosciuto + time = adesso + 10 min | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-029 | tipo sconosciuto + (fonte, id) già ACCEPTED | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-030 | tipo sconosciuto + membro non in indice | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-031 | tipo sconosciuto + membro BLOCKED | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-032 | tipo non ammesso dalla fonte + amount -1 | REJECTED/TYPE_NOT_ALLOWED (vince tipo non ammesso dalla fonte) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-033 | tipo non ammesso dalla fonte + time = adesso + 10 min | REJECTED/TYPE_NOT_ALLOWED (vince tipo non ammesso dalla fonte) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-034 | tipo non ammesso dalla fonte + (fonte, id) già ACCEPTED | REJECTED/TYPE_NOT_ALLOWED (vince tipo non ammesso dalla fonte) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-035 | tipo non ammesso dalla fonte + membro non in indice | REJECTED/TYPE_NOT_ALLOWED (vince tipo non ammesso dalla fonte) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-036 | tipo non ammesso dalla fonte + membro BLOCKED | REJECTED/TYPE_NOT_ALLOWED (vince tipo non ammesso dalla fonte) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-037 | amount -1 + time = adesso + 10 min | REJECTED/INVALID_DATA (vince amount -1) | ingestion §5 §5.4; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-038 | amount -1 + (fonte, id) già ACCEPTED | REJECTED/INVALID_DATA (vince amount -1) | ingestion §5 §5.4; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-039 | amount -1 + membro non in indice | REJECTED/INVALID_DATA (vince amount -1) | ingestion §5 §5.4; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-040 | amount -1 + membro BLOCKED | REJECTED/INVALID_DATA (vince amount -1) | ingestion §5 §5.4; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-041 | time = adesso + 10 min + (fonte, id) già ACCEPTED | REJECTED/INVALID_TIME (vince time = adesso + 10 min) | ingestion §5 §5.5; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-042 | time = adesso + 10 min + membro non in indice | REJECTED/INVALID_TIME (vince time = adesso + 10 min) | ingestion §5 §5.5; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-043 | time = adesso + 10 min + membro BLOCKED | REJECTED/INVALID_TIME (vince time = adesso + 10 min) | ingestion §5 §5.5; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-044 | (fonte, id) già ACCEPTED + membro non in indice | DUPLICATE (vince (fonte, id) già ACCEPTED) | ingestion §5 §5.6; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-045 | (fonte, id) già ACCEPTED + membro BLOCKED | DUPLICATE (vince (fonte, id) già ACCEPTED) | ingestion §5 §5.6; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-046 | specversion 0.3 + fonte disabilitata + tipo sconosciuto + tipo non ammesso dalla fonte + amount -1 + time = adesso + 10 min + (fonte, id) già ACCEPTED + membro non in indice | 400, nulla salvato (vince specversion 0.3) | ingestion §5 §5.1; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-047 | fonte disabilitata + tipo sconosciuto + tipo non ammesso dalla fonte + amount -1 + time = adesso + 10 min + (fonte, id) già ACCEPTED + membro non in indice | REJECTED/SOURCE_DISABLED (vince fonte disabilitata) | ingestion §5 §5.2; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-048 | tipo sconosciuto + tipo non ammesso dalla fonte + amount -1 + time = adesso + 10 min + (fonte, id) già ACCEPTED + membro non in indice | REJECTED/UNKNOWN_TYPE (vince tipo sconosciuto) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-049 | tipo non ammesso dalla fonte + amount -1 + time = adesso + 10 min + (fonte, id) già ACCEPTED + membro non in indice | REJECTED/TYPE_NOT_ALLOWED (vince tipo non ammesso dalla fonte) | ingestion §5 §5.3; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-050 | amount -1 + time = adesso + 10 min + (fonte, id) già ACCEPTED + membro non in indice | REJECTED/INVALID_DATA (vince amount -1) | ingestion §5 §5.4; ordine §5 | `TestbookIngPipelineIT#pip` |
| TB-ING-PIP-051 | time = adesso + 10 min + (fonte, id) già ACCEPTED + membro non in indice | REJECTED/INVALID_TIME (vince time = adesso + 10 min) | ingestion §5 §5.5; ordine §5 | `TestbookIngPipelineIT#pip` |

### 3.3 SRC — Fonte esistente, abilitata, tipi ammessi (passi 2-3)

**Regola.** La fonte (codice di `urn:loyaltyhub:source:<codice>`) deve esistere ed essere abilitata, altrimenti
`REJECTED/SOURCE_DISABLED` (fonte sconosciuta compresa: `pos-legacy`, Q-129). Il tipo deve essere nell'elenco
`allowed_types` della fonte, **elenco vuoto = tutti** (ingestion §2, §5.2-5.3; F-ING-05). Tipi ammessi per fonte come la
colonna *Origine* di docs/05 §3 (docs/10 §3). Codice: `IngestionService` righe 137-150, `Source#allows`,
`#normalizeSource`/`#sourceCodeOf` (293-300).

| Ingresso | Classi |
|---|---|
| fonte | 5 fonti HTTP del seed con elenco · `internal`, `simulator` (elenco vuoto) · sconosciuta · disabilitata (con e senza il tipo in elenco) · forma breve senza URN · URN di servizio · URN estraneo · maiuscole · codice vuoto |
| tipo | i 10 tipi esterni di docs/05 §3 + un tipo interno (`tier.upgraded`) come classe «tipo interno» |

**Strategia.** Tabella **completa** fonte HTTP × tipo = 5 × 11 = 55 (≤ 64), atteso dalla colonna *Origine* di docs/05 §3;
fonti a elenco vuoto × 3 tipi rappresentativi (6); poi una riga per ogni classe non valida o speciale della fonte (9).
`data` = `sample_data` del seed del tipo, membro nuovo `ACTIVE`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-SRC-001 | fonte crm · tipo purchase.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-002 | fonte crm · tipo purchase.returned | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-003 | fonte crm · tipo ebill.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-004 | fonte crm · tipo directdebit.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-005 | fonte crm · tipo selfreading.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-006 | fonte crm · tipo app.login.daily | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-007 | fonte crm · tipo survey.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-008 | fonte crm · tipo quiz.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-009 | fonte crm · tipo review.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-010 | fonte crm · tipo newsletter.subscribed | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-011 | fonte crm · tipo tier.upgraded | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-012 | fonte app · tipo purchase.completed | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-013 | fonte app · tipo purchase.returned | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-014 | fonte app · tipo ebill.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-015 | fonte app · tipo directdebit.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-016 | fonte app · tipo selfreading.submitted | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-017 | fonte app · tipo app.login.daily | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-018 | fonte app · tipo survey.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-019 | fonte app · tipo quiz.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-020 | fonte app · tipo review.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-021 | fonte app · tipo newsletter.subscribed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-022 | fonte app · tipo tier.upgraded | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-023 | fonte ecommerce · tipo purchase.completed | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-024 | fonte ecommerce · tipo purchase.returned | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-025 | fonte ecommerce · tipo ebill.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-026 | fonte ecommerce · tipo directdebit.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-027 | fonte ecommerce · tipo selfreading.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-028 | fonte ecommerce · tipo app.login.daily | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-029 | fonte ecommerce · tipo survey.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-030 | fonte ecommerce · tipo quiz.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-031 | fonte ecommerce · tipo review.submitted | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-032 | fonte ecommerce · tipo newsletter.subscribed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-033 | fonte ecommerce · tipo tier.upgraded | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-034 | fonte billing · tipo purchase.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-035 | fonte billing · tipo purchase.returned | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-036 | fonte billing · tipo ebill.activated | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-037 | fonte billing · tipo directdebit.activated | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-038 | fonte billing · tipo selfreading.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-039 | fonte billing · tipo app.login.daily | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-040 | fonte billing · tipo survey.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-041 | fonte billing · tipo quiz.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-042 | fonte billing · tipo review.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-043 | fonte billing · tipo newsletter.subscribed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-044 | fonte billing · tipo tier.upgraded | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-045 | fonte partner · tipo purchase.completed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-046 | fonte partner · tipo purchase.returned | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-047 | fonte partner · tipo ebill.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-048 | fonte partner · tipo directdebit.activated | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-049 | fonte partner · tipo selfreading.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-050 | fonte partner · tipo app.login.daily | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-051 | fonte partner · tipo survey.completed | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-052 | fonte partner · tipo quiz.completed | ACCEPTED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-053 | fonte partner · tipo review.submitted | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-054 | fonte partner · tipo newsletter.subscribed | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-055 | fonte partner · tipo tier.upgraded | REJECTED/TYPE_NOT_ALLOWED | docs/05 §3 (colonna Origine); docs/10 §3; ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-056 | fonte internal (tipi ammessi vuoti) · tipo purchase.completed | ACCEPTED (elenco vuoto = tutti) | ingestion §2 (allowed_types vuoto = tutti) | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-057 | fonte internal (tipi ammessi vuoti) · tipo newsletter.subscribed | ACCEPTED (elenco vuoto = tutti) | ingestion §2 (allowed_types vuoto = tutti) | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-058 | fonte internal (tipi ammessi vuoti) · tipo tier.upgraded | ACCEPTED (elenco vuoto = tutti) | ingestion §2 (allowed_types vuoto = tutti) | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-059 | fonte simulator (tipi ammessi vuoti) · tipo purchase.completed | ACCEPTED (elenco vuoto = tutti) | ingestion §2 (allowed_types vuoto = tutti) | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-060 | fonte simulator (tipi ammessi vuoti) · tipo newsletter.subscribed | ACCEPTED (elenco vuoto = tutti) | ingestion §2 (allowed_types vuoto = tutti) | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-061 | fonte simulator (tipi ammessi vuoti) · tipo tier.upgraded | ACCEPTED (elenco vuoto = tutti) | ingestion §2 (allowed_types vuoto = tutti) | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-062 | fonte sconosciuta pos-legacy | REJECTED/SOURCE_DISABLED | ingestion §5.2; Q-129 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-063 | fonte esistente ma disabilitata (elenco vuoto) | REJECTED/SOURCE_DISABLED | ingestion §5.2; F-ING-05 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-064 | fonte disabilitata che ammetterebbe il tipo | REJECTED/SOURCE_DISABLED | ingestion §5.2; F-ING-05 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-065 | fonte abilitata con elenco che ammette il tipo | ACCEPTED | ingestion §5.3 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-066 | codice fonte in maiuscolo (ECOMMERCE) | REJECTED/SOURCE_DISABLED (fonte inesistente) | ingestion §5.2 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-067 | fonte in forma breve senza URN (ecommerce) | Q-258 DECISA: 400, nulla salvato (la forma breve è ammessa solo ai chiamanti interni) | docs/05 §2 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-068 | fonte con URN di servizio (urn:loyaltyhub:service:ecommerce) | REJECTED/SOURCE_DISABLED: non è una fonte (DIVERGENZA) | docs/05 §2 (source delle azioni = urn:loyaltyhub:source:&lt;codice&gt;); ingestion §5.2 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-069 | fonte con URN estraneo che termina con :ecommerce (urn:altro:ecommerce) | REJECTED/SOURCE_DISABLED: fonte inesistente (DIVERGENZA) | docs/05 §2; ingestion §5.2 | `TestbookIngPipelineIT#src` |
| TB-ING-SRC-070 | URN di fonte con codice vuoto (urn:loyaltyhub:source:) | Q-258: REJECTED/SOURCE_DISABLED (nessun 400) | docs/05 §2; envelope.schema.json | `TestbookIngPipelineIT#src` |

### 3.4 FON — Registro fonti via API (F-ING-05)

**Regola.** `GET/POST/PUT /v1/sources`, `/v1/sources/{code}`: abilitazione e tipi ammessi, gestiti da BO-09 con la capacità
`program.config` (solo ADMIN, docs/08 §2) (ingestion §3; docs/17 US-E01-11). Codice: solo `GET /v1/sources`
(`RegistryController`) — **regola non implementata** per `POST`/`PUT`.

**Strategia.** Una riga per operazione prevista (elenco, modifica ADMIN con effetto sulla pipeline, creazione ADMIN) e per
la guardia (MARKETING). Le righe di modifica restano rosse finché l'API non esiste.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-FON-001 | elenco delle fonti | le 7 fonti del seed con stato e tipi ammessi | ingestion §3; docs/10 §3 | `TestbookIngConfigIT#fon` |
| TB-ING-FON-002 | ADMIN spegne una fonte da API | 200; gli eventi successivi REJECTED/SOURCE_DISABLED (DIVERGENZA: PUT /v1/sources/{code} assente) | ingestion §3; F-ING-05; BO-09; docs/17 US-E01-11 | `TestbookIngConfigIT#fon` |
| TB-ING-FON-003 | ADMIN crea una fonte da API | 201 (DIVERGENZA: POST /v1/sources assente) | ingestion §3; F-ING-05 | `TestbookIngConfigIT#fon` |
| TB-ING-FON-004 | MARKETING modifica una fonte | 403 FORBIDDEN_ROLE (DIVERGENZA: endpoint assente) | docs/08 §2 (program.config: ADMIN); docs/17 US-E01-11 | `TestbookIngConfigIT#fon` |

### 3.5 TYP — Tipo noto e abilitato (passo 3)

**Regola.** Il tipo deve essere noto e abilitato (`UNKNOWN_TYPE`); `type` breve e completo sono equivalenti
(`purchase.completed` ≡ `io.loyaltyhub.action.purchase.completed`) e si pubblica sempre la forma completa; solo la famiglia
`action` (ingestion §5.3; docs/05 §2). Codice: `IngestionService` righe 143-147, `#normalizeType`/`#shortType` (283-291);
`EventType#hasSchema` (ramo senza specifica: tipo senza schema ⇒ nessuna validazione).

| Ingresso | Classi |
|---|---|
| forma del `type` | breve · completa · famiglia `fact`/`effect` · maiuscole · doppio prefisso |
| registro | di sistema abilitato · custom abilitato · custom disabilitato · custom senza schema · inesistente |

**Strategia.** Una riga per classe (guasto singolo); la precedenza su `TYPE_NOT_ALLOWED` è in PIP; la disabilitazione di un
tipo di sistema è in ETY.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-TYP-001 | type breve purchase.completed | ACCEPTED; type pubblicato in forma completa | ingestion §5.3; docs/05 §2 (breve ≡ completo) | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-002 | type completo io.loyaltyhub.action.purchase.completed | ACCEPTED; stesso type pubblicato | ingestion §5.3; docs/05 §2 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-003 | type sconosciuto tb.never.seen | REJECTED/UNKNOWN_TYPE | ingestion §5.3 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-004 | famiglia fact (io.loyaltyhub.fact.tier.upgraded) | REJECTED/UNKNOWN_TYPE (solo azioni) | ingestion §5.3; docs/05 §1 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-005 | famiglia effect (io.loyaltyhub.effect.points.grant) | REJECTED/UNKNOWN_TYPE | ingestion §5.3; docs/05 §1 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-006 | maiuscole Purchase.Completed | REJECTED/UNKNOWN_TYPE | ingestion §5.3 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-007 | doppio prefisso action.purchase.completed | REJECTED/UNKNOWN_TYPE | ingestion §5.3 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-008 | tipo custom disabilitato | REJECTED/UNKNOWN_TYPE | ingestion §5.3 (noto e abilitato) | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-009 | tipo custom abilitato | ACCEPTED | ingestion §5.3; F-ING-06 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-010 | tipo custom senza JSON Schema, data qualunque | Q-270: ACCEPTED, il passo 4 non respinge (ramo senza specifica) | ingestion §5.4; docs/17 US-E01-03 | `TestbookIngPipelineIT#typ` |
| TB-ING-TYP-011 | tipo custom da fonte con elenco che non lo contiene (ecommerce) | Q-270: REJECTED/TYPE_NOT_ALLOWED (docs/05 §3: custom da «qualsiasi fonte») | ingestion §5.3; docs/05 §3 EVT-ACT-9x | `TestbookIngPipelineIT#typ` |

### 3.6 SCH — `data` contro lo schema del tipo (passo 4)

**Regola.** `data` valido contro il JSON Schema del tipo, altrimenti `REJECTED/INVALID_DATA` col dettaglio degli errori
campo per campo (ingestion §5.4, §7; BO-26). **Oracolo del contenuto**: `contracts/events/action/*.schema.json` (precedenza
2, CLAUDE.md §6) per gli 8 tipi che ne hanno uno; per gli altri docs/05 §3 (campi `*` obbligatori, enum, `rating 1–5`) e,
dove tacciono, lo schema del seed (`seed/event-types.json`, precedenza 6). Codice: `IngestionService` righe 152-158 con lo
schema di `event_type.data_schema` caricato dal seed (`DemoSeeder#seedEventTypes`).

| Tipo | Campi e vincoli (oracolo) | Valori provati |
|---|---|---|
| `purchase.completed` | `orderId*` string ≥ 1 · `amount*` number ≥ 0 · `currency*` string ≥ 1 · `channel` ONLINE/STORE/APP · `items[]` {`sku` string, `quantity` integer ≥ 0, `unitPrice` number ≥ 0} (contratto) | assente, null, vuoto, 1 car., tipo sbagliato; amount −0,01 / −20 / 0 / 0,01 / 3 decimali / 10⁹ / stringa; currency 1-3-4 car.; ogni enum + minuscolo + sconosciuto + null; quantity −1/0/1,5/2.0; unitPrice −0,01/0; extra |
| `purchase.returned` | `orderId*`, `amount*` > 0 (docs/05 + seed) | assenti, 0, 0,01, −5, stringa |
| `ebill.activated`, `directdebit.activated` | `contractId*` string ≥ 1 | valido, assente, vuoto, numero |
| `selfreading.submitted` | `meterId*`, `reading*` number ≥ 0 | assenti, −1, 0, decimale, stringa |
| `app.login.daily` | `platform` IOS/ANDROID/WEB (contratto) | ogni enum, minuscolo, sconosciuto, assente |
| `survey.completed` | `surveyId*`, `score` integer 0–100 | −1, 0, 1, 99, 100, 101, 50,5, assente |
| `quiz.completed` | `quizId*`, `correctAnswers*` ≥ 0, `totalQuestions*` ≥ 1 | assenti, −1/0, 0/1, correct > total |
| `review.submitted` | `productId*`, `rating*` 1–5 (docs/05 §3) | 0, 1, 2, 4, 5, 6, 4,5, stringa, assenti |
| `newsletter.subscribed`, `member.registered`, `member.birthday`, `tier.upgraded` | docs/05 §3 + seed | vuoto, extra, tipo sbagliato, obbligatori |
| `member.profile.completed`, `instantwin.won`, `achievement.completed`, `badge.awarded`, `referral.completed`, `reward.redeemed` | contratti (pattern `^[A-Z][A-Z0-9-]{2,39}$`, `^MBR-[0-9]{6}$`, enum, minimum 1, minLength 1, required) | valido, obbligatori assenti, ogni enum + sconosciuto, pattern min−1/min/max/max+1, minimum 0/1, vuoti |

**Strategia.** Per tipo: una riga valida completa, ogni campo obbligatorio assente da solo, e per ogni campo i valori limite
(min−1, min, min+1, max−1, max, max+1 dove esistono), i tipi sbagliati, null/vuoto e ogni valore d'enum più uno sconosciuto,
un campo alla volta (guasto singolo). Nessuna combinazione tra campi: la validazione JSON Schema li valuta in modo
indipendente e riporta tutti gli errori (una combinazione è provata in MON-006). Fonte `simulator` (elenco vuoto).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-SCH-001 | purchase.completed: dati completi validi · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-002 | purchase.completed: solo i campi obbligatori · data `{"orderId":"ORD-TB-1","amount":10,"currency":"EUR"}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-003 | purchase.completed: orderId assente · data `{"amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su orderId | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01; ingestion §7 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-004 | purchase.completed: orderId vuoto · data `{"orderId":"","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su orderId | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-005 | purchase.completed: orderId di 1 carattere · data `{"orderId":"X","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-006 | purchase.completed: orderId numerico · data `{"orderId":123,"amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su orderId | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (type string) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-007 | purchase.completed: amount assente · data `{"orderId":"ORD-TB-1","currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su amount | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01; ingestion §7 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-008 | purchase.completed: amount null · data `{"orderId":"ORD-TB-1","amount":null,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su amount | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-009 | purchase.completed: amount -0,01 · data `{"orderId":"ORD-TB-1","amount":-0.01,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su amount | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-010 | purchase.completed: amount -20 (SCN-BAD-EVENT) · data `{"orderId":"ORD-TB-1","amount":-20,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su amount | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01; docs/10 §8 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-011 | purchase.completed: amount 0 · data `{"orderId":"ORD-TB-1","amount":0,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | DIVERGENZA: ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minimum 0, incluso) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-012 | purchase.completed: amount 0,01 · data `{"orderId":"ORD-TB-1","amount":0.01,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-013 | purchase.completed: amount con 3 decimali 64,999 · data `{"orderId":"ORD-TB-1","amount":64.999,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (nessun multipleOf) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-014 | purchase.completed: amount molto grande 1 000 000 000 · data `{"orderId":"ORD-TB-1","amount":1000000000,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-015 | purchase.completed: amount stringa "10" · data `{"orderId":"ORD-TB-1","amount":"10","currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su amount | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (type number) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-016 | purchase.completed: currency assente · data `{"orderId":"ORD-TB-1","amount":64.9,"channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su currency | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-017 | purchase.completed: currency vuota · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su currency | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-018 | purchase.completed: currency di 1 carattere (E) · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"E","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | DIVERGENZA: ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-019 | purchase.completed: currency EUR · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-020 | purchase.completed: currency di 4 caratteri (EURO) · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EURO","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | DIVERGENZA: ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (nessun maxLength) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-021 | purchase.completed: currency numerica 978 · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":978,"channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su currency | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (type string) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-022 | purchase.completed: channel ONLINE · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-023 | purchase.completed: channel STORE · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"STORE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-024 | purchase.completed: channel APP · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"APP","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-025 | purchase.completed: channel minuscolo online · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"online","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su channel | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-026 | purchase.completed: channel sconosciuto KIOSK · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"KIOSK","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su channel | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-027 | purchase.completed: channel null · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":null,"items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su channel | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-028 | purchase.completed: items vuoto [] · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-029 | purchase.completed: items oggetto invece di array · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":{}}` | REJECTED/INVALID_DATA su items | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (type array) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-030 | purchase.completed: items[0].quantity 0 · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":0,"unitPrice":39.9}]}` | DIVERGENZA: ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-031 | purchase.completed: items[0].quantity -1 · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":-1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su quantity | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-032 | purchase.completed: items[0].quantity 1,5 · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1.5,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su quantity | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (type integer) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-033 | purchase.completed: items[0].quantity 2.0 (intero con parte decimale nulla) · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":2.0,"unitPrice":39.9}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (JSON Schema 2020-12: 2.0 è integer) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-034 | purchase.completed: items[0].unitPrice 0 · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":0}]}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-035 | purchase.completed: items[0].unitPrice -0,01 · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":-0.01}]}` | REJECTED/INVALID_DATA su unitPrice | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-036 | purchase.completed: items[0].sku numerico · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":100,"category":"casa","quantity":1,"unitPrice":39.9}]}` | REJECTED/INVALID_DATA su sku | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (type string) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-037 | purchase.completed: items[0] stringa invece di oggetto · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":["SKU-1"]}` | REJECTED/INVALID_DATA su items | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (items: object) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-038 | purchase.completed: campo aggiuntivo data.coupon · data `{"orderId":"ORD-TB-1","amount":64.9,"currency":"EUR","channel":"ONLINE","items":[{"sku":"SKU-1","category":"casa","quantity":1,"unitPrice":39.9}],"coupon":"WELCOME"}` | ACCEPTED | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (additionalProperties true) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-039 | purchase.completed: data vuoto {} · data `{}` | REJECTED/INVALID_DATA su orderId | contracts/events/action/purchase.completed.schema.json; docs/05 §3 EVT-ACT-01 (required) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-040 | purchase.returned: reso valido · data `{"orderId":"ORD-TB-2","amount":24.9}` | ACCEPTED | docs/05 §3 EVT-ACT-02; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-041 | purchase.returned: reso senza orderId · data `{"amount":24.9}` | REJECTED/INVALID_DATA su orderId | docs/05 §3 EVT-ACT-02; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-042 | purchase.returned: reso senza amount · data `{"orderId":"ORD-TB-2"}` | REJECTED/INVALID_DATA su amount | docs/05 §3 EVT-ACT-02; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-043 | purchase.returned: reso con amount 0 · data `{"orderId":"ORD-TB-2","amount":0}` | REJECTED/INVALID_DATA su amount | docs/05 §3 EVT-ACT-02; seed/event-types.json (exclusiveMinimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-044 | purchase.returned: reso con amount 0,01 · data `{"orderId":"ORD-TB-2","amount":0.01}` | ACCEPTED | docs/05 §3 EVT-ACT-02; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-045 | purchase.returned: reso con amount -5 · data `{"orderId":"ORD-TB-2","amount":-5}` | REJECTED/INVALID_DATA su amount | docs/05 §3 EVT-ACT-02; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-046 | purchase.returned: reso con amount stringa · data `{"orderId":"ORD-TB-2","amount":"5"}` | REJECTED/INVALID_DATA su amount | docs/05 §3 EVT-ACT-02; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-047 | ebill.activated: ebill.activated valido · data `{"contractId":"CTR-77120"}` | ACCEPTED | docs/05 §3 EVT-ACT-03; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-048 | ebill.activated: ebill.activated senza contractId · data `{}` | REJECTED/INVALID_DATA su contractId | docs/05 §3 EVT-ACT-03; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-049 | ebill.activated: contractId vuoto · data `{"contractId":""}` | REJECTED/INVALID_DATA su contractId | docs/05 §3 EVT-ACT-03; seed/event-types.json (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-050 | ebill.activated: contractId numerico · data `{"contractId":77120}` | REJECTED/INVALID_DATA su contractId | docs/05 §3 EVT-ACT-03; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-051 | directdebit.activated: directdebit.activated valido · data `{"contractId":"CTR-77120"}` | ACCEPTED | docs/05 §3 EVT-ACT-04; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-052 | directdebit.activated: directdebit.activated senza contractId · data `{}` | REJECTED/INVALID_DATA su contractId | docs/05 §3 EVT-ACT-04; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-053 | selfreading.submitted: autolettura valida · data `{"meterId":"MTR-1","reading":14820}` | ACCEPTED | docs/05 §3 EVT-ACT-05; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-054 | selfreading.submitted: senza meterId · data `{"reading":14820}` | REJECTED/INVALID_DATA su meterId | docs/05 §3 EVT-ACT-05; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-055 | selfreading.submitted: senza reading · data `{"meterId":"MTR-1"}` | REJECTED/INVALID_DATA su reading | docs/05 §3 EVT-ACT-05; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-056 | selfreading.submitted: reading -1 · data `{"meterId":"MTR-1","reading":-1}` | REJECTED/INVALID_DATA su reading | docs/05 §3 EVT-ACT-05; seed/event-types.json (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-057 | selfreading.submitted: reading 0 · data `{"meterId":"MTR-1","reading":0}` | ACCEPTED | docs/05 §3 EVT-ACT-05; seed/event-types.json (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-058 | selfreading.submitted: reading decimale 14820,5 · data `{"meterId":"MTR-1","reading":14820.5}` | ACCEPTED | docs/05 §3 EVT-ACT-05; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-059 | selfreading.submitted: reading stringa · data `{"meterId":"MTR-1","reading":"14820"}` | REJECTED/INVALID_DATA su reading | docs/05 §3 EVT-ACT-05; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-060 | app.login.daily: platform IOS · data `{"platform":"IOS"}` | ACCEPTED | contracts/events/action/app.login.daily.schema.json; docs/05 §3 EVT-ACT-06 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-061 | app.login.daily: platform ANDROID · data `{"platform":"ANDROID"}` | ACCEPTED | contracts/events/action/app.login.daily.schema.json; docs/05 §3 EVT-ACT-06 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-062 | app.login.daily: platform WEB · data `{"platform":"WEB"}` | ACCEPTED | contracts/events/action/app.login.daily.schema.json; docs/05 §3 EVT-ACT-06 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-063 | app.login.daily: platform minuscola ios · data `{"platform":"ios"}` | REJECTED/INVALID_DATA su platform | contracts/events/action/app.login.daily.schema.json; docs/05 §3 EVT-ACT-06 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-064 | app.login.daily: platform sconosciuta LINUX · data `{"platform":"LINUX"}` | REJECTED/INVALID_DATA su platform | contracts/events/action/app.login.daily.schema.json; docs/05 §3 EVT-ACT-06 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-065 | app.login.daily: senza platform {} · data `{}` | ACCEPTED | contracts/events/action/app.login.daily.schema.json; docs/05 §3 EVT-ACT-06 (facoltativa) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-066 | survey.completed: senza surveyId · data `{"score":80}` | REJECTED/INVALID_DATA su surveyId | docs/05 §3 EVT-ACT-07; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-067 | survey.completed: score -1 · data `{"surveyId":"SRV-1","score":-1}` | REJECTED/INVALID_DATA su score | docs/05 §3 EVT-ACT-07; seed/event-types.json (0–100) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-068 | survey.completed: score 0 · data `{"surveyId":"SRV-1","score":0}` | ACCEPTED | docs/05 §3 EVT-ACT-07; seed/event-types.json (0–100) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-069 | survey.completed: score 1 · data `{"surveyId":"SRV-1","score":1}` | ACCEPTED | docs/05 §3 EVT-ACT-07; seed/event-types.json (0–100) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-070 | survey.completed: score 99 · data `{"surveyId":"SRV-1","score":99}` | ACCEPTED | docs/05 §3 EVT-ACT-07; seed/event-types.json (0–100) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-071 | survey.completed: score 100 · data `{"surveyId":"SRV-1","score":100}` | ACCEPTED | docs/05 §3 EVT-ACT-07; seed/event-types.json (0–100) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-072 | survey.completed: score 101 · data `{"surveyId":"SRV-1","score":101}` | REJECTED/INVALID_DATA su score | docs/05 §3 EVT-ACT-07; seed/event-types.json (0–100) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-073 | survey.completed: score decimale 50,5 · data `{"surveyId":"SRV-1","score":50.5}` | REJECTED/INVALID_DATA su score | docs/05 §3 EVT-ACT-07; seed/event-types.json (integer) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-074 | survey.completed: score assente · data `{"surveyId":"SRV-1"}` | ACCEPTED | docs/05 §3 EVT-ACT-07; seed/event-types.json (facoltativo) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-075 | quiz.completed: quiz valido · data `{"quizId":"QZ-1","correctAnswers":8,"totalQuestions":10}` | ACCEPTED | docs/05 §3 EVT-ACT-08; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-076 | quiz.completed: senza quizId · data `{"correctAnswers":8,"totalQuestions":10}` | REJECTED/INVALID_DATA su quizId | docs/05 §3 EVT-ACT-08; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-077 | quiz.completed: senza correctAnswers · data `{"quizId":"QZ-1","totalQuestions":10}` | REJECTED/INVALID_DATA su correctAnswers | docs/05 §3 EVT-ACT-08; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-078 | quiz.completed: senza totalQuestions · data `{"quizId":"QZ-1","correctAnswers":8}` | REJECTED/INVALID_DATA su totalQuestions | docs/05 §3 EVT-ACT-08; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-079 | quiz.completed: correctAnswers -1 · data `{"quizId":"QZ-1","correctAnswers":-1,"totalQuestions":10}` | REJECTED/INVALID_DATA su correctAnswers | docs/05 §3 EVT-ACT-08; seed/event-types.json (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-080 | quiz.completed: correctAnswers 0 · data `{"quizId":"QZ-1","correctAnswers":0,"totalQuestions":10}` | ACCEPTED | docs/05 §3 EVT-ACT-08; seed/event-types.json (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-081 | quiz.completed: totalQuestions 0 · data `{"quizId":"QZ-1","correctAnswers":0,"totalQuestions":0}` | REJECTED/INVALID_DATA su totalQuestions | docs/05 §3 EVT-ACT-08; seed/event-types.json (minimum 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-082 | quiz.completed: totalQuestions 1 · data `{"quizId":"QZ-1","correctAnswers":1,"totalQuestions":1}` | ACCEPTED | docs/05 §3 EVT-ACT-08; seed/event-types.json (minimum 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-083 | quiz.completed: correctAnswers 11 &gt; totalQuestions 10 · data `{"quizId":"QZ-1","correctAnswers":11,"totalQuestions":10}` | Q-265 DECISA: REJECTED/INVALID_DATA su correctAnswers (vincolo tra campi dopo lo schema) | docs/05 §3 EVT-ACT-08; seed/event-types.json (nessun vincolo incrociato) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-084 | review.submitted: senza productId · data `{"rating":5}` | REJECTED/INVALID_DATA su productId | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-085 | review.submitted: senza rating · data `{"productId":"SKU-100"}` | REJECTED/INVALID_DATA su rating | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-086 | review.submitted: rating 0 · data `{"productId":"SKU-100","rating":0}` | REJECTED/INVALID_DATA su rating | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-087 | review.submitted: rating 1 · data `{"productId":"SKU-100","rating":1}` | ACCEPTED | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-088 | review.submitted: rating 2 · data `{"productId":"SKU-100","rating":2}` | ACCEPTED | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-089 | review.submitted: rating 4 · data `{"productId":"SKU-100","rating":4}` | ACCEPTED | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-090 | review.submitted: rating 5 · data `{"productId":"SKU-100","rating":5}` | ACCEPTED | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-091 | review.submitted: rating 6 · data `{"productId":"SKU-100","rating":6}` | REJECTED/INVALID_DATA su rating | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-092 | review.submitted: rating 4,5 · data `{"productId":"SKU-100","rating":4.5}` | REJECTED/INVALID_DATA su rating | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json (integer) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-093 | review.submitted: rating stringa "5" · data `{"productId":"SKU-100","rating":"5"}` | REJECTED/INVALID_DATA su rating | docs/05 §3 EVT-ACT-09 (rating* 1–5); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-094 | newsletter.subscribed: data vuoto {} · data `{}` | ACCEPTED | docs/05 §3 EVT-ACT-10 (data —); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-095 | newsletter.subscribed: campo aggiuntivo · data `{"list":"autunno"}` | ACCEPTED | docs/05 §3 EVT-ACT-10 (data —); seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-096 | member.registered: data vuoto {} · data `{}` | ACCEPTED | docs/05 §3 EVT-ACT-20; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-097 | member.registered: channel WEB, referred true · data `{"channel":"WEB","referred":true}` | ACCEPTED | docs/05 §3 EVT-ACT-20; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-098 | member.registered: referred stringa "si" · data `{"channel":"WEB","referred":"si"}` | REJECTED/INVALID_DATA su referred | docs/05 §3 EVT-ACT-20; seed/event-types.json (boolean) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-099 | member.profile.completed: data vuoto {} · data `{}` | ACCEPTED | contracts/events/action/member.profile.completed.schema.json; docs/05 §3 EVT-ACT-21 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-100 | member.profile.completed: memberId MBR-000001 · data `{"memberId":"MBR-000001"}` | ACCEPTED | contracts/events/action/member.profile.completed.schema.json; docs/05 §3 EVT-ACT-21 (pattern ^MBR-[0-9]{6}$) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-101 | member.profile.completed: memberId MBR-1 (non conforme al pattern) · data `{"memberId":"MBR-1"}` | DIVERGENZA: REJECTED/INVALID_DATA su memberId | contracts/events/action/member.profile.completed.schema.json; docs/05 §3 EVT-ACT-21 (pattern) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-102 | member.birthday: age -1 · data `{"age":-1}` | REJECTED/INVALID_DATA su age | docs/05 §3 EVT-ACT-22; seed/event-types.json (minimum 0) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-103 | member.birthday: age 0 · data `{"age":0}` | ACCEPTED | docs/05 §3 EVT-ACT-22; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-104 | member.birthday: age stringa · data `{"age":"34"}` | REJECTED/INVALID_DATA su age | docs/05 §3 EVT-ACT-22; seed/event-types.json (integer) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-105 | tier.upgraded: senza newTier · data `{"previousTier":"SILVER"}` | REJECTED/INVALID_DATA su newTier | docs/05 §3 EVT-ACT-23; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-106 | tier.upgraded: SILVER → GOLD · data `{"previousTier":"SILVER","newTier":"GOLD"}` | ACCEPTED | docs/05 §3 EVT-ACT-23; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-107 | tier.upgraded: newTier numerico · data `{"newTier":3}` | REJECTED/INVALID_DATA su newTier | docs/05 §3 EVT-ACT-23; seed/event-types.json | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-108 | instantwin.won: vincita punti valida · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | ACCEPTED | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-109 | instantwin.won: senza contestCode · data `{"prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | REJECTED/INVALID_DATA su contestCode | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-110 | instantwin.won: senza prizeCode · data `{"contestCode":"IW-AUTUNNO","prizeType":"POINTS","points":500}` | REJECTED/INVALID_DATA su prizeCode | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-111 | instantwin.won: senza prizeType · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","points":500}` | REJECTED/INVALID_DATA su prizeType | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-112 | instantwin.won: prizeType COUPON con rewardCode · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"COUPON","rewardCode":"RWD-COFFEE-5"}` | ACCEPTED | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-113 | instantwin.won: prizeType PHYSICAL · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"PHYSICAL"}` | ACCEPTED | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-114 | instantwin.won: prizeType CASH · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"CASH","points":500}` | REJECTED/INVALID_DATA su prizeType | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-115 | instantwin.won: points 0 · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"POINTS","points":0}` | DIVERGENZA: REJECTED/INVALID_DATA su points | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (minimum 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-116 | instantwin.won: points 1 · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"POINTS","points":1}` | ACCEPTED | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (minimum 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-117 | instantwin.won: contestCode minuscolo iw-autunno · data `{"contestCode":"iw-autunno","prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | DIVERGENZA: REJECTED/INVALID_DATA su contestCode | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (pattern) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-118 | instantwin.won: contestCode di 2 caratteri (IW) · data `{"contestCode":"IW","prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | DIVERGENZA: REJECTED/INVALID_DATA su contestCode | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (pattern: 3–40) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-119 | instantwin.won: contestCode di 3 caratteri (IWA) · data `{"contestCode":"IWA","prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | ACCEPTED | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (pattern: 3–40) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-120 | instantwin.won: contestCode di 40 caratteri · data `{"contestCode":"IWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW","prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | ACCEPTED | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (pattern: 3–40) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-121 | instantwin.won: contestCode di 41 caratteri · data `{"contestCode":"IWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW","prizeCode":"PZ-500","prizeType":"POINTS","points":500}` | DIVERGENZA: REJECTED/INVALID_DATA su contestCode | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (pattern: 3–40) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-122 | instantwin.won: rewardCode minuscolo rwd-x · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"COUPON","points":500,"rewardCode":"rwd-x"}` | DIVERGENZA: REJECTED/INVALID_DATA su rewardCode | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (pattern) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-123 | instantwin.won: playId vuoto · data `{"contestCode":"IW-AUTUNNO","prizeCode":"PZ-500","prizeType":"POINTS","points":500,"playId":""}` | DIVERGENZA: REJECTED/INVALID_DATA su playId | contracts/events/action/instantwin.won.schema.json; docs/05 §3 EVT-ACT-24 (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-124 | achievement.completed: obiettivo valido · data `{"achievementCode":"ACH-3-PURCHASES","periodKey":"2026-Q3"}` | ACCEPTED | contracts/events/action/achievement.completed.schema.json; docs/05 §3 EVT-ACT-25 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-125 | achievement.completed: senza achievementCode · data `{"periodKey":"2026-Q3"}` | REJECTED/INVALID_DATA su achievementCode | contracts/events/action/achievement.completed.schema.json; docs/05 §3 EVT-ACT-25 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-126 | achievement.completed: senza periodKey · data `{"achievementCode":"ACH-3-PURCHASES"}` | REJECTED/INVALID_DATA su periodKey | contracts/events/action/achievement.completed.schema.json; docs/05 §3 EVT-ACT-25 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-127 | achievement.completed: achievementCode minuscolo · data `{"achievementCode":"ach-3","periodKey":"2026-Q3"}` | DIVERGENZA: REJECTED/INVALID_DATA su achievementCode | contracts/events/action/achievement.completed.schema.json; docs/05 §3 EVT-ACT-25 (pattern) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-128 | achievement.completed: periodKey vuoto · data `{"achievementCode":"ACH-3-PURCHASES","periodKey":""}` | DIVERGENZA: REJECTED/INVALID_DATA su periodKey | contracts/events/action/achievement.completed.schema.json; docs/05 §3 EVT-ACT-25 (minLength 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-129 | badge.awarded: badge valido · data `{"badgeCode":"BDG-EXPLORER"}` | ACCEPTED | contracts/events/action/badge.awarded.schema.json; docs/05 §3 EVT-ACT-26 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-130 | badge.awarded: senza badgeCode · data `{}` | REJECTED/INVALID_DATA su badgeCode | contracts/events/action/badge.awarded.schema.json; docs/05 §3 EVT-ACT-26 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-131 | badge.awarded: badgeCode minuscolo · data `{"badgeCode":"bdg-explorer"}` | DIVERGENZA: REJECTED/INVALID_DATA su badgeCode | contracts/events/action/badge.awarded.schema.json; docs/05 §3 EVT-ACT-26 (pattern) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-132 | badge.awarded: origin ACHIEVEMENT · data `{"badgeCode":"BDG-EXPLORER","origin":"ACHIEVEMENT"}` | ACCEPTED | contracts/events/action/badge.awarded.schema.json; docs/05 §3 EVT-ACT-26 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-133 | badge.awarded: origin CAMPAIGN · data `{"badgeCode":"BDG-EXPLORER","origin":"CAMPAIGN"}` | ACCEPTED | contracts/events/action/badge.awarded.schema.json; docs/05 §3 EVT-ACT-26 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-134 | badge.awarded: origin OTHER · data `{"badgeCode":"BDG-EXPLORER","origin":"OTHER"}` | DIVERGENZA: REJECTED/INVALID_DATA su origin | contracts/events/action/badge.awarded.schema.json; docs/05 §3 EVT-ACT-26 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-135 | referral.completed: REFERRER con controparte · data `{"role":"REFERRER","counterpartMemberId":"MBR-000009"}` | ACCEPTED | contracts/events/action/referral.completed.schema.json; docs/05 §3 EVT-ACT-27 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-136 | referral.completed: REFEREE con controparte · data `{"role":"REFEREE","counterpartMemberId":"MBR-000002"}` | ACCEPTED | contracts/events/action/referral.completed.schema.json; docs/05 §3 EVT-ACT-27 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-137 | referral.completed: senza role · data `{"counterpartMemberId":"MBR-000009"}` | REJECTED/INVALID_DATA su role | contracts/events/action/referral.completed.schema.json; docs/05 §3 EVT-ACT-27 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-138 | referral.completed: role OTHER · data `{"role":"OTHER","counterpartMemberId":"MBR-000009"}` | REJECTED/INVALID_DATA su role | contracts/events/action/referral.completed.schema.json; docs/05 §3 EVT-ACT-27 (enum) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-139 | referral.completed: senza counterpartMemberId · data `{"role":"REFERRER"}` | DIVERGENZA: REJECTED/INVALID_DATA su counterpartMemberId | contracts/events/action/referral.completed.schema.json; docs/05 §3 EVT-ACT-27 (required) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-140 | referral.completed: counterpartMemberId MBR-9 · data `{"role":"REFERRER","counterpartMemberId":"MBR-9"}` | DIVERGENZA: REJECTED/INVALID_DATA su counterpartMemberId | contracts/events/action/referral.completed.schema.json; docs/05 §3 EVT-ACT-27 (pattern) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-141 | reward.redeemed: riscatto valido · data `{"rewardCode":"RWD-COFFEE-5","pointsCost":500}` | ACCEPTED | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-142 | reward.redeemed: senza rewardCode · data `{"pointsCost":500}` | REJECTED/INVALID_DATA su rewardCode | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-143 | reward.redeemed: senza pointsCost · data `{"rewardCode":"RWD-COFFEE-5"}` | REJECTED/INVALID_DATA su pointsCost | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-144 | reward.redeemed: pointsCost 0 · data `{"rewardCode":"RWD-COFFEE-5","pointsCost":0}` | DIVERGENZA: REJECTED/INVALID_DATA su pointsCost | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 (minimum 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-145 | reward.redeemed: pointsCost 1 · data `{"rewardCode":"RWD-COFFEE-5","pointsCost":1}` | ACCEPTED | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 (minimum 1) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-146 | reward.redeemed: pointsCost -1 · data `{"rewardCode":"RWD-COFFEE-5","pointsCost":-1}` | REJECTED/INVALID_DATA su pointsCost | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-147 | reward.redeemed: pointsCost 2,5 · data `{"rewardCode":"RWD-COFFEE-5","pointsCost":2.5}` | REJECTED/INVALID_DATA su pointsCost | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 (integer) | `TestbookIngPipelineIT#sch` |
| TB-ING-SCH-148 | reward.redeemed: rewardCode minuscolo · data `{"rewardCode":"rwd-coffee","pointsCost":500}` | DIVERGENZA: REJECTED/INVALID_DATA su rewardCode | contracts/events/action/reward.redeemed.schema.json; docs/05 §3 EVT-ACT-28 (pattern) | `TestbookIngPipelineIT#sch` |

### 3.7 TIM — Finestra temporale (passo 5)

**Regola.** `time` non nel futuro oltre 5 minuti né più vecchio di 30 giorni, altrimenti `REJECTED/INVALID_TIME`
(ingestion §5.5). «> 5 min» e «più vecchio di 30 giorni» ⇒ i limiti esatti sono ammessi. Codice: `IngestionService`
righe 160-164 (`Duration` di 5 min e di 30 × 24 h sull'orologio del servizio).

| Ingresso | Classi e limiti |
|---|---|
| `time − adesso` | 0 · +4:59,999 · +5:00 · +5:00,001 · +6 min · +10 min · +1 g · −1 ms · −30 g + 1 ms · −30 g · −30 g − 1 ms · −40 g |
| calendario (Europe/Rome) | mezzanotte (23:59:59 / 00:00), fine mese (31/3 → 1/3), 29 febbraio 2028, cambio dell'ora di marzo (29/3/2026) e di ottobre (25/10/2026) |
| formato | offset `+02:00`, `t`/`z` minuscole, nanosecondi |

**Strategia.** Valori limite di ciascun estremo (min−1, min, min+1) con orologio del servizio fisso (`TbClock`); casi di
calendario uno per classe; formati validi uno per classe. Il cambio dell'ora rende ambigua la lettura «30 giorni»
(720 ore o 30 giorni di calendario a Roma): righe Q-257 che asseriscono il comportamento attuale (720 ore).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-TIM-001 | time = adesso · servizio `2026-09-24T10:00:00Z` · time `Δ+PT0S` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-002 | adesso + 4 min 59,999 s · servizio `2026-09-24T10:00:00Z` · time `Δ+PT4M59.999S` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-003 | adesso + 5 min esatti · servizio `2026-09-24T10:00:00Z` · time `Δ+PT5M` | ACCEPTED (rifiuto solo se &gt; 5 min) | ingestion §5.5; docs/17 US-E01-03 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-004 | adesso + 5 min + 1 ms · servizio `2026-09-24T10:00:00Z` · time `Δ+PT5M0.001S` | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-005 | adesso + 6 min · servizio `2026-09-24T10:00:00Z` · time `Δ+PT6M` | REJECTED/INVALID_TIME | ingestion §5.5; docs/17 US-E01-03 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-006 | orologio del client avanti di 10 min · servizio `2026-09-24T10:00:00Z` · time `Δ+PT10M` | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-007 | adesso + 1 giorno · servizio `2026-09-24T10:00:00Z` · time `Δ+P1D` | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-008 | adesso − 1 ms · servizio `2026-09-24T10:00:00Z` · time `Δ-PT0.001S` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-009 | adesso − 30 giorni + 1 ms · servizio `2026-09-24T10:00:00Z` · time `Δ-P29DT23H59M59.999S` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-010 | adesso − 30 giorni esatti · servizio `2026-09-24T10:00:00Z` · time `Δ-P30D` | ACCEPTED (non «più vecchio di 30 giorni») | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-011 | adesso − 30 giorni − 1 ms · servizio `2026-09-24T10:00:00Z` · time `Δ-P30DT0.001S` | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-012 | batch notturno: adesso − 40 giorni · servizio `2026-09-24T10:00:00Z` · time `Δ-P40D` | REJECTED/INVALID_TIME | ingestion §5.5; docs/17 US-E01-03 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-013 | time con offset +02:00 equivalente ad adesso · servizio `2026-09-24T10:00:00Z` · time `@offset` | ACCEPTED | ingestion §5.5; docs/05 §2 (RFC 3339) | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-014 | time con t e z minuscole · servizio `2026-09-24T10:00:00Z` · time `@lower` | ACCEPTED | docs/05 §2; RFC 3339 §5.6 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-015 | time con nanosecondi · servizio `2026-09-24T10:00:00Z` · time `@nanos` | ACCEPTED | docs/05 §2; RFC 3339 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-016 | 29 febbraio 2028 (bisestile), 1 h prima · servizio `2028-02-29T12:00:00Z` · time `2028-02-29T11:00:00Z` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-017 | 30 giorni esatti che iniziano il 29 febbraio 2028 · servizio `2028-03-30T12:00:00Z` · time `2028-02-29T12:00:00Z` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-018 | fine mese: 31/3 → 1/3 stessa ora (30 giorni) · servizio `2026-03-31T10:00:00Z` · time `2026-03-01T10:00:00Z` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-019 | fine mese: 31/3 → 1/3 un secondo prima · servizio `2026-03-31T10:00:00Z` · time `2026-03-01T09:59:59Z` | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-020 | mezzanotte di Roma: servizio alle 00:00 del 25/9, evento alle 23:59:59 del 24/9 · servizio `2026-09-24T22:00:00Z` · time `2026-09-24T21:59:59Z` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-021 | mezzanotte di Roma: servizio alle 23:59:59, evento alle 00:00 del giorno dopo (+1 s) · servizio `2026-09-24T21:59:59Z` · time `2026-09-24T22:00:00Z` | ACCEPTED | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-022 | cambio dell'ora di marzo: evento 30 gg prima alle 09:30 CET (dentro 720 h, fuori da 30 gg di calendario) · servizio `2026-04-15T08:00:00Z` · time `2026-03-16T08:30:00Z` | Q-257: ACCEPTED (finestra di 720 ore, non di 30 giorni di calendario Europe/Rome) | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-023 | cambio dell'ora di marzo: evento oltre sia 720 h sia 30 gg di calendario · servizio `2026-04-15T08:00:00Z` · time `2026-03-16T07:59:59Z` | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-024 | cambio dell'ora di ottobre: evento 30 gg di calendario prima alle 10:30 CEST (fuori da 720 h) · servizio `2026-11-10T09:00:00Z` · time `2026-10-11T08:30:00Z` | Q-257: REJECTED/INVALID_TIME (720 ore; 30 gg di calendario lo ammetterebbero) | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-025 | notte del 25/10/2026: +5 min reali ma ora locale indietro di 55 min · servizio `2026-10-25T00:58:00Z` · time `2026-10-25T01:03:00Z` | ACCEPTED (finestra sugli istanti) | ingestion §5.5 | `TestbookIngPipelineIT#tim` |
| TB-ING-TIM-026 | notte del 29/3/2026: +5 min reali ma ora locale avanti di 65 min · servizio `2026-03-29T00:58:00Z` · time `2026-03-29T01:03:00Z` | ACCEPTED (finestra sugli istanti) | ingestion §5.5 | `TestbookIngPipelineIT#tim` |

### 3.8 DUP — Deduplica (passo 6)

**Regola.** Stesso `source`+`id` già visto ⇒ `DUPLICATE`, nessuna pubblicazione; la riga `DUPLICATE` si salva (ingestion
§5.6, §7; F-ING-02; docs/12 M0). La chiave è la coppia (fonte, id), non il contenuto. Codice: `IngestionService` righe
166-170 (`acceptedExists`: conta **solo** gli `ACCEPTED`) e 102-110 (gara sull'indice unico parziale ⇒ `DUPLICATE`).

| Ingresso | Classi |
|---|---|
| invio precedente con stessa (fonte, id) | nessuno · `ACCEPTED` · `REJECTED` · `UNMATCHED` · `DUPLICATE` |
| differenze rispetto al precedente | fonte · contenuto (`type`, `data`) · membro · forma della fonte (URN/breve) · forma del `type` · maiuscole dell'id |
| concorrenza | 8 invii simultanei |

**Strategia.** Una riga per classe di «precedente» e per ogni differenza (guasto singolo); la precedenza di dati e tempo sul
duplicato è in PIP (coppie DATA×DUP, TIME×DUP). «Già visto» (F-ING-02) non dice se un precedente `REJECTED`/`UNMATCHED`
conti: righe Q-256 che asseriscono il comportamento attuale (non conta), coerente con la riprova (F-ING-09).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-DUP-001 | stesso source+id inviato due volte | 1° ACCEPTED, 2° DUPLICATE (riga salvata con spiegazione), 1 sola pubblicazione | F-ING-02; ingestion §5.6; ingestion §7; docs/12 M0 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-002 | terzo invio dello stesso evento | DUPLICATE di nuovo; 2 righe DUPLICATE, 1 pubblicazione | F-ING-02; ingestion §5.6 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-003 | stesso id da due fonti diverse | entrambi ACCEPTED, 1 pubblicazione per fonte | F-ING-02; ingestion §5.6 (chiave = fonte+id); docs/05 §2 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-004 | stesso source+id con type e data diversi | DUPLICATE (la chiave non guarda il contenuto) | F-ING-02; ingestion §5.6 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-005 | stesso source+id per un altro membro | DUPLICATE | F-ING-02; ingestion §5.6 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-006 | primo invio REJECTED/INVALID_DATA, secondo corretto con lo stesso id | Q-256: secondo ACCEPTED (conta solo un ACCEPTED; F-ING-02 dice «già visto») | F-ING-02; ingestion §5.6; docs/17 US-E01-04 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-007 | primo invio UNMATCHED, poi il membro arriva e la fonte rimanda lo stesso id | Q-256: secondo ACCEPTED | F-ING-02; ingestion §5.6; docs/17 US-E01-04 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-008 | due invii per un membro BLOCKED | Q-256: due righe REJECTED/MEMBER_NOT_ACTIVE, nessun DUPLICATE | F-ING-02; ingestion §5.6 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-009 | prima con URN, poi con codice breve della stessa fonte | Q-258 DECISA: il secondo invio in forma breve è `400` (nulla salvato), una sola pubblicazione | F-ING-02; ingestion §5.6; docs/05 §2 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-010 | prima con type breve, poi completo | DUPLICATE | F-ING-02; ingestion §5.6; docs/05 §2 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-011 | id che differiscono solo per maiuscole | entrambi ACCEPTED (id distinti) | F-ING-02; ingestion §5.6 | `TestbookIngPipelineIT#dup` |
| TB-ING-DUP-012 | 8 invii concorrenti dello stesso evento | esattamente 1 ACCEPTED e 7 DUPLICATE, 1 pubblicazione | F-ING-02; ingestion §5.6; RNF-03; docs/17 US-E01-04 | `TestbookIngPipelineIT#dup` |

### 3.9 MBR — Risoluzione del membro (passo 7)

**Regola.** `subject` = `member:<id>` · `external:<externalId>` · `email:<email>`, risolto sull'indice locale; membro non
trovato ⇒ `UNMATCHED` (parcheggiato); stato ≠ `ACTIVE` ⇒ `REJECTED/MEMBER_NOT_ACTIVE` (F-ING-03, F-ING-04; ingestion §5.7;
docs/03 §2). Dopo l'anonimizzazione l'indice non ha più e-mail ed externalId: `email:`/`external:` ⇒ `UNMATCHED`,
`member:` ⇒ `MEMBER_NOT_ACTIVE` (Q-128). Codice: `IngestionService#resolveMember` (257-269), righe 172-183;
`MemberIndexRepository`; `MemberErasureRepository#erase`.

| Ingresso | Classi |
|---|---|
| forma del `subject` | `member:` · `external:` · `email:` minuscola · `email:` con maiuscole · senza prefisso · prefisso sconosciuto · `member:` vuoto · `external:` con maiuscole diverse |
| stato nell'indice | assente · `ACTIVE` · `INACTIVE` · `BLOCKED` · `ANONYMIZED` (cancellazione reale) · stato sconosciuto `SUSPENDED` |

**Strategia.** Tabella **completa** forma × stato = 5 × 6 = 30 (≤ 64); le forme speciali una riga ciascuna; 6 righe sui
membri del seed (Marco, Roberto `BLOCKED`, Alessandro `ANONYMIZED`, `CRM-999`). Il subject senza prefisso non è una forma
prevista: righe Q-255 (DECISA) ⇒ `UNMATCHED`, come ogni forma non prevista.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-MBR-001 | member:&lt;id&gt; · membro assente dall'indice | UNMATCHED | F-ING-03, F-ING-04; ingestion §5.7 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-002 | member:&lt;id&gt; · membro ACTIVE | ACCEPTED + memberId | F-ING-03; ingestion §5.7-§5.8 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-003 | member:&lt;id&gt; · membro INACTIVE | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-004 | member:&lt;id&gt; · membro BLOCKED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-005 | member:&lt;id&gt; · membro anonimizzato (cancellazione reale) | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula); Q-128 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-006 | member:&lt;id&gt; · stato sconosciuto SUSPENDED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-007 | external:&lt;externalId&gt; · membro assente dall'indice | UNMATCHED | F-ING-03, F-ING-04; ingestion §5.7 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-008 | external:&lt;externalId&gt; · membro ACTIVE | ACCEPTED + memberId | F-ING-03; ingestion §5.7-§5.8 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-009 | external:&lt;externalId&gt; · membro INACTIVE | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-010 | external:&lt;externalId&gt; · membro BLOCKED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-011 | external:&lt;externalId&gt; · membro anonimizzato (cancellazione reale) | UNMATCHED | Q-128 (indice senza e-mail/externalId) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-012 | external:&lt;externalId&gt; · stato sconosciuto SUSPENDED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-013 | email:&lt;e-mail minuscola&gt; · membro assente dall'indice | UNMATCHED | F-ING-03, F-ING-04; ingestion §5.7 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-014 | email:&lt;e-mail minuscola&gt; · membro ACTIVE | ACCEPTED + memberId | F-ING-03; ingestion §5.7-§5.8 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-015 | email:&lt;e-mail minuscola&gt; · membro INACTIVE | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-016 | email:&lt;e-mail minuscola&gt; · membro BLOCKED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-017 | email:&lt;e-mail minuscola&gt; · membro anonimizzato (cancellazione reale) | UNMATCHED | Q-128 (indice senza e-mail/externalId) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-018 | email:&lt;e-mail minuscola&gt; · stato sconosciuto SUSPENDED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-019 | email:&lt;e-mail con maiuscole&gt; · membro assente dall'indice | UNMATCHED | F-ING-03, F-ING-04; ingestion §5.7 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-020 | email:&lt;e-mail con maiuscole&gt; · membro ACTIVE | ACCEPTED + memberId | F-ING-03; ingestion §5.7-§5.8 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-021 | email:&lt;e-mail con maiuscole&gt; · membro INACTIVE | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-022 | email:&lt;e-mail con maiuscole&gt; · membro BLOCKED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-023 | email:&lt;e-mail con maiuscole&gt; · membro anonimizzato (cancellazione reale) | UNMATCHED | Q-128 (indice senza e-mail/externalId) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-024 | email:&lt;e-mail con maiuscole&gt; · stato sconosciuto SUSPENDED | REJECTED/MEMBER_NOT_ACTIVE + memberId | F-ING-03; docs/03 §2 (solo ACTIVE accumula) | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-025 | &lt;id&gt; senza prefisso · membro assente dall'indice | Q-255 DECISA: UNMATCHED (forma non prevista, recuperabile con Abbina) | F-ING-03 (forme ammesse: member:/external:/email:); docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-026 | &lt;id&gt; senza prefisso · membro ACTIVE | Q-255 DECISA: UNMATCHED (forma non prevista, recuperabile con Abbina) | F-ING-03 (forme ammesse: member:/external:/email:); docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-027 | &lt;id&gt; senza prefisso · membro INACTIVE | Q-255 DECISA: UNMATCHED (forma non prevista, recuperabile con Abbina) | F-ING-03 (forme ammesse: member:/external:/email:); docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-028 | &lt;id&gt; senza prefisso · membro BLOCKED | Q-255 DECISA: UNMATCHED (forma non prevista, recuperabile con Abbina) | F-ING-03 (forme ammesse: member:/external:/email:); docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-029 | &lt;id&gt; senza prefisso · membro anonimizzato (cancellazione reale) | Q-255 DECISA: UNMATCHED (forma non prevista, recuperabile con Abbina) | F-ING-03 (forme ammesse: member:/external:/email:); docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-030 | &lt;id&gt; senza prefisso · stato sconosciuto SUSPENDED | Q-255 DECISA: UNMATCHED (forma non prevista, recuperabile con Abbina) | F-ING-03 (forme ammesse: member:/external:/email:); docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-031 | external: con maiuscole diverse dall'indice | Q-255 DECISA: UNMATCHED (confronto esatto sull'externalId) | F-ING-03 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-032 | prefisso sconosciuto phone: | Q-255 DECISA: UNMATCHED (forma non prevista) | F-ING-03 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-033 | member: senza id | Q-255 DECISA: UNMATCHED (member: senza id) | F-ING-03; ingestion §5.7 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-034 | seed: member:MBR-000002 (Marco, ACTIVE) | ACCEPTED memberId MBR-000002 | ingestion §7; docs/10 §2 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-035 | seed: external:CRM-102 | ACCEPTED memberId MBR-000002 | F-ING-03; docs/10 §2 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-036 | seed: email:MARCO.BIANCHI@example.org | ACCEPTED memberId MBR-000002 | F-ING-03; docs/17 US-E01-01 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-037 | seed: member:MBR-000008 (Roberto, BLOCKED) | REJECTED/MEMBER_NOT_ACTIVE, niente sul topic | docs/12 M1; docs/10 §2; docs/03 §2 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-038 | seed: member:MBR-000012 (ANONYMIZED) | REJECTED/MEMBER_NOT_ACTIVE | Q-128; docs/10 §2 | `TestbookIngPipelineIT#mbr` |
| TB-ING-MBR-039 | seed: external:CRM-999 (inesistente) | UNMATCHED (parcheggiato) | F-ING-04; docs/17 US-E01-05 | `TestbookIngPipelineIT#mbr` |

### 3.10 ACC — Arricchimento e pubblicazione (passo 8)

**Regola.** Evento accettato ⇒ `202 {eventId, status, memberId}`; envelope canonico con `type` completo, `source` URN,
`subject` normalizzato a `member:<id>`, `dataschema`, `lhtenant`, `lhcorrelationid = id`, `lhhop = 0` (gli `lh*` li
aggiunge ingestion); outbox su `lh.actions.v1` con chiave `memberId` (ingestion §3, §5.8, §7; docs/05 §1-§2). Codice:
`IngestionService` righe 185-188, `#enrich` (272-278), `OutboxWriter`.

**Strategia.** Una riga per proprietà dell'esito (risposta, envelope, chiave, normalizzazione, riga del monitor, attributi
`lh*` forniti dalla fonte, conformità ai contratti, assenza di guardia, record reale su Kafka).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-ACC-001 | risposta dell'evento accettato | 202 {eventId, status=ACCEPTED, memberId} | ingestion §3 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-002 | envelope pubblicato | type completo, source URN, subject member:&lt;id&gt;, datacontenttype, dataschema urn:loyaltyhub:schema:action.purchase.completed:1, lhtenant aurora, lhcorrelationid = id, lhhop 0, time e data invariati | ingestion §5.8; docs/05 §2 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-003 | chiave e topic | record su lh.actions.v1 con chiave = memberId | docs/05 §1; ingestion §7 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-004 | subject email: normalizzato | subject pubblicato member:&lt;id&gt; | ingestion §5.8; docs/05 §2; F-ING-03 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-005 | riga del monitor | riga ACCEPTED, origin EXTERNAL, member_id, correlation_id = id | ingestion §2; docs/17 US-E01-01 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-006 | subject sulla riga di un ingresso accettato | Q-259: la riga ACCEPTED porta il subject normalizzato member:&lt;id&gt; (per riprova/abbina Q-118 conserva invece l'originale) | ingestion §2; Q-118 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-007 | attributi lh* inviati dalla fonte | ignorati: lhhop 0, lhcorrelationid = id, lhtenant aurora | docs/05 §2 (gli lh* li aggiunge ingestion) | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-008 | conformità ai contratti | envelope valido per envelope.schema.json, data valido per purchase.completed.schema.json | docs/05 §2; contracts/events | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-009 | nessuna guardia di ruolo sull'ingresso | X-LH-Actor ANALYST → 202 ACCEPTED | docs/06 §3 (solo scritture da backoffice); docs/17 ING-22 | `TestbookIngPipelineIT#acc` |
| TB-ING-ACC-010 | record reale su Kafka | record su lh.actions.v1 con chiave memberId e lhhop 0 entro 15 s | ingestion §7; docs/12 M0 | `TestbookIngPipelineIT#acc` |

### 3.11 MON — Monitor ingressi (F-ING-09, BO-26)

**Regola.** `GET /v1/inbound-events` con filtri `status, source, type, memberId, from, to, q`; dettaglio con payload completo
e `correlationId`; schede per esito con conteggi; storico demo di 40 righe degli ultimi 3 giorni con tutti gli esiti
(ingestion §3, §6; BO-26). Codice: `InboundEventsController`, `InboundEventRepository#search/#countByStatus`.
`from`, `to`, `q` e lo storico demo: **regole non implementate**.

**Strategia.** Una riga per filtro/funzione; i conteggi con una combinazione di tutti e 4 gli esiti più il duplicato ripetuto.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-MON-001 | conteggi per esito di una fonte senza eventi | {ACCEPTED:0, DUPLICATE:0, REJECTED:0, UNMATCHED:0} | ingestion §3; F-ING-09; BO-26 (schede per esito con conteggi) | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-002 | conteggi dopo 1 accettato, 2 duplicati, 1 respinto, 1 non abbinato | 1 / 2 / 1 / 1 | ingestion §3; F-ING-09; BO-26 | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-003 | conteggi filtrati per membro | solo le righe del membro | ingestion §3; F-ING-09; BO-26 | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-004 | elenco filtrato per esito e fonte | solo righe REJECTED della fonte | ingestion §3; F-ING-09; BO-26 (filtro status, source) | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-005 | elenco filtrato per tipo | solo righe del tipo | ingestion §3; F-ING-09; BO-26 (filtro type) | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-006 | dettaglio di un INVALID_DATA | CloudEvent completo, correlationId, errori di schema campo per campo | ingestion §3; F-ING-09; BO-26 | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-007 | dettaglio inesistente | 404 | docs/06 §2 | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-008 | filtro per intervallo from/to | solo le righe ricevute nell'intervallo (DIVERGENZA: filtro assente) | ingestion §3; F-ING-09; BO-26 (filtri from, to) | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-009 | ricerca libera q | solo le righe che contengono il testo (DIVERGENZA: filtro assente) | ingestion §3; F-ING-09; BO-26 (filtro q) | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-010 | storico demo all'avvio | ≥ 40 righe negli ultimi 3 giorni con tutti e 4 gli esiti (DIVERGENZA: storico non seminato) | ingestion §6 (storico: 40 inbound_event degli ultimi 3 giorni) | `TestbookIngPipelineIT#mon` |
| TB-ING-MON-011 | lettura con ruolo ANALYST | 200 su elenco, conteggi e dettaglio | docs/06 §3; docs/08 §2 | `TestbookIngPipelineIT#mon` |

### 3.12 RES — Riprova e Abbina (F-ING-04, F-ING-09)

**Regola.** *Riprova* (`POST …/{id}/retry`): solo `REJECTED`/`UNMATCHED`; rivaluta il payload salvato con la stessa pipeline
e, se valido, pubblica. *Abbina* (`POST …/{id}/match {memberId}`): solo `UNMATCHED`. Capacità `inbound.handle`: ADMIN,
CARE (ingestion §3; docs/08 §2). Scelte registrate: la riga si aggiorna *in place*, subject originale conservato, esito
negativo sostituito (Q-118); accettato altrove nel frattempo ⇒ `DUPLICATE` senza ripubblicazione (Q-114); audit
`TRANSITION` con l'attore (Q-117). Codice: `InboundResolutionService#retry/#match/#apply/#auditResolution`,
`InboundResolution#canRetry/#canMatch`, `InboundEventRepository#markAccepted/#updateOutcome`.

| Ingresso | Classi |
|---|---|
| stato della riga | `ACCEPTED`, `DUPLICATE`, `REJECTED` (ogni codice), `UNMATCHED`, inesistente |
| azione | Riprova, Abbina |
| ruolo | ADMIN, CARE, MARKETING, LEGAL, ANALYST, intestazione assente, intestazione non valida |
| membro (Abbina) | ACTIVE, INACTIVE, BLOCKED, ANONYMIZED, non indicizzato, vuoto, corpo assente, `{}`, con spazi |
| causa rimossa? (Riprova) | sì / no, per ogni codice di rifiuto e per `UNMATCHED` |

**Strategia.** Prodotto 5 × 2 × 7 × 9 > 64 ⇒ riduzione in decisioni indipendenti, perché la guardia di ruolo è valutata
prima di qualunque stato (intercettore) e lo stato prima del membro: (a) **ruolo × azione** completa (7 × 2 = 14) su una
riga risolvibile; (b) **stato × azione** completa (5 × 2 = 10) con ADMIN; (c) per la Riprova ogni codice di rifiuto ×
{causa rimossa, causa presente} più `UNMATCHED` × {membro assente, ACTIVE, BLOCKED} e le proprietà trasversali (identità,
dedup dopo la riprova, concorrenza, audit); (d) per Abbina ogni classe del membro da sola più «altro passo fallisce» e
«riprova dopo l'abbinamento».

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-RES-001 | Riprova di un UNMATCHED risolvibile · ADMIN | 200, riga ACCEPTED, 1 pubblicazione, audit con l'attore | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-002 | Riprova di un UNMATCHED risolvibile · CARE | 200, riga ACCEPTED, 1 pubblicazione, audit con l'attore | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-003 | Riprova di un UNMATCHED risolvibile · MARKETING | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-004 | Riprova di un UNMATCHED risolvibile · LEGAL | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-005 | Riprova di un UNMATCHED risolvibile · ANALYST | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-006 | Riprova di un UNMATCHED risolvibile · intestazione assente | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-007 | Riprova di un UNMATCHED risolvibile · intestazione non valida PIRATA:x | Q-261: 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato (ruolo non valido trattato come ANALYST) | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-008 | Abbina di un UNMATCHED risolvibile · ADMIN | 200, riga ACCEPTED, 1 pubblicazione, audit con l'attore | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-009 | Abbina di un UNMATCHED risolvibile · CARE | 200, riga ACCEPTED, 1 pubblicazione, audit con l'attore | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-010 | Abbina di un UNMATCHED risolvibile · MARKETING | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-011 | Abbina di un UNMATCHED risolvibile · LEGAL | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-012 | Abbina di un UNMATCHED risolvibile · ANALYST | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-013 | Abbina di un UNMATCHED risolvibile · intestazione assente | 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |
| TB-ING-RES-014 | Abbina di un UNMATCHED risolvibile · intestazione non valida PIRATA:x | Q-261: 403 FORBIDDEN_ROLE, riga UNMATCHED, nulla pubblicato (ruolo non valido trattato come ANALYST) | ingestion §3; docs/08 §2 (inbound.handle: ADMIN, CARE); docs/06 §3 | `TestbookIngResolutionIT#guard` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-RES-015 | Riprova su riga ACCEPTED | 409, nessuna ripubblicazione | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED); docs/17 US-E01-07 | `TestbookIngResolutionIT#state` |
| TB-ING-RES-016 | Abbina su riga ACCEPTED | 409, nessuna ripubblicazione | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED) | `TestbookIngResolutionIT#state` |
| TB-ING-RES-017 | Riprova su riga DUPLICATE | 409 | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED) | `TestbookIngResolutionIT#state` |
| TB-ING-RES-018 | Abbina su riga DUPLICATE | 409 | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED) | `TestbookIngResolutionIT#state` |
| TB-ING-RES-019 | Riprova su riga REJECTED | 200, rivalutato e accettato (membro riattivato) | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED); F-ING-09 | `TestbookIngResolutionIT#state` |
| TB-ING-RES-020 | Abbina su riga REJECTED | 409 (si abbinano solo i non abbinati) | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED) | `TestbookIngResolutionIT#state` |
| TB-ING-RES-021 | Riprova su riga UNMATCHED | 200, accettato (membro ora indicizzato) | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#state` |
| TB-ING-RES-022 | Abbina su riga UNMATCHED | 200, accettato col membro indicato | ingestion §3 (riprova solo REJECTED/UNMATCHED; abbina un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#state` |
| TB-ING-RES-023 | Riprova su riga INESISTENTE | 404 | docs/06 §2 | `TestbookIngResolutionIT#state` |
| TB-ING-RES-024 | Abbina su riga INESISTENTE | 404 | docs/06 §2 | `TestbookIngResolutionIT#state` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-RES-025 | Riprova: SOURCE_DISABLED, fonte ancora spenta | REJECTED/SOURCE_DISABLED sulla stessa riga, nulla pubblicato | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; Q-118; docs/17 US-E01-07 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-026 | Riprova: SOURCE_DISABLED, fonte riaccesa | ACCEPTED, 1 pubblicazione con lo stesso id, resolution RETRY | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; Q-118 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-027 | Riprova: UNKNOWN_TYPE, tipo ancora disabilitato | REJECTED/UNKNOWN_TYPE | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-028 | Riprova: UNKNOWN_TYPE, tipo riabilitato | ACCEPTED | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-029 | Riprova: TYPE_NOT_ALLOWED, elenco della fonte corretto | ACCEPTED | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-030 | Riprova: INVALID_DATA, payload invariato | REJECTED/INVALID_DATA (il payload salvato non cambia) | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-031 | Riprova: INVALID_DATA, schema del tipo custom allentato | ACCEPTED (schema valido da subito) | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; F-ING-06 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-032 | Riprova: INVALID_TIME nel futuro, orologio avanzato di 10 min | ACCEPTED | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; ingestion §5.5 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-033 | Riprova: INVALID_TIME di 31 giorni fa | REJECTED/INVALID_TIME | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; ingestion §5.5 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-034 | Riprova: MEMBER_NOT_ACTIVE, membro ancora BLOCKED | REJECTED/MEMBER_NOT_ACTIVE | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; docs/03 §2 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-035 | Riprova: MEMBER_NOT_ACTIVE, membro riattivato | ACCEPTED | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-036 | Riprova: UNMATCHED, membro ancora sconosciuto | UNMATCHED, nessuna resolution | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-037 | Riprova: UNMATCHED, membro ora nell'indice | ACCEPTED col memberId risolto | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; F-ING-04 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-038 | Riprova: UNMATCHED, membro ora nell'indice ma BLOCKED | REJECTED/MEMBER_NOT_ACTIVE (esito sostituito) | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; Q-118 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-039 | Riprova: REJECTED, nel frattempo stessa fonte+id accettata da un nuovo invio | DUPLICATE, nessuna ripubblicazione | ingestion §3 (riprova: rivaluta e, se valido, pubblica); F-ING-09; Q-114 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-040 | Riprova: dopo una riprova accettata la fonte rimanda lo stesso id | DUPLICATE | F-ING-02; Q-118 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-041 | Riprova: riprova accettata: identità e provenienza | stesso id, lhcorrelationid = id, lhhop 0, subject pubblicato member:&lt;id&gt;, subject della riga originale | Q-118; docs/05 §2 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-042 | Riprova: due riprova concorrenti | una 200 ACCEPTED e una 409, 1 sola pubblicazione | Q-118; RNF-03; docs/17 US-E01-07 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-043 | Riprova: audit della riprova riuscita | voce TRANSITION su lh.audit.v1 con l'attore, prima/dopo dello stato | Q-117; F-AUD-01 | `TestbookIngResolutionIT#retryOutcome` |
| TB-ING-RES-044 | Riprova: audit della riprova ancora negativa | voce TRANSITION anche senza cambio di esito | Q-117 | `TestbookIngResolutionIT#retryOutcome` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-RES-045 | Abbina: membro ACTIVE | 200 ACCEPTED, resolution MANUAL_MATCH, subject pubblicato member:&lt;scelto&gt;, subject della riga originale, audit TRANSITION | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; Q-117; Q-118 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-046 | Abbina: membro seed MBR-000002 (Marco) | 200 ACCEPTED memberId MBR-000002 | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; docs/17 US-E01-07 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-047 | Abbina: membro INACTIVE | 422 MEMBER_NOT_ACTIVE, riga UNMATCHED | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; docs/03 §2 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-048 | Abbina: membro seed MBR-000008 (Roberto, BLOCKED) | 422 MEMBER_NOT_ACTIVE | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; docs/03 §2; docs/17 US-E01-07 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-049 | Abbina: membro ANONYMIZED | 422 MEMBER_NOT_ACTIVE | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; docs/03 §2 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-050 | Abbina: membro non indicizzato | Q-262: 422 MEMBER_NOT_FOUND (codice non specificato) | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-051 | Abbina: memberId di soli spazi | Q-262: 422 MEMBER_REQUIRED | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-052 | Abbina: corpo assente | Q-262: 422 MEMBER_REQUIRED | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-053 | Abbina: corpo {} senza memberId | Q-262: 422 MEMBER_REQUIRED | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-054 | Abbina: memberId con spazi attorno | Q-262: 200 ACCEPTED (spazi ignorati) | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-055 | Abbina: fonte spenta nel frattempo | 200 con esito REJECTED/SOURCE_DISABLED sulla stessa riga, nulla pubblicato | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; Q-118 | `TestbookIngResolutionIT#matchOutcome` |
| TB-ING-RES-056 | Abbina: riprova dopo l'abbinamento | 409 INBOUND_NOT_RETRYABLE, 1 sola pubblicazione | ingestion §3 (abbina {memberId} un UNMATCHED); F-ING-04; ingestion §3 | `TestbookIngResolutionIT#matchOutcome` |

### 3.13 AUT — Abbinamento automatico alla registrazione

**Regola.** Alla registrazione del membro (`member.registered`, non `member.updated` né `member.status.changed`) le righe
`UNMATCHED` con subject `external:<externalId>` o `email:<email>` (senza maiuscole) — mai `member:<id>` — ricevute negli
ultimi 7 giorni, al massimo 100 (le più vecchie per prime), passano dalla pipeline; solo l'esito `ACCEPTED` tocca la riga
(`AUTO_MATCH`, `resolved_by = system`, audit come job) (F-ING-04; Q-115, Q-116, Q-117, Q-119). Codice:
`InboundResolutionService#autoMatch` (righe 140-163), `InboundResolution#autoMatchKeys`,
`InboundEventRepository#findUnmatchedIds`, `FactsHandler#updateMemberIndex`.

| Fattore | Classi |
|---|---|
| forma del subject parcheggiato | `external:` · `email:` · `email:` con maiuscole · `member:` |
| età della riga | 1 h · 6 g 23 h · 7 g 1 h (+ limiti 7 g − 1 s / 7 g / 7 g + 1 s) |
| stato del nuovo membro | ACTIVE · BLOCKED |
| fatto | `member.registered` · `member.updated` |

**Strategia.** Tabella **completa** 4 × 3 × 2 × 2 = 48 (≤ 64); limiti della finestra uno per valore (3) con orologio fisso;
poi una riga per ciascuna regola accessoria (senza chiavi, fonte o tipo spenti nel frattempo, riga già risolta,
riconsegna, limite di 100, provenienza, due chiavi, cambio di stato).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-AUT-001 | external:&lt;externalId&gt; · parcheggiato da 1 h · membro ACTIVE · member.registered | ACCEPTED, resolution AUTO_MATCH | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-002 | external:&lt;externalId&gt; · parcheggiato da 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-003 | external:&lt;externalId&gt; · parcheggiato da 1 h · membro BLOCKED · member.registered | resta UNMATCHED: membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-004 | external:&lt;externalId&gt; · parcheggiato da 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-005 | external:&lt;externalId&gt; · da 6 g 23 h · membro ACTIVE · member.registered | ACCEPTED, resolution AUTO_MATCH | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-006 | external:&lt;externalId&gt; · da 6 g 23 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-007 | external:&lt;externalId&gt; · da 6 g 23 h · membro BLOCKED · member.registered | resta UNMATCHED: membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-008 | external:&lt;externalId&gt; · da 6 g 23 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-009 | external:&lt;externalId&gt; · da 7 g 1 h · membro ACTIVE · member.registered | resta UNMATCHED: fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-010 | external:&lt;externalId&gt; · da 7 g 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119), fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-011 | external:&lt;externalId&gt; · da 7 g 1 h · membro BLOCKED · member.registered | resta UNMATCHED: fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-012 | external:&lt;externalId&gt; · da 7 g 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-013 | email:&lt;e-mail&gt; · parcheggiato da 1 h · membro ACTIVE · member.registered | ACCEPTED, resolution AUTO_MATCH | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-014 | email:&lt;e-mail&gt; · parcheggiato da 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-015 | email:&lt;e-mail&gt; · parcheggiato da 1 h · membro BLOCKED · member.registered | resta UNMATCHED: membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-016 | email:&lt;e-mail&gt; · parcheggiato da 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-017 | email:&lt;e-mail&gt; · da 6 g 23 h · membro ACTIVE · member.registered | ACCEPTED, resolution AUTO_MATCH | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-018 | email:&lt;e-mail&gt; · da 6 g 23 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-019 | email:&lt;e-mail&gt; · da 6 g 23 h · membro BLOCKED · member.registered | resta UNMATCHED: membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-020 | email:&lt;e-mail&gt; · da 6 g 23 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-021 | email:&lt;e-mail&gt; · da 7 g 1 h · membro ACTIVE · member.registered | resta UNMATCHED: fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-022 | email:&lt;e-mail&gt; · da 7 g 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119), fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-023 | email:&lt;e-mail&gt; · da 7 g 1 h · membro BLOCKED · member.registered | resta UNMATCHED: fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-024 | email:&lt;e-mail&gt; · da 7 g 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-025 | email:&lt;e-mail con maiuscole&gt; · parcheggiato da 1 h · membro ACTIVE · member.registered | ACCEPTED, resolution AUTO_MATCH | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-026 | email:&lt;e-mail con maiuscole&gt; · parcheggiato da 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-027 | email:&lt;e-mail con maiuscole&gt; · parcheggiato da 1 h · membro BLOCKED · member.registered | resta UNMATCHED: membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-028 | email:&lt;e-mail con maiuscole&gt; · parcheggiato da 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-029 | email:&lt;e-mail con maiuscole&gt; · da 6 g 23 h · membro ACTIVE · member.registered | ACCEPTED, resolution AUTO_MATCH | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-030 | email:&lt;e-mail con maiuscole&gt; · da 6 g 23 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-031 | email:&lt;e-mail con maiuscole&gt; · da 6 g 23 h · membro BLOCKED · member.registered | resta UNMATCHED: membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-032 | email:&lt;e-mail con maiuscole&gt; · da 6 g 23 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-033 | email:&lt;e-mail con maiuscole&gt; · da 7 g 1 h · membro ACTIVE · member.registered | resta UNMATCHED: fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-034 | email:&lt;e-mail con maiuscole&gt; · da 7 g 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119), fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-035 | email:&lt;e-mail con maiuscole&gt; · da 7 g 1 h · membro BLOCKED · member.registered | resta UNMATCHED: fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-036 | email:&lt;e-mail con maiuscole&gt; · da 7 g 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-037 | member:&lt;id&gt; · parcheggiato da 1 h · membro ACTIVE · member.registered | resta UNMATCHED: mai member:&lt;id&gt; (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-038 | member:&lt;id&gt; · parcheggiato da 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119), mai member:&lt;id&gt; (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-039 | member:&lt;id&gt; · parcheggiato da 1 h · membro BLOCKED · member.registered | resta UNMATCHED: mai member:&lt;id&gt; (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-040 | member:&lt;id&gt; · parcheggiato da 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), mai member:&lt;id&gt; (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-041 | member:&lt;id&gt; · da 6 g 23 h · membro ACTIVE · member.registered | resta UNMATCHED: mai member:&lt;id&gt; (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-042 | member:&lt;id&gt; · da 6 g 23 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119), mai member:&lt;id&gt; (Q-119) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-043 | member:&lt;id&gt; · da 6 g 23 h · membro BLOCKED · member.registered | resta UNMATCHED: mai member:&lt;id&gt; (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-044 | member:&lt;id&gt; · da 6 g 23 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), mai member:&lt;id&gt; (Q-119), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-045 | member:&lt;id&gt; · da 7 g 1 h · membro ACTIVE · member.registered | resta UNMATCHED: mai member:&lt;id&gt; (Q-119), fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-046 | member:&lt;id&gt; · da 7 g 1 h · membro ACTIVE · member.updated | resta UNMATCHED: solo member.registered (Q-119), mai member:&lt;id&gt; (Q-119), fuori finestra 7 g (Q-115) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-047 | member:&lt;id&gt; · da 7 g 1 h · membro BLOCKED · member.registered | resta UNMATCHED: mai member:&lt;id&gt; (Q-119), fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-048 | member:&lt;id&gt; · da 7 g 1 h · membro BLOCKED · member.updated | resta UNMATCHED: solo member.registered (Q-119), mai member:&lt;id&gt; (Q-119), fuori finestra 7 g (Q-115), membro non attivo (Q-116) | F-ING-04 (alla registrazione del membro); Q-115; Q-116; Q-119 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-049 | limite della finestra: parcheggiato da 7 g − 1 s | ACCEPTED (received_at ≥ adesso − 7 g) | Q-115 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-050 | limite della finestra: parcheggiato da 7 g esatti | ACCEPTED (received_at ≥ adesso − 7 g) | Q-115 | `TestbookIngResolutionIT#autoMatch` |
| TB-ING-AUT-051 | limite della finestra: parcheggiato da 7 g + 1 s | UNMATCHED (fuori finestra) | Q-115 | `TestbookIngResolutionIT#autoMatch` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-AUT-052 | registrazione senza externalId né e-mail | nessuna riga toccata | Q-119 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-053 | fonte dell'evento spenta nel frattempo | resta UNMATCHED | Q-116; docs/17 US-E01-06 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-054 | tipo dell'evento disabilitato nel frattempo | resta UNMATCHED | Q-116 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-055 | riga già abbinata a mano | saltata: resta ACCEPTED col primo membro, 1 pubblicazione | ingestion §3; docs/17 ING-13 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-056 | fatto member.registered riconsegnato | 1 sola pubblicazione | docs/04 §5 (consumer idempotenti); docs/17 US-E01-06 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-057 | 101 righe parcheggiate per lo stesso membro | 100 accettate (le più vecchie), 1 resta UNMATCHED | Q-115 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-058 | provenienza dell'abbinamento automatico | resolution AUTO_MATCH, resolvedBy system, audit come job (lhactor system) | Q-117; Q-118 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-059 | riga via e-mail e riga via externalId dello stesso membro | entrambe ACCEPTED | F-ING-04; Q-119 | `TestbookIngResolutionIT#autoMatchExtra` |
| TB-ING-AUT-060 | member.status.changed → ACTIVE di un membro prima BLOCKED | resta UNMATCHED (solo alla registrazione) | Q-119 | `TestbookIngResolutionIT#autoMatchExtra` |

### 3.14 ETY — Tipi azione SYSTEM e CUSTOM (F-ING-06, M6.7)

**Regola.** `GET/POST/PUT /v1/event-types`: i tipi `SYSTEM` permettono di modificare solo `name, description, enabled,
icon`; i `CUSTOM` tutto tranne `code` (ingestion §3). Capacità `actiontype.custom` (ADMIN, MARKETING); un tipo di sistema
si modifica solo con ADMIN; codice minuscolo a punti, 2–4 parti; categorie custom TRANSACTION/ENGAGEMENT/SERVICE (Q-89).
Lo schema vale da subito per le azioni in ingresso (docs/12 M6.7). `GET …/fields` elenca i campi `data.*` per BO-06.
Codice: `EventTypeService#create/#update/#customDraft`, `EventTypesController`, `SchemaFields`.

| Ingresso | Classi |
|---|---|
| `code` | 2, 3, 4 parti · 1 e 5 parti · maiuscole/`_` · maiuscola interna · cifra iniziale · 60/61 caratteri · vuoto · già esistente (custom, di sistema) |
| `name` | valido · vuoto · 60/61 caratteri |
| `category` | TRANSACTION, ENGAGEMENT, SERVICE · INTERNAL · sconosciuta · assente |
| `dataSchema` / `sampleData` | schema oggetto · assente · `array` · non conforme al meta-schema · esempio che viola lo schema · esempio assente |
| `enabled` | true · false · assente |
| ruolo | ADMIN, MARKETING, LEGAL, CARE, ANALYST, assente, non valido |
| origine × campo modificato | CUSTOM × {tutto, codice, categoria, schema, abilitazione} · SYSTEM × {nome/descrizione/icona, abilitazione, categoria, schema, esempio, stesso schema, codice, nome vuoto} |

**Strategia.** Creazione: guasto singolo su una richiesta valida (ogni classe non valida da sola, ogni limite da solo, ogni
categoria ammessa una volta); ruoli uno per riga. Modifica: origine × campo (tabella completa delle celle significative,
2 × 5 + 8) più ruoli. Effetti sulla pipeline (uso immediato, schema aggiornato, disabilitazione) verificati nella stessa riga.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-ETY-001 | crea custom a 3 parti (MARKETING) | 201, origin CUSTOM, abilitato | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-002 | crea custom a 2 parti | 201 | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-003 | crea custom a 4 parti | 201 | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-004 | codice di 1 parte | 422 su code | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-005 | codice di 5 parti | 422 su code | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-006 | codice Meter_Reading | 422 su code | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE); docs/17 US-E01-12 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-007 | codice con maiuscola interna (x.readingSent) | 422 su code: non minuscolo (DIVERGENZA) | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-008 | codice che inizia con una cifra | 422 su code | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-009 | codice di 60 caratteri | Q-260: 201 (limite di 60 non specificato) | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-010 | codice di 61 caratteri | Q-260: 422 (limite di 60 non specificato) | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-011 | codice vuoto | 422 su code | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-012 | codice di un custom già esistente | 409 | ingestion §3 (event-types); F-ING-06; Q-89; docs/06 §2 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-013 | codice di un tipo di sistema (purchase.completed) | 409 | ingestion §3 (event-types); F-ING-06; Q-89; docs/06 §2 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-014 | nome vuoto | 422 su name | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-015 | nome di 60 caratteri | Q-260: 201 (limite non specificato) | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-016 | nome di 61 caratteri | Q-260: 422 (limite non specificato) | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-017 | categoria TRANSACTION | 201 | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-018 | categoria ENGAGEMENT | 201 | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-019 | categoria SERVICE | 201 | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-020 | categoria INTERNAL (riservata ai tipi di sistema) | 422 su category | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-021 | categoria sconosciuta FOO | 422 su category | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-022 | categoria assente | Q-260: 201 con categoria ENGAGEMENT | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-023 | schema assente | 422 su dataSchema | ingestion §3 (event-types); F-ING-06; Q-89 (JSON Schema dei campi) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-024 | schema di tipo array | 422 su dataSchema | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-025 | schema non conforme al meta-schema (type: nonsense) | 422 su dataSchema (DIVERGENZA: oggi 201) | ingestion §3 (event-types); F-ING-06; Q-89 (JSON Schema valido) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-026 | sampleData che viola lo schema | 422 su sampleData | ingestion §3 (event-types); F-ING-06; Q-89; ingestion §2 (sample_data) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-027 | sampleData assente con schema senza obbligatori | Q-260: 201 con sampleData {} | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-028 | creato disabilitato | 201 enabled=false; i suoi eventi sono UNKNOWN_TYPE | ingestion §3 (event-types); F-ING-06; Q-89; ingestion §5.3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-029 | abilitazione assente ⇒ abilitato | Q-260: 201 enabled=true | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-030 | crea con LEGAL | 403 | docs/08 §2 (actiontype.custom: ADMIN, MARKETING) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-031 | crea con CARE | 403 | docs/08 §2 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-032 | crea con ANALYST | 403 | docs/08 §2; docs/06 §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-033 | crea senza X-LH-Actor | 403 (assente = ANALYST) | docs/06 §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-034 | crea con X-LH-Actor non valido | Q-261: 403 (ruolo sconosciuto = ANALYST) | docs/06 §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-035 | audit della creazione | voce CREATE su lh.audit.v1 con l'attore | ingestion §4; F-AUD-01 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-036 | custom usabile subito | evento valido ACCEPTED, evento senza campo obbligatorio INVALID_DATA | ingestion §3 (event-types); F-ING-06; Q-89; docs/12 M6.7 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-037 | campi del custom per il costruttore di condizioni | GET …/fields: data.meterId string obbligatorio, data.kind enum | ingestion §3 (event-types); F-ING-06; Q-89 (fields); BO-06 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-038 | custom nell'elenco dei tipi | GET /v1/event-types lo elenca con origin CUSTOM | ingestion §3 (event-types); F-ING-06; Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-039 | modifica di tutto tranne il codice (MARKETING) | 200 con nome, descrizione, categoria, schema, esempio, icona, abilitazione nuovi; audit UPDATE | ingestion §3 (CUSTOM: tutto tranne code) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-040 | modifica con codice diverso | 422 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-041 | modifica con lo stesso codice nel corpo | 200 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-042 | modifica con categoria non ammessa | 422 | Q-89 (codice minuscolo a punti, 2–4 parti; categorie TRANSACTION/ENGAGEMENT/SERVICE) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-043 | nuovo campo obbligatorio vale subito | evento successivo senza il campo: INVALID_DATA | ingestion §3 (event-types); F-ING-06; Q-89 (senza rilascio); docs/12 M6.7 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-044 | disabilita e riabilita un custom | disabilitato ⇒ UNKNOWN_TYPE; riabilitato ⇒ ACCEPTED | ingestion §5.3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-045 | modifica di un tipo inesistente | 404 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-046 | modifica di un custom con LEGAL | 403 | docs/08 §2 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-047 | tipo di sistema modificato da MARKETING | 403 | Q-89; docs/17 US-E01-12 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-048 | tipo di sistema modificato da CARE | 403 | Q-89 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-049 | tipo di sistema modificato da ANALYST | 403 | Q-89; docs/06 §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-050 | ADMIN cambia nome, descrizione, icona di un tipo di sistema | 200; origin, categoria e schema invariati | ingestion §3 (SYSTEM: name, description, enabled, icon) | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-051 | ADMIN disabilita un tipo di sistema | 200; nuovi eventi UNKNOWN_TYPE | ingestion §3; docs/17 US-E01-12 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-052 | ADMIN cambia la categoria di un tipo di sistema | 422 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-053 | ADMIN cambia lo schema di un tipo di sistema | 422 | ingestion §3; docs/17 US-E01-12 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-054 | ADMIN cambia l'esempio di un tipo di sistema | 422 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-055 | ADMIN rimanda lo stesso schema del tipo di sistema | 200 (nessun cambiamento) | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-056 | ADMIN cambia il codice di un tipo di sistema | 422 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-057 | ADMIN manda un nome vuoto a un tipo di sistema | Q-260: 200, il nome resta quello di prima | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-058 | campi di purchase.completed | data.amount number obbligatorio, data.channel enum, data.items[*].sku | ingestion §3 (fields); docs/03 §3.3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-059 | campi di un tipo inesistente | 404 | ingestion §3 | `TestbookIngConfigIT#ety` |
| TB-ING-ETY-060 | elenco dei tipi di sistema | i 19 tipi EVT-ACT del seed con origin SYSTEM | docs/10 §3; docs/05 §3 | `TestbookIngConfigIT#ety` |

### 3.15 TXN — `POST /v1/transactions` (F-ING-07)

**Regola.** `{source, orderId, memberRef, amount, currency, channel, items[], occurredAt}` ⇒ azione `purchase.completed`
con `id = "txn-" + orderId`, poi la stessa pipeline di `/v1/events`; reso con `kind = RETURN` ⇒ `purchase.returned`,
`id = "txn-return-" + orderId`, `data {orderId, amount}` (ingestion §3; F-ING-07; Q-49). Codice: `TransactionsController`
(righe 44-90).

| Ingresso | Classi |
|---|---|
| `kind` | assente · PURCHASE · purchase · RETURN · REFUND |
| campi | tutti · senza `source`/`orderId`/`memberRef`/`amount`/`currency` · `orderId` di spazi · senza `channel`/`items` · `occurredAt` assente/40 giorni fa |
| corpo | JSON valido · vuoto · `amount` non numerico |
| pipeline | fonte sconosciuta, tipo non ammesso, membro sconosciuto, dati non validi, duplicato, due fonti |

**Strategia.** Guasto singolo per ogni campo e valore di `kind`; una riga per ogni esito della pipeline raggiunto tramite
la transazione; mappatura verificata sull'envelope pubblicato.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-TXN-001 | ordine senza kind per external:CRM-102 | 202 ACCEPTED, eventId txn-&lt;orderId&gt;, memberId MBR-000002, type purchase.completed, data mappato | ingestion §3 (POST /v1/transactions); F-ING-07; docs/17 US-E01-08 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-002 | kind PURCHASE esplicito | come senza kind | ingestion §3 (POST /v1/transactions); F-ING-07; Q-49 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-003 | kind purchase minuscolo | Q-269: accettato (maiuscole ignorate) | Q-49 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-004 | reso (kind RETURN) | purchase.returned, eventId txn-return-&lt;orderId&gt;, data {orderId, amount} | Q-49; F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-005 | reso senza currency | 202 ACCEPTED | Q-49 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-006 | kind REFUND | 400 | Q-49; docs/17 US-E01-08 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-007 | acquisto senza currency | Q-269 DECISA: 202 REJECTED/INVALID_DATA (riga visibile in BO-26) | ingestion §3 (POST /v1/transactions); F-ING-07; docs/17 US-E01-08 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-008 | senza source | 400 | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-009 | senza orderId | 400 | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-010 | orderId di soli spazi | 400 | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-011 | senza memberRef | 400 | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-012 | senza amount | Q-269 DECISA: 202 REJECTED/INVALID_DATA (riga visibile in BO-26) | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-013 | corpo vuoto | 400 RFC 9457 (DIVERGENZA: oggi 500) | ingestion §3 (POST /v1/transactions); F-ING-07; docs/06 §2 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-014 | amount non numerico "abc" | 400 RFC 9457 (DIVERGENZA: oggi 500) | ingestion §3 (POST /v1/transactions); F-ING-07; docs/06 §2 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-015 | stesso ordine rinviato | secondo invio DUPLICATE (id deterministico) | ingestion §3 (POST /v1/transactions); F-ING-07; F-ING-02 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-016 | stesso ordine da due fonti | entrambi ACCEPTED | F-ING-02 (chiave fonte+id) | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-017 | acquisto e reso dello stesso ordine | entrambi ACCEPTED (id diversi) | Q-49 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-018 | amount negativo | REJECTED/INVALID_DATA | ingestion §3 (POST /v1/transactions); F-ING-07; contracts purchase.completed | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-019 | amount 0 | ACCEPTED (DIVERGENZA: lo schema del seed esclude lo 0) | contracts/events/action/purchase.completed.schema.json (minimum 0) | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-020 | occurredAt assente ⇒ adesso | time dell'azione = istante del servizio | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-021 | occurredAt di 40 giorni fa | REJECTED/INVALID_TIME | ingestion §5.5 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-022 | fonte pos-legacy | REJECTED/SOURCE_DISABLED | ingestion §5.2; Q-129 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-023 | reso dalla fonte app (non ammesso) | REJECTED/TYPE_NOT_ALLOWED | ingestion §5.3; docs/05 §3 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-024 | memberRef sconosciuto | UNMATCHED | F-ING-04 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-025 | channel e items assenti | data senza channel né items | ingestion §3 (POST /v1/transactions); F-ING-07 | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-026 | channel KIOSK | REJECTED/INVALID_DATA | contracts purchase.completed (enum) | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-027 | riga con quantity -1 | REJECTED/INVALID_DATA | contracts purchase.completed | `TestbookIngConfigIT#txn` |
| TB-ING-TXN-028 | nessuna guardia di ruolo (ANALYST) | 202 ACCEPTED | docs/06 §3; docs/17 ING-22 | `TestbookIngConfigIT#txn` |

### 3.16 SIM — Simulatore (F-DEMO-03, BO-28)

**Regola.** `POST /v1/demo/simulator/fire {memberId, type, data?, source?, occurredAt?, count?=1}`; `data` assente ⇒
`sample_data` del tipo con piccole variazioni casuali; risposta `[{eventId, correlationId, status}]`; stessa pipeline con
origine `SIMULATOR` (ingestion §2-§3); ripetizioni 1–20 (BO-28); capacità `demo.simulate` (tutti tranne ANALYST, docs/06 §3).
Codice: `SimulatorController` (righe 49-67).

**Strategia.** Ruoli uno per riga; `count` sui limiti (assente, 0, −3, 20, 21); default di fonte, dati e istante; due
esiti della pipeline per provare che è la stessa.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-SIM-001 | ADMIN invia | 200 [{eventId, correlationId, status}] | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; docs/08 §2 (demo.simulate) | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-002 | MARKETING invia | 200 | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; docs/08 §2 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-003 | LEGAL invia | 200 | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; docs/08 §2 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-004 | CARE invia | 200 | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; docs/08 §2 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-005 | ANALYST invia | 403 FORBIDDEN_ROLE | docs/06 §3; docs/08 §2 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-006 | senza X-LH-Actor | 403 FORBIDDEN_ROLE | docs/06 §3 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-007 | fonte assente ⇒ simulator | riga con fonte simulator, ACCEPTED | ingestion §3 (simulator/fire); F-DEMO-03; BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-008 | origine della riga | origin SIMULATOR (DIVERGENZA: oggi EXTERNAL) | ingestion §2 (origin EXTERNAL, INTERNAL, SIMULATOR); docs/17 ING-20 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-009 | count assente ⇒ 1 | 1 evento | ingestion §3 (simulator/fire); F-DEMO-03; BO-28 (count?=1) | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-010 | count 20 | 20 eventi, id distinti | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; BO-28 (ripetizioni 1–20) | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-011 | count 21 | Q-268 DECISA: 422, nessun evento | BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-012 | count 0 | Q-268 DECISA: 422, nessun evento | BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-013 | count -3 | Q-268 DECISA: 422, nessun evento | BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-014 | data assente ⇒ sample_data del tipo | data con le chiavi del sample_data del tipo | ingestion §3 (simulator/fire); F-DEMO-03; BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-015 | data assente: piccole variazioni casuali | due invii senza data hanno data diversi (DIVERGENZA: sample_data identico) | ingestion §3 (simulator/fire); F-DEMO-03; BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-016 | occurredAt assente ⇒ adesso | time = istante del servizio | ingestion §3 (simulator/fire); F-DEMO-03; BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-017 | occurredAt esplicito | time = occurredAt | ingestion §3 (simulator/fire); F-DEMO-03; BO-28 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-018 | stessa pipeline: tipo non ammesso dalla fonte scelta | REJECTED/TYPE_NOT_ALLOWED | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; ingestion §5 | `TestbookIngConfigIT#sim` |
| TB-ING-SIM-019 | stessa pipeline: membro BLOCKED | REJECTED/MEMBER_NOT_ACTIVE | ingestion §3 (simulator/fire); F-DEMO-03; BO-28; docs/03 §2 | `TestbookIngConfigIT#sim` |

### 3.17 SCN — Scenari guidati (F-DEMO-04, docs/10 §8)

**Regola.** Passi `{delayMs, memberId, type, data, source, note, at?, expect?}`; l'esecutore rispetta i ritardi (max 10 s
per passo) e passa dalla stessa pipeline con origine `SIMULATOR`; un passo può dichiarare `expect: REJECTED|DUPLICATE|
UNMATCHED`; `GET …/scenario-runs/{runId}` dà l'avanzamento con il `correlationId` di ogni evento (ingestion §3, §5). Gli
scenari di docs/10 §8 danno gli esiti descritti; `SCN-BAD-EVENT` usa `pos-legacy` (Q-129); `SCN-DUPLICATE` usa `{run}`
(Q-130). Codice: `ScenarioService`, `ScenariosController`.

**Strategia.** Una riga per ogni scenario di docs/10 §8 (eseguito davvero, esiti passo per passo), ripetibilità di
`SCN-DUPLICATE`, ruoli uno per riga, poi una riga per regola dell'esecutore (404, avanzamento, origine, tetto del ritardo,
`expect`, `at`, fonte di default) con scenari di prova creati per il caso.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-SCN-001 | elenco scenari | contiene i 9 scenari di docs/10 §8 | docs/10 §8; ingestion §3 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-002 | SCN-ONBOARDING | DONE; app.login.daily → member.profile.completed → purchase.completed 24,90 tutti ACCEPTED | docs/10 §8; ingestion §5 (scenari); Q-63 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-003 | SCN-TIER-UP | DONE; purchase.completed 130 € ACCEPTED | docs/10 §8; ingestion §5 (scenari) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-004 | SCN-DIGITAL | DONE; ebill.activated → directdebit.activated ACCEPTED | docs/10 §8; ingestion §5 (scenari) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-005 | SCN-REFERRAL | DONE; purchase.completed 45 € ACCEPTED | docs/10 §8; ingestion §5 (scenari) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-006 | SCN-WEEKEND-BURST | DONE; 12 acquisti ACCEPTED | docs/10 §8; ingestion §5 (scenari) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-007 | SCN-DUPLICATE | DONE; ACCEPTED poi DUPLICATE (expect), un solo accredito | docs/10 §8; ingestion §5 (scenari); Q-130 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-008 | SCN-DUPLICATE eseguito una seconda volta | di nuovo ACCEPTED + DUPLICATE | Q-130; docs/17 US-E01-04 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-009 | SCN-BAD-EVENT | DONE; INVALID_DATA, SOURCE_DISABLED, UNMATCHED, MEMBER_NOT_ACTIVE, tutti come expect | docs/10 §8; ingestion §5 (scenari); Q-129 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-010 | SCN-POISON | DONE; l'azione con _poison è ACCEPTED in ingresso | docs/10 §8; ingestion §5 (scenari) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-011 | SCN-SMOKE | DONE; app.login.daily ACCEPTED | docs/10 §8; ingestion §5 (scenari) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-012 | scenario inesistente | 404 | ingestion §3; docs/06 §2 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-013 | esecuzione inesistente | 404 | ingestion §3 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-014 | avvio con ADMIN | 202 {runId} | docs/06 §3; docs/08 §2 (demo.simulate) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-015 | avvio con MARKETING | 202 | docs/08 §2 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-016 | avvio con LEGAL | 202 | docs/08 §2 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-017 | avvio con CARE | 202 | docs/08 §2 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-018 | avvio con ANALYST | 403 | docs/06 §3 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-019 | avvio senza X-LH-Actor | 403 | docs/06 §3 | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-020 | avanzamento passo per passo | stepsTotal, stepsDone, un esito con correlationId per passo, attore registrato | ingestion §3 (scenario-runs) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-021 | origine delle righe degli scenari | origin SIMULATOR | ingestion §5 (origine SIMULATOR) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-022 | ritardo di un passo oltre 10 s | attesa limitata a 10 s | ingestion §5 (max 10 s per passo) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-023 | expect non rispettato | passo ok=false; esecuzione DONE | Q-266: ingestion §5 (esito dell'esecuzione non specificato) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-024 | at = @now | DONE, passo all'istante corrente (DIVERGENZA: espressione non riconosciuta, FAILED) | docs/10 §8 (at è un'espressione del §1, default @now) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-025 | at non valido ("domani") | Q-266: esecuzione FAILED (esito di un passo non valido non specificato) | docs/10 §1; ingestion §2 (scenario_run.status) | `TestbookIngConfigIT#scn` |
| TB-ING-SCN-026 | passo senza source | Q-266: fonte simulator | docs/10 §8 | `TestbookIngConfigIT#scn` |

### 3.18 SCT — Istante di un passo (`at`, docs/10 §1)

**Regola.** `at` è un'espressione di docs/10 §1 (default `@now`), fuso Europe/Rome: `@now`, `@today±…`, `@lastWeekday`
/ `@lastSaturday` = ultimo giorno feriale / ultimo sabato **precedente a oggi**, suffisso `T10:30` = ora del giorno;
altrimenti un istante ISO-8601. Codice: `ScenarioTime#resolve` (righe 24-47): solo `@last<Giorno>T<hh:mm>` e ISO.

| Ingresso | Classi |
|---|---|
| espressione | assente · ISO · `@lastWeekdayT..` · `@lastSaturdayT..` · `@lastSundayT..` · `@lastWeekday` senza orario · `@now` · `@today-1dT..` · giorno sconosciuto · orario non valido |
| adesso (Roma) | prima/dopo l'orario del giorno stesso · mezzanotte esatta · lunedì, sabato, domenica · settimana del cambio dell'ora (marzo, ottobre) · ora inesistente/doppia · fine anno · 29 febbraio |

**Strategia.** Logica pura (unit test, orologio fisso per riga): per ogni espressione, «adesso» prima e dopo l'orario nello
stesso giorno (il caso che distingue «precedente a oggi» da «non futuro»), i giorni della settimana di confine e ogni
anomalia di calendario una volta.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-ING-SCT-001 | at assente ⇒ adesso · at `-` · adesso `2026-09-24T15:00` (Roma) | → 2026-09-24T15:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-002 | at ISO · at `2026-09-20T08:00:00Z` · adesso `2026-09-24T15:00` (Roma) | → 2026-09-20T08:00:00Z UTC | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-003 | @lastWeekdayT10:30 un giovedì alle 15:00 · at `@lastWeekdayT10:30` · adesso `2026-09-24T15:00` (Roma) | DIVERGENZA: → 2026-09-23T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-004 | @lastWeekdayT10:30 un giovedì alle 09:00 · at `@lastWeekdayT10:30` · adesso `2026-09-24T09:00` (Roma) | → 2026-09-23T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-005 | @lastWeekdayT10:30 un lunedì · at `@lastWeekdayT10:30` · adesso `2026-09-21T09:00` (Roma) | → 2026-09-18T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-006 | @lastWeekdayT00:00 alla mezzanotte esatta di lunedì · at `@lastWeekdayT00:00` · adesso `2026-09-21T00:00` (Roma) | DIVERGENZA: → 2026-09-18T00:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-007 | @lastWeekdayT23:59 alle 00:00:01 di martedì · at `@lastWeekdayT23:59` · adesso `2026-09-22T00:00:01` (Roma) | → 2026-09-21T23:59 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-008 | @lastWeekdayT10:30 una domenica · at `@lastWeekdayT10:30` · adesso `2026-09-27T12:00` (Roma) | Q-267 (feriale = lun–ven): → 2026-09-25T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-009 | @lastWeekdayT10:30 un sabato · at `@lastWeekdayT10:30` · adesso `2026-09-26T12:00` (Roma) | → 2026-09-25T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-010 | @lastSaturdayT11:00 un sabato alle 12:00 · at `@lastSaturdayT11:00` · adesso `2026-09-26T12:00` (Roma) | DIVERGENZA: → 2026-09-19T11:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-011 | @lastSaturdayT11:00 un sabato alle 10:00 · at `@lastSaturdayT11:00` · adesso `2026-09-26T10:00` (Roma) | → 2026-09-19T11:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-012 | @lastSaturdayT23:59 la domenica alle 00:30 · at `@lastSaturdayT23:59` · adesso `2026-09-27T00:30` (Roma) | → 2026-09-26T23:59 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-013 | @lastSaturdayT10:05 un venerdì alle 23:59:59 · at `@lastSaturdayT10:05` · adesso `2026-09-25T23:59:59` (Roma) | → 2026-09-19T10:05 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-014 | @lastSaturdayT10:00 dopo il cambio dell'ora di marzo (sabato in CET) · at `@lastSaturdayT10:00` · adesso `2026-03-30T12:00` (Roma) | → 2026-03-28T10:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-015 | @lastWeekdayT10:30 il lunedì dopo il cambio dell'ora di marzo · at `@lastWeekdayT10:30` · adesso `2026-03-30T09:00` (Roma) | → 2026-03-27T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-016 | @lastSundayT02:30 nell'ora che non esiste (29/3/2026) · at `@lastSundayT02:30` · adesso `2026-03-30T12:00` (Roma) | Q-267 (ora inesistente ⇒ 03:30 CEST): → 2026-03-29T01:30:00Z UTC | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-017 | @lastSaturdayT11:00 dopo il cambio dell'ora di ottobre (sabato in CEST) · at `@lastSaturdayT11:00` · adesso `2026-10-26T12:00` (Roma) | → 2026-10-24T11:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-018 | @lastSundayT02:30 nell'ora ripetuta (25/10/2026) · at `@lastSundayT02:30` · adesso `2026-10-26T12:00` (Roma) | Q-267 (ora doppia ⇒ prima occorrenza, CEST): → 2026-10-25T00:30:00Z UTC | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-019 | @lastWeekdayT10:30 il 1° gennaio 2027 alle 12:00 (fine anno) · at `@lastWeekdayT10:30` · adesso `2027-01-01T12:00` (Roma) | DIVERGENZA: → 2026-12-31T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-020 | @lastWeekdayT10:30 il 1° marzo 2028 (29 febbraio) · at `@lastWeekdayT10:30` · adesso `2028-03-01T08:00` (Roma) | → 2028-02-29T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-021 | @lastWeekday senza orario · at `@lastWeekday` · adesso `2026-09-24T15:00` (Roma) | DIVERGENZA: → 2026-09-23T00:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-022 | @now · at `@now` · adesso `2026-09-24T15:00` (Roma) | DIVERGENZA: → 2026-09-24T15:00 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-023 | @today-1dT10:30 · at `@today-1dT10:30` · adesso `2026-09-24T15:00` (Roma) | DIVERGENZA: → 2026-09-23T10:30 (Roma) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-024 | giorno sconosciuto @lastFoodayT10:00 · at `@lastFoodayT10:00` · adesso `2026-09-24T15:00` (Roma) | errore (espressione non valida) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |
| TB-ING-SCT-025 | orario non valido @lastWeekdayT25:00 · at `@lastWeekdayT25:00` · adesso `2026-09-24T15:00` (Roma) | errore (espressione non valida) | docs/10 §1 (espressioni di data, Europe/Rome); docs/10 §8 | `TestbookIngScenarioTimeTest#sct` |

## 4. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate | 28 (R-01…R-28) |
| Rami del codice mappati | 111: `IngestionService` 27, `Source#allows` 2, `InboundResolution` 5, `InboundResolutionService` 14, `EventTypeService` 21, `TransactionsController` 11, `SimulatorController` 5, `ScenarioService` 8, `ScenarioTime` 6, monitor (`InboundEventsController`/`InboundEventRepository`) 6, guardie `@RequiresRole` 6 |
| Fuori da questo file | ponte fatti → azioni e `LOOP_GUARD` (`FactsHandler#bridge`), `PUT /v1/internal-mappings` e riprocessa DLQ (`X-LH-Reprocess`, `ActionReplayService`): foresta docs/17 ING-09, ING-10, ING-15, ING-16 |
| Righe del testbook | 685 |
| Righe per area | FRM 29, PIP 51, SRC 70, FON 4, TYP 11, SCH 148, TIM 26, DUP 12, MBR 39, ACC 10, MON 11, RES 56, AUT 60, ETY 60, TXN 28, SIM 19, SCN 26, SCT 25 |
| Combinazioni ridotte | PIP (512 ⇒ 51: singoli + coppie + catene), RES (> 64 ⇒ 4 tabelle indipendenti: 14 + 10 + 20 + 12), SCH (guasto singolo per campo e limite, 148), ETY (guasto singolo + origine × campo), TXN, SIM (guasto singolo) |
| Tabelle complete | SRC fonte × tipo (55), MBR forma × stato (30), AUT forma × età × stato × fatto (48), RES ruolo × azione (14), RES stato × azione (10) |
| Rami senza specifica | 17 (§2), 48 righe Q-In (18 voci in docs/15, §6) |
| Regole non implementate | 6 (R-14, R-23, filtri di R-13, parti di R-25 e R-27, R-28) |
| Divergenze | 46 righe, 14 cause (§5): tutte risolte |

## 5. Registro delle divergenze

| N. | Righe | Specifica | Comportamento osservato | Causa (file:riga) | Stato |
|---|---|---|---|---|---|
| 1 | SCH-011, 018, 020, 030, 101, 115, 117, 118, 121, 122, 123, 127, 128, 131, 134, 139, 140, 144, 148; TXN-019 | `contracts/events/action/*.schema.json` (precedenza 2): `amount` ≥ 0, `currency` minLength 1, `quantity` ≥ 0, pattern dei codici, `points`/`pointsCost` ≥ 1, `counterpartMemberId` obbligatorio, enum `origin`, minLength 1 | la validazione usa gli schemi di `seed/event-types.json`, più stretti (`exclusiveMinimum 0`, `currency` 3 caratteri, `quantity` ≥ 1) o più larghi (nessun pattern/enum/required, minimum 0) dei contratti | `seed/event-types.json` (`dataSchema` dei tipi); `IngestionService.java:153-158` | risolta: schemi di `seed/event-types.json` allineati ai contratti per gli 8 tipi con contratto; `check-seed.mjs` verifica che coincidano |
| 2 | FRM-024, FRM-025 | envelope: `data` è un oggetto; errori di forma ⇒ 400 (ingestion §5.1; `envelope.schema.json`) | `202 REJECTED/INVALID_DATA` e riga salvata | `IngestionService.java:237` (controlla solo `null`) | risolta: `IngestionService.java:242` (`data` non oggetto ⇒ 400) |
| 3 | FRM-026, FRM-027, TXN-013, TXN-014 | corpo non leggibile ⇒ 400 RFC 9457 (ingestion §3; docs/06 §2) | 500 `INTERNAL_ERROR` | lh-common `GlobalExceptionHandler.java:66` (nessun gestore per `HttpMessageNotReadableException`) | risolta: lh-common `GlobalExceptionHandler.java:48` (`HttpMessageNotReadableException` ⇒ 400 RFC 9457; anche `:60` per i parametri non convertibili) |
| 4 | SRC-068, SRC-069 | `source` di un'azione = `urn:loyaltyhub:source:<codice>` (docs/05 §2) | qualunque stringa che termina con `:ecommerce` vale come fonte `ecommerce` ⇒ ACCEPTED | `IngestionService.java:293-300` (`normalizeSource` + ultimo segmento dopo `:`) | risolta: `IngestionService.java:302-315` (confronto esatto su `urn:loyaltyhub:source:`) |
| 5 | MON-008, MON-009 | filtri `from`, `to`, `q` (ingestion §3) | parametri ignorati | `InboundEventsController.java:55-61` | risolta: `InboundEventsController.java:55-97`, `InboundEventRepository.java:42, 88` (anche su `/counts`; Q-272) |
| 6 | MON-010 | storico di 40 `inbound_event` degli ultimi 3 giorni (ingestion §6) | nessuna riga seminata | `DemoSeeder` (nessun seed di `inbound_event`) | risolta: `seed/inbound-history.json` + `InboundHistorySeeder.java:66`, chiamato da `DemoSeeder.java:82` (Q-271) |
| 7 | FON-002, FON-003, FON-004 | `POST`/`PUT /v1/sources` (ingestion §3; F-ING-05) | `PUT` 404, `POST` 500 | `RegistryController.java:22` (solo `GET`) | risolta: `PUT` dal ramo trasversale (`RegistryController.java:75`); `POST` in `RegistryController.java:68` + `SourceService.java:52` (Q-263) |
| 8 | ETY-007 | codice custom minuscolo (Q-89) | `x.readingSent` accettato | `EventTypeService.java:42` (`[a-zA-Z0-9]` dopo il primo carattere) | risolta: `EventTypeService.java:42` (codice tutto minuscolo; anche `web/lib/actiontypes/schema.ts`) |
| 9 | ETY-025 | lo schema di un custom è un JSON Schema valido (F-ING-06) | `{"type":"nonsense"}` accettato | `EventTypeService.java:146-158` (la compilazione non verifica il meta-schema) | risolta: `EventTypeService.java:144` + lh-common `JsonSchemaValidator.java:44` (meta-schema 2020-12) |
| 10 | SIM-008 | origine `SIMULATOR` per gli eventi del simulatore (ingestion §2) | origine `EXTERNAL` | `SimulatorController.java:62` (`ingest(event)` senza origine) | risolta dal ramo trasversale: `SimulatorController.java:74` (origine `SIMULATOR`) |
| 11 | SIM-015 | `data` assente ⇒ `sample_data` con piccole variazioni casuali (ingestion §3) | `sample_data` identico | `SimulatorController.java:55`, `#sampleData` | risolta: `SimulatorController.java:69, 84` + `SampleVariation.java:33` (Q-264) |
| 12 | SCN-024, SCT-022, SCT-023 | `at` è un'espressione di docs/10 §1 (default `@now`) | `@now`, `@today…` non riconosciuti (esecuzione `FAILED`) | `ScenarioTime.java:28-30` (`Instant.parse`) | risolta: `ScenarioTime.java:25` (grammatica di `SeedDates`, docs/10 §1) |
| 13 | SCT-003, SCT-006, SCT-010, SCT-019 | `@lastWeekday`/`@lastSaturday` = giorno **precedente a oggi** (docs/10 §1) | se l'orario di oggi è già passato si usa oggi | `ScenarioTime.java:38-41` (`back = 0` ammesso) | risolta: `ScenarioTime.java:25` (`@last…` = giorno precedente a oggi) |
| 14 | SCT-021 | `@lastWeekday` senza orario è valido (docs/10 §1) | eccezione «senza orario» | `ScenarioTime.java:32-33` | risolta: `ScenarioTime.java:25` (senza orario = 00:00) |

## 6. Ambiguità (righe Q-In, docs/15)

Ogni riga che la specifica non decide rimanda a una voce di docs/15 (id provvisori `Q-I*`); dove la scelta in uso non è
la più conservativa, la voce lo dice e propone l'alternativa senza implementarla.

| Voce | Righe | Tema | In uso conservativo? |
|---|---|---|---|
| Q-255 | MBR-025…MBR-033 | subject senza prefisso, prefisso sconosciuto, `member:` vuoto, `external:` con maiuscole | DECISA (ogni forma non prevista ⇒ `UNMATCHED`) |
| Q-256 | DUP-006, DUP-007, DUP-008 | la dedup conta solo un `ACCEPTED` | sì |
| Q-257 | TIM-022, TIM-024 | «30 giorni» = 720 ore al cambio dell'ora | — |
| Q-258 | SRC-067, SRC-070 | `source` in forma breve; URN con codice vuoto | DECISA (forma breve su `POST /v1/events` ⇒ `400`) |
| Q-259 | ACC-006 | subject normalizzato sulla riga `ACCEPTED` | — |
| Q-260 | ETY-009, 010, 015, 016, 022, 027, 029, 057 | limiti e default dei tipi custom; nome vuoto su un SYSTEM | sì |
| Q-261 | ETY-034, RES-007, RES-014 | `X-LH-Actor` non valido = ANALYST | sì |
| Q-262 | RES-050…RES-054 | codici e pulizia di *Abbina* | sì |
| Q-265 | SCH-083 | `correctAnswers` > `totalQuestions` | DECISA (`INVALID_DATA`) |
| Q-266 | SCN-023, SCN-025, SCN-026 | esito dell'esecuzione di uno scenario; fonte di default | — |
| Q-267 | SCT-008, SCT-016, SCT-018 | feriale = lun–ven; ora inesistente/doppia | — |
| Q-268 | SIM-011, SIM-012, SIM-013 | `count` fuori 1–20 limitato | DECISA (`422`) |
| Q-269 | TXN-003, TXN-007, TXN-012 | `kind` senza maiuscole; `amount`/`currency` mancanti ⇒ 400 | DECISA (`INVALID_DATA` visibile in BO-26; `kind` resta senza maiuscole) |
| Q-270 | TYP-010, TYP-011 | custom senza schema; custom da fonte con elenco | sì |

Voci nate dalle correzioni, senza righe AMBIGUO: Q-263 (`POST /v1/sources`, FON-003), Q-264 (ampiezza delle variazioni
del simulatore, SIM-015), Q-271 (composizione dello storico demo, MON-010), Q-272 (semantica di `from`/`to`/`q`, MON-008/009).

