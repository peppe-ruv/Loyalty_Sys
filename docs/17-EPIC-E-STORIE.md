# 17 — Epic, storie utente e foresta delle decisioni

Catalogo di **tutte** le epic e le storie utente che si possono associare alle decisioni del codice e delle feature così come si usano in un contesto reale. Serve a verificare che il testbook funzionale (`docs/16`, righe `TB-<DOM>-NNN`) sia completo: ogni storia dice quali decisioni attraversa, con quali esiti, e quale dominio del testbook la deve provare.

## 1. Scopo e come si usa

- **Catena di tracciabilità**: `F-…` (docs/02) → **storia** `US-Enn-nn` → schermate `BO-/PT-/HUB-`, API, eventi `EVT-…` → **decisioni** (regola → rami, con codici d'errore) → **nodo della foresta** (§5, dal codice) → **riga di testbook** `TB-<DOM>-NNN`.
- **Oracolo = la specifica** (come in docs/16): i criteri di accettazione derivano da docs/02–10, docs/servizi, docs/12 e dalle decisioni `Q-nn` di docs/15. La foresta (§5) nasce dal **codice** e serve a due cose: (a) rendere esaustivo l'elenco dei rami; (b) far emergere i **rami senza specifica** e le **regole non implementate**.
- **Stato di copertura** (§6): `coperta` = esistono righe TB · `pianificata` = il dominio TB esiste ma le righe non sono scritte · `scoperta` = nessun dominio TB la coprirebbe · `fuori perimetro PoC` = feature P2 (docs/01 §4). Alla data di questo documento docs/16 non contiene ancora righe (tutte le sezioni sono «In revisione») e `docs/testbook/` non esiste: nessuna storia è quindi `coperta`.
- **Manutenzione**: nuova feature o nuova schermata → nuova storia (o criterio) qui; nuovo `LhException`, nuovo enum di esito, nuovo `@RequiresRole` o nuovo job → nuova riga in §5 (i comandi per rifare l'inventario sono in §5.0); nuove righe TB → aggiornare la colonna *Testbook* della storia e la matrice §6. Un ramo che resta senza storia va in §6.3.
- Convenzioni: `Dato/Quando/Allora` = criterio di accettazione; «SPEC-GAP» = scelta registrata in docs/15; «⚠ divergenza» = il codice non segue la specifica (va in docs/16 §12 quando la riga TB esiste); «⛔ non implementata» = regola di specifica senza codice.

## 2. Attori

### 2.1 Persone del backoffice (identità simulata `X-LH-Actor`, docs/06 §3)

| Username (spec) | Nome (docs/01 §5, docs/10 §2) | Ruolo | Capacità principali (docs/08 §2) | Nel codice (`web/lib/persona/personas.ts`) |
|---|---|---|---|---|
| `marta.admin` | Marta Villa | `ADMIN` | tutto; reset, job, pianta istante, applica chiusura, riprocessa DLQ, webhook, override approvazioni | `marta.admin` «Marta Villa» |
| `luca.marketing` | Luca Serra | `MARKETING` | `object.edit`, `content.write`, `segment.write`, `actiontype.custom`, simulatore/scenari; *invia in revisione* | `luca.marketing` «Luca Serra» |
| `elena.legal` | Elena Riva | `LEGAL` | `object.approve` (APPROVE/REJECT), `instants.view`, simulatore/scenari | `elena.legal` «Elena Riva» |
| `paolo.care` | Paolo Neri | `CARE` | `member.write`, `points.adjust`, `redemption.handle`, `delivery.handle`, `coupon.void`, `inbound.handle` | `paolo.care` «Paolo Neri» |
| `sara.analyst` | Sara Longo | `ANALYST` | sola lettura (ogni scrittura → 403) | `sara.analyst` «Sara Longo» |
| — | chiamante senza header | `ANALYST:anonymous` | sola lettura; header malformato o ruolo ignoto ⇒ `ANALYST` | `ActorContext.parse` |

Nomi e username delle personas nel web allineati a docs/01/docs/10 (`web/lib/persona/personas.test.ts`). ⚠ divergenza residua: `seed/personas.json` (docs/10 §1) non esiste (le personas sono solo nel web). Da registrare in docs/15 e da coprire in TB-WEB (selettore persona).

### 2.2 Membri del portale (dati demo, docs/10 §2)

| ID | Nome | Stato · tier · PTS · STS | Situazione | Storie che la usano |
|---|---|---|---|---|
| MBR-000001 | Anna Rossi | ACTIVE · BASE · 100 · 0 | iscritta ieri, profilo incompleto | US-E10-16, US-E10-06, US-E07-03 |
| MBR-000002 | Marco Bianchi | ACTIVE · SILVER · 1.850 · 1.420 | cliente tipo, membro predefinito del portale, ha invitato Elisa | US-E10-14, US-E01-04, US-E11-09, US-E08-11 |
| MBR-000003 | Giulia Ferri | ACTIVE · SILVER · 3.240 · 2.880 | a 120 STS da GOLD | US-E10-03, US-E04-03 |
| MBR-000004 | Davide Russo | ACTIVE · GOLD · 12.300 · 4.100 | punti da spendere, 2 coupon (uno scade tra 9 gg) | US-E10-04, US-E05-04 |
| MBR-000005 | Francesca Romano | ACTIVE · PLATINUM · 28.750 · 9.600 | premi riservati, classifica | US-E05-04, US-E06-15 |
| MBR-000006 | Stefano Galli | ACTIVE · GOLD · 5.400 · 650 | poco attivo → scende a SILVER in chiusura | US-E04-12, US-E04-13, US-E02-08 |
| MBR-000007 | Chiara Marino | ACTIVE · SILVER · 2.400 · 1.150 | 1.900 PTS in scadenza a +12 gg | US-E10-13, US-E04-04, US-E04-05 |
| MBR-000008 | Roberto Costa | BLOCKED · BASE · 320 · 40 | sospeso dall'assistenza | US-E01-05, US-E10-11, US-E02-04 |
| MBR-000009 | Elisa Fontana | ACTIVE · BASE · 300 · 0 | invitata da Marco, nessun acquisto | US-E10-15, US-E06-17 |
| MBR-000010 | Matteo Ricci | ACTIVE · SILVER · 1.120 · 1.060 | 3 giocate su IW-AUTUNNO, streak 5/7 | US-E10-07, US-E06-05 |
| MBR-000011 | Sofia Greco | ACTIVE · GOLD · 6.900 · 3.350 | borraccia CONFIRMED in attesa di spedizione | US-E10-05, US-E05-10 |
| MBR-000012 | (Membro anonimo) | ANONYMIZED | non selezionabile; movimenti conservati | US-E02-05, US-E11-02 |

Membro nuovo (registrato dal portale o da BO-02): US-E10-08, US-E01-06.

### 2.3 Sistemi esterni

| Sistema | Come entra | Storie |
|---|---|---|
| Fonti HTTP `crm`, `app`, `ecommerce`, `billing`, `partner` | `POST /v1/events` (CloudEvent), `POST /v1/transactions` | US-E01-01…08 |
| Fonte `internal` (ponte fatti → azioni) | consumer `FactsHandler` in ingestion | US-E01-09 |
| Fonte `simulator` (demo) | BO-28, PT-14, scenari BO-29 | US-E11-05, US-E11-06 |
| Fonte sconosciuta o spenta (es. `pos-legacy`, Q-129) | `POST /v1/events` | US-E01-03 |
| Ricevitori webhook (`https://…`, esempio in `deploy/webhook-receiver/`) | consegne firmate HMAC da engagement | US-E08-08…10 |
| Browser del membro / del presentatore | proxy Next.js `/api/lh/**`, SSE diretto a insight | US-E10-*, US-E11-* |
| Provider gratuiti (Kafka, Postgres, hosting) | sonno/risveglio, spegnimento Kafka | US-E11-01, US-E12-05 |

### 2.4 Il sistema stesso (job e processi automatici)

| Processo | Servizio | Cadenza / innesco | Interruttore | Decide | Storie |
|---|---|---|---|---|---|
| `OutboxRelay` | tutti | ogni 500 ms | sempre | pubblica righe `outbox` non pubblicate | US-E12-01 |
| `OutboxCleanup` | tutti | ogni ora (:15) | sempre | cancella outbox pubblicati > 24 h | US-E09-06 |
| `CampaignCache.reload` | campaign | 30 s + a ogni scrittura | sempre | quali campagne `LIVE` valuta il motore | US-E03-03 |
| Rilascio pending | wallet | ogni ora (cron senza zona) | `loyaltyhub.jobs.enabled` | lotti `PENDING` con `availableAt ≤ asOf` → `ACTIVE` | US-E04-02 |
| Scadenza punti | wallet | 02:00 (cron senza zona) | `jobs.enabled` | lotti `ACTIVE` con `expiresAt ≤ asOf` → `EXPIRED` | US-E04-04 |
| Preavvisi scadenza | wallet | 09:00 (cron senza zona) | `jobs.enabled` | lotti in scadenza entro 30 gg, una volta per lotto | US-E04-05 |
| Timeout richieste | reward | ogni 60 s | `loyaltyhub.reward.redemption-timeout.enabled` (acceso, Q-55) | `PENDING` > 10 min → `REJECTED/TIMEOUT` | US-E05-08 |
| Scadenza coupon | reward | 02:30 Europe/Rome | `jobs.enabled` | `ISSUED` scaduti → `EXPIRED` | US-E05-17 |
| Fine concorsi | gamification | ogni 5 min | `jobs.enabled` | `LIVE` con `endAt` passato → `ENDED`, istanti `OPEN` → `VOID` | US-E06-10 |
| Fine contenuti · pulizie | engagement | 10 min · 03:30 Rome | `jobs.enabled` | `LIVE` con `endAt` passato → `ENDED`; inbox > 180 gg, pop-up > 90 gg, consegne > 14 gg | US-E07-02, US-E09-06 |
| Dispatcher webhook | engagement | ogni 30 s | `loyaltyhub.webhooks.dispatcher.enabled` (acceso, Q-101) | consegne dovute: `OK` / `FAILED` / `GAVE_UP` | US-E08-09 |
| Ricalcolo segmenti | member | ogni 15 min se ci sono variazioni | `jobs.enabled` | `member.segment.entered/left` solo per differenze | US-E02-11 |
| Riannuncio segmenti | member | dopo seed/reset + 15 s (Q-81) | sempre | `entered` per tutte le appartenenze | US-E02-11 |
| Retention insight | insight | ogni ora (:20) | sempre | event store 14 gg / 200 000 righe, audit 180 gg | US-E09-06 |
| Esecutore scenari | ingestion | su comando | — | passi con ritardo ≤ 10 s, esito `DONE/FAILED` | US-E11-06 |
| Heartbeat SSE | insight | 15 s | sempre | tiene aperte le connessioni live | US-E09-01 |
| Chiusura edizione | wallet | solo su comando (BO-08) | ruolo ADMIN | discesa morbida | US-E04-13 |

### 2.5 Operatore demo
Il presentatore (in pratica `marta.admin` + un membro scelto) che accende la demo da HUB-01, lancia scenari (BO-29), simula eventi (BO-28, PT-14), usa la macchina del tempo e il reset (BO-30). Storie: epic E11.

## 3. Epic

| Epic | Obiettivo | Attori | Feature | Schermate | Servizi | TB |
|---|---|---|---|---|---|---|
| **E01 Ingresso eventi** | ogni fatto del cliente diventa un'azione canonica, una sola volta, per il membro giusto | fonti, CARE, ADMIN, MARKETING | F-ING-01…10 | BO-09, BO-26 | ingestion | TB-ING |
| **E02 Membri e segmenti** | anagrafica affidabile, stati, privacy, segmenti usati da campagne/premi/contenuti | CARE, ADMIN, MARKETING | F-MBR-01…05, 08, F-SEG-01…03 | BO-02, BO-03, BO-04 | member (+ tutti per gli snapshot) | TB-GOV |
| **E03 Campagne e motore regole** | regole configurabili, spiegabili, simulabili, con limiti e budget | MARKETING, LEGAL, membro | F-CMP-01…14 | BO-05, BO-06, BO-03 (azioni), BO-25, PT-02 | campaign | TB-CMP |
| **E04 Wallet, livelli ed edizioni** | punti a doppia valuta, lotti e scadenze, tier annuali con discesa morbida | sistema, CARE, ADMIN, membro | F-WAL-01…09, F-TIER-01…06 | BO-03, BO-07, BO-08, BO-30, PT-01, PT-07, PT-08 | wallet | TB-WAL |
| **E05 Premi e coupon** | catalogo a fasce, richiesta premio in saga, coupon ed evasione | membro, MARKETING, LEGAL, CARE, sistema | F-RWD-01…08, F-CPN-01…03 | BO-10…13, PT-03, PT-04, PT-13 | reward (+ wallet) | TB-RWD |
| **E06 Gioco** | instant win certificabile, obiettivi, badge, classifiche, referral | membro, MARKETING, LEGAL, CARE, ADMIN | F-IW-01…08, F-ACH-01…03, F-LDB-01, F-REF-01…02 | BO-14…17, PT-05, PT-06, PT-09, PT-10, PT-11 | gamification, member | TB-GAM |
| **E07 Contenuti, messaggi, tema** | il backoffice fa da CMS: card, pop-up, card vincita, inbox, tema a runtime | MARKETING, membro, sistema | F-CNT-01…04, F-MSG-01…02, F-THM-01 | BO-18, BO-19, BO-20, PT-01, PT-03, PT-05, PT-06, PT-12 | engagement | TB-ENG |
| **E08 Governance** | approvazioni per policy, ruoli, audit, webhook, DLQ | LEGAL, MARKETING, ADMIN, ricevitori | F-APR-01…03, F-AUD-01, F-WBH-01, F-INS-05 | BO-21, BO-22, BO-23, BO-27 | tutti, lh-common, engagement, insight | TB-GOV, TB-ENG |
| **E09 Osservabilità** | vedere l'architettura: flusso live, tracciati, KPI, stato pipeline | ANALYST, tutti | F-INS-01…04, F-INS-06 | BO-01, BO-24, BO-25, rail | insight | (nessuno: TB-WEB solo lato client) |
| **E10 Portale del membro** | percorsi reali del cliente finale, end-to-end tra servizi | membro | F-MBR-06, F-MBR-07 + tutte le feature visibili nel portale | PT-01…PT-14 | web + tutti | TB-WEB (+ TB-E2E proposto) |
| **E11 Demo e ambiente** | accendere, raccontare, riportare a zero la demo senza interventi manuali | operatore demo, ADMIN | F-DEMO-01…07 | HUB-01, BO-28, BO-29, BO-30, PT-14 | web, ingestion, tutti (`/v1/demo/**`) | TB-WEB, TB-ING |
| **E12 Affidabilità e contratti** | nessuna perdita, nessun doppio effetto, ordine per membro, contratti stabili | sistema | RNF-01…10 (docs/02 §2) | — | lh-common, tutti | (nessuno: parziale nei domini) |

## 4. Storie utente

Formato: *Come … voglio … così che …* · **Contesto reale** · **Tocca** (feature, schermate, API, eventi, nodi della foresta §5) · **Decisioni** (regola → rami/esiti) · **Criteri** (Dato/Quando/Allora; i negativi sono marcati ✗) · **Testbook**. Tutti i tempi di business sono in Europe/Rome.

### E01 — Ingresso eventi

#### US-E01-01 · Una fonte invia un'azione valida
*Come* sistema e-commerce, *voglio* inviare l'ordine appena pagato come CloudEvent, *così che* il cliente riceva i punti senza che io conosca le regole del programma.
- **Contesto reale**: sabato sera Marco paga online 130 €; l'e-commerce invia `purchase.completed` con `subject=email:Marco.Bianchi@example.org` e `type` breve.
- **Tocca**: F-ING-01, F-ING-03 · `POST /v1/events` · EVT-ACT-01 su `lh.actions.v1` · BO-26 · ING-01…ING-08, ING-22.
- **Decisioni**: tipo breve o completo → normalizzato · subject `member:`/`external:`/`email:` (e-mail senza maiuscole) → `member:<id>` · esito `ACCEPTED` → 202 `{eventId, status, memberId}`, `lhhop=0`, `lhcorrelationid=id`, chiave Kafka = memberId.
- **Criteri**:
  1. Dato un evento valido per MBR-000002, quando lo invio, allora 202 `ACCEPTED` e un record su `lh.actions.v1` con chiave `MBR-000002` e `lhhop=0` (ingestion §7).
  2. Dato `subject=email:MARCO.BIANCHI@example.org`, allora l'evento è attribuito a MBR-000002; con `external:CRM-102` idem.
  3. Dato `type=purchase.completed` oppure `io.loyaltyhub.action.purchase.completed`, allora esito identico e `type` pubblicato in forma completa.
  4. Dato un evento accettato, allora la riga BO-26 ha `origin=EXTERNAL`, membro risolto e collegamento al tracciato.
- **Testbook**: TB-ING (da coprire).

#### US-E01-02 · Un evento malformato viene respinto senza traccia
*Come* integratore di una nuova fonte, *voglio* un 400 chiaro quando l'envelope è sbagliato, *così che* corregga l'integrazione prima di andare in produzione.
- **Contesto reale**: il partner dei sondaggi manda `time` come «24/09/2026» o dimentica `data`.
- **Tocca**: F-ING-01 · `POST /v1/events` · ING-01 · errori RFC 9457.
- **Decisioni**: passo 1 della pipeline → 400 per `specversion` ≠ `1.0`, campo obbligatorio vuoto (`id, source, type, subject, time`), `data` nullo, `time` non RFC 3339; nulla salvato, nulla pubblicato.
- **Criteri**:
  1. ✗ Dato `specversion: "0.3"`, allora 400 `BAD_REQUEST` e nessuna riga in BO-26.
  2. ✗ Dato un evento senza `subject` (o con `subject` di soli spazi), allora 400 con il campo indicato nel `detail`.
  3. ✗ Dato `data: null`, allora 400; dato `time: "24/09/2026"`, allora 400.
  4. Dato un corpo con `content-type: application/cloudevents+json`, allora è accettato come `application/json`.
- **Testbook**: TB-ING (da coprire).

#### US-E01-03 · Rifiuti di business (fonte, tipo, dati, tempo)
*Come* responsabile delle integrazioni, *voglio* che un evento non ammissibile sia registrato come `REJECTED` con un codice, *così che* la fonte non ritenti e io veda il motivo in BO-26.
- **Contesto reale**: un vecchio POS (`pos-legacy`) continua a inviare; un'app manda `amount` negativo; un batch notturno rimanda ordini di 40 giorni fa; un'azione arriva con l'orologio del client avanti di 10 minuti.
- **Tocca**: F-ING-01, F-ING-05, F-ING-06 · ING-02…ING-05, ING-23 · `SCN-BAD-EVENT` · Q-129.
- **Decisioni**: ordine fisso, primo fallimento vince: fonte inesistente/spenta → `SOURCE_DISABLED` · tipo inesistente/spento → `UNKNOWN_TYPE` · tipo non ammesso dalla fonte (elenco non vuoto) → `TYPE_NOT_ALLOWED` · `data` contro schema → `INVALID_DATA` · `time` > +5 min o < −30 gg → `INVALID_TIME`. Tutti 202 con `status=REJECTED`.
- **Criteri**:
  1. ✗ Dato `source=pos-legacy`, allora 202 `REJECTED/SOURCE_DISABLED` e riga in BO-26 (Q-129).
  2. ✗ Dato `purchase.completed` senza `data.amount`, allora `REJECTED/INVALID_DATA` con il campo indicato (ingestion §7).
  3. ✗ Dato `time` = adesso + 6 min, allora `INVALID_TIME`; dato adesso + 5 min esatti, allora prosegue.
  4. ✗ Dato un evento con fonte spenta **e** dati non validi, allora il codice è `SOURCE_DISABLED` (ordine della pipeline).
  5. Dato un tipo senza JSON Schema, allora il passo 4 non respinge (ramo senza specifica, ING-04).
- **Testbook**: TB-ING (da coprire).

#### US-E01-04 · Deduplica: lo stesso evento non paga due volte
*Come* membro, *voglio* che un ordine rinviato dalla fonte non mi dia punti doppi, *così che* il saldo sia giusto; *come* azienda, *voglio* non pagare due volte.
- **Contesto reale**: l'e-commerce va in timeout sulla risposta e ritenta lo stesso evento dopo 30 s; due repliche dell'integrazione inviano lo stesso id nello stesso istante.
- **Tocca**: F-ING-02 · ING-06 · `SCN-DUPLICATE` (Q-130) · RNF-03.
- **Decisioni**: esiste un `ACCEPTED` con stessa fonte+id → `DUPLICATE` (riga salvata, nulla sul topic) · un precedente `REJECTED/UNMATCHED` non blocca · gara sull'insert → `DUPLICATE`.
- **Criteri**:
  1. Dato lo stesso `source`+`id` inviato due volte, allora la seconda risposta è 202 `DUPLICATE` e su Kafka c'è un solo record (docs/12 M0).
  2. Dato lo stesso `id` da due fonti diverse, allora entrambi `ACCEPTED`.
  3. Dato un primo invio `REJECTED/INVALID_DATA` e un secondo corretto con lo stesso id, allora il secondo è `ACCEPTED`.
  4. Dato due invii concorrenti, allora esattamente un `ACCEPTED` e un `DUPLICATE`.
  5. Dato `SCN-DUPLICATE` eseguito due volte, allora ogni esecuzione ha un `ACCEPTED` e un `DUPLICATE` (Q-130).
- **Testbook**: TB-ING (da coprire).

#### US-E01-05 · Membro sconosciuto o non attivo
*Come* responsabile del programma, *voglio* che gli eventi di un cliente non iscritto vengano parcheggiati e quelli di un membro sospeso respinti, *così che* nessuno accumuli fuori regola ma nessun evento vada perso.
- **Contesto reale**: un cliente acquista in negozio prima di iscriversi; l'assistenza ha sospeso Roberto (BLOCKED) che continua a comprare; un membro anonimizzato riceve ancora eventi dal CRM.
- **Tocca**: F-ING-03, F-ING-04, F-MBR-04 · ING-07 · Q-128.
- **Decisioni**: membro non trovato → `UNMATCHED` · stato ≠ ACTIVE → `REJECTED/MEMBER_NOT_ACTIVE` · anonimizzato: `member:` → `MEMBER_NOT_ACTIVE`, `email:`/`external:` → `UNMATCHED` (Q-128) · subject senza prefisso o con prefisso sconosciuto → `UNMATCHED` (Q-255).
- **Criteri**:
  1. ✗ Dato Roberto (MBR-000008, BLOCKED), quando invio un acquisto, allora `REJECTED/MEMBER_NOT_ACTIVE`, niente sul topic, riga in BO-26 (docs/12 M1).
  2. Dato `subject=external:CRM-999` inesistente, allora `UNMATCHED` (parcheggiato).
  3. ✗ Dato MBR-000012 (ANONYMIZED) con `member:MBR-000012`, allora `MEMBER_NOT_ACTIVE`.
  4. Dato un membro INACTIVE, allora `MEMBER_NOT_ACTIVE`.
  5. Dato `subject=MBR-000002` (senza prefisso), allora `UNMATCHED`, recuperabile con *Abbina* (Q-255).
- **Testbook**: TB-ING (da coprire).

#### US-E01-06 · Abbinamento automatico alla registrazione
*Come* nuovo iscritto, *voglio* ritrovare i punti degli acquisti fatti prima di iscrivermi (ultimi giorni), *così che* l'iscrizione non mi faccia perdere nulla.
- **Contesto reale**: martedì Luca compra con la carta fedeltà ma non è ancora nel programma (`external:CRM-200`); giovedì si registra con quell'externalId.
- **Tocca**: F-ING-04 · `member.registered` · ING-13, ING-14 · Q-115, Q-116, Q-119 · CMP-01 (⛔ ritentativo NO_MEMBER).
- **Decisioni**: solo a `member.registered` e solo subject `external:`/`email:` (mai `member:`) · finestra 7 gg, max 100 righe · riga già risolta → saltata · esito ACCEPTED per quel membro → `AUTO_MATCH` + outbox · altro esito → riga resta `UNMATCHED`.
- **Criteri**:
  1. Dato un `UNMATCHED` di 3 giorni fa con `external:CRM-200`, quando si registra il membro con quell'externalId, allora la riga diventa `ACCEPTED` (risoluzione `AUTO_MATCH`, audit come job) e l'azione arriva al motore.
  2. ✗ Dato un `UNMATCHED` di 8 giorni fa, allora resta `UNMATCHED`.
  3. ✗ Dato un `UNMATCHED` con `member:MBR-000099`, allora non si abbina mai automaticamente.
  4. ✗ Dato un evento parcheggiato la cui fonte nel frattempo è stata spenta, allora la riga resta `UNMATCHED` (Q-116).
  5. Dato il fatto `member.registered` riconsegnato, allora nessuna seconda pubblicazione.
  6. ⛔ Dato un'azione accettata prima che lo snapshot del membro arrivi al motore, allora oggi è `NO_MEMBER` subito (campaign §5 chiede 3 ritentativi).
- **Testbook**: TB-ING (da coprire); il punto 6 in TB-CMP.

#### US-E01-07 · L'assistenza riprova o abbina un ingresso
*Come* operatore CARE, *voglio* riprovare un evento respinto dopo aver corretto la causa, o abbinare a mano un evento non abbinato, *così che* il cliente ottenga i punti che gli spettano.
- **Contesto reale**: un cliente chiama: «ho comprato ieri ma non ho punti»; in BO-26 l'evento è `UNMATCHED` perché la cassa ha usato un'e-mail vecchia.
- **Tocca**: F-ING-04, F-ING-09 · BO-26 · `POST /v1/inbound-events/{id}/retry|match` · ING-11, ING-12, ING-22, WEB-09 · Q-114, Q-117, Q-118.
- **Decisioni**: *Riprova* solo `REJECTED/UNMATCHED` (altri → 409 `INBOUND_NOT_RETRYABLE`) · *Abbina* solo `UNMATCHED` (409 `INBOUND_NOT_UNMATCHED`) · `memberId` vuoto → 422 `MEMBER_REQUIRED` · inesistente → 422 `MEMBER_NOT_FOUND` · non attivo → 422 `MEMBER_NOT_ACTIVE` · rivalutazione ACCEPTED → riga aggiornata + outbox · altro esito → nuovo esito sulla stessa riga · accettato altrove → `DUPLICATE` · audit `TRANSITION` · ruoli ADMIN, CARE.
- **Criteri**:
  1. Dato un `UNMATCHED`, quando CARE lo abbina a MBR-000002, allora la riga diventa `ACCEPTED` (`MANUAL_MATCH`), l'azione arriva al motore con lo **stesso** id e compare una voce di audit.
  2. ✗ Dato un `ACCEPTED`, quando riprovo, allora 409 `INBOUND_NOT_RETRYABLE` e nessuna ripubblicazione.
  3. ✗ Dato l'abbinamento a Roberto (BLOCKED), allora 422 `MEMBER_NOT_ACTIVE`.
  4. ✗ Dato un `REJECTED/SOURCE_DISABLED` riprovato con la fonte ancora spenta, allora la riga resta `REJECTED` con lo stesso codice.
  5. ✗ Dato MARKETING o ANALYST, allora i pulsanti sono disabilitati e l'API risponde 403.
  6. Dato due *Riprova* concorrenti, allora una sola pubblicazione.
- **Testbook**: TB-ING (da coprire); pulsanti in TB-WEB.

#### US-E01-08 · Transazioni d'acquisto e resi
*Come* sistema di cassa, *voglio* inviare un ordine con le righe (e il reso) senza costruire un CloudEvent, *così che* l'integrazione sia semplice.
- **Contesto reale**: il gestionale del negozio chiude lo scontrino e chiama `POST /v1/transactions`; tre giorni dopo il cliente rende la merce.
- **Tocca**: F-ING-07, F-CMP-14 (P2) · ING-19 · Q-49.
- **Decisioni**: 400 corpo o campi mancanti (`source`, `orderId`, `memberRef`) · `amount`/`currency` mancanti → `202 REJECTED/INVALID_DATA` dello schema, visibile in BO-26 (Q-269) · `kind` ∉ PURCHASE/RETURN → 400 · PURCHASE → `purchase.completed`, `id=txn-<orderId>` · RETURN → `purchase.returned`, `id=txn-return-<orderId>` · poi la pipeline normale.
- **Criteri**:
  1. Dato un ordine ORD-1 per `external:CRM-102`, allora 202 `ACCEPTED` con `eventId=txn-ORD-1`.
  2. Dato lo stesso ordine rinviato, allora `DUPLICATE` (id deterministico).
  3. Dato un ordine senza `currency`, allora `REJECTED/INVALID_DATA` (Q-269); dato `kind=REFUND`, allora 400.
  4. Dato un reso, allora `purchase.returned` accettato ma nessuno storno di punti (F-CMP-14 fuori perimetro, US-E03-18).
- **Testbook**: TB-ING (da coprire).

#### US-E01-09 · Ponte interno: i fatti diventano azioni
*Come* responsabile marketing, *voglio* premiare accadimenti interni (salita di livello, vincita, badge, referral, premio riscattato) con le stesse campagne, *così che* tutto sia «un'azione premiante».
- **Contesto reale**: Giulia sale a GOLD; il fatto `tier.upgraded` rientra come azione e `CMP-TIER-UP-BONUS` le dà 500 PTS, nello stesso tracciato.
- **Tocca**: F-ING-08, F-IW-06 · ING-15 · docs/05 §7 · EVT-ACT-20…28 · `LOOP_GUARD`.
- **Decisioni**: solo i 9 fatti mappabili · mappatura spenta → nessuna azione · `lhhop+1 > 3` → DLQ `LOOP_GUARD` · azione con nuovo id, `source=internal`, stesso subject/time/correlazione, causazione = fatto, hop + 1 · nessun controllo sullo stato del membro (lo fa il motore).
- **Criteri**:
  1. Dato il fatto `tier.upgraded`, allora compare `action.tier.upgraded` con `source=…:internal`, stesso `lhcorrelationid`, `lhhop=1` (ingestion §7).
  2. ✗ Dato un fatto mappato con `lhhop=3`, allora l'azione non è pubblicata e c'è un record DLQ `LOOP_GUARD` (ingestion §7, docs/12 M3).
  3. Dato il ponte `contest.won` spento da BO-09, allora la vincita non consegna punti (nessuna azione) e il fatto è comunque consumato.
  4. Dato `SCN-TIER-UP`, allora nel tracciato c'è **un solo albero** (docs/12 M3).
- **Testbook**: TB-ING (da coprire).

#### US-E01-10 · L'amministratore governa il ponte
*Come* ADMIN, *voglio* accendere o spegnere una mappatura fatto → azione, *così che* possa fermare un premio interno che crea problemi senza rilasciare codice.
- **Contesto reale**: una campagna sui badge sta pagando troppo: Marta spegne `badge.awarded → action.badge.awarded`.
- **Tocca**: F-ING-08 · BO-09 (scheda *Ponte*) · `PUT /v1/internal-mappings/{factType}` · ING-16, ING-22.
- **Decisioni**: famiglia vietata (`wallet.points.*`, `wallet.spend.*`, `campaign.*`, `message.*`, `*.status.changed`) → 422 `INTERNAL_MAPPING_FORBIDDEN` · `enabled` assente → 422 `ENABLED_REQUIRED` · inesistente → 404 · ok → audit · solo ADMIN.
- **Criteri**:
  1. Dato ADMIN che spegne `badge.awarded`, allora `GET /v1/internal-mappings` lo mostra spento e c'è una voce di audit.
  2. ✗ Dato `PUT …/fact.wallet.points.earned`, allora 422 `INTERNAL_MAPPING_FORBIDDEN`.
  3. ✗ Dato MARKETING, allora 403.
- **Testbook**: TB-ING (da coprire).

#### US-E01-11 · Gestione delle fonti ⛔
*Come* ADMIN, *voglio* disabilitare una fonte o restringerne i tipi ammessi da BO-09, *così che* un sistema che invia dati errati venga fermato subito.
- **Contesto reale**: il partner dei sondaggi invia duplicati a raffica: Marta spegne `partner`.
- **Tocca**: F-ING-05 · BO-09 (scheda *Fonti*, interruttore) · ING-02, ING-03, ING-22 (`PUT /v1/sources/{code}`; ⛔ `POST /v1/sources` assente).
- **Decisioni**: fonte spenta → `SOURCE_DISABLED`; tipi ammessi → `TYPE_NOT_ALLOWED`; `PUT /v1/sources/{code}` `{enabled?, allowedTypes?}` solo ADMIN (`program.config`), audit `UPDATE`; corpo vuoto o tipo sconosciuto → 422 `SOURCE_INVALID`; fonte inesistente → 404.
- **Criteri**:
  1. Dato ADMIN, quando spegne una fonte da BO-09, allora gli eventi successivi sono `REJECTED/SOURCE_DISABLED`.
  2. ✗ Dato MARKETING, allora la modifica è negata (403).
  3. ⛔ Dato ADMIN, quando vuole aggiungere una fonte nuova, allora oggi non può (`POST /v1/sources` assente).
- **Testbook**: TB-ING (da coprire; 3 in divergenza finché l'API manca).

#### US-E01-12 · Tipi azione custom
*Come* MARKETING, *voglio* creare un nuovo tipo azione con i suoi campi, *così che* possa premiarlo con una campagna senza rilasciare codice.
- **Contesto reale**: arriva un nuovo servizio «lettura contatore via smart meter»: Luca crea `meter.reading.sent` con `meterId` obbligatorio.
- **Tocca**: F-ING-06 · BO-09, BO-06, BO-28 · `POST/PUT /v1/event-types`, `GET …/fields` · ING-17, ING-18 · Q-89 · docs/12 M6.
- **Decisioni**: codice minuscolo a punti 2–4 parti ≤ 60 · nome ≤ 60 · categoria TRANSACTION/ENGAGEMENT/SERVICE · schema `object` · esempio valido → altrimenti 422 `EVENT_TYPE_INVALID` · duplicato → 409 `EVENT_TYPE_EXISTS` · codice diverso in modifica → 422 `EVENT_TYPE_IMMUTABLE_FIELD` · tipo di sistema: solo ADMIN (altri 403), solo nome/descrizione/icona/abilitazione (altrimenti 422 `EVENT_TYPE_SYSTEM_LOCKED`).
- **Criteri**:
  1. Dato un tipo custom creato da BO-09, allora è inviabile da BO-28, selezionabile in BO-06 con i suoi campi e produce punti senza ridistribuire nulla (docs/12 M6).
  2. ✗ Dato il codice `Meter_Reading`, allora 422 con errore sul campo `code`.
  3. ✗ Dato MARKETING che modifica `purchase.completed`, allora 403; dato ADMIN che ne cambia lo schema, allora 422 `EVENT_TYPE_SYSTEM_LOCKED`.
  4. Dato ADMIN che disabilita un tipo, allora i nuovi eventi di quel tipo sono `UNKNOWN_TYPE`.
- **Testbook**: TB-ING (da coprire).

#### US-E01-13 · Monitor ingressi
*Come* ANALYST o CARE, *voglio* vedere ogni evento ricevuto con esito, codice e payload, *così che* possa rispondere a «perché non ho i punti?».
- **Contesto reale**: dopo `SCN-BAD-EVENT` si apre BO-26 e si leggono i 4 esiti negativi.
- **Tocca**: F-ING-09 · BO-26 · `GET /v1/inbound-events`, `/counts`, `/{id}` · ING-23.
- **Decisioni**: schede per esito (`ACCEPTED, DUPLICATE, REJECTED, UNMATCHED`) con conteggi · filtri `status, source, type, memberId, from, to, q` · dettaglio con errori di schema e `TraceLink` se accettato.
- **Criteri**:
  1. Dato `SCN-BAD-EVENT`, allora BO-26 mostra `INVALID_DATA`, `SOURCE_DISABLED`, `UNMATCHED`, `MEMBER_NOT_ACTIVE` (docs/10 §8).
  2. Dato un filtro per membro, allora solo le righe di quel membro; conteggi coerenti col filtro.
  3. Dato un `REJECTED/INVALID_DATA`, allora il dettaglio elenca gli errori dello schema campo per campo.
- **Testbook**: TB-ING (API) · TB-WEB (schermata).

#### US-E01-14 · Invio batch — fuori perimetro PoC
*Come* sistema che esporta di notte, *voglio* inviare fino a 100 eventi in una chiamata con esito per elemento.
- **Tocca**: F-ING-10 (P2) — nessun endpoint `POST /v1/events/batch`.
- **Criteri**: nessuno nel PoC; al passaggio a P1 derivare i criteri da US-E01-01…05 per elemento.
- **Testbook**: fuori perimetro PoC.

### E02 — Membri e segmenti

#### US-E02-01 · L'assistenza iscrive un membro da backoffice
*Come* operatore CARE, *voglio* iscrivere un cliente al telefono, *così che* possa iniziare subito ad accumulare.
- **Contesto reale**: un cliente chiama il servizio clienti e chiede di aderire; Paolo lo registra da BO-02.
- **Tocca**: F-MBR-01 · BO-02 · `POST /v1/members` · EVT-FACT-01 → wallet, ingestion, campaign, … · MBR-01, MBR-20 · CMP-WELCOME.
- **Decisioni**: e-mail obbligatoria (400) · e-mail duplicata → 409 `EMAIL_TAKEN` · codice amico facoltativo (US-E06-16) · stato ACTIVE, nickname «Nome I.», canale default · `member.registered` con snapshot completo · ⚠ nessuna guardia di ruolo sull'endpoint.
- **Criteri**:
  1. Dato un nuovo membro creato, allora esiste il fatto `member.registered` con snapshot completo e `referralCode` di 8 caratteri (member §7).
  2. Dato il fatto, allora il wallet crea PTS/STS e tier BASE, e `CMP-WELCOME` accredita 100 PTS; in BO-02 la riga resta «in elaborazione» fino al bonus.
  3. ✗ Dato un'e-mail già registrata, allora 409 `EMAIL_TAKEN` sul campo.
  4. ✗ Dato ANALYST o MARKETING, allora il pulsante *Nuovo membro* non è disponibile; ⚠ l'API oggi accetta comunque (docs/08 §2 `member.write` = ADMIN, CARE).
- **Testbook**: TB-GOV (da coprire).

#### US-E02-02 · Correggere l'anagrafica
*Come* operatore CARE, *voglio* correggere e-mail, telefono, attributi ed etichette, *così che* il profilo resti affidabile per segmenti e comunicazioni.
- **Contesto reale**: Francesca cambia e-mail; due operatori aprono la stessa scheda.
- **Tocca**: F-MBR-01, F-MBR-03 · BO-03 · `PATCH /v1/members/{id}` · MBR-02 · EVT-FACT-02.
- **Decisioni**: 404 · anonimizzato → 409 `MEMBER_ANONYMIZED` · versione superata → 409 `VERSION_CONFLICT` · e-mail già usata → 409 `EMAIL_TAKEN` · attributi/etichette non validi → 422 `MEMBER_INVALID` · primo profilo completo → `member.profile.completed` · `member.updated` con snapshot completo.
- **Criteri**:
  1. Dato un PATCH valido, allora `member.updated` porta lo snapshot completo e gli altri servizi lo sovrascrivono (member §5).
  2. ✗ Dato due PATCH con la stessa `version`, allora il secondo 409 e il dialogo «ricarica / sovrascrivi».
  3. ✗ Dato un attributo `householdSize: "tanti"`, allora 422 `MEMBER_INVALID` sul campo.
  4. ✗ Dato un membro anonimizzato, allora 409 e campi in sola lettura.
  5. ✗ Dato MARKETING, LEGAL o ANALYST, allora il PATCH di gestione è 403 `FORBIDDEN_ROLE` (`member.write` = ADMIN, CARE); il profilo dal portale passa da `PATCH /v1/portal/members/{id}`.
- **Testbook**: TB-GOV (da coprire).

#### US-E02-03 · Trovare un membro
*Come* operatore, *voglio* cercare per nome, e-mail, ID o externalId e filtrare per stato, tier, etichetta, segmento, *così che* trovi il cliente in pochi secondi.
- **Tocca**: F-MBR-01 · BO-02, ⌘K · `GET /v1/members` · MBR-07.
- **Criteri**:
  1. Dato `q=CRM-104`, allora Davide; dato `q=rossi`, allora Anna (ricerca senza maiuscole).
  2. Dato `tier=GOLD`, allora Davide, Stefano, Sofia; `status=BLOCKED` → Roberto.
  3. ✗ Dato `status=SOSPESO`, allora 400.
  4. Dato MBR-000012, allora la riga è «Membro anonimo» in corsivo grigio.
- **Testbook**: TB-GOV (API) · TB-WEB (lista).

#### US-E02-04 · Bloccare, sbloccare, disattivare
*Come* operatore CARE, *voglio* sospendere un membro sospetto di frode, *così che* non accumuli né spenda finché non si chiarisce.
- **Contesto reale**: segnalazione di abuso su Roberto: viene bloccato; un mese dopo, sbloccato. Un altro cliente chiede di uscire dal programma: disattivato.
- **Tocca**: F-MBR-04 · BO-03 · `POST /v1/members/{id}/status` · MBR-03, MBR-20, WEB-12 · ING-07, WAL-08, RWD-01, GAM-01 · Q-137, Q-138.
- **Decisioni**: destinazione ∉ {ACTIVE, INACTIVE, BLOCKED} → 400 · stesso stato → nessun effetto · anonimizzato → 409 · cambio → `member.status.changed` + audit · effetti a valle: ingresso `REJECTED/MEMBER_NOT_ACTIVE`, spesa `wallet.spend.rejected (MEMBER_NOT_ACTIVE)`, giocata 422, richiesta premio 422 · solo ADMIN, CARE · INACTIVE: nessuna voce di menu (Q-137).
- **Criteri**:
  1. Dato Marco bloccato, quando arriva un acquisto, allora `REJECTED/MEMBER_NOT_ACTIVE`; il saldo resta intatto.
  2. Dato Marco sbloccato, allora il successivo acquisto è accettato e paga.
  3. ✗ Dato MARKETING, allora 403.
  4. ✗ Dato `status=ANONYMIZED` via questo endpoint, allora 400.
  5. Dato un cambio senza motivo, allora il fatto ha `reason` vuoto (Q-138).
- **Testbook**: TB-GOV (da coprire); effetti a valle in TB-ING/TB-WAL/TB-RWD/TB-GAM.

#### US-E02-05 · Diritto all'oblio: anonimizzazione
*Come* ADMIN, *voglio* anonimizzare un membro che lo chiede, *così che* nessun servizio esponga più i suoi dati personali ma i conti restino.
- **Contesto reale**: richiesta GDPR di cancellazione; Marta anonimizza dopo aver digitato l'ID.
- **Tocca**: F-MBR-05 · BO-03 · `POST /v1/members/{id}/anonymize` · MBR-04, ING-14, CMP-26, ENG-08, GAM-19 · Q-120…Q-128 · docs/12 M7.5.
- **Decisioni**: conferma ≠ ID → 422 `CONFIRM_MISMATCH` · già anonimizzato → 409 · versione → 409 · segnaposto nel nickname, dati personali null, attributi e consensi cancellati · propagazione: indice ingestion, snapshot, messaggi già resi (Q-125), note di consegna (Q-124), spedizioni (Q-123), copie in insight (Q-126) · dopo: rettifiche 409 (Q-127), eventi `UNMATCHED`/`MEMBER_NOT_ACTIVE` (Q-128), nessun messaggio (Q-70) · solo ADMIN.
- **Criteri**:
  1. Dato un membro di prova anonimizzato, allora nessuna API di gestione espone più nome o e-mail e i movimenti restano (docs/12 M7).
  2. ✗ Dato `confirm` diverso dall'ID, allora 422 `CONFIRM_MISMATCH`.
  3. ✗ Dato CARE, allora 403.
  4. Dato un evento successivo con `email:` del membro, allora `UNMATCHED`; con `member:` → `MEMBER_NOT_ACTIVE`.
  5. Dato un fatto `wallet.points.earned` successivo, allora nessun messaggio in inbox.
- **Testbook**: TB-GOV (da coprire) + una riga per servizio che ripulisce.

#### US-E02-06 · Scheda 360° del membro
*Come* operatore CARE, *voglio* vedere tutto su un cliente in una pagina (anche se qualche servizio dorme), *così che* risolva un reclamo senza aprire dieci schermate.
- **Tocca**: F-MBR-02, F-WAL-02, F-TIER-06, F-CMP-09 · BO-03 (schede `overview, ledger, actions, rewards, game, messages, segments, timeline, tiers`) · INS-08 (⛔ timeline), ⛔ `GET /v1/members/{id}/gamification` assente.
- **Decisioni**: ogni scheda degrada da sola (`SERVICE_ASLEEP`) · azioni in base al ruolo.
- **Criteri**:
  1. Dato Giulia, allora intestazione con tessera GOLD/SILVER, «mancano N STS», avviso scadenze se presenti.
  2. Dato wallet addormentato, allora le schede wallet sono *degraded* e le altre funzionano.
  3. ⛔ Dato la scheda `timeline` o `game`, allora oggi manca l'endpoint di servizio (insight §3, gamification §3): da coprire come divergenza.
- **Testbook**: TB-WEB (da coprire) · TB-GOV per le API.

#### US-E02-07 · Attributi personalizzati ed etichette
*Come* MARKETING, *voglio* definire attributi tipizzati (es. `hasGasContract`) ed etichette, *così che* li usi in segmenti e condizioni.
- **Contesto reale**: si aggiunge `preferredChannel` (APP/WEB/STORE); le attivazioni digitali mettono le etichette `ebill`/`directdebit`.
- **Tocca**: F-MBR-03 · BO-04 (definizioni, Q-94), BO-03 (valori) · `PUT /v1/attribute-definitions` · MBR-02, MBR-11, MBR-19 · Q-80, Q-93.
- **Decisioni**: definizione non valida → 422 `ATTRIBUTE_DEFINITION_INVALID` · togliere o cambiare tipo con valori presenti → 409 `ATTRIBUTE_IN_USE` · valore non conforme → 422 `MEMBER_INVALID` · etichette automatiche solo aggiunte.
- **Criteri**:
  1. Dato un nuovo attributo enum, allora compare nel `ConditionBuilder` di BO-06 e BO-04.
  2. ✗ Dato la rimozione di `city` mentre dei membri lo valorizzano, allora 409 `ATTRIBUTE_IN_USE`.
  3. Dato `ebill.activated` per Marco, allora etichetta `ebill` e `member.updated`; ripetuto → nessun cambio.
- **Testbook**: TB-GOV (da coprire).

#### US-E02-08 · Segmento dinamico con anteprima
*Come* MARKETING, *voglio* definire un segmento con criteri e vedere subito quanti e quali membri include, *così che* indirizzi campagne, premi e contenuti.
- **Contesto reale**: «clienti a rischio»: `lastActivityDaysAgo > 45` → Stefano; «GOLD e PLATINUM» → 4 membri.
- **Tocca**: F-SEG-02, F-SEG-03 · BO-04 · `POST /v1/segments/preview`, `POST /v1/segments`, `…/refresh` · MBR-13, MBR-15…17 · Q-85, Q-87.
- **Decisioni**: codice/nome/tipo/criteri non validi → 422 (`INVALID_CODE`, `NAME_REQUIRED`, `INVALID_TYPE`, `INVALID_CRITERIA`) · codice duplicato → 409 · criteri vuoti → nessun membro · ANONYMIZED esclusi · ricalcolo emette solo le differenze.
- **Criteri**:
  1. Dato `member.tier in [GOLD, PLATINUM]`, allora l'anteprima conta 4 (member §7).
  2. Dato *Ricalcola ora*, allora toast `{entered, left, total}` e i fatti `member.segment.entered/left` nel rail.
  3. Dato un secondo ricalcolo senza variazioni, allora `entered=0, left=0`.
  4. ✗ Dato criteri `{}`, allora 0 membri (Q-87).
- **Testbook**: TB-GOV (da coprire).

#### US-E02-09 · Segmento statico
*Come* MARKETING, *voglio* un elenco manuale di membri (es. invitati a un evento VIP), *così che* un premio o contenuto sia solo per loro.
- **Tocca**: F-SEG-01 · BO-04 · `PUT /v1/segments/{id}/members` · MBR-13, MBR-15.
- **Criteri**:
  1. Dato SEG-VIP-EVENT con Francesca e Davide, allora solo loro sono membri; sostituire l'elenco emette `left`/`entered` per le differenze.
  2. ✗ Dato un ID inesistente nell'elenco, allora 422 `MEMBER_NOT_FOUND`.
  3. ✗ Dato l'elenco manuale su un segmento DYNAMIC, allora 409 `SEGMENT_NOT_STATIC`.
- **Testbook**: TB-GOV (da coprire).

#### US-E02-10 · Modificare o archiviare un segmento
*Come* MARKETING, *voglio* modificare criteri e nome o archiviare un segmento superato, *così che* le regole che lo usano restino coerenti.
- **Tocca**: F-SEG-02/03 · BO-04 · `PUT /v1/segments/{id}` · MBR-14, MBR-15 · Q-88.
- **Decisioni**: codice o tipo cambiati → 409 `SEGMENT_IMMUTABLE_FIELD` · stato ∉ ACTIVE/ARCHIVED → 422 `INVALID_STATUS` · archiviazione → `left` per tutti · archiviato: ricalcolo/elenco → 409 `SEGMENT_ARCHIVED` · versione → 409.
- **Criteri**:
  1. Dato SEG-AT-RISK archiviato, allora Stefano esce (`member.segment.left`) e `CMP-REVIEW` non lo vede più nel pubblico.
  2. ✗ Dato un cambio di tipo, allora 409.
  3. ✗ Dato *Ricalcola* su un archiviato, allora 409 `SEGMENT_ARCHIVED`.
- **Testbook**: TB-GOV (da coprire).

#### US-E02-11 · Ricalcolo periodico e dopo il reset
*Come* sistema, *voglio* ricalcolare i segmenti dinamici ogni 15 minuti solo se qualcosa è cambiato e riannunciare le appartenenze dopo un reset, *così che* gli snapshot degli altri servizi siano allineati.
- **Tocca**: F-SEG-03 · MBR-16, MBR-18 · Q-81 · `POST /v1/demo/jobs/refresh-segments`.
- **Criteri**:
  1. Dato nessuna variazione in `member_stats`/`member_projection`, allora il job non ricalcola.
  2. Dato `SCN-DIGITAL` + ricalcolo, allora Marco entra in `SEG-DIGITAL` e un contenuto riservato gli compare (docs/12 M6).
  3. Dato un reset, allora `entered` per tutte le appartenenze, ripetuto dopo 15 s; i consumatori restano idempotenti.
- **Testbook**: TB-GOV (da coprire).

#### US-E02-12 · Proiezione saldi/tier e statistiche di attività
*Come* member-service, *voglio* riflettere saldo, tier e attività di ogni membro, *così che* elenchi e segmenti non chiamino il wallet.
- **Tocca**: MBR-10, MBR-12, CMP-26 · EVT-FACT-20…31.
- **Criteri**:
  1. Dato `wallet.points.earned` per un membro, allora `member_projection.balance_pts = balanceAfter` (member §7).
  2. Dato un'azione `internal`, allora `last_activity_at` e acquisti non cambiano (member §5).
  3. Dato un acquisto di 45 €, allora `purchases_amount_90d` +45.
- **Testbook**: TB-GOV (da coprire).

#### US-E02-13 · Compleanno — fuori perimetro PoC
*Come* membro, *voglio* un regalo per il compleanno. F-MBR-08 (P2): nessun job, nessun `member.birthday`; `CMP-BIRTHDAY` esiste nel seed ma non scatta mai.
- **Testbook**: fuori perimetro PoC.

### E03 — Campagne e motore regole

#### US-E03-01 · Creare una campagna in bozza
*Come* MARKETING, *voglio* creare una campagna con trigger, pubblico, condizioni, effetti, limiti e calendario, *così che* la provi prima di pubblicarla.
- **Contesto reale**: Luca prepara «Autolettura: +50 punti al mese».
- **Tocca**: F-CMP-01 · BO-05, BO-06 · `POST /v1/campaigns`, `POST /v1/campaigns/validate` · CMP-19, CMP-20, CMP-30.
- **Decisioni**: nessun trigger / nessun effetto / `MULTIPLIER.factor` ∉ [1.1, 5] / `GRANT_PLAYS` senza concorso / `SEND_MESSAGE` senza template o `params` non oggetto / `endAt ≤ startAt` → 422 `CAMPAIGN_INVALID` · codice mancante → 400 · duplicato → 409 `CODE_TAKEN` · creata `DRAFT`, priorità 100, pubblico «tutti».
- **Criteri**:
  1. Dato una campagna valida, allora 201 in `DRAFT` con audit `CREATE` (attore `MARKETING:luca.marketing`).
  2. ✗ Dato `MULTIPLIER` con fattore 6, allora 422 con il messaggio sul fattore; `validate` restituisce `{valid:false, errors[]}` senza salvare.
  3. ✗ Dato un codice già usato, allora 409 `CODE_TAKEN`.
  4. ✗ Dato ANALYST, allora il pulsante non c'è e l'API risponde 403 `FORBIDDEN_ROLE` (`object.edit` = ADMIN, MARKETING).
- **Testbook**: TB-CMP (da coprire).

#### US-E03-02 · Modificare una campagna in base allo stato
*Come* MARKETING, *voglio* poter cambiare nome, priorità o data di fine di una campagna attiva ma non le sue regole, *così che* i membri non vedano cambiare le condizioni in corsa.
- **Contesto reale**: `CMP-PURCHASE-BASE` è LIVE; Luca vuole allungarla di un mese e, per errore, prova a cambiare anche la soglia.
- **Tocca**: F-CMP-01, F-CMP-02 · BO-06 (`LifecycleBar`, campi in sola lettura) · `PUT /v1/campaigns/{id}` · CMP-21 · Q-51, Q-112.
- **Decisioni**: codice → 409 `CODE_IMMUTABLE` · ENDED/ARCHIVED → 409 `CAMPAIGN_NOT_EDITABLE` · LIVE/PAUSED: trigger, pubblico, condizioni, effetti, limiti, `startAt`, gruppo, visibilità, etichette → 409 `CAMPAIGN_LIVE_LOCKED`; nome, descrizioni, icona, priorità, `endAt` ammessi · versione → 409.
- **Criteri**:
  1. Dato una LIVE, quando cambio `endAt` e nome, allora 200, cache ricaricata e audit con diff prima/dopo (docs/12 M2).
  2. ✗ Dato una LIVE, quando cambio le condizioni, allora 409 `CAMPAIGN_LIVE_LOCKED` sia da UI sia da API (docs/12 M3).
  3. ✗ Dato una PAUSED, allora stesse regole della LIVE (Q-51); dato una ENDED, allora 409 `CAMPAIGN_NOT_EDITABLE`.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-03 · Pubblicare, mettere in pausa, terminare
*Come* MARKETING, *voglio* pubblicare una campagna (o inviarla in revisione se la policy lo chiede), metterla in pausa e terminarla, *così che* il motore la valuti solo quando deve.
- **Contesto reale**: `CMP-REVIEW` va in pausa per una promozione concorrente; a fine stagione si termina e si archivia.
- **Tocca**: F-CMP-02 · BO-05, BO-06 · `POST /v1/campaigns/{id}/transitions` · CMP-23, CMP-29, CMN-04…07 · EVT-FACT-11.
- **Decisioni**: vedi macchina a stati (US-E08-01/03) · `ACTIVATE` = `PUBLISH` · policy CAMPAIGN (LEGAL se `requiresLegal` o budget > 100 000) · cache ricaricata subito · `campaign.status.changed`.
- **Criteri**:
  1. Dato una DRAFT senza vincoli di policy, quando *Pubblica*, allora LIVE e la prossima azione la valuta (senza attendere 30 s).
  2. Dato una LIVE messa in pausa, allora le azioni successive non la considerano.
  3. ✗ Dato *Pubblica* su una DRAFT con `requiresLegal`, allora 409 `APPROVAL_REQUIRED`.
- **Testbook**: TB-CMP (da coprire); macchina a stati in TB-GOV.

#### US-E03-04 · Un'azione fa scattare una campagna
*Come* membro, *voglio* ricevere i punti previsti dalla campagna quando faccio l'azione, *così che* il programma mantenga la promessa.
- **Contesto reale**: martedì Marco (SILVER) compra 130 €.
- **Tocca**: F-CMP-03…05 · `lh.actions.v1` → `lh.effects.v1` · CMP-02…05, CMP-11, CMP-12, CMP-17 · EVT-EFF-01, EVT-FACT-10.
- **Decisioni**: candidate LIVE con trigger · ordine priorità/codice · calendario · pubblico · condizioni · limiti · effetti · `MATCHED`.
- **Criteri**:
  1. Dato Marco SILVER e 130 € di martedì, allora due `points.grant`: PTS 130 con `tierMultiplierApplies`, STS 130; log `MATCHED` (campaign §7).
  2. Dato lo stesso evento, allora il ledger ha `EARN 162 PTS` e `EARN 130 STS` con `campaignCode=CMP-PURCHASE-BASE` e lo stesso `correlationId` entro 8 s p95 (docs/12 M1).
  3. Dato `campaign.evaluated`, allora `matched[]` contiene la campagna con gli effetti e `skipped[]` le altre col motivo.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-05 · Perché la campagna non è scattata?
*Come* operatore CARE, *voglio* vedere per ogni azione il motivo per cui ogni campagna non è scattata, *così che* risponda al cliente senza aprire un ticket tecnico.
- **Contesto reale**: «ho attivato la bolletta digitale ma non ho i 300 punti»: in BO-03 `CMP-EBILL` risulta `LIMIT` (già ottenuta).
- **Tocca**: F-CMP-06, F-CMP-09 · BO-03 (scheda `actions`), BO-25 («Perché») · CMP-01, CMP-03…11, CMP-31.
- **Decisioni**: `NO_MEMBER` (snapshot assente o membro non attivo) · `NOT_IN_SCHEDULE` · `AUDIENCE` · `CONDITION` (+ foglie fallite con valore osservato) · `EXCLUSIVE` · `EFFECT_NOT_SUPPORTED_YET` · `LIMIT` · `BUDGET` · `NO_MATCH` se nessuna scatta.
- **Criteri**:
  1. Dato `app.login.daily` due volte nello stesso giorno, allora la seconda è `skipped` con `LIMIT` (campaign §7).
  2. Dato un acquisto di 30 € di un GOLD, allora `CMP-GOLD-PURCHASE-PLAY` è `CONDITION` con `data.amount gte 50 · actual 30`.
  3. Dato un acquisto di un SILVER, allora `CMP-GOLD-PURCHASE-PLAY` è `AUDIENCE`.
  4. Dato un acquisto di martedì, allora `CMP-WEEKEND-X2` è `CONDITION` (`context.dayOfWeek`); ⚠ docs/12 M1 lo descrive come «condizione di calendario» e F-CMP-09 prevede `NOT_LIVE`, che il motore non emette.
  5. ⚠ Dato il quarto acquisto del giorno, allora `reason=LIMIT` (docs/12 M1 scrive `MEMBER_LIMIT_REACHED`: da allineare come Q-50).
- **Testbook**: TB-CMP (da coprire).

#### US-E03-06 · Condizioni su dati, membro, contesto e storico
*Come* MARKETING, *voglio* costruire condizioni annidate su campi dell'azione, del membro, del contesto e dello storico, *così che* premi solo i comportamenti giusti.
- **Contesto reale**: «acquisto online ≥ 50 € in categoria casa, non per chi ha già 5 acquisti dello stesso tipo».
- **Tocca**: F-CMP-03 · BO-06 (`ConditionBuilder`) · CMP-16, CMP-17, WEB-15 · Q-90, Q-91, Q-92.
- **Decisioni**: `all/any/not` (op ignoto → `all`; `not` = «non tutte vere») · campo assente → falso tranne `nexists` · array: basta un elemento · tipi incompatibili → falso · `context.*` in Rome · `history.actionCount` conta le precedenti · `context.source` = URN completo della fonte (anche in simulazione).
- **Criteri**:
  1. Dato `data.items[*].category eq casa` con due righe di cui una «casa», allora vero.
  2. Dato un campo assente con `gte`, allora foglia falsa e nessuna eccezione (campaign §7); con `nexists` vero.
  3. Dato `context.hour` alle 23:30 UTC di un giorno d'estate, allora vale 1 (ora di Roma) e `context.date` è il giorno dopo.
  4. Dato `context.source eq urn:loyaltyhub:source:ecommerce`, allora simulazione (anche con `source: "ecommerce"`, normalizzato in URN) e valutazione reale danno lo stesso esito.
  5. Dato un gruppo NESSUNA con 2 regole, allora BO-06 avvisa (Q-90).
- **Testbook**: TB-CMP (da coprire); costruttore in TB-WEB.

#### US-E03-07 · Quanti punti: modi di GRANT_POINTS
*Come* MARKETING, *voglio* dare punti fissi, per importo, da un campo o da una tabella, con minimo e massimo, *così che* ogni regola commerciale sia esprimibile.
- **Contesto reale**: «1 punto ogni euro», «bonus livello da tabella», «punti dal premio vinto».
- **Tocca**: F-CMP-04 · CMP-12 · Q-44.
- **Decisioni**: FIXED · PER_AMOUNT `rounding(amount/unitStep) × value` (FLOOR/CEIL/ROUND, metà esatta per difetto: Q-227; `unitStep ≤ 0` → 422 al salvataggio e campagna scartata nel motore: Q-228; campo non numerico → scartato) · FROM_FIELD intero (decimale → scartato) · LOOKUP (campo o chiave assenti → scartato) · `min`/`max` · ≤ 0 → scartato.
- **Criteri**:
  1. Dato PER_AMOUNT FLOOR su 64,90 €, allora 64; con CEIL 65; con ROUND 65.
  2. Dato `CMP-TIER-UP-BONUS` e `newTier=GOLD`, allora 500; con un tier assente dalla tabella, allora nessun effetto.
  3. ✗ Dato FROM_FIELD con `data.points=50.5`, allora effetto scartato (Q-44).
  4. Dato `max=100` e calcolo 130, allora 100.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-08 · Moltiplicatori, priorità, gruppi esclusivi
*Come* MARKETING, *voglio* raddoppiare i punti nel weekend e far vincere una sola campagna in un gruppo, *così che* le promozioni si combinino in modo prevedibile.
- **Contesto reale**: sabato Giulia compra 130 €: base ×2 weekend × 1,25 SILVER; a Black Friday ×3 non deve cumularsi con altri ×.
- **Tocca**: F-CMP-07 · CMP-02, CMP-06, CMP-13 · Q-50.
- **Decisioni**: `MULTIPLIER` agisce sui `GRANT_POINTS` delle **altre** campagne della stessa valuta (tutti o per etichetta) · prodotto, tetto 5, floor · gruppo esclusivo: vince la prima per priorità/codice, le altre `EXCLUSIVE`.
- **Criteri**:
  1. Dato 130 € di sabato per un SILVER, allora `campaignMultiplier=2`, PTS 325 = floor(130 × 2 × 1,25), STS 130 (docs/12 M1).
  2. Dato due moltiplicatori ×3 e ×2 sulla stessa azione, allora fattore 5 (tetto).
  3. Dato due campagne nello stesso gruppo, allora scatta solo quella a priorità più alta; l'altra `EXCLUSIVE` (docs/12 M3, Q-50).
  4. Dato un moltiplicatore sugli STS, allora non tocca i PTS.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-09 · Limiti per membro e budget
*Come* responsabile del budget, *voglio* limitare quante volte un membro ottiene un premio e quanto spende una campagna in totale, *così che* i costi siano sotto controllo.
- **Contesto reale**: `CMP-APP-DAILY` 1/giorno; `CMP-BLACK-FRIDAY` budget 250 000; un membro fa l'ultimo acquisto alle 23:59 e il successivo alle 00:01.
- **Tocca**: F-CMP-05 · CMP-08…10, CMP-17, CMN-14 · RNF-04.
- **Decisioni**: conteggio del periodo (DAY/WEEK/MONTH/EDITION/ALWAYS in Rome sul `time` dell'azione) ≥ max → `LIMIT` · punti decisi ≥ `maxPoints` o attivazioni ≥ `maxMatches` → `BUDGET` · punti decisi dalla campagna per il membro ≥ `perMemberPoints` → `LIMIT` · meno di `cooldownMinutes` dall'ultimo match del membro (sul `time` di business) → `LIMIT` (Q-165).
- **Criteri**:
  1. Dato 3 acquisti in un giorno, allora il quarto `LIMIT` e nessun movimento (docs/12 M1).
  2. Dato un acquisto alle 23:59 e uno alle 00:01 ora di Roma, allora periodi DAY diversi (non UTC).
  3. Dato un budget di 1 000 già a 990, allora l'attivazione successiva scatta con l'accredito ridotto a 10 (Q-237) e quella dopo è `BUDGET`.
  4. Dato `cooldownMinutes=60` e due azioni a 10 minuti, allora la seconda è `LIMIT`.
  5. Dato `perMemberPoints=500`, allora raggiunti 500 punti le attivazioni successive sono `LIMIT` (l'ultima sotto il tetto si riduce al residuo, Q-237).
- **Testbook**: TB-CMP (4–5: TB-CMP-SIM-015, TB-CMP-SIM-021).

#### US-E03-10 · Effetti non monetari: giocate, coupon, badge, messaggi
*Come* MARKETING, *voglio* che una campagna dia giocate, coupon, badge o un messaggio, *così che* il programma premi anche senza punti.
- **Contesto reale**: `CMP-SURVEY` dà 80 PTS + 1 giocata; `CMP-IW-PRIZE-COUPON` emette il coupon vinto.
- **Tocca**: F-CMP-04 · CMP-07, CMP-14 · EVT-EFF-02…05 · DLQ `CONTEST_NOT_FOUND`, `REWARD_NOT_FOUND`, `BADGE_NOT_FOUND`, `TEMPLATE_NOT_FOUND`.
- **Decisioni**: parametri mancanti → campagna `EFFECT_NOT_SUPPORTED_YET` · `ISSUE_COUPON` con codice da campo assente → nessun effetto · esistenza di concorsi/premi/template non verificabile: l'errore emerge a valle in DLQ.
- **Criteri**:
  1. Dato `survey.completed` di Matteo, allora `points.grant` 80 e `plays.grant` 1 su IW-AUTUNNO; in PT-05 +1 giocata.
  2. ✗ Dato un `GRANT_PLAYS` verso un concorso inesistente, allora voce DLQ `CONTEST_NOT_FOUND` e tracciato `FAILED`.
  3. ✗ Dato un `SEND_MESSAGE` senza `templateCode` in una campagna seminata a mano, allora `EFFECT_NOT_SUPPORTED_YET` e nessun punto «a metà» (Q-73).
- **Testbook**: TB-CMP (da coprire) · TB-GAM/TB-RWD/TB-ENG per il lato consumatore.

#### US-E03-11 · Simulare prima di pubblicare
*Come* MARKETING, *voglio* provare una bozza su un membro (reale o ipotetico) e un'azione di prova, *così che* veda cosa succederebbe senza scrivere nulla.
- **Contesto reale**: Luca verifica che Black Friday ×3 non scatti di martedì.
- **Tocca**: F-CMP-08 · BO-06 (pannello *Simulazione*), BO-28 (*Anteprima*) · `POST /v1/campaigns/simulate` · CMP-24.
- **Decisioni**: `action.type` mancante o `time` non valido → 400 · `campaignIds` → anche bozze · `memberOverride` · limiti solo letti · nessuna scrittura.
- **Criteri**:
  1. Dato `CMP-WEEKEND-X2` e un evento di martedì, allora non scatta con la condizione marcata ✗ e nessun evento emesso (docs/12 M1).
  2. Dato `ebill.activated` per un membro che l'ha già ottenuta, allora `matched=false, reason=LIMIT`, nessuna scrittura (campaign §7).
  3. Dato un profilo ipotetico GOLD, allora il pubblico è valutato col tier ipotetico.
  4. Dato 10 simulazioni, allora contatori e registro invariati.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-12 · Registro delle valutazioni
*Come* ANALYST, *voglio* consultare l'esito per campagna di ogni azione, *così che* possa spiegare ogni movimento.
- **Tocca**: F-CMP-09 · `GET /v1/evaluations`, `/{actionId}` · BO-03, BO-25 · CMP-17.
- **Criteri**:
  1. Dato un'azione valutata, allora `GET /v1/evaluations/{actionId}` restituisce `outcome` e `results` con motivi e foglie fallite.
  2. ✗ Dato un `actionId` inesistente, allora 404.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-13 · Statistiche e budget residuo
*Come* MARKETING, *voglio* vedere attivazioni, membri unici, punti decisi/erogati e budget residuo, *così che* sappia se la campagna rende.
- **Tocca**: F-CMP-10 · BO-05 · `GET /v1/campaigns/{id}/stats` · CMP-17, CMP-27, CMP-28.
- **Criteri**:
  1. Dato 3 attivazioni per 2 membri, allora `matches=3`, `uniqueMembers=2`.
  2. Dato `wallet.points.earned` con moltiplicatore di tier, allora `pointsGranted` > `pointsDecided`.
  3. Dato una campagna senza limiti globali, allora `budget=null`.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-14 · «Come guadagnare» nel portale
*Come* membro, *voglio* vedere le campagne attive per me con il premio e quante volte posso ancora ottenerlo, *così che* sappia cosa fare.
- **Tocca**: F-CMP-11 · PT-02 · `GET /v1/portal/campaigns` · CMP-25.
- **Criteri**:
  1. Dato Marco, allora vede `CMP-EBILL` «+300 punti» e `CMP-APP-DAILY` con «1 di 1 oggi» dopo il login.
  2. Dato un SILVER, allora non vede `CMP-GOLD-PURCHASE-PLAY` (pubblico).
  3. Dato una campagna con `visible_in_portal=false`, allora non compare.
- **Testbook**: TB-CMP (API) · TB-WEB (schermata).

#### US-E03-15 · Campagne di sistema
*Come* ADMIN, *voglio* che le campagne che consegnano i premi dei concorsi non si possano archiviare, *così che* le vincite siano sempre consegnate.
- **Tocca**: F-CMP-12 · BO-05 (lucchetto) · CMP-23 · `SYSTEM_LOCKED`.
- **Criteri**:
  1. ✗ Dato `CMP-IW-PRIZE-POINTS`, quando *Archivia*, allora 409 `SYSTEM_LOCKED`.
  2. Dato una vincita di 50 punti, allora `CMP-IW-PRIZE-POINTS` accredita 50 PTS senza moltiplicatore.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-16 · Duplicare una campagna
*Come* MARKETING, *voglio* duplicare una campagna attiva per cambiarne le regole, *così che* non tocchi quella in corso.
- **Tocca**: F-CMP-13 · `POST /v1/campaigns/{id}/duplicate` · CMP-22.
- **Criteri**:
  1. Dato `CMP-PURCHASE-BASE`, allora copia `CMP-PURCHASE-BASE-COPY-1` in DRAFT; una seconda copia `-COPY-2`.
  2. Dato una campagna di sistema, allora la copia non è di sistema.
- **Testbook**: TB-CMP (da coprire).

#### US-E03-17 · Doppia consegna di un'azione
*Come* azienda, *voglio* che la riconsegna della stessa azione da Kafka non produca effetti doppi, *così che* un riavvio non regali punti.
- **Tocca**: RNF-03 · CMP-15, CMN-09 · `effectId`.
- **Criteri**:
  1. Dato la stessa azione consegnata due volte, allora un solo insieme di effetti in outbox e contatori incrementati una volta (campaign §7).
  2. Dato lo stesso `actionId`, allora gli `effectId` sono sempre gli stessi (sha256 troncato).
- **Testbook**: TB-CMP (da coprire).

#### US-E03-18 · Storno su reso — fuori perimetro PoC
*Come* azienda, *voglio* togliere i punti di un acquisto reso. F-CMP-14 (P2): `purchase.returned` è accettato (US-E01-08) ma nessuna campagna né il wallet stornano.
- **Testbook**: fuori perimetro PoC.

#### US-E03-19 · Una campagna a tempo finisce da sola
*Come* MARKETING, *voglio* che una campagna con data di fine passi a ENDED quando la data è superata, *così che* elenchi e portale non la mostrino più come attiva.
- **Contesto reale**: `CMP-BLACK-FRIDAY` finisce il 30 novembre.
- **Tocca**: F-CMP-02 · campaign §5 («job ogni minuto») · CMP-03, CMP-29.
- **Criteri**:
  1. Dato una LIVE con `endAt` superato, allora le azioni successive sono `NOT_IN_SCHEDULE` (oggi vero).
  2. Dato lo stesso caso, allora entro un minuto lo stato diventa `ENDED` con `campaign.status.changed` (job con `loyaltyhub.jobs.enabled`; esattamente a `endAt` resta `LIVE`).
- **Testbook**: TB-CMP (da coprire).

### E04 — Wallet, livelli ed edizioni

#### US-E04-01 · Accredito dei punti decisi dal motore
*Come* membro, *voglio* che i punti arrivino nel mio saldo con il vantaggio del mio livello, *così che* essere GOLD valga qualcosa.
- **Contesto reale**: Marco SILVER riceve 130 PTS base → 162 PTS.
- **Tocca**: F-WAL-01…03, F-TIER-03 · `points.grant` → `wallet.points.earned` · WAL-01, WAL-03 · PT-07 (scomposizione).
- **Decisioni**: effectId già visto → nulla · wallet assente → creato al volo · moltiplicatore solo PTS con `tierMultiplierApplies` (floor) · ≤ 0 → nulla · lotto con scadenza `ROLLING_MONTHS` 12 a fine mese (Rome) · stato del membro non verificato.
- **Criteri**:
  1. Dato PTS 130 con `tierMultiplierApplies` per un SILVER, allora `EARN 162`, `metadata.baseAmount=130`, lotto con scadenza a fine mese +12 (wallet §7).
  2. Dato lo stesso `effectId` due volte, allora un solo movimento (wallet §7).
  3. Dato un effetto per un membro senza wallet, allora wallet e tier BASE creati e accredito eseguito.
  4. Dato un guadagno il 31 gennaio alle 23:30 UTC, allora la scadenza è fine febbraio **dell'anno dopo** calcolata sul giorno di Roma (1 febbraio → fine febbraio+12).
- **Testbook**: TB-WAL (da coprire).

#### US-E04-02 · Punti in attesa e rilascio
*Come* azienda, *voglio* accreditare alcuni punti «in attesa» per N giorni (periodo di reso), *così che* non si spendano punti di acquisti poi annullati.
- **Tocca**: F-WAL-05 · PT-07 («in attesa» + data di rilascio) · WAL-01, WAL-04, WAL-19 · `POST /v1/demo/jobs/release-pending?asOf=`.
- **Decisioni**: `pendingDays > 0` → lotto PENDING, saldo in attesa · job/`asOf` → ACTIVE + `RELEASE` + `wallet.points.released` · STS rilasciati → verifica salita.
- **Criteri**:
  1. Dato un accredito con `pendingDays=14`, allora saldo attivo invariato, «in attesa» +N, `wallet.points.earned` con `pending=true`.
  2. Dato il job con `asOf` = +14 gg, allora lotto ACTIVE e saldo aumentato; con `asOf` = +13 gg nulla.
  3. Dato STS pending rilasciati che superano la soglia, allora `tier.upgraded` (nuova correlazione).
- **Testbook**: TB-WAL (da coprire).

#### US-E04-03 · Salita di livello immediata
*Come* membro, *voglio* salire di livello appena supero la soglia, *così che* ne goda subito.
- **Contesto reale**: Giulia (2 880 STS) compra 130 € → 3 010 STS → GOLD.
- **Tocca**: F-TIER-02 · WAL-01, WAL-02 · EVT-FACT-28 → ponte → `CMP-TIER-UP-BONUS`.
- **Decisioni**: dopo ogni accredito STS: tier più alto con soglia ≤ `periodSts`; rank maggiore → salita (anche di più livelli), storico `UPGRADE`, `tier.upgraded`; altrimenti nulla.
- **Criteri**:
  1. Dato Giulia + 130 STS, allora `tier.upgraded` SILVER→GOLD nello stesso commit (wallet §7).
  2. Dato un BASE che riceve 7 000 STS in un colpo, allora sale direttamente a PLATINUM (un solo fatto).
  3. Dato un GOLD che riceve STS senza superare 7 000, allora nessun fatto.
  4. Dato la salita, allora i PTS della **stessa** azione usano il moltiplicatore del tier precedente perché nel seed l'effetto PTS precede quello STS (Giulia: 162 PTS, docs/10 §4); con l'ordine inverso userebbero il nuovo tier — dipendenza dall'ordine degli effetti senza specifica (WAL-01).
- **Testbook**: TB-WAL (da coprire).

#### US-E04-04 · Scadenza dei punti
*Come* azienda, *voglio* far scadere i lotti arrivati a scadenza, *così che* la passività resti sotto controllo.
- **Contesto reale**: i 1 900 PTS di Chiara scadono tra 12 giorni.
- **Tocca**: F-WAL-06 · BO-30 (macchina del tempo) · `POST /v1/demo/jobs/expire-points?asOf=` · WAL-05, WAL-19.
- **Decisioni**: lotti ACTIVE con `expiresAt ≤ asOf` → EXPIRED + `wallet.points.expired` · un movimento `EXPIRE` per membro/valuta col totale.
- **Criteri**:
  1. Dato il job con `asOf` = +31 gg, allora Chiara perde 1 900 PTS (`EXPIRE`), fatto `wallet.points.expired`, saldo aggiornato nel portale (docs/12 M3).
  2. Dato `asOf` = +11 gg, allora nulla scade.
  3. Dato due lotti scaduti dello stesso membro, allora un solo movimento `EXPIRE` e un solo fatto col totale (`lotId` solo se il lotto è uno).
- **Testbook**: TB-WAL (da coprire).

#### US-E04-05 · Preavviso di scadenza
*Come* membro, *voglio* essere avvisato un mese prima che i punti scadano, *così che* li usi.
- **Tocca**: F-WAL-06 · PT-01 (avviso), PT-12 (MSG-POINTS-EXPIRING) · `POST /v1/demo/jobs/expiry-warnings?asOf=` · WAL-06.
- **Criteri**:
  1. Dato il lotto di Chiara in scadenza entro 30 gg, allora `wallet.points.expiring` e un messaggio in inbox.
  2. Dato il job rilanciato, allora nessun secondo preavviso per lo stesso lotto.
- **Testbook**: TB-WAL (da coprire) · TB-ENG per il messaggio.

#### US-E04-06 · Rettifica manuale motivata
*Come* operatore CARE, *voglio* accreditare o addebitare punti con un motivo e una nota, *così che* risolva un reclamo lasciando traccia.
- **Contesto reale**: Davide lamenta un acquisto non accreditato: Paolo accredita 200 PTS «GOODWILL» con nota.
- **Tocca**: F-WAL-07 · BO-03 (*Rettifica punti*) · `POST /v1/wallets/{id}/adjustments` · WAL-07, WAL-20 · Q-45, Q-46, Q-127.
- **Decisioni**: ordine: valuta ≠ PTS → 422 `CURRENCY_NOT_ADJUSTABLE` · importo ≤ 0 → 422 `INVALID_AMOUNT` · nota < 10 → 422 `NOTE_TOO_SHORT` · motivo ∉ {GOODWILL, CORRECTION, COMPLAINT, TEST} → 422 `INVALID_REASON` · direzione → 422 `INVALID_DIRECTION` · anonimizzato → 409 · addebito oltre saldo → 422 `INSUFFICIENT_BALANCE` · ok → movimento, lotto o consumo FIFO, `wallet.points.adjusted`, audit · ruoli CARE, ADMIN.
- **Criteri**:
  1. Dato un accredito di 200 PTS GOODWILL con nota valida, allora `ADJUST_CREDIT`, audit con saldo prima/dopo, riga evidenziata in BO-03.
  2. ✗ Dato un addebito oltre il saldo, allora 422 `INSUFFICIENT_BALANCE`; senza nota → 422 (wallet §7).
  3. ✗ Dato MARKETING, allora 403; dato STS, allora 422 (Q-46).
  4. ✗ Dato il motivo `FRAUD` (docs/03 §4.2), allora 422 `INVALID_REASON` (Q-45).
- **Testbook**: TB-WAL (da coprire).

#### US-E04-07 · Spesa dei punti per un premio (lato wallet)
*Come* wallet, *voglio* spendere tutto o niente consumando prima i lotti in scadenza, *così che* il membro non perda punti inutilmente.
- **Tocca**: F-WAL-04, F-WAL-08 · `reward.redemption.requested` → `wallet.points.spent` / `wallet.spend.rejected` · WAL-08, WAL-09.
- **Decisioni**: richiesta già spesa → nulla · membro non ACTIVE → `MEMBER_NOT_ACTIVE` · saldo < costo → `INSUFFICIENT_BALANCE` (nessun movimento) · ok → `SPEND` + FIFO.
- **Criteri**:
  1. Dato lotti [500 scad. ott, 800 scad. dic, 900 scad. mar] e spesa 1 500, allora consumati 500 + 800 + 200 con `lot_consumption` coerente (wallet §7).
  2. ✗ Dato una spesa oltre il saldo, allora nessun movimento e `wallet.spend.rejected` con `available` (wallet §7).
  3. Dato lo stesso `reward.redemption.requested` rielaborato, allora una sola spesa.
  4. Dato lotti senza scadenza, allora consumati per ultimi.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-08 · Rimborso di una richiesta annullata
*Come* membro, *voglio* riavere i punti se la richiesta è annullata, *così che* non paghi un premio che non ricevo.
- **Tocca**: F-RWD-07, F-WAL-08 · `reward.redemption.cancelled (refund=true)` → `wallet.points.refunded` · WAL-10 · docs/03 §4.2 (supera Q-54).
- **Decisioni**: `refund ≠ true` → nulla · nessuna spesa → nulla · già rimborsata → nulla · tutto l'importo in un lotto nuovo con scadenza `max(scadenza più lontana dei lotti consumati, oggi + 30 gg)` (lotto consumato senza scadenza → nessuna scadenza; nessun consumo registrato → oggi + 30 gg).
- **Criteri**:
  1. Dato un annullo CONFIRMED, allora `REFUND` di pari importo e `wallet.points.refunded` (reward §7).
  2. Dato un annullo del membro in PENDING (`refund=false`), allora nessun movimento.
  3. Dato un lotto d'origine scaduto nel frattempo, allora il lotto di rimborso scade a max(originaria più lontana, oggi + 30 gg); i lotti d'origine restano consumati.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-09 · Configurare i livelli
*Come* ADMIN, *voglio* cambiare soglie, moltiplicatori, vantaggi e colori dei livelli, *così che* il programma evolva.
- **Tocca**: F-TIER-01 · BO-07 · `PUT /v1/tiers/{code}` · WAL-12, WAL-20.
- **Criteri**:
  1. Dato GOLD a 3 500, allora la tessera e la barra di progresso lo riflettono in PT-01/PT-08.
  2. ✗ Dato SILVER a 3 500 (≥ GOLD), allora 422 `TIER_THRESHOLDS_NOT_MONOTONIC`; BASE ≠ 0 idem.
  3. ✗ Dato moltiplicatore 0, allora 422 `TIER_MULTIPLIER_INVALID`; MARKETING → 403.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-10 · Valute e politica di scadenza
*Come* ADMIN, *voglio* scegliere come scadono i PTS (mesi mobili, fine edizione + tolleranza, mai), *così che* la regola sia chiara ai membri.
- **Tocca**: F-WAL-03 · BO-08 (scheda `currencies`, esempio calcolato) · `PUT /v1/currencies/{code}` · WAL-03, WAL-13 · Q-47.
- **Criteri**:
  1. Dato `ROLLING_MONTHS 6`, allora i **nuovi** lotti scadono a fine mese +6; i vecchi restano.
  2. ✗ Dato 0 o 61 mesi, `graceDays` negativo o tipo sconosciuto, allora 422 `INVALID_EXPIRY_POLICY`.
  3. Dato `END_OF_EDITION_PLUS_GRACE`, allora scadenza = fine giorno di `redemptionGraceUntil`; se nessuna edizione copre la data, il lotto non scade (Q-47).
- **Testbook**: TB-WAL (da coprire).

#### US-E04-11 · Edizioni del programma
*Come* ADMIN, *voglio* definire le edizioni annuali senza sovrapposizioni, *così che* tier e scadenze abbiano un calendario certo.
- **Tocca**: docs/03 §4.4 · BO-08 (scheda `editions`) · `POST/PUT /v1/editions` · WAL-14.
- **Criteri**:
  1. ✗ Dato campi mancanti, allora 422 `EDITION_FIELD_REQUIRED`; codice esistente 409; inizio dopo fine 422 `EDITION_DATES_INVALID`; sovrapposizione 422 `EDITION_OVERLAP`.
  2. Dato l'anno prossimo PLANNED, allora compare nella linea del tempo.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-12 · Anteprima di chiusura edizione
*Come* ADMIN (o chiunque in lettura), *voglio* vedere chi resta e chi scende prima di chiudere l'anno, *così che* non ci siano sorprese.
- **Contesto reale**: a dicembre Marta apre l'anteprima: Stefano (GOLD, 650 STS) scenderebbe a SILVER.
- **Tocca**: F-TIER-05 · BO-08 · `POST /v1/editions/{code}/close?dryRun=true` · WAL-15…17.
- **Decisioni**: `earned` da `periodSts`; pavimento = livello attuale − 1; nuovo = il più alto; mai salita; esito RETAINED/DOWNGRADED; nessuna scrittura; ogni ruolo.
- **Criteri**:
  1. Dato Stefano, allora `earnedTier=BASE`, `newTier=SILVER`, `DOWNGRADED` (wallet §7).
  2. Dato un PLATINUM con 0 STS, allora GOLD (scende di uno solo).
  3. Dato un SILVER con 3 500 STS nell'edizione (salita mancata per un errore), allora resta SILVER (mai salita in chiusura).
  4. Dato ANALYST, allora l'anteprima è consentita.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-13 · Applicare la chiusura dell'edizione
*Come* ADMIN, *voglio* chiudere l'edizione applicando la discesa morbida, *così che* il nuovo anno parta con i livelli giusti e gli STS azzerati.
- **Tocca**: F-TIER-04 · BO-08 (*Applica chiusura*, digitazione del codice) · WAL-15, WAL-16 · EVT-FACT-29…31 · Q-48.
- **Decisioni**: ruolo ≠ ADMIN → 403 · già CLOSED → 422 · non ACTIVE → 422 · membri ACTIVE a pagine · fatti per membro + `edition.closed` · `periodSts=0` · prossima PLANNED → ACTIVE (nessuna PLANNED → nessuna attiva).
- **Criteri**:
  1. Dato l'applicazione, allora i fatti `tier.*` scorrono nel rail e `edition.closed {retained, downgraded}`.
  2. ✗ Dato una seconda chiusura, allora 422 `EDITION_ALREADY_CLOSED`; LEGAL → 403.
  3. Dato un accredito STS durante la chiusura, allora conta per una sola edizione (Q-48).
  4. Dato nessuna edizione PLANNED, allora dopo la chiusura nessuna è ACTIVE (ramo senza specifica).
  5. Dato un membro BLOCKED, allora non è toccato dalla chiusura.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-14 · Saldo, progresso e passività
*Come* membro, *voglio* vedere saldo, punti in scadenza e quanto mi manca al prossimo livello; *come* ANALYST, la passività per mese.
- **Tocca**: F-WAL-01, F-WAL-09 · PT-01, PT-08, BO-01, BO-08 · `GET /v1/wallets/{id}`, `/v1/liability`, `/v1/tiers/distribution` · WAL-18.
- **Criteri**:
  1. Dato Giulia, allora `next.code=GOLD`, `missing=120`.
  2. Dato Francesca PLATINUM, allora nessun prossimo livello e «livello più alto».
  3. Dato Chiara, allora `expiringSoon.amount=1 900`.
  4. ⛔ Dato ottobre e un GOLD con `periodSts` sotto 3 000, allora `keepWarning {tier, missing}`: oggi assente.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-15 · Storico livelli
*Come* operatore CARE, *voglio* vedere quando e perché un membro è salito o sceso, *così che* risponda ai reclami sul livello.
- **Tocca**: F-TIER-06 · BO-03 (scheda `tiers`) · `GET /v1/members/{id}/tier-history` · WAL-21.
- **Criteri**: Dato la salita di Giulia e la chiusura, allora righe `UPGRADE` e `RETAIN/DOWNGRADE` con edizione.
- **Testbook**: TB-WAL (da coprire).

#### US-E04-16 · Il wallet segue iscrizione e stato del membro
*Come* wallet, *voglio* creare i conti all'iscrizione e conoscere lo stato del membro, *così che* possa rifiutare la spesa di un sospeso.
- **Tocca**: WAL-11, WAL-08 · EVT-FACT-01, EVT-FACT-03.
- **Criteri**:
  1. Dato `member.registered`, allora wallet PTS/STS a zero e tier BASE; riconsegna → nessun doppione.
  2. Dato `member.status.changed` a BLOCKED, allora la spesa successiva è `wallet.spend.rejected (MEMBER_NOT_ACTIVE)`.
- **Testbook**: TB-WAL (da coprire).

### E05 — Premi e coupon

#### US-E05-01 · Creare e mantenere un premio
*Come* MARKETING, *voglio* inserire un premio con tipo, fascia, stock, limite per membro, visibilità ed evasione, *così che* compaia nel catalogo dopo l'approvazione.
- **Contesto reale**: Luca prepara «Termostato smart» (F5, 30 pezzi, evasione manuale); a campagna in corso aumenta lo stock.
- **Tocca**: F-RWD-01, F-RWD-03, F-RWD-04, F-RWD-08 · BO-10 · `POST/PUT /v1/rewards`, `…/duplicate`, `…/transitions` · RWD-19, RWD-20, RWD-23, RWD-25.
- **Decisioni**: codice mancante → 400 · duplicato → 409 `CODE_TAKEN` · nome/tipo/evasione/fascia/categoria/`AUTO_COUPON` solo COUPON/stock < 0/limite < 1/`validTo ≤ validFrom` → 422 `REWARD_INVALID` · modifica: DRAFT/PAUSED tutto; LIVE solo stock totale, `validTo`, immagine (altrimenti 409 `REWARD_LIVE_LOCKED`); IN_REVIEW/APPROVED/ENDED/ARCHIVED → 409 `REWARD_NOT_EDITABLE` · codice → 409 · versione → 409 · approvazione LEGAL sempre (US-E08-01).
- **Criteri**:
  1. Dato un premio COUPON con evasione `AUTO_COUPON` e pool, allora 201 in DRAFT con audit.
  2. ✗ Dato `AUTO_COUPON` su un premio PHYSICAL, allora 422 `REWARD_INVALID`.
  3. Dato un LIVE, quando porto lo stock da 150 a 200, allora il residuo cresce di 50; quando cambio fascia, allora 409 `REWARD_LIVE_LOCKED`.
  4. ✗ Dato un premio IN_REVIEW, allora 409 `REWARD_NOT_EDITABLE` (⚠ reward §3 cita lo stato `REJECTED`, ora rimpiazzato dal ritorno in DRAFT).
  5. Dato *Duplica*, allora copia `-COPY` in DRAFT.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-02 · Fasce premi: una soglia, un prezzo
*Come* MARKETING, *voglio* raggruppare i premi in fasce con soglie crescenti, *così che* il costo sia chiaro e il membro veda cosa può raggiungere.
- **Tocca**: F-RWD-02 · BO-11 · `POST/PUT/DELETE /v1/reward-bands` · RWD-21.
- **Criteri**:
  1. Dato F2 portata da 1 500 a 1 600, allora il costo dei premi di F2 diventa 1 600 (BO-11 mostra l'impatto prima di salvare).
  2. ✗ Dato una soglia uguale a un'altra fascia, allora 422 `BAND_THRESHOLD_DUPLICATE`.
  3. ✗ Dato l'eliminazione di F1 con premi, allora 409 `BAND_IN_USE`.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-03 · Categorie
*Come* MARKETING, *voglio* gestire le categorie del catalogo, *così che* il portale le usi come filtro.
- **Tocca**: F-RWD-01 · BO-10 · `POST/PUT /v1/reward-categories` · RWD-22.
- **Criteri**: ✗ Dato una categoria senza codice o nome, allora 422 `CATEGORY_INVALID`; dato un premio con categoria inesistente, allora 422 `REWARD_INVALID`.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-04 · Il catalogo che vedo io
*Come* membro, *voglio* vedere i premi per fascia con cosa posso richiedere, cosa è esaurito, riservato o già richiesto, *così che* scelga senza tentativi a vuoto.
- **Contesto reale**: Davide (GOLD, 12 300 PTS) vede il weekend benessere; Giulia (SILVER) lo vede col lucchetto; Francesca vede la serata PLATINUM.
- **Tocca**: F-RWD-01…04 · PT-03, PT-04 · `GET /v1/portal/catalog`, `/v1/portal/rewards/{code}` · RWD-18, WEB-08.
- **Decisioni**: `stockState` AVAILABLE / LOW (< 10 %) / SOLD_OUT · tier non ammesso → visibile con `lockedByTier` · fuori segmento → escluso · `perMemberLimitReached` · raggiungibilità calcolata dal frontend (saldo).
- **Criteri**:
  1. Dato `RWD-WEEKEND` per un SILVER, allora compare con `lockedByTier {GOLD, PLATINUM}` e «Riservato a GOLD e PLATINUM» (reward §7).
  2. Dato `RWD-SHOP-25` con 9 residui su 150, allora «Ultimi pezzi»; `RWD-POWERBANK` «Esaurito».
  3. Dato `RWD-EBIKE-RENT` riservato a SEG-TORINO, allora solo Davide lo vede (Q-83).
  4. Dato Anna (100 PTS), allora la fascia F1 mostra «ti mancano 400 punti».
- **Testbook**: TB-RWD (API) · TB-WEB (PT-03/04).

#### US-E05-05 · Richiedere un premio: controlli immediati
*Come* membro, *voglio* sapere subito se non posso richiedere un premio, *così che* non aspetti una conferma che non arriverà.
- **Contesto reale**: due membri richiedono l'ultimo pezzo nello stesso istante; un membro dimentica l'indirizzo per un premio fisico.
- **Tocca**: F-RWD-03, F-RWD-05 · PT-04 · `POST /v1/portal/redemptions` · RWD-01 · EVT-FACT-40.
- **Decisioni**: ordine: 400 dati mancanti → `MEMBER_NOT_ACTIVE` → `REWARD_NOT_AVAILABLE` (non LIVE, fuori validità, fuori segmento, inesistente) → `TIER_NOT_ELIGIBLE` → `SHIPPING_REQUIRED` → `MEMBER_LIMIT_REACHED` → `REWARD_SOLD_OUT` (decremento atomico) → 202 PENDING. Il saldo **non** è controllato qui.
- **Criteri**:
  1. Dato Davide e `RWD-SHOP-10`, allora 202 `{redemptionId, status: PENDING, correlationId}`, stock −1.
  2. ✗ Dato `stock_remaining=1` e due richieste concorrenti, allora una PENDING e l'altra 422 `REWARD_SOLD_OUT` (reward §7).
  3. ✗ Dato `RWD-WEEKEND` per un SILVER, allora 422 `TIER_NOT_ELIGIBLE` (reward §7).
  4. ✗ Dato `RWD-BILL-20` richiesto la terza volta (max 2), allora 422 `MEMBER_LIMIT_REACHED`.
  5. ✗ Dato `RWD-BORRACCIA` senza spedizione, allora 422 `SHIPPING_REQUIRED`; Roberto (BLOCKED) → 422 `MEMBER_NOT_ACTIVE`.
  6. ✗ Dato `RWD-THERMOSTAT` (DRAFT), allora 422 `REWARD_NOT_AVAILABLE`.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-06 · Conferma ed evasione automatica
*Come* membro, *voglio* ricevere subito il codice del buono dopo la richiesta, *così che* lo usi in negozio.
- **Contesto reale**: Davide richiede il buono shopping 10 € e vede il codice in pochi secondi.
- **Tocca**: F-RWD-05, F-RWD-06, F-CPN-02 · `wallet.points.spent` → `reward.redemption.confirmed` → `coupon.issued` → `reward.redemption.fulfilled` · RWD-02, RWD-03, RWD-15 · ponte → `action.reward.redeemed`.
- **Decisioni**: richiesta sconosciuta → ignorata · PENDING → CONFIRMED + evasione (AUTO_COUPON → coupon; INSTANT → FULFILLED; MANUAL → resta CONFIRMED) · già CONFIRMED/FULFILLED → nulla.
- **Criteri**:
  1. Dato Davide e `RWD-SHOP-10` (1 500 PTS), allora entro 10 s `FULFILLED` con coupon, saldo −1 500, stock −1 (reward §7).
  2. Dato `wallet.points.spent` rielaborato, allora nessun secondo coupon (reward §7).
  3. Dato `RWD-DONATION-TREE` (INSTANT), allora FULFILLED senza codice.
  4. Dato `reward.redemption.confirmed`, allora il ponte genera `action.reward.redeemed` nello stesso tracciato.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-07 · Il wallet rifiuta la spesa
*Come* membro, *voglio* sapere che la richiesta non è andata per saldo insufficiente, senza addebiti, *così che* capisca cosa fare.
- **Tocca**: F-RWD-05 · `wallet.spend.rejected` → `reward.redemption.rejected` · RWD-04, WAL-08 · MSG-REWARD-REJECTED.
- **Criteri**:
  1. Dato Anna (100 PTS) e `RWD-COFFEE-5`, allora 202, poi `REJECTED (INSUFFICIENT_BALANCE)`, stock invariato (reward §7).
  2. Dato un rifiuto per una richiesta già chiusa o sconosciuta, allora ignorato (nessuna DLQ).
- **Testbook**: TB-RWD (da coprire).

#### US-E05-08 · Timeout della saga e compensazione
*Come* azienda, *voglio* che una richiesta non resti in attesa per sempre se il wallet dorme, e che una spesa arrivata tardi venga rimborsata, *così che* stock e punti restino coerenti.
- **Contesto reale**: il wallet si addormenta durante la richiesta di Davide; si sveglia dopo 12 minuti.
- **Tocca**: F-RWD-05 · RWD-02, RWD-05, RWD-24 · `POST /v1/demo/jobs/timeout-redemptions?asOf=` · Q-55.
- **Decisioni**: PENDING > 10 min → `REJECTED (TIMEOUT)` + stock · spesa arrivata dopo → `reward.redemption.cancelled (LATE_SPEND, refund=true)` → rimborso.
- **Criteri**:
  1. Dato wallet fermo durante la richiesta, allora PENDING e poi CONFIRMED al riavvio se entro 10 min, altrimenti `REJECTED (TIMEOUT)` con stock ripristinato (docs/12 M4).
  2. Dato la spesa arrivata dopo il timeout, allora cancellazione con rimborso e saldo tornato al valore iniziale.
  3. Dato `asOf` = +9 min, allora nessun timeout.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-09 · Il membro annulla mentre è in conferma
*Come* membro, *voglio* annullare una richiesta finché è in conferma, *così che* rimedi a un errore.
- **Tocca**: docs/03 §5 (PENDING → CANCELLED) · PT-13 · `POST /v1/portal/redemptions/{id}/cancel` · RWD-06, RWD-02.
- **Criteri**:
  1. Dato una PENDING, allora CANCELLED (`MEMBER`), stock +1, nessun rimborso da fare.
  2. ✗ Dato una CONFIRMED, allora 409 `REDEMPTION_NOT_CANCELLABLE`; richiesta di un altro membro → 404.
  3. Dato la spesa del wallet arrivata subito dopo l'annullo, allora `cancelled (LATE_SPEND, refund=true)` e rimborso.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-10 · Evasione manuale di un premio fisico
*Come* operatore CARE, *voglio* segnare come spedito un premio fisico con nota e tracking, *così che* il membro sappia che è in arrivo.
- **Contesto reale**: la borraccia di Sofia è in coda da due giorni.
- **Tocca**: F-RWD-06 · BO-13 · `POST /v1/redemptions/{id}/fulfil` · RWD-07, RWD-25 · MSG-REWARD-READY.
- **Criteri**:
  1. Dato la richiesta di Sofia, quando segno spedito con nota e tracking, allora FULFILLED, `fulfilled {note}`, messaggio in PT-12, audit.
  2. ✗ Dato una richiesta di un premio COUPON o non CONFIRMED, allora 409 `REDEMPTION_NOT_FULFILLABLE`; senza nota 422 `NOTE_REQUIRED`.
  3. ✗ Dato MARKETING, allora 403.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-11 · Annullo con rimborso da backoffice
*Come* operatore CARE, *voglio* annullare una richiesta confermata e restituire i punti, *così che* gestisca un premio non più disponibile.
- **Tocca**: F-RWD-07 · BO-13 · `POST /v1/redemptions/{id}/cancel` · RWD-08, WAL-10.
- **Criteri**:
  1. Dato una CONFIRMED con coupon, allora CANCELLED, coupon VOID, stock +1, `wallet.points.refunded` (reward §7).
  2. ✗ Dato una FULFILLED o PENDING, allora 409 `REDEMPTION_NOT_CANCELLABLE`; motivo vuoto → 422 `REASON_REQUIRED`.
  3. Dato l'annullo, allora BO-13 mostra «in elaborazione» finché arriva il rimborso.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-12 · Pool esaurito: richieste da verificare
*Come* operatore CARE, *voglio* vedere le richieste confermate senza codice e ritentarle dopo aver generato nuovi codici, *così che* nessuno resti senza premio.
- **Tocca**: docs/03 §5 (`needsAttention`) · BO-13 (*Da verificare*) · `POST /v1/redemptions/{id}/retry-fulfilment` · RWD-03, RWD-09, RWD-15.
- **Criteri**:
  1. Dato un pool vuoto, allora la richiesta resta CONFIRMED con `needsAttention` e nessun `fulfilled`.
  2. Dato nuovi codici e *Ritenta*, allora FULFILLED col coupon.
  3. ✗ Dato *Ritenta* col pool ancora vuoto, allora 409 `COUPON_POOL_EMPTY`; su una richiesta senza flag → 409 `REDEMPTION_NOT_RETRYABLE`.
  4. Dato un premio AUTO_COUPON senza pool configurato, allora anche lui `needsAttention` (ramo senza specifica).
- **Testbook**: TB-RWD (da coprire).

#### US-E05-13 · Pool di coupon: crea, genera, importa
*Come* MARKETING, *voglio* creare un pool, generare codici o importare quelli del partner, *così che* i premi coupon siano evadibili.
- **Tocca**: F-CPN-01 · BO-12 · `POST /v1/coupon-pools`, `…/generate`, `…/import` · RWD-12…14.
- **Criteri**:
  1. Dato 100 codici generati con prefisso `CAF`, allora formato `CAF-XXXX-XXXX` in `A-Z2-9`.
  2. ✗ Dato 5 001 codici, allora 422 `COUPON_COUNT_INVALID`; prefisso `caf!` 422 `COUPON_PREFIX_INVALID`; validità 0 → 422.
  3. Dato un import con 3 duplicati, allora `{imported, skipped[3]}`; import vuoto → 422 `COUPON_IMPORT_EMPTY`.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-14 · Usare un coupon alla cassa
*Come* cassiere (simulato), *voglio* verificare e bruciare un codice, *così che* non sia usato due volte.
- **Tocca**: F-CPN-03 · BO-12 (cassa simulata), PT-13 · `POST /v1/coupons/{code}/use` · RWD-10 · Q-52.
- **Criteri**:
  1. Dato un codice ISSUED valido, allora USED e `coupon.used`.
  2. ✗ Dato lo stesso codice due volte, allora la seconda 409 `COUPON_ALREADY_USED` (reward §7).
  3. ✗ Dato il coupon di Davide dopo la scadenza, allora 410 `COUPON_EXPIRED` anche prima del job; codice AVAILABLE → 409 `COUPON_NOT_ISSUED`; VOID → 409; inesistente → 404.
  4. ✗ Dato ANALYST, allora 403.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-15 · Annullare un codice
*Come* operatore CARE, *voglio* annullare un codice emesso per errore, *così che* non sia più spendibile.
- **Tocca**: BO-12 (*Annulla*) · `POST /v1/coupons/{code}/void` · RWD-11 · Q-52.
- **Criteri**: Dato un ISSUED, allora VOID + audit; ✗ USED → 409 `COUPON_ALREADY_USED`; VOID → 409 `COUPON_VOID`; MARKETING → 403.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-16 · Coupon regalati da una campagna
*Come* MARKETING, *voglio* che una campagna o una vincita emetta un coupon, *così che* il premio arrivi senza richiesta.
- **Contesto reale**: Matteo vince un buono colazione alla Ruota d'Autunno.
- **Tocca**: F-CPN-02, F-IW-06 · effetto `coupon.issue` · RWD-16 · MSG-COUPON-GIFT.
- **Decisioni**: `effectId` già usato → nulla · premio sconosciuto → DLQ `REWARD_NOT_FOUND` · senza pool → DLQ `COUPON_POOL_MISSING` · pool vuoto → DLQ `COUPON_POOL_EMPTY` · ok → coupon `CAMPAIGN`.
- **Criteri**:
  1. Dato una vincita coupon, allora `coupon.issued (origin=CAMPAIGN)`, messaggio «regalo» e coupon in PT-13.
  2. ✗ Dato il pool vuoto, allora voce DLQ non ritentabile e tracciato FAILED.
  3. Dato l'effetto riconsegnato, allora nessun secondo coupon.
- **Testbook**: TB-RWD (da coprire).

#### US-E05-17 · Scadenza dei coupon
*Come* azienda, *voglio* che i coupon non usati scadano, *così che* il portale mostri lo stato vero.
- **Tocca**: F-CPN-02 · RWD-17, RWD-24 · `POST /v1/demo/jobs/expire-coupons?asOf=`.
- **Criteri**: Dato il secondo coupon di Davide e `asOf` = +10 gg, allora EXPIRED; in PT-13 in fondo, attenuato «scaduto».
- **Testbook**: TB-RWD (da coprire).

### E06 — Gioco

#### US-E06-01 · Creare un concorso e il suo montepremi
*Come* MARKETING, *voglio* configurare un instant win con meccanica, periodo, giocata gratuita, limiti e premi, *così che* possa inviarlo a LEGAL.
- **Tocca**: F-IW-01, F-IW-02 · BO-14 · `POST/PUT /v1/contests`, `…/duplicate` · GAM-05, GAM-06, GAM-12, GAM-21 · Q-56, Q-113.
- **Decisioni**: codice non valido → 422 `CONTEST_INVALID` · duplicato → 409 · codice → 409 `CODE_IMMUTABLE` · ENDED/ARCHIVED → 409 `CONTEST_NOT_EDITABLE` · LIVE/PAUSED: solo nome, descrizione, `endAt` (docs/03 §3.6, istanti invariati); premi/inizio/istanti/regole/regolamento → 409 `CONTEST_LIVE_LOCKED` · prima di LIVE: cambiare premi/periodo/distribuzione/seme cancella gli istanti · versione → 409.
- **Criteri**:
  1. Dato un concorso in DRAFT con istanti generati, quando cambio il periodo, allora gli istanti vanno rigenerati.
  2. ✗ Dato IW-AUTUNNO LIVE, quando cambio un premio, allora 409 `CONTEST_LIVE_LOCKED` (anche per il regolamento); nome, descrizione e proroga di `endAt` sono ammessi.
  3. Dato *Duplica*, allora `<code>-COPY-1` in DRAFT, premi a quantità piena, istanti da generare.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-02 · Generare gli istanti vincenti con un seme
*Come* LEGAL, *voglio* che gli istanti vincenti siano pre-generati con un seme riproducibile e immutabili dopo l'avvio, *così che* l'assegnazione sia verificabile.
- **Tocca**: F-IW-03 · BO-14 (scheda `instants`) · `POST /v1/contests/{id}/instants/generate` · GAM-04, GAM-08.
- **Criteri**:
  1. Dato il seme 42 due volte, allora identico elenco di istanti; 355 istanti per IW-AUTUNNO (gamification §7).
  2. Dato `BUSINESS_HOURS`, allora nessun istante fuori 08–22 ora di Roma (anche nei giorni del cambio d'ora).
  3. ✗ Dato un concorso LIVE, allora 409 `INSTANTS_LOCKED`; senza premi 422 `CONTEST_INVALID`.
  4. ✗ Dato un periodo di un'ora di notte con `BUSINESS_HOURS`, allora errore (nessun orario valido; ramo senza specifica).
- **Testbook**: TB-GAM (da coprire).

#### US-E06-03 · Chi può vedere gli istanti
*Come* LEGAL o ADMIN, *voglio* vedere la tabella degli istanti; *come* MARKETING, solo l'istogramma, *così che* chi configura non conosca i momenti vincenti.
- **Tocca**: F-IW-07 · BO-14 · `GET /v1/contests/{id}/instants`, `…/histogram` · GAM-21 · docs/08 §2 `instants.view`.
- **Criteri**: ✗ Dato MARKETING, allora 403 sugli istanti e l'istogramma visibile (gamification §7); LEGAL vede la tabella (Playwright di docs/12 §4).
- **Testbook**: TB-GAM (API) · TB-WEB (Forbidden).

#### US-E06-04 · Pubblicare un concorso
*Come* MARKETING, *voglio* pubblicare il concorso approvato, *così che* i membri giochino; il sistema deve impedirlo senza istanti.
- **Tocca**: F-IW-01 · BO-14, BO-21 · `POST /v1/contests/{id}/transitions` · GAM-07, CMN-04…07.
- **Criteri**:
  1. ✗ Dato *Pubblica* senza istanti, allora 422 `INSTANTS_NOT_GENERATED` con il collegamento alla scheda istanti.
  2. ✗ Dato *Pubblica* da DRAFT con approvazione attiva, allora 409 `APPROVAL_REQUIRED` (serve LEGAL, US-E08-01).
- **Testbook**: TB-GAM (da coprire).

#### US-E06-05 · Giocare: esiti e rifiuti
*Come* membro, *voglio* giocare e sapere subito se ho vinto, *così che* il gioco sia divertente e onesto.
- **Contesto reale**: Matteo gioca la gratuita del giorno, poi due crediti; alle 23:59 finisce le giocate e riprova alle 00:01.
- **Tocca**: F-IW-04 · PT-05, PT-06 · `POST /v1/portal/contests/{code}/play` · GAM-01, GAM-02, WEB-14 · EVT-FACT-51/52.
- **Decisioni**: ordine: `MEMBER_REQUIRED` → 404 → `MEMBER_NOT_ACTIVE` → `CONTEST_NOT_LIVE` (stato o periodo) → `DAILY_LIMIT_REACHED` → `NO_PLAYS_AVAILABLE` · prima la gratuita poi i crediti · `maxWinsPerMember` raggiunto → LOSE · claim → WIN o LOSE.
- **Criteri**:
  1. Dato Matteo con gratuita e crediti, allora la prima giocata è `FREE_DAILY`, le successive `CREDIT`; la risposta è sincrona con `playsAvailable`.
  2. ✗ Dato una seconda giocata gratuita nello stesso giorno senza crediti, allora 422 `NO_PLAYS_AVAILABLE` (gamification §7).
  3. Dato la giocata alle 00:01 ora di Roma, allora la gratuita è di nuovo disponibile (giorno di Roma, non UTC).
  4. ✗ Dato 5 giocate oggi su IW-AUTUNNO, allora la sesta 422 `DAILY_LIMIT_REACHED` anche con crediti.
  5. ✗ Dato un concorso con `endAt` passato ma ancora LIVE (job non girato), allora 422 `CONTEST_NOT_LIVE`.
  6. ✗ Dato Roberto, allora 422 `MEMBER_NOT_ACTIVE`.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-06 · Guadagnare giocate
*Come* membro, *voglio* ricevere giocate da campagne (sondaggio, acquisto GOLD ≥ 50 €), *così che* giochi di più.
- **Tocca**: F-IW-05 · effetto `plays.grant` · GAM-02, GAM-13 · EVT-FACT-50.
- **Criteri**:
  1. Dato `survey.completed`, allora +1 credito e `contest.plays.granted`; riconsegna → nessun credito in più.
  2. ✗ Dato un `plays.grant` verso un concorso inesistente, allora DLQ `CONTEST_NOT_FOUND`.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-07 · Nessuna vincita doppia sotto carico
*Come* LEGAL, *voglio* che un istante vincente sia assegnato a una sola giocata anche con molti giocatori contemporanei, *così che* il montepremi sia rispettato.
- **Tocca**: F-IW-04 · GAM-03 · docs/06 §9 (concorrenza).
- **Criteri**:
  1. Dato 50 giocate concorrenti con un solo istante scaduto, allora esattamente 1 WIN (gamification §7).
  2. Dato due richieste simultanee dello stesso membro, allora non superano i crediti disponibili (lock per membro+concorso).
- **Testbook**: TB-GAM (da coprire).

#### US-E06-08 · Consegna della vincita
*Come* membro vincitore, *voglio* ricevere il premio (punti, coupon o fisico), *così che* la vincita sia reale.
- **Contesto reale**: Matteo vince 50 punti; un altro membro vince il powerbank e l'assistenza lo spedisce.
- **Tocca**: F-IW-06, F-IW-07 · `contest.won` → ponte → `instantwin.won` → `CMP-IW-PRIZE-POINTS/COUPON` · BO-14 (*Aggiorna consegna*) · `POST /v1/plays/{playId}/delivery` · GAM-01, GAM-11, CMP-14.
- **Criteri**:
  1. Dato una vincita di punti, allora entro 10 s il tracciato mostra `contest.won → action.instantwin.won → campaign.evaluated → points.grant → wallet.points.earned` (gamification §7).
  2. Dato una vincita PHYSICAL, allora consegna PENDING; CARE la porta a DELIVERED con nota.
  3. ✗ Dato *Aggiorna consegna* su una vincita di punti, allora 409 `DELIVERY_NOT_APPLICABLE`; stato `SHIPPED` → 422 `DELIVERY_STATUS_INVALID`; MARKETING → 403.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-09 · Piantare un istante per la demo
*Come* operatore demo, *voglio* garantire che la prossima giocata vinca il premio scelto, *così che* la demo non dipenda dal caso.
- **Tocca**: F-IW-08 · BO-14, BO-30 · `POST /v1/demo/contests/{id}/plant-instant` · GAM-09 · Q-62.
- **Criteri**:
  1. Dato *Pianta un istante* su 50 punti e poi una giocata di Matteo, allora WIN di **quel** premio anche se esistono istanti maturi di altri premi (Q-62), montepremi invariato.
  2. ✗ Dato un concorso non LIVE, allora 409 `CONTEST_NOT_LIVE`; premio di un altro concorso → 422 `PRIZE_NOT_FOUND`; nessun istante aperto per quel premio → 422 `NO_OPEN_INSTANT`; MARKETING → 403.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-10 · Fine del concorso
*Come* LEGAL, *voglio* che alla fine del periodo gli istanti non assegnati siano annullati e il concorso chiuso, *così che* il verbale sia completo.
- **Tocca**: F-IW-01 · GAM-10, GAM-20 · `POST /v1/demo/jobs/close-contests?asOf=`.
- **Criteri**:
  1. Dato IW-AUTUNNO e `asOf` = +41 gg, allora ENDED e istanti OPEN → VOID.
  2. Dato un concorso PAUSED scaduto, allora non viene chiuso (ramo senza specifica).
- **Testbook**: TB-GAM (da coprire).

#### US-E06-11 · Vincitori, statistiche ed export
*Come* LEGAL, *voglio* l'elenco dei vincitori con stato di consegna ed export CSV, *così che* prepari il verbale.
- **Tocca**: F-IW-07 · BO-14 (schede `winners`, `stats`) · `GET …/winners`, `…/winners.csv`, `…/stats`.
- **Criteri**: Dato IW-ESTATE, allora le 142 vincite storiche (Q-58) e il CSV con le stesse righe; tasso di vincita per giorno.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-12 · Configurare obiettivi e badge
*Come* MARKETING, *voglio* definire obiettivi (metrica, traguardo, periodo, ripetibilità) collegati a badge, *così che* il comportamento ripetuto sia premiato.
- **Tocca**: F-ACH-01, F-ACH-03 · BO-15 · `POST/PUT /v1/achievements`, `/v1/badges` · GAM-17.
- **Criteri**: ✗ Dato una metrica sconosciuta o un traguardo ≤ 0, allora 422 `ACHIEVEMENT_INVALID`; badge senza nome 422 `BADGE_INVALID`; codice duplicato 409; LEGAL → 403.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-13 · Progresso degli obiettivi
*Come* membro, *voglio* vedere il progresso verso un obiettivo e completarlo una volta sola (o una volta per periodo), *così che* sappia quanto manca.
- **Contesto reale**: Giulia a 2/3 acquisti del mese; Matteo a 5/7 giorni di login; Marco completa «Tutto digitale».
- **Tocca**: F-ACH-01, F-ACH-02 · PT-09, PT-01 (obiettivo più vicino) · GAM-15, GAM-16 · Q-59.
- **Decisioni**: membro non ACTIVE → ignorato · filtro · non ripetibile già completato → saltato · `achievement.progressed` solo al cambio · COUNT/SUM/DISTINCT_TYPES/STREAK · periodo MONTH/EDITION (⚠ DAY/WEEK trattati come EVER) · azioni interne solo se elencate.
- **Criteri**:
  1. Dato 3 acquisti nel mese, allora `ACH-3-PURCHASES-MONTH` completato una volta; il 4° non riemette (gamification §7).
  2. Dato login il 1°, 2° e 4° giorno, allora la serie riparte da 1 il 4°.
  3. Dato SUM su 45,90 €, allora +45.
  4. ⚠ Dato un obiettivo con periodo `WEEK`, allora oggi il progresso non si azzera a fine settimana.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-14 · Badge
*Come* membro, *voglio* collezionare badge (da obiettivi o da campagne) e ottenere il bonus collegato, *così che* abbia un riconoscimento visibile.
- **Tocca**: F-ACH-03 · PT-09 · effetto `badge.award`, fatto `badge.awarded` → ponte → `CMP-BADGE-BONUS` · GAM-14.
- **Criteri**:
  1. Dato `ebill.activated` + `directdebit.activated`, allora `ACH-DIGITAL` completato, badge assegnato, `badge.awarded` rientra come azione e `CMP-BADGE-BONUS` accredita 100 PTS (gamification §7).
  2. Dato un badge già posseduto, allora nessun secondo fatto né bonus.
  3. ✗ Dato un badge inesistente in un effetto, allora DLQ `BADGE_NOT_FOUND`.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-15 · Classifiche
*Come* membro, *voglio* vedere la mia posizione in classifica con i nickname, *così che* competa senza esporre il mio nome.
- **Tocca**: F-LDB-01 · BO-16, PT-10 · GAM-18 · Q-60.
- **Decisioni**: punteggi PTS/STS da `wallet.points.earned` (importo effettivo) o conteggio azioni · solo ACTIVE nel ranking · parimerito: chi prima · configurazione: 422 `LEADERBOARD_INVALID`, metrica/periodo non modificabili (409 `LEADERBOARD_LOCKED`).
- **Criteri**:
  1. Dato Roberto BLOCKED con punteggio alto, allora escluso dal ranking (Q-60).
  2. Dato due membri a pari punti, allora prima chi li ha raggiunti prima.
  3. Dato il portale, allora solo nickname; in BO-16 nickname e nome reale affiancati.
  4. ✗ Dato un cambio di metrica, allora 409 `LEADERBOARD_LOCKED`; top N 60 → 422.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-16 · Registrarsi con il codice di un amico
*Come* nuovo membro invitato, *voglio* inserire il codice dell'amico alla registrazione, *così che* entrambi otteniamo il premio al mio primo acquisto.
- **Tocca**: F-REF-01 · PT-08 (`/portal/join?ref=`), BO-02 · MBR-05, MBR-06 · Q-61.
- **Criteri**:
  1. Dato il codice di Marco, allora il legame è creato (invitante unico, non modificabile).
  2. ✗ Dato un codice inesistente o di un membro non ACTIVE, allora 422 `REFERRAL_CODE_INVALID` sul campo e nessun membro creato.
  3. Dato `REFERRAL_SELF` (member §5), allora non è raggiungibile alla registrazione (il codice nasce col membro).
- **Testbook**: TB-GAM (da coprire).

#### US-E06-17 · Completamento del referral
*Come* invitante, *voglio* ricevere il premio quando l'amico fa il primo acquisto, *così che* valga la pena invitare.
- **Contesto reale**: Elisa (invitata da Marco) compra 45 €.
- **Tocca**: F-REF-02 · MBR-09 · EVT-FACT-08 → ponte → `CMP-REFERRAL-REFERRER/REFEREE`.
- **Criteri**:
  1. Dato Elisa al primo `purchase.completed`, allora due `referral.completed` con ruoli opposti e nessun altro ai successivi acquisti (member §7).
  2. Dato `SCN-REFERRAL`, allora Marco +500 PTS / +250 STS, Elisa +200 PTS.
  3. Dato l'undicesimo referral di Marco nell'edizione, allora `CMP-REFERRAL-REFERRER` è `LIMIT`.
- **Testbook**: TB-GAM (da coprire).

#### US-E06-18 · Panoramica referral
*Come* MARKETING, *voglio* vedere inviti, completati, tasso e top presentatori; *come* membro, i miei invitati, *così che* misuri il passaparola.
- **Tocca**: F-REF-01/02 · BO-17, PT-11 · `GET /v1/referral/overview`, `/v1/members/{id}/referrals`, `/v1/portal/members/{id}/referral` · WEB-17 · Q-61.
- **Criteri**: Dato Marco, allora Elisa «iscritta» prima e «premio ottenuto» dopo il suo acquisto; «n di 10 inviti premiati» conta l'anno solare (Q-61).
- **Testbook**: TB-GAM (API) · TB-WEB.

### E07 — Contenuti, messaggi, tema

#### US-E07-01 · Creare card, pop-up e banner
*Come* MARKETING, *voglio* creare contenuti per il portale con posizionamento, pubblico, calendario e CTA, *così che* il portale sia gestito dal backoffice.
- **Tocca**: F-CNT-01, F-CNT-02 · BO-18 · `POST/PUT /v1/contents`, `…/duplicate` · ENG-05, ENG-24.
- **Criteri**:
  1. Dato una card HOME_GRID valida, allora 201 in DRAFT.
  2. ✗ Dato dati non validi, allora 422 `CONTENT_INVALID`; codice duplicato 409; cambiare `kind` 409 `KIND_IMMUTABLE`; contenuto ENDED/ARCHIVED 409 `CONTENT_NOT_EDITABLE`; versione 409.
  3. ✗ Dato CARE, allora 403.
- **Testbook**: TB-ENG (da coprire).

#### US-E07-02 · Pubblicare un contenuto senza approvazione
*Come* MARKETING, *voglio* pubblicare subito un contenuto, *così che* reagisca in fretta.
- **Tocca**: F-CNT-01 · BO-18 · `POST /v1/contents/{id}/transitions` · ENG-06, ENG-07, CMN-07.
- **Criteri**:
  1. Dato DRAFT → *Pubblica*, allora LIVE e `content.status.changed`.
  2. ✗ Dato `SUBMIT` o `APPROVE` su un contenuto, allora 409 `INVALID_TRANSITION`.
  3. Dato `endAt` passato, allora il job lo porta ENDED entro 10 min (con `jobs.enabled`).
- **Testbook**: TB-ENG (da coprire).

#### US-E07-03 · Cosa vede un membro in ogni posizione
*Come* membro, *voglio* vedere contenuti pertinenti (per livello, segmento, stato), *così che* il portale parli a me.
- **Contesto reale**: Anna vede «Passa alla bolletta digitale»; Davide no (è già digitale) ma vede «Grazie per essere digitale».
- **Tocca**: F-CNT-01 · PT-01, PT-03, PT-05 · `GET /v1/portal/content` · ENG-01…03 · Q-71, Q-72.
- **Decisioni**: LIVE ∧ calendario ∧ pubblico (⚠ tier, segmenti e stati in AND) · priorità decrescente · limite per posizione.
- **Criteri**:
  1. Dato un contenuto con `audience.tiers=[GOLD, PLATINUM]`, allora assente per Anna e presente per Davide (engagement §7).
  2. Dato più di 6 card HOME_GRID idonee, allora le 6 a priorità più alta.
  3. ⚠ Dato un pubblico con tier GOLD **e** segmento SEG-DIGITAL, allora serve soddisfare entrambi (come le campagne, Q-212).
- **Testbook**: TB-ENG (da coprire).

#### US-E07-04 · Un pop-up alla volta, con frequenza
*Come* membro, *voglio* al più un pop-up per visita e non rivedere quello già chiuso, *così che* il portale non sia invadente.
- **Tocca**: F-CNT-02 · PT-01 · `GET /v1/portal/popups/next`, `POST …/seen` · ENG-04.
- **Criteri**:
  1. Dato `ONCE` visto e chiuso, allora non ricompare; `ONCE_PER_DAY` ricompare il giorno dopo (orologio iniettato, giorno di Roma) (engagement §7).
  2. Dato nessun pop-up idoneo, allora 204.
  3. Dato un errore di rendering prima di `seen`, allora il pop-up non è consumato.
  4. Dato `POP-WEEKEND` di martedì, allora escluso (`daysOfWeek`, Q-71).
- **Testbook**: TB-ENG (da coprire).

#### US-E07-05 · Anteprima per membro
*Come* MARKETING, *voglio* vedere cosa vede un membro e perché gli altri contenuti sono esclusi, *così che* verifichi il targeting.
- **Tocca**: F-CNT-04 · BO-18 · `GET /v1/contents/preview` · ENG-01, ENG-04.
- **Criteri**: Dato Anna e HOME_GRID, allora i contenuti mostrati e per gli altri `NOT_IN_AUDIENCE`, `OUT_OF_SCHEDULE`, `NOT_LIVE` o `FREQUENCY`.
- **Testbook**: TB-ENG (da coprire).

#### US-E07-06 · Card di vincita
*Come* MARKETING, *voglio* una card specifica per ogni premio del concorso, *così che* il momento della vincita sia curato.
- **Tocca**: F-CNT-03 · PT-06 · `GET /v1/portal/content?placement=WIN&prizeCode=`.
- **Criteri**: Dato una vincita di 50 punti, allora `WIN-POINTS-50`; premio senza card → fallback generico.
- **Testbook**: TB-ENG (API) · TB-WEB.

#### US-E07-07 · Template dei messaggi
*Come* MARKETING, *voglio* scrivere titolo e testo con segnaposto e vedere l'anteprima su un evento di esempio, *così che* i messaggi siano corretti.
- **Tocca**: F-MSG-02 · BO-19 · `POST/PUT /v1/message-templates`, `…/render` · ENG-12, ENG-13, WEB-16 · Q-66, Q-74, Q-75.
- **Criteri**:
  1. Dato `{{data.amount|number}}` con 1500, allora «1.500».
  2. Dato un segnaposto inesistente, allora stringa vuota (e WARN), nessun errore.
  3. ✗ Dato un template senza titolo o con canale sconosciuto, allora 422 `TEMPLATE_INVALID`; link esterno rifiutato dall'editor (Q-74).
- **Testbook**: TB-ENG (da coprire).

#### US-E07-08 · Regole di notifica
*Come* MARKETING, *voglio* collegare un tipo di fatto (con condizione) a un template, *così che* i membri siano avvisati degli eventi importanti.
- **Tocca**: F-MSG-01 · BO-19 · `POST/PUT /v1/notification-rules` · ENG-09, ENG-14 · Q-65.
- **Criteri**:
  1. Dato `wallet.points.earned` con condizione `data.currency = PTS`, allora un solo messaggio per acquisto (Q-65).
  2. ✗ Dato una regola su `message.delivered` o un fatto sconosciuto, allora 422 `RULE_INVALID`.
  3. Dato una regola disabilitata, allora nessun messaggio.
- **Testbook**: TB-ENG (da coprire).

#### US-E07-09 · Messaggi in inbox dai fatti
*Come* membro, *voglio* ricevere in-app i messaggi su punti, livello, premi, vincite e scadenze, *così che* non mi perda nulla.
- **Tocca**: F-MSG-01 · PT-12, BO-19 (registro) · ENG-08…10 · Q-67, Q-70 · EVT-FACT-60.
- **Criteri**:
  1. Dato `wallet.points.earned` di 162 PTS, allora «Hai guadagnato 162 punti»; stesso evento rielaborato → nessun duplicato (engagement §7).
  2. Dato un membro BLOCKED, allora riceve il messaggio; ANONYMIZED no (Q-70).
  3. Dato un template `EMAIL_FAKE`, allora solo nel registro BO-19, non in inbox (Q-67).
  4. Dato `message.delivered`, allora nessuna regola lo considera (niente cicli).
- **Testbook**: TB-ENG (da coprire).

#### US-E07-10 · Messaggio deciso da una campagna
*Come* MARKETING, *voglio* che una campagna invii un messaggio con parametri, *così che* comunichi un'offerta al momento giusto.
- **Tocca**: F-MSG-01, F-CMP-04 · effetto `message.send` · ENG-11 · Q-68.
- **Criteri**:
  1. Dato `CMP-BIRTHDAY` (se attivato) con `SEND_MESSAGE`, allora un messaggio per effetto (dedup `effectId`).
  2. ✗ Dato un template inesistente, allora DLQ `TEMPLATE_NOT_FOUND`.
- **Testbook**: TB-ENG (da coprire).

#### US-E07-11 · Leggere le notifiche
*Come* membro, *voglio* segnare come lette le notifiche e vedere il contatore, *così che* sappia cosa è nuovo.
- **Tocca**: F-MSG-01 · PT-12, campanella · `GET /v1/portal/inbox`, `/unread-count`, `POST …/read`, `/read-all` · ENG-16 · Q-76.
- **Criteri**:
  1. Dato Marco con 3 non letti, allora campanella 3; *Segna tutte* → 0.
  2. ✗ Dato la lettura di un messaggio di un altro membro, allora 404.
  3. Dato un messaggio nato da un job, allora la campanella lo mostra entro 30 s (Q-76).
- **Testbook**: TB-ENG (API) · TB-WEB.

#### US-E07-12 · Tema del portale a runtime
*Come* MARKETING, *voglio* cambiare nome, logo e colori del portale con verifica del contrasto, *così che* il brand cambi senza rilascio e resti leggibile.
- **Tocca**: F-THM-01 · BO-20, tutto il portale · `PUT /v1/theme`, `GET /v1/portal/theme` · ENG-15 · Q-79.
- **Criteri**:
  1. Dato un nuovo `primary`, allora `GET /v1/portal/theme` lo restituisce e il portale cambia colore senza rebuild (engagement §7).
  2. ✗ Dato `primary` che con `night` ha contrasto < 4,5:1, allora 422 `THEME_CONTRAST_TOO_LOW`; colore `rosso` → 422 `THEME_INVALID`.
  3. Dato engagement addormentato, allora il portale usa i colori di default.
- **Testbook**: TB-ENG (da coprire) · TB-WEB.

### E08 — Governance

#### US-E08-01 · Revisione e approvazione LEGAL
*Come* LEGAL, *voglio* approvare concorsi, premi e campagne sopra soglia prima che vadano online, *così che* l'azienda sia tutelata.
- **Contesto reale**: `IW-NATALE` e `CMP-BLACK-FRIDAY` sono in revisione da ieri; Elena li apre in BO-21.
- **Tocca**: F-APR-01, F-APR-02, F-RWD-08 · BO-21, `LifecycleBar` · `POST /v1/<risorsa>/{id}/transitions` · CMN-04, CMN-05, CMN-07, CMP-23, RWD-23, GAM-07 · Q-08.
- **Decisioni**: policy: CONTEST, REWARD sempre LEGAL; CAMPAIGN se `requiresLegal` o budget > 100 000; CONTENT mai · `SUBMIT` DRAFT→IN_REVIEW · `APPROVE` → APPROVED · `PUBLISH` → LIVE · approvazione spenta: `SUBMIT` pubblica direttamente.
- **Criteri**:
  1. Dato `luca.marketing`, allora non può portare un concorso a LIVE: solo *Invia in revisione*; `elena.legal` approva con commento; storico e audit visibili (docs/12 M7).
  2. Dato una campagna con budget 100 001, allora serve LEGAL; con 100 000 no.
  3. Dato `loyaltyhub.approval.enabled=false`, allora `SUBMIT` da DRAFT porta a LIVE.
- **Testbook**: TB-GOV (da coprire).

#### US-E08-02 · Rifiutare con un commento
*Come* LEGAL, *voglio* rifiutare motivando, *così che* MARKETING sappia cosa correggere.
- **Tocca**: F-APR-01 · CMN-05.
- **Criteri**: ✗ Dato un rifiuto senza commento, allora 422 `REJECT_COMMENT_REQUIRED` (docs/12 M7); con commento → DRAFT e commento nello storico.
- **Testbook**: TB-GOV (da coprire).

#### US-E08-03 · Transizioni non ammesse
*Come* sistema, *voglio* rifiutare ogni transizione non prevista, *così che* il ciclo di vita resti coerente.
- **Tocca**: docs/03 §3.6 · CMN-03, CMN-05, ENG-06.
- **Decisioni**: azione sconosciuta → 422 `INVALID_ACTION` · `PUBLISH` da DRAFT con approvazione richiesta → 409 `APPROVAL_REQUIRED` · ogni altra coppia non in tabella → 409 `INVALID_TRANSITION`.
- **Criteri**: tabella completa stato × azione (7 × 8) per campagne, premi e concorsi, più la tabella ridotta dei contenuti; ✗ es. `RESUME` da LIVE → 409; `ARCHIVE` da LIVE → 409; `FOO` → 422.
- **Testbook**: TB-GOV (da coprire).

#### US-E08-04 · Chi può fare quale transizione
*Come* ADMIN, *voglio* che solo MARKETING/ADMIN gestiscano il ciclo di vita e solo LEGAL/ADMIN approvino, con l'override di ADMIN tracciato, *così che* la separazione dei compiti sia garantita.
- **Tocca**: docs/08 §2 (`object.edit`, `object.approve`) · CMN-04, CMN-06, CMP-30, RWD-25, GAM-21.
- **Criteri**:
  1. ✗ Dato MARKETING che approva, allora 403; LEGAL che pubblica, allora 403; CARE qualunque transizione, 403.
  2. Dato ADMIN che approva al posto di LEGAL, allora consentito e audit «[override ADMIN]».
- **Testbook**: TB-GOV (da coprire).

#### US-E08-05 · Casella approvazioni unica
*Come* LEGAL, *voglio* una sola lista di ciò che attende me, anche se un servizio dorme, *così che* non dimentichi nulla.
- **Tocca**: F-APR-03 · BO-21 · `GET /v1/approvals`, `?submittedBy=`, `/v1/approvals/policy` · WEB-11 · Q-96.
- **Criteri**:
  1. Dato il seed, allora `elena.legal` vede `CMP-BLACK-FRIDAY`, `IW-NATALE`, `RWD-GIFT-50`; `luca.marketing` li vede in *Inviate da me*.
  2. Dato gamification addormentato, allora le voci degli altri servizi restano e la fonte è *degraded*.
- **Testbook**: TB-GOV (API) · TB-WEB.

#### US-E08-06 · Identità simulata e matrice dei permessi
*Come* presentatore, *voglio* cambiare persona e vedere cambiare ciò che posso fare, e il backend deve rifiutare comunque, *così che* la demo mostri una governance credibile senza login.
- **Tocca**: docs/06 §3, docs/08 §2 · CMN-01, CMN-02, WEB-01, WEB-02 · tutte le righe «Guardie» di §5.
- **Criteri**:
  1. Dato nessun header, allora `ANALYST:anonymous` e ogni scrittura 403.
  2. Dato `X-LH-Actor: ADMINISTRATOR:x`, allora ruolo sconosciuto ⇒ ANALYST.
  3. Dato ogni capacità di docs/08 §2 × 5 ruoli, allora UI e API concordano (●: 403 lato server).
  4. Dato ANALYST che chiama `POST /v1/campaigns` o `PATCH /v1/members/{id}`, allora 403; ⚠ `POST /v1/members` resta senza guardia (serve anche alla registrazione dal portale).
- **Testbook**: TB-GOV (API) · TB-WEB (UI).

#### US-E08-07 · Audit di ogni scrittura
*Come* ANALYST, *voglio* sapere chi ha cambiato cosa e quando, con prima/dopo, *così che* ogni decisione sia tracciabile.
- **Tocca**: F-AUD-01 · BO-22 · `lh.audit.v1` → insight `GET /v1/audit` · INS-04, INS-08, CMN-06 · Q-109, Q-117.
- **Criteri**:
  1. Dato una modifica di campagna da BO-06, allora riga in BO-22 con diff e attore `MARKETING:luca.marketing` (docs/12 M2).
  2. Dato un job o un reset, allora azione `JOB`/`RESET` con attore `system`/ADMIN.
  3. Dato filtri per ruolo e periodo, allora solo le righe pertinenti; ✗ data non valida → 400.
- **Testbook**: scoperta (nessun dominio TB prevede F-AUD-01; proposta TB-INS o estensione di TB-GOV).

#### US-E08-08 · Configurare un webhook in uscita
*Come* ADMIN, *voglio* abbonare un sistema esterno ad alcuni tipi di fatto, *così che* riceva gli aggiornamenti senza interrogare.
- **Contesto reale**: il CRM vuole sapere quando un membro sale di livello.
- **Tocca**: F-WBH-01 · BO-23 · `POST/PUT/DELETE /v1/webhooks` · ENG-17, ENG-18, ENG-24 · Q-97, Q-99.
- **Criteri**:
  1. Dato un webhook `https://crm.example.org/hook` su `tier.upgraded`, allora creato e il `secret` compare una sola volta.
  2. ✗ Dato `http://example.org`, `https://10.0.0.5/…`, `https://intranet.local`, un tipo `message.delivered` o nessun tipo, allora 422 `WEBHOOK_INVALID` con il campo.
  3. ✗ Dato MARKETING, allora 403.
- **Testbook**: TB-ENG (da coprire).

#### US-E08-09 · Consegna firmata e ritentativi
*Come* sistema ricevente, *voglio* ricevere il CloudEvent firmato e, se sono giù, essere ritentato, *così che* non perda eventi e possa verificarne l'autenticità.
- **Tocca**: F-WBH-01 · ENG-19, ENG-20, ENG-22, ENG-23 · `deploy/webhook-receiver/` · Q-98, Q-100, Q-101.
- **Criteri**:
  1. Dato un endpoint che risponde 500, allora 3 ritentativi a 1, 5, 15 min e stato finale `GAVE_UP`; firma verificabile (engagement §7, docs/12 M7).
  2. Dato un 302, allora fallimento (niente redirect).
  3. Dato un webhook disabilitato, allora nessuna consegna.
  4. Dato *Invia evento di prova*, allora consegna con l'esempio del contratto e 201.
- **Testbook**: TB-ENG (da coprire).

#### US-E08-10 · Riprovare una consegna
*Come* ADMIN, *voglio* ritentare a mano una consegna fallita, *così che* recuperi dopo che il ricevente è tornato su.
- **Tocca**: BO-23 (*Ritenta*) · `POST /v1/webhook-deliveries/{id}/retry` · ENG-21 · Q-98.
- **Criteri**:
  1. Dato una `GAVE_UP` e il ricevente di nuovo su, allora *Riprova* → `OK` (docs/12 M7).
  2. ✗ Dato una consegna `OK` o `PENDING`, allora 409 `DELIVERY_NOT_RETRYABLE`; un tentativo in corso → 409 `DELIVERY_BUSY`.
  3. Dato *Riprova* fallito dopo `GAVE_UP`, allora resta `GAVE_UP` senza nuovo ciclo.
- **Testbook**: TB-ENG (da coprire).

#### US-E08-11 · Riprocessare un messaggio in DLQ
*Come* ADMIN, *voglio* riprocessare un'azione finita in DLQ dopo aver risolto la causa, *così che* il membro ottenga i suoi punti.
- **Contesto reale**: `SCN-POISON` manda un'azione di Marco in DLQ; dopo la verifica Marta la riprocessa.
- **Tocca**: F-INS-05 · BO-27 · `POST /v1/dlq/{id}/reprocess` → ingestion `POST /v1/events` con `X-LH-Reprocess` · INS-01, ING-09, ING-10, CMP-18, WEB-10 · Q-104…Q-111.
- **Decisioni**: voce non OPEN → 409 `DLQ_NOT_OPEN` · non azione → 409 `NOT_REPROCESSABLE` · ingestion non accetta → 409 `REPROCESS_REJECTED` · ingestion irraggiungibile → 503 · ok → REPROCESSED + audit · solo ADMIN (anche lato ingestion).
- **Criteri**:
  1. Dato `SCN-POISON`, allora voce DLQ con `errorCode=DEMO_POISON`, tracciato `FAILED` (insight §7).
  2. Dato *Riprocessa*, allora ingestion ripubblica la stessa azione (stesso id), i consumer che l'avevano già elaborata la saltano e la voce è `REPROCESSED`; il tracciato non è più FAILED (Q-106).
  3. ✗ Dato un effetto in DLQ, allora 409 `NOT_REPROCESSABLE`; una seconda riprocessa → 409 `DLQ_NOT_OPEN`.
  4. ✗ Dato CARE, allora 403 (anche con `X-LH-Reprocess` diretto su ingestion).
- **Testbook**: scoperta (insight non ha dominio TB; il lato ingestion in TB-ING).

#### US-E08-12 · Scartare un messaggio in DLQ
*Come* ADMIN, *voglio* scartare con una nota un messaggio non recuperabile, *così che* la coda resti pulita e la decisione tracciata.
- **Tocca**: F-INS-05 · BO-27 · `POST /v1/dlq/{id}/discard` · INS-02, WEB-10 · Q-107, Q-109, Q-110.
- **Criteri**: ✗ Dato una nota vuota, allora 422 `NOTE_REQUIRED`; voce già chiusa → 409; ok → `DISCARDED`, audit, tracciato ancora FAILED (Q-106).
- **Testbook**: scoperta.

#### US-E08-13 · Due operatori sulla stessa configurazione
*Come* operatore, *voglio* essere avvisato se qualcun altro ha modificato lo stesso oggetto nel frattempo, *così che* non sovrascriva il suo lavoro senza saperlo.
- **Tocca**: docs/06 §4, Q-112 · `VERSION_CONFLICT` su campagne, premi, concorsi, contenuti, template, regole, tema, webhook, membri, segmenti · BO `EditorPage` (dialogo «ricarica / sovrascrivi»).
- **Criteri**: ✗ Dato due `PUT` con la stessa `version`, allora il secondo 409 `VERSION_CONFLICT` e nessuna modifica persa; la UI propone ricarica/sovrascrivi.
- **Testbook**: TB-GOV (da coprire) + una riga per dominio proprietario.

### E09 — Osservabilità

#### US-E09-01 · Flusso eventi live
*Come* chiunque guardi la demo, *voglio* vedere in tempo reale gli eventi che attraversano i 5 topic, colorati e filtrabili, *così che* l'architettura a eventi sia visibile.
- **Tocca**: F-INS-01 · BO-24, rail eventi · SSE `GET /v1/stream/events` · INS-05, WEB-06, CMN-12 · Q-26.
- **Decisioni**: filtri `topics, memberId, types, correlationId` · heartbeat 15 s · `Last-Event-ID` → ultimi ≤ 200 · client lento o chiuso → disconnesso senza errore · lato web: 3 fallimenti → polling 3 s «live ridotto».
- **Criteri**:
  1. Dato un acquisto dal simulatore, allora entro 3 s almeno 4 messaggi SSE (`action`, `campaign.evaluated`, `points.grant`, `wallet.points.earned`) con lo stesso `correlationId` (insight §7, docs/12 M2).
  2. Dato una riconnessione con `Last-Event-ID`, allora nessun evento perso tra i due collegamenti (test con 20 eventi) (insight §7).
  3. Dato l'SSE interrotto, allora dopo 3 tentativi la UI passa a polling e lo dichiara (docs/12 M2).
- **Testbook**: TB-WEB (lato client, da coprire); lato server scoperto.

#### US-E09-02 · Stato della pipeline
*Come* ADMIN, *voglio* vedere per ogni topic l'ultimo evento e i volumi 1 h/24 h, *così che* capisca se qualcosa si è fermato.
- **Tocca**: F-INS-06 · BO-24 (striscia pipeline) · `GET /v1/pipeline/status`.
- **Criteri**: Dato 12 azioni di `SCN-WEEKEND-BURST`, allora `lh.actions.v1` conta +12 nell'ultima ora e l'ultimo evento è recente.
- **Testbook**: scoperta.

#### US-E09-03 · Tracciato di un'azione
*Come* operatore CARE, *voglio* vedere la catena completa di un'azione (valutazione, effetti, fatti, messaggi) con i tempi, *così che* spieghi al cliente cosa è successo.
- **Tocca**: F-INS-02 · BO-25, `TraceLink` ovunque · `GET /v1/traces/{correlationId}` · INS-03 · Q-106.
- **Decisioni**: albero per causazione · `FAILED` se c'è una voce DLQ OPEN/DISCARDED · `COMPLETE` dopo 5 s di quiete · altrimenti `IN_PROGRESS` · correlazione sconosciuta → `IN_PROGRESS` vuoto (non 404).
- **Criteri**:
  1. Dato lo scenario SILVER 130 €, allora radice = azione, `durationMs > 0`, `outcome.points = [{PTS,162},{STS,130}]` (insight §7).
  2. Dato `SCN-POISON`, allora `FAILED` (insight §7); dopo *Riprocessa*, non più `FAILED`.
  3. Dato `SCN-TIER-UP`, allora un solo albero con azione interna e bonus (docs/12 M3).
  4. Dato un `correlationId` inesistente, allora risposta vuota `IN_PROGRESS` (ramo senza specifica: valutare 404).
- **Testbook**: scoperta.

#### US-E09-04 · Dashboard KPI con storico dimostrativo
*Come* direttore marketing, *voglio* KPI e serie storiche piene anche a demo appena accesa, *così che* valuti il programma in un colpo d'occhio.
- **Tocca**: F-INS-03, F-INS-04, F-WAL-09 · BO-01 · `GET /v1/kpi/overview|timeseries|breakdown`, wallet `/v1/liability`, `/v1/tiers/distribution` · INS-04, INS-07.
- **Criteri**:
  1. Dato un reset, allora serie di 90 giorni non vuote con `synthetic=true` e legenda «storico dimostrativo» (insight §7).
  2. Dato un evento duplicato sul topic, allora una sola riga nell'event store e metriche non raddoppiate (insight §7).
  3. Dato un acquisto reale oggi, allora si somma al dato sintetico del giorno.
- **Testbook**: scoperta.

#### US-E09-05 · Linea del tempo del membro ⛔
*Come* operatore CARE, *voglio* vedere nella Scheda 360° gli eventi del membro raggruppati per tracciato, *così che* ricostruisca la sua storia recente.
- **Tocca**: F-MBR-02 · BO-03 (scheda `timeline`) · insight `GET /v1/members/{memberId}/timeline` (⛔ assente, INS-08).
- **Criteri**: ⛔ Dato Giulia dopo `SCN-TIER-UP`, allora la scheda mostra un gruppo per tracciato: oggi l'endpoint non esiste.
- **Testbook**: scoperta.

#### US-E09-06 · Conservazione e pulizia dei dati
*Come* responsabile dei costi, *voglio* che log e tabelle tecniche si puliscano da sole, *così che* il database gratuito non si riempia (≤ 300 MB).
- **Tocca**: RNF-07 · INS-06, CMN-11, ENG-23, CMP-29, OutboxCleanup.
- **Criteri**:
  1. Dato eventi più vecchi di 14 giorni, allora rimossi dall'event store; audit oltre 180 gg rimosso; outbox pubblicati oltre 24 h rimossi.
  2. ⛔ Dato `processed_event` > 14 gg, `inbound_event` > 7 gg (o > 20 000 righe) ed `evaluation_log` > 30 gg, allora la specifica chiede la pulizia: oggi nessun job.
- **Testbook**: scoperta.

#### US-E09-07 · Cercare eventi
*Come* ANALYST, *voglio* cercare eventi per tipo, membro, correlazione, testo e periodo, *così che* indaghi un'anomalia.
- **Tocca**: F-INS-01 · BO-24 (dettaglio), BO-25 · `GET /v1/events`, `/{eventId}` · INS-08.
- **Criteri**: ✗ Dato `from` non valido, allora 400; evento inesistente → 404; ricerca `q=ORD-88213` trova l'azione e i suoi figli.
- **Testbook**: scoperta.

### E10 — Portale del membro (percorsi end-to-end)

#### US-E10-01 · Home: quanto ho, a che punto sono, cosa posso fare
*Come* membro, *voglio* vedere tessera, saldo, progresso di livello, scadenze, contenuti e azioni rapide, *così che* in un colpo d'occhio sappia cosa fare.
- **Tocca**: F-WAL-01, F-TIER-02, F-WAL-06, F-CNT-01/02 · PT-01 · WAL-18, ENG-01…04, WEB-07.
- **Criteri**:
  1. Dato Giulia, allora «Ti mancano 120 punti status per GOLD»; Francesca «Hai raggiunto il livello più alto».
  2. Dato Chiara, allora «1.900 punti scadono il …» con link a PT-03; Anna nessun avviso.
  3. Dato un membro con giocate, allora pallino su *Gioca* e numero nell'azione rapida.
  4. ⛔ Dato ottobre e un GOLD sotto soglia, allora avviso di mantenimento (dipende da `keepWarning`, US-E04-14).
- **Testbook**: TB-WEB (da coprire).

#### US-E10-02 · Il saldo non mente
*Come* membro, *voglio* vedere «in arrivo…» subito dopo un'azione e il saldo cambiare solo quando i punti sono davvero accreditati, *così che* non veda numeri falsi.
- **Tocca**: docs/07 §7, docs/09 §2 · PT-01, PT-07, PT-14 · WEB-07.
- **Criteri**:
  1. Dato un acquisto da PT-14, allora compare «in arrivo…», poi il saldo sale con count-up; nessun aggiornamento ottimistico (docs/12 M1).
  2. Dato 20 s senza fatto atteso, allora «Ci sta mettendo più del solito» con collegamento al tracciato.
  3. Dato una voce DLQ sullo stesso `correlationId`, allora errore con la causa.
- **Testbook**: TB-WEB (da coprire).

#### US-E10-03 · Percorso: acquisto → punti → nuovo livello → bonus → notifica
*Come* Giulia, *voglio* che il mio acquisto mi porti a GOLD e mi dia il bonus e la notifica, *così che* senta il valore del programma.
- **Contesto reale**: `SCN-TIER-UP` (acquisto 130 € l'ultimo giorno feriale alle 10:30).
- **Tocca**: ING (US-E01-01) → CMP (US-E03-04) → WAL (US-E04-01, US-E04-03) → ING ponte (US-E01-09) → CMP `CMP-TIER-UP-BONUS` → WAL → GAM (`ACH-3-PURCHASES-MONTH`, US-E06-13) → ENG (MSG-TIER-UP, MSG-POINTS-EARNED) → PT-01/PT-12 · E2E n. 1 di docs/09 §4.
- **Criteri**:
  1. Dato l'acquisto, allora +162 PTS e +130 STS, `tier.upgraded` a GOLD, azione interna `lhhop=1`, +500 PTS di bonus, obiettivo del mese completato con badge e bonus, messaggi in PT-12 — tutto in **un** tracciato.
  2. Dato il portale aperto come Giulia, allora la tessera si capovolge su GOLD e il saldo fa count-up.
  3. Dato lo scenario rieseguito lo stesso giorno, allora `CMP-PURCHASE-BASE` conta il limite 3/giorno e nessun secondo tier-up.
- **Testbook**: scoperta (serve TB-E2E; i singoli passi sono in TB-ING/CMP/WAL/GAM/ENG).

#### US-E10-04 · Percorso: premio con coupon
*Come* Davide, *voglio* richiedere un buono e vederne subito il codice, *così che* lo usi.
- **Tocca**: PT-03 → PT-04 → reward (US-E05-05, US-E05-06) → wallet (US-E04-07) → PT-13 · E2E n. 2 di docs/09 §4.
- **Criteri**:
  1. Dato `RWD-COFFEE-5`, allora foglio con saldo prima → dopo, attesa, codice in grande con scadenza, coupon in PT-13, saldo diminuito.
  2. Dato BO-25, allora il tracciato della saga è leggibile: richiesta → spesa → conferma → coupon → messaggio (docs/12 M4).
- **Testbook**: scoperta (TB-E2E).

#### US-E10-05 · Percorso: premio fisico spedito
*Come* Sofia, *voglio* richiedere la borraccia con l'indirizzo e sapere quando è spedita, *così che* la aspetti.
- **Tocca**: PT-04 (form spedizione) → reward (CONFIRMED) → BO-13 CARE (US-E05-10) → MSG-REWARD-READY → PT-13 «spedita».
- **Criteri**:
  1. ✗ Dato la richiesta senza indirizzo, allora errore sul foglio (`SHIPPING_REQUIRED`).
  2. Dato la richiesta, allora «Ti avviseremo alla spedizione»; dopo l'evasione di CARE, notifica e stato «spedita».
- **Testbook**: scoperta (TB-E2E).

#### US-E10-06 · Percorso: punti non sufficienti
*Come* Anna, *voglio* sapere perché la richiesta non è andata, senza addebiti, *così che* non mi senta presa in giro.
- **Tocca**: PT-04 (blocco preventivo «Ti mancano N punti», WEB-08) → richiesta via API → `wallet.spend.rejected` → `REJECTED` → MSG-REWARD-REJECTED → PT-13 «Punti non sufficienti».
- **Criteri**:
  1. Dato Anna su `RWD-COFFEE-5`, allora la barra d'azione mostra «Ti mancano 400 punti» e non consente la richiesta.
  2. Dato la richiesta forzata via API, allora 202 e poi `REJECTED (INSUFFICIENT_BALANCE)`, stock invariato, messaggio.
- **Testbook**: scoperta (TB-E2E); il blocco UI in TB-WEB.

#### US-E10-07 · Percorso: vincita garantita alla ruota
*Come* Matteo, *voglio* girare la ruota, vincere e vedere arrivare i punti, *così che* il gioco sia emozionante.
- **Tocca**: BO-14 *Pianta un istante* (US-E06-09) → PT-06 *Gira* → WIN → `WinCard` → ponte → `CMP-IW-PRIZE-POINTS` → wallet → PT-01 · E2E n. 3 di docs/09 §4.
- **Criteri**:
  1. Dato l'istante piantato, allora la ruota si ferma sullo spicchio vinto, coriandoli (salvo `prefers-reduced-motion`), card vincita, «I punti stanno arrivando…», poi saldo +50.
  2. Dato una seconda giocata, allora LOSE con «riprova domani» e giocate rimaste.
- **Testbook**: scoperta (TB-E2E); animazione e stati in TB-WEB.

#### US-E10-08 · Iscrizione dal portale
*Come* visitatore invitato da un amico, *voglio* iscrivermi con pochi campi e ricevere subito il benvenuto, *così che* inizi a partecipare.
- **Tocca**: F-MBR-06, F-REF-01 · PT-08 `/portal/join?ref=`, HUB-01 · `POST /v1/members` · MBR-01, MBR-05, WEB-17 · `CMP-WELCOME`, `POP-WELCOME`.
- **Criteri**:
  1. Dato il form valido, allora il nuovo membro diventa la persona attiva, PT-01 con pop-up di benvenuto e «+100 punti in arrivo…».
  2. ✗ Dato un codice amico non valido, allora errore sul campo e nessun membro creato; e-mail già usata → errore sul campo.
- **Testbook**: TB-GOV (API) · TB-WEB (form).

#### US-E10-09 · Completare il profilo
*Come* membro, *voglio* completare il profilo e ricevere il premio una volta, *così che* sia ricompensato per i dati che condivido.
- **Tocca**: F-MBR-07 · PT-08 · `PATCH /v1/portal/members/{id}` · MBR-02, MBR-08 · `CMP-PROFILE` · Q-63.
- **Criteri**:
  1. Dato Anna che inserisce la città (ultimo campo mancante), allora `member.profile.completed`; se `SCN-ONBOARDING` non ha già pagato, +150 PTS.
  2. Dato un secondo salvataggio completo, allora nessun secondo fatto.
  3. Dato il profilo, allora indicatore di completezza con i campi mancanti.
- **Testbook**: TB-GOV (API) · TB-WEB.

#### US-E10-10 · Attività e scomposizione dei movimenti
*Come* membro, *voglio* capire ogni movimento («130 punti base × 1,25 SILVER = 162»), *così che* mi fidi del conto.
- **Tocca**: F-WAL-02, F-TIER-03 · PT-07 · `GET /v1/portal/wallets/{id}/activity` · WAL-21.
- **Criteri**: Dato l'acquisto di Marco, allora «Acquisto online», +162 verde, dettaglio con scomposizione e scadenza del lotto; filtri guadagnati/spesi/scaduti/status.
- **Testbook**: TB-WEB (da coprire).

#### US-E10-11 · Portale di un membro sospeso
*Come* membro sospeso, *voglio* poter consultare ma sapere che non posso accumulare o richiedere, *così che* capisca la mia situazione.
- **Tocca**: docs/09 §2 · PT-* · US-E02-04.
- **Criteri**: Dato Roberto, allora banda «Il tuo profilo è sospeso…», pulsanti di richiesta e gioco disabilitati; MBR-000012 non selezionabile.
- **Testbook**: TB-WEB (da coprire).

#### US-E10-12 · Un servizio che dorme non rompe il portale
*Come* membro, *voglio* che il portale resti usabile anche se un pezzo sta ripartendo, *così che* non veda una pagina d'errore.
- **Tocca**: docs/07 §6, docs/09 §2 · WEB-03 · RNF-06.
- **Criteri**:
  1. Dato wallet fermo, allora PT-01 mostra la sezione saldo *degraded* (ultimo saldo noto «aggiornato alle …») e il resto funziona; al riavvio l'arretrato è elaborato e il saldo è corretto (docs/12 M1).
  2. Dato reward fermo, allora solo PT-03 degrada con «Stiamo recuperando i tuoi premi…».
- **Testbook**: TB-WEB (da coprire).

#### US-E10-13 · Percorso: punti in scadenza
*Come* Chiara, *voglio* essere avvisata, e se non spendo vedere la scadenza nel conto e ricevere il messaggio, *così che* capisca cosa è successo.
- **Tocca**: PT-01 (avviso) → BO-30 preavvisi (US-E04-05) → PT-12 → BO-30 scadenze `asOf=+31d` (US-E04-04) → PT-07 riga rossa.
- **Criteri**: Dato la sequenza, allora avviso, messaggio di preavviso, `EXPIRE 1 900`, saldo nel portale 500.
- **Testbook**: scoperta (TB-E2E).

#### US-E10-14 · Percorso: cliente che diventa digitale
*Come* Marco, *voglio* attivare bolletta digitale e domiciliazione e ricevere punti, badge e bonus, e vedere contenuti diversi, *così che* il programma riconosca il mio passaggio.
- **Contesto reale**: `SCN-DIGITAL`.
- **Tocca**: ING → CMP (`CMP-EBILL`, `CMP-DIRECT-DEBIT`) → WAL → GAM (`ACH-DIGITAL`, badge) → ponte → `CMP-BADGE-BONUS` → MBR (etichette Q-80, segmento SEG-DIGITAL al ricalcolo) → ENG (contenuti per segmento).
- **Criteri**:
  1. Dato lo scenario, allora +300/+150 e +400/+200, badge «Zero carta», +100 PTS.
  2. Dato il ricalcolo dei segmenti, allora Marco entra in SEG-DIGITAL, esce da SEG-NOT-EBILL e vede «Grazie per essere digitale» al posto di «Passa alla bolletta digitale» (docs/12 M6).
  3. Dato lo scenario ripetuto, allora `LIMIT` su entrambe le campagne (1 / sempre) e nessun secondo badge.
- **Testbook**: scoperta (TB-E2E).

#### US-E10-15 · Percorso: porta un amico
*Come* Marco ed Elisa, *vogliamo* essere premiati entrambi al primo acquisto di Elisa, *così che* invitare convenga.
- **Tocca**: PT-11 (codice, link `/portal/join?ref=`) → US-E06-16 → US-E06-17 → PT-11 stato «premio ottenuto».
- **Criteri**: Dato `SCN-REFERRAL`, allora Marco +500/+250 STS, Elisa +200, messaggio a Marco (MSG-REFERRAL-DONE); il secondo acquisto di Elisa non genera altri referral.
- **Testbook**: scoperta (TB-E2E).

#### US-E10-16 · Percorso: primo giorno di un nuovo iscritto
*Come* Anna, *voglio* accumulare subito con login, profilo e primo acquisto e vedere la catena di premi, *così che* mi appassioni al programma.
- **Contesto reale**: `SCN-ONBOARDING`.
- **Tocca**: ING → CMP (`CMP-APP-DAILY`, `CMP-PROFILE`, `CMP-PURCHASE-BASE`) → GAM (`ACH-FIRST-PURCHASE` → `BDG-FIRST`) → ponte → `CMP-BADGE-BONUS` · Q-63.
- **Criteri**: Dato lo scenario, allora +5, +150, punti dell'acquisto, badge «Rompighiaccio» e +100 in **un solo tracciato** per il passo d'acquisto (docs/12 M5); il profilo di Anna resta incompleto in PT-08 (Q-63).
- **Testbook**: scoperta (TB-E2E).

### E11 — Demo e ambiente

#### US-E11-01 · Accendere la demo
*Come* presentatore, *voglio* accendere la demo da una pagina e vedere quando è pronta, *così che* non debba toccare i servizi.
- **Tocca**: F-DEMO-01 · HUB-01 · `/api/demo/wake`, `/api/demo/status` · WEB-04 · Q-133…Q-135 · S3.
- **Criteri**:
  1. Dato servizi spenti, allora ogni tessera `DOWN/SLEEPING` senza errori in pagina (docs/12 M0).
  2. Dato *Accendi la demo*, allora le tessere passano da WAKING a UP con polling 3 s; gli ingressi si attivano con ingestion, member, campaign, wallet UP; stato ignoto → ingressi bloccati.
  3. Dato Kafka `DOWN` da più di 2 minuti, allora il riquadro con le istruzioni di riaccensione; `SLEEPING` non conta.
  4. Dato l'online, allora «Accendi la demo» porta 6/10 verdi e `smoke.sh` passa (docs/12 M1).
- **Testbook**: TB-WEB (da coprire).

#### US-E11-02 · Scegliere chi sono
*Come* presentatore, *voglio* impersonare un ruolo del backoffice o un membro del portale in un clic, *così che* mostri cosa vede ciascuno.
- **Tocca**: F-DEMO-02 · selettore persona (BO), PT-14 · `GET /v1/demo/personas` · WEB-01, WEB-18 · Q-136.
- **Criteri**:
  1. Dato il cambio persona, allora cookie aggiornato, cache invalidata, permessi ricalcolati (MARKETING non vede la Console demo; LEGAL vede gli istanti — Playwright docs/12 §4).
  2. Dato un cookie illeggibile, allora default `marta.admin` / MBR-000002.
  3. Dato il seed, allora 11 membri selezionabili (MBR-000012 escluso, Q-136).
  4. Dato le personas mostrate, allora nomi e username sono quelli di docs/10 (§2.1).
- **Testbook**: TB-WEB (da coprire).

#### US-E11-03 · Keep-alive gentile
*Come* titolare dell'account gratuito, *voglio* che i servizi restino svegli solo finché qualcuno guarda la demo, *così che* resti a costo zero.
- **Tocca**: F-DEMO-07 · `useKeepAlive` · WEB-05 · Q-132 · ADR-014.
- **Criteri**: Dato la scheda visibile, allora `wake` ogni 4 min; scheda nascosta → nessun `wake`; 45 min senza interazione → fermo; nuova interazione → riparte senza sveglia immediata.
- **Testbook**: TB-WEB (da coprire).

#### US-E11-04 · Proxy verso i servizi
*Come* browser, *voglio* parlare con un solo indirizzo che aggiunge identità e correlazione e mi dice se un servizio dorme, *così che* l'interfaccia degradi con garbo.
- **Tocca**: docs/07 §3 · `app/api/lh/[service]/[...path]` · WEB-03.
- **Criteri**: Dato 502/503/504 o rete giù, allora 503 `{type: SERVICE_ASLEEP, service}`; dato 25 s senza risposta, allora timeout; ogni chiamata porta `X-LH-Actor` dal cookie e `X-Correlation-Id`.
- **Testbook**: TB-WEB (da coprire).

#### US-E11-05 · Simulatore di eventi
*Come* presentatore, *voglio* inviare un'azione vera per un membro e vederne il tracciato dal vivo, *così che* il pubblico veda la reazione del sistema.
- **Tocca**: F-DEMO-03 · BO-28, PT-14 · `POST /v1/demo/simulator/fire` · ING-20, ING-22.
- **Decisioni**: ripetizioni 1…20 · fonte `simulator` · `data` dal `sample_data` · passa dalla pipeline normale (dedup, stato membro) · ruoli: tutti tranne ANALYST.
- **Criteri**:
  1. Dato *Acquisto 130 € (tier-up di Giulia)*, allora il tracciato si popola in tempo reale con il tier-up.
  2. Dato `count=50`, allora 20 eventi; dato ANALYST, allora 403.
  3. Dato la scorciatoia «Evento duplicato», allora il secondo è `DUPLICATE`.
  4. Dato un evento dal simulatore, allora la riga BO-26 ha origine `SIMULATOR`.
- **Testbook**: TB-ING (da coprire) · TB-WEB.

#### US-E11-06 · Scenari guidati
*Come* presentatore, *voglio* lanciare con un clic una storia (tier-up, onboarding, referral, eventi errati) e seguirne i passi, *così che* la demo sia ripetibile.
- **Tocca**: F-DEMO-04 · BO-29 · `GET /v1/demo/scenarios`, `POST …/run`, `GET /v1/demo/scenario-runs/{id}` · ING-21 · Q-63, Q-129, Q-130.
- **Decisioni**: esecuzione asincrona (202 `runId`), ritardo per passo ≤ 10 s, `at` relativo a oggi, `expect` per i passi negativi, `{run}` nell'id · esito `DONE` o `FAILED` (eccezione) · scenario inesistente 404.
- **Criteri**:
  1. Dato `SCN-WEEKEND-BURST`, allora 12 azioni, avanzamento visibile, 12 valutazioni nell'event store (docs/12 M2).
  2. Dato `SCN-BAD-EVENT`, allora ogni passo ha l'esito atteso e l'esecuzione è `DONE`.
  3. Dato un passo con `at` malformato, allora esecuzione `FAILED` coi passi già fatti.
  4. Dato gli scenari del seed non descritti in docs/10 (`SCN-WEEKEND-ANNA`, `SCN-MIXED-DAY`, `SCN-REJECTS`), allora da documentare (seed senza specifica).
- **Testbook**: TB-ING (da coprire) · TB-WEB.

#### US-E11-07 · Riportare la demo allo stato iniziale
*Come* presentatore, *voglio* ripristinare tutti i dati con un comando, *così che* ogni demo parta dalla stessa storia.
- **Tocca**: F-DEMO-05 · BO-30 · `POST /v1/demo/reset` (ogni servizio) · CMN-13, CMN-14, INS-07, MBR-16, WEB-13 · ⛔ `GET /v1/demo/info`.
- **Decisioni**: solo ADMIN · conferma digitando `RESET` · insight per primo, poi gli altri in parallelo · seed con date relative, semi fissi · audit `RESET`.
- **Criteri**:
  1. Dato il reset, allora i 12 membri tornano allo stato di docs/10 e `check-seed` è verde (docs/12 M1).
  2. Dato due reset consecutivi, allora stesso stato (stessi codici coupon, stessi istanti).
  3. ✗ Dato MARKETING, allora 403; senza `RESET` digitato, pulsante disabilitato.
  4. ⛔ Dato BO-30, allora versione, profili, conteggi e ultimo reset per servizio da `/v1/demo/info`: endpoint assente.
- **Testbook**: scoperta (reset e seed non hanno dominio; la conferma UI in TB-WEB).

#### US-E11-08 · Macchina del tempo
*Come* presentatore, *voglio* lanciare i job con una data di riferimento, *così che* mostri in un minuto cosa succede tra un mese.
- **Tocca**: F-DEMO-06 · BO-30 · `/v1/demo/jobs/*` (wallet ×3, reward ×2, gamification, member, engagement) · WAL-04…06, RWD-05, RWD-17, GAM-10, MBR-16, ENG-23.
- **Criteri**: Dato `asOf`=+31 gg sulle scadenze, allora «scaduti 1.900 PTS per 1 membro»; ogni job solo ADMIN (403 altrimenti); ogni job mostra l'esito.
- **Testbook**: TB-WAL / TB-RWD / TB-GAM / TB-GOV (un job per dominio, da coprire).

#### US-E11-09 · Prova di fumo
*Come* CI e come presentatore, *voglio* uno script che provi che la pipeline è viva, *così che* sappia che la demo funziona prima di iniziare.
- **Tocca**: `scripts/smoke.sh` · `SCN-SMOKE` · docs/12 §4.
- **Criteri**: Dato servizi svegli, allora +5 PTS a Marco entro 15 s; se il limite giornaliero è già scattato, in demo basta `campaign.evaluated` (scattata o `LIMIT`), in CI si fa prima il reset.
- **Testbook**: scoperta (TB-E2E).

### E12 — Affidabilità e contratti

#### US-E12-01 · Nessun evento perso
*Come* azienda, *voglio* che ogni scrittura che produce eventi li pubblichi prima o poi anche se Kafka è giù, *così che* nessun punto vada perso.
- **Tocca**: RNF-05, ADR-008 · CMN-11, CMP-17.
- **Criteri**: Dato Kafka irraggiungibile durante una scrittura, allora la riga resta in outbox ed è pubblicata al ritorno (docs/12 M0).
- **Testbook**: scoperta (lh-common non ha dominio TB).

#### US-E12-02 · Rielaborare non cambia lo stato
*Come* azienda, *voglio* che la riconsegna di un evento a qualunque consumer non produca effetti doppi, *così che* riavvii e ritentativi siano sicuri.
- **Tocca**: RNF-03 · CMN-08, CMN-09 e i rami di idempotenza di ogni servizio (ING-14, CMP-15, WAL-01, WAL-08, WAL-10, RWD-02, RWD-16, GAM-13, GAM-14, ENG-10, INS-04).
- **Criteri**: Dato ogni handler, allora doppio invio = stesso stato (docs/06 §9); un `type` non gestito è ignorato senza `processed_event`.
- **Testbook**: TB-ING/CMP/WAL/RWD/GAM/ENG (una riga «doppio invio» per consumer, da coprire).

#### US-E12-03 · Messaggi velenosi in DLQ
*Come* azienda, *voglio* che un messaggio che fa fallire un consumer venga ritentato se ha senso e poi messo da parte, *così che* non blocchi gli altri.
- **Tocca**: docs/04 §5 · CMN-10 · §5.1 bis.
- **Criteri**: Dato un consumer che lancia sempre eccezione, allora dopo 3 tentativi il messaggio è su `lh.dlq.v1` con `errorCode` e il consumer prosegue (docs/12 M0); un errore non ritentabile va in DLQ al primo tentativo.
- **Testbook**: scoperta.

#### US-E12-04 · Ordine per membro e concorrenza
*Come* azienda, *voglio* che gli eventi di uno stesso membro siano elaborati in ordine e che le risorse condivise (saldo, stock, istanti, contatori) reggano la concorrenza, *così che* non ci siano saldi negativi o premi doppi.
- **Tocca**: RNF-04 · chiave Kafka = memberId · WAL (lock di riga), RWD-01 (`UPDATE … WHERE stock_remaining > 0`), GAM-03 (`SKIP LOCKED`), CMP-17 (⚠ contatori letti e poi incrementati).
- **Criteri**: Dato 20 giocate parallele, allora una vincita per istante; dato richieste concorrenti sull'ultimo pezzo, allora una sola PENDING; dato due azioni dello stesso membro in partizioni diverse (non deve accadere), allora i limiti potrebbero essere superati — da coprire come caso limite.
- **Testbook**: TB-WAL/RWD/GAM/CMP (da coprire).

#### US-E12-05 · Risveglio dopo il sonno
*Come* azienda, *voglio* che un servizio addormentato recuperi l'arretrato al risveglio, *così che* il free tier non costi dati.
- **Tocca**: RNF-06 · `auto.offset.reset=earliest` · Q-25.
- **Criteri**: Dato wallet fermo per 10 minuti con 5 accrediti in coda, allora al risveglio 5 movimenti; oltre 3 giorni (retention) il reset riallinea (Q-25).
- **Testbook**: scoperta.

#### US-E12-06 · Contratti evento stabili
*Come* sviluppatore di un servizio, *voglio* che gli eventi rispettino gli schemi e che i consumer tollerino campi nuovi, *così che* i servizi evolvano indipendentemente.
- **Tocca**: docs/05 §9 · `contracts/events/` · Q-53, Q-139.
- **Criteri**: Dato ogni esempio, allora valida contro lo schema; dato ogni produttore, allora l'evento prodotto valida; dato un campo sconosciuto, allora il consumer non fallisce.
- **Testbook**: scoperta (test di contratto fuori dai domini funzionali).

#### US-E12-07 · Errori comprensibili
*Come* frontend e come integratore, *voglio* errori RFC 9457 con `code` stabile e `detail` in italiano, *così che* li mostri all'utente e li gestisca nel codice.
- **Tocca**: docs/06 §2 · CMN-12 · §5.1.
- **Criteri**: Dato ogni codice di §5.1, allora `type` `urn:loyaltyhub:problem:<suffix>`, `status`, `code`, `detail` italiano; i 422 di campo hanno `errors[]` mappati sui campi del form (docs/07 §6); percorso inesistente → 404; eccezione imprevista → 500 `INTERNAL_ERROR` senza dettagli interni.
- **Testbook**: TB-ING/CMP/WAL/RWD/GAM/GOV/ENG (per i propri codici) · TB-WEB (stato *Error*/*Validation*).

#### US-E12-08 · Requisiti non funzionali della demo gratuita
*Come* titolare del progetto, *voglio* che ogni servizio stia in 512 MB, che la pipeline sia veloce e che l'interfaccia sia accessibile e responsive, *così che* la demo funzioni sul free tier e per tutti.
- **Tocca**: RNF-01, RNF-02, RNF-08, RNF-09, RNF-10.
- **Criteri**: RSS ≤ 450 MB dopo 2 min; azione → movimento p50 ≤ 3 s, p95 ≤ 8 s; contrasto AA, focus visibile, tastiera, `prefers-reduced-motion`; portale ≥ 360 px; log JSON con `eventId`, `correlationId`, `memberId`.
- **Testbook**: scoperta (non funzionale).

## 5. Foresta delle decisioni

Inventario dei punti di decisione **ricavato dal codice** (`services/*/src/main`, `libs/lh-common/src/main`, `web/lib`, `web/app/api`). Una riga = una foglia (esito); `↳` = altra foglia dello stesso nodo. Colonna *Regola*: riferimento di specifica, oppure **nessuna** (= *ramo senza specifica*). ⚠ = il ramo contraddice una fonte; ⛔ = regola di specifica senza codice (elencate anche in §5.12). Colonna *Testbook*: dominio che deve provare la foglia (`—` = nessun dominio lo prevede oggi).

### 5.0 Come è costruito (e come rifarlo)

| Criterio di esaustività | Estrazione | Dove finisce |
|---|---|---|
| (a) ogni `LhException.<kind>("CODE"` e ogni problema generico | `grep -rnoE 'LhException\.(validation\|conflict\|gone)\(\s*"[A-Z_]+"' services libs` + fabbriche generiche + `GlobalExceptionHandler` | §5.1 (un codice per riga, con stato HTTP) |
| (a') codici DLQ | `grep -rn 'NonRetryableEventException(' services`, `DlqRecords.errorCode` | §5.1 bis |
| (b) enum di esiti/stati/motivi | `grep -rn 'enum ' …/domain …/engine`, costanti `PENDING/ACTIVE/…` | righe «enum» di ogni tabella |
| (c) `switch`/`if` che cambiano l'esito in `domain/`, `engine/`, `application/`, `messaging/` | lettura dei servizi applicativi e dei gestori | nodi delle tabelle §5.2–5.11 |
| (d) guardie `@RequiresRole` e controlli di ruolo nel codice | `grep -rn -B4 '@RequiresRole'` + `ActorHolder.get().role()` | nodo «Guardie» di ogni servizio |
| (e) job pianificati | `grep -rn '@Scheduled\|@ConditionalOnProperty'` | nodo «Job» di ogni servizio e §2.4 |

### 5.1 Catalogo dei codici d'errore HTTP (RFC 9457)

| Codice | HTTP | Dove (servizio · classe) | Regola di specifica | Storie |
|---|---|---|---|---|
| `BAD_REQUEST` | 400 | tutti (envelope, parametri, JSON, filtri) | docs/06 §2 | US-E01-02, US-E01-08, US-E03-11, US-E12-07 |
| `FORBIDDEN_ROLE` | 403 | lh-common `RequiresRoleInterceptor`, `GovernedTransitions`; ingestion `EventsController`, `EventTypeService`; wallet `EditionsController` | docs/06 §3, docs/08 §2 | US-E08-04, US-E08-06 |
| `NOT_FOUND` | 404 | tutti | docs/06 §2 | US-E12-07 (+ ogni storia di gestione) |
| `VALIDATION` | 422 | lh-common (bean validation) | docs/06 §2 | US-E12-07 |
| `DEPENDENCY_UNAVAILABLE` | 503 | insight `IngestionClient` | docs/06 §2 | US-E08-11 |
| `INTERNAL_ERROR` | 500 | lh-common `GlobalExceptionHandler` | nessuna | US-E12-07 |
| `ACHIEVEMENT_INVALID` | 422 | gamification `AchievementAdminService` | F-ACH-01 | US-E06-12 |
| `APPROVAL_REQUIRED` | 409 | lh-common `ApprovalStateMachine` | docs/03 §3.6, docs/06 §7 | US-E08-03 |
| `ATTRIBUTE_DEFINITION_INVALID` | 422 | member `AttributeService` | F-MBR-03 | US-E02-07 |
| `ATTRIBUTE_IN_USE` | 409 | member `AttributeService` | Q-93 | US-E02-07 |
| `BADGE_INVALID` | 422 | gamification `AchievementAdminService` | F-ACH-03 | US-E06-12 |
| `BAND_INVALID` | 422 | reward `CatalogAdminService` | F-RWD-02 | US-E05-02 |
| `BAND_IN_USE` | 409 | reward `CatalogAdminService` | reward §3 | US-E05-02 |
| `BAND_THRESHOLD_DUPLICATE` | 422 | reward `CatalogAdminService` | reward §3 | US-E05-02 |
| `CAMPAIGN_INVALID` | 422 | campaign `CampaignAdminService` | campaign §5 | US-E03-01, US-E03-02 |
| `CAMPAIGN_LIVE_LOCKED` | 409 | campaign `CampaignAdminService` | campaign §3, docs/03 §3.6, Q-51 | US-E03-02 |
| `CAMPAIGN_NOT_EDITABLE` | 409 | campaign `CampaignAdminService` | Q-51 | US-E03-02 |
| `CATEGORY_INVALID` | 422 | reward `CatalogAdminService` | F-RWD-01 | US-E05-03 |
| `CODE_IMMUTABLE` | 409 | campaign, engagement (contenuti, regole, template, webhook), gamification (obiettivi, concorsi, classifiche), reward | docs/06 §2, Q-51 | US-E03-02, US-E05-01, US-E06-01, US-E06-12, US-E06-15, US-E07-01, US-E07-07, US-E07-08, US-E08-08 |
| `CODE_TAKEN` | 409 | campaign, engagement ×4, gamification ×4, member (segmenti), reward (premi, pool) | docs/06 §2 | US-E03-01, US-E02-08, US-E05-01, US-E05-13, US-E06-01, US-E06-12, US-E06-15, US-E07-01, US-E07-07, US-E07-08, US-E08-08 |
| `CONFIRM_MISMATCH` | 422 | member `MemberService` | docs/08 §3.5, BO-03 | US-E02-05 |
| `CONTENT_INVALID` | 422 | engagement `ContentService` | F-CNT-01 | US-E07-01 |
| `CONTENT_LIVE_LOCKED` | 409 | engagement `ContentService` | docs/03 §3.6, docs/06 §2 | US-E07-01 |
| `CONTENT_NOT_EDITABLE` | 409 | engagement `ContentService` | nessuna | US-E07-01 |
| `CONTEST_INVALID` | 422 | gamification `ContestAdminService` | F-IW-01, F-IW-02 | US-E06-01, US-E06-02 |
| `CONTEST_LIVE_LOCKED` | 409 | gamification `ContestAdminService` | gamification §3 | US-E06-01 |
| `CONTEST_NOT_EDITABLE` | 409 | gamification `ContestAdminService` | nessuna | US-E06-01 |
| `CONTEST_NOT_LIVE` | 422 (giocata) · 409 (pianta istante) | gamification `PlayService`, `ContestAdminService` | gamification §3 | US-E06-05, US-E06-09 |
| `COUPON_ALREADY_USED` | 409 | reward `CouponService` | reward §3 | US-E05-14, US-E05-15 |
| `COUPON_COUNT_INVALID` | 422 | reward `CouponService` | reward §3 (≤ 5000) | US-E05-13 |
| `COUPON_EXPIRED` | 410 | reward `CouponService` | reward §3 | US-E05-14 |
| `COUPON_IMPORT_EMPTY` | 422 | reward `CouponService` | nessuna | US-E05-13 |
| `COUPON_NOT_ISSUED` | 409 | reward `CouponService` | nessuna | US-E05-14 |
| `COUPON_POOL_EMPTY` | 409 | reward `RedemptionService.retryFulfilment` | reward §5 | US-E05-12 |
| `COUPON_PREFIX_INVALID` | 422 | reward `CouponService` | F-CPN-01 | US-E05-13 |
| `COUPON_VALIDITY_INVALID` | 422 | reward `CouponService` | nessuna | US-E05-13 |
| `COUPON_VOID` | 409 | reward `CouponService` | nessuna | US-E05-14, US-E05-15 |
| `CURRENCY_NOT_ADJUSTABLE` | 422 | wallet `WalletService` | Q-46 | US-E04-06 |
| `DAILY_LIMIT_REACHED` | 422 | gamification `PlayService` | gamification §3 | US-E06-05 |
| `DELIVERY_BUSY` | 409 | engagement `WebhookService` | nessuna | US-E08-10 |
| `DELIVERY_NOT_APPLICABLE` | 409 | gamification `ContestAdminService` | F-IW-07 | US-E06-08 |
| `DELIVERY_NOT_RETRYABLE` | 409 | engagement `WebhookService` | Q-98 | US-E08-10 |
| `DELIVERY_STATUS_INVALID` | 422 | gamification `ContestAdminService` | gamification §3 | US-E06-08 |
| `DLQ_NOT_OPEN` | 409 | insight `DlqService` | Q-107 | US-E08-11, US-E08-12 |
| `EDITION_ALREADY_CLOSED` | 422 | wallet `EditionCloseBatchService` | wallet §3 | US-E04-13 |
| `EDITION_DATES_INVALID` | 422 | wallet `EditionService` | docs/03 §4.4 | US-E04-11 |
| `EDITION_EXISTS` | 409 | wallet `EditionService` | docs/06 §2 (codice duplicato) | US-E04-11 |
| `EDITION_FIELD_REQUIRED` | 422 | wallet `EditionService` | nessuna | US-E04-11 |
| `EDITION_NOT_ACTIVE` | 422 | wallet `EditionCloseBatchService` | docs/03 §4.4 | US-E04-13 |
| `EDITION_OVERLAP` | 422 | wallet `EditionService` | wallet §3, docs/03 §4.4 | US-E04-11 |
| `EMAIL_TAKEN` | 409 | member `MemberService` | docs/03 §2 | US-E02-01, US-E02-02, US-E10-08 |
| `ENABLED_REQUIRED` | 422 | ingestion `InternalMappingsController` | nessuna | US-E01-10 |
| `EVENT_TYPE_EXISTS` | 409 | ingestion `EventTypeService` | Q-89 | US-E01-12 |
| `EVENT_TYPE_IMMUTABLE_FIELD` | 422 | ingestion `EventTypeService` | ingestion §3 | US-E01-12 |
| `EVENT_TYPE_INVALID` | 422 | ingestion `EventTypeService` | Q-89 | US-E01-12 |
| `EVENT_TYPE_SYSTEM_LOCKED` | 422 | ingestion `EventTypeService` | ingestion §3 | US-E01-12 |
| `INBOUND_NOT_RETRYABLE` | 409 | ingestion `InboundResolutionService` | ingestion §3, Q-114 | US-E01-07 |
| `INBOUND_NOT_UNMATCHED` | 409 | ingestion `InboundResolutionService` | ingestion §3 | US-E01-07 |
| `INSTANTS_LOCKED` | 409 | gamification `ContestAdminService` | gamification §3, docs/03 §6 | US-E06-02 |
| `INSTANTS_NOT_GENERATED` | 422 | gamification `ContestAdminService` | gamification §3 | US-E06-04 |
| `INSUFFICIENT_BALANCE` | 422 | wallet `WalletService.adjustBalance` | wallet §3, docs/03 §4.2 | US-E04-06 |
| `INTERNAL_MAPPING_FORBIDDEN` | 422 | ingestion `InternalMappingsController` | docs/05 §7 | US-E01-10 |
| `INVALID_ACTION` | 422 | lh-common `GovernedTransitions` | nessuna | US-E08-03 |
| `INVALID_AMOUNT` | 422 | wallet `WalletService` | nessuna | US-E04-06 |
| `INVALID_CODE` | 422 | member `SegmentService` | docs/06 §2 (formato codici) | US-E02-08 |
| `INVALID_CRITERIA` | 422 | member `SegmentService` | F-SEG-02 | US-E02-08 |
| `INVALID_DIRECTION` | 422 | wallet `WalletService` | wallet §3 | US-E04-06 |
| `INVALID_EXPIRY_POLICY` | 422 | wallet `CurrencyService` | docs/03 §4.1 | US-E04-10 |
| `INVALID_REASON` | 422 | wallet `WalletService` | Q-45 | US-E04-06 |
| `INVALID_STATUS` | 422 | member `SegmentService` | nessuna | US-E02-10 |
| `INVALID_TRANSITION` | 409 | lh-common `ApprovalStateMachine`, engagement `ContentService` | docs/06 §2 | US-E08-03, US-E07-02 |
| `INVALID_TYPE` | 422 | member `SegmentService` | F-SEG-01/02 | US-E02-08 |
| `KIND_IMMUTABLE` | 409 | engagement `ContentService` | nessuna | US-E07-01 |
| `LEADERBOARD_INVALID` | 422 | gamification `LeaderboardService` | F-LDB-01 | US-E06-15 |
| `LEADERBOARD_LOCKED` | 409 | gamification `LeaderboardService` | nessuna | US-E06-15 |
| `MEMBER_ANONYMIZED` | 409 | member `MemberService` ×2, wallet `WalletService` | F-MBR-05, Q-127 | US-E02-02, US-E02-04, US-E02-05, US-E04-06 |
| `MEMBER_INVALID` | 422 | member `MemberService` | F-MBR-03 | US-E02-02, US-E02-07 |
| `MEMBER_LIMIT_REACHED` | 422 | reward `RedemptionService` | reward §3 | US-E05-05 |
| `MEMBER_NOT_ACTIVE` | 422 | reward `RedemptionService`, gamification `PlayService`, ingestion `InboundResolutionService` | docs/03 §2, reward §3, gamification §3 | US-E05-05, US-E06-05, US-E01-07 |
| `MEMBER_NOT_FOUND` | 422 | ingestion `InboundResolutionService`, member `SegmentService` | nessuna | US-E01-07, US-E02-09 |
| `MEMBER_REQUIRED` | 422 | ingestion `InboundResolutionService`, gamification `PlayService` | nessuna | US-E01-07, US-E06-05 |
| `NAME_REQUIRED` | 422 | member `SegmentService` | nessuna | US-E02-08 |
| `NOTE_REQUIRED` | 422 | reward `RedemptionService.fulfilManually`, insight `DlqService.discard` | reward §3, Q-110 | US-E05-10, US-E08-12 |
| `NOTE_TOO_SHORT` | 422 | wallet `WalletService` | wallet §3, docs/03 §4.2 | US-E04-06 |
| `NOT_REPROCESSABLE` | 409 | insight `DlqService` | insight §5 | US-E08-11 |
| `NO_OPEN_INSTANT` | 422 | gamification `ContestAdminService` | nessuna | US-E06-09 |
| `NO_PLAYS_AVAILABLE` | 422 | gamification `PlayService` | gamification §3 | US-E06-05 |
| `PRIZE_NOT_FOUND` | 422 | gamification `ContestAdminService` | nessuna | US-E06-09 |
| `REASON_REQUIRED` | 422 | reward `RedemptionService.cancelWithRefund` | reward §3 (`{reason}`) | US-E05-11 |
| `REDEMPTION_NOT_CANCELLABLE` | 409 | reward `RedemptionService` ×2 | docs/03 §5 | US-E05-09, US-E05-11 |
| `REDEMPTION_NOT_FULFILLABLE` | 409 | reward `RedemptionService` | reward §3 | US-E05-10 |
| `REDEMPTION_NOT_RETRYABLE` | 409 | reward `RedemptionService` | nessuna | US-E05-12 |
| `REFERRAL_CODE_EXHAUSTED` | 409 | member `MemberService` | nessuna | US-E06-16 |
| `REFERRAL_CODE_INVALID` | 422 | member `MemberService` ×2 | member §5, Q-61 | US-E06-16, US-E10-08 |
| `REJECT_COMMENT_REQUIRED` | 422 | lh-common `ApprovalStateMachine` | docs/03 §3.6, docs/12 M7 | US-E08-02 |
| `REPROCESS_REJECTED` | 409 | insight `DlqService` | nessuna | US-E08-11 |
| `REWARD_INVALID` | 422 | reward `CatalogAdminService` | F-RWD-01, F-RWD-03 | US-E05-01 |
| `REWARD_LIVE_LOCKED` | 409 | reward `CatalogAdminService` | reward §3 | US-E05-01 |
| `REWARD_NOT_AVAILABLE` | 422 | reward `RedemptionService` | reward §3 | US-E05-05 |
| `REWARD_NOT_EDITABLE` | 409 | reward `CatalogAdminService` | reward §3 | US-E05-01 |
| `REWARD_SOLD_OUT` | 422 | reward `RedemptionService` | reward §3, §5 | US-E05-05 |
| `RULE_INVALID` | 422 | engagement `RuleAdminService` | F-MSG-01 | US-E07-08 |
| `SEGMENT_ARCHIVED` | 409 | member `SegmentService` | Q-88 | US-E02-10 |
| `SEGMENT_IMMUTABLE_FIELD` | 409 | member `SegmentService` | Q-88 | US-E02-10 |
| `SEGMENT_NOT_STATIC` | 409 | member `SegmentService` | member §3 | US-E02-09 |
| `SHIPPING_REQUIRED` | 422 | reward `RedemptionService` | reward §3 | US-E05-05 |
| `SYSTEM_LOCKED` | 409 | campaign `CampaignAdminService` | campaign §3, F-CMP-12 | US-E03-15 |
| `TEMPLATE_INVALID` | 422 | engagement `TemplateAdminService` | F-MSG-02 | US-E07-07 |
| `THEME_CONTRAST_TOO_LOW` | 422 | engagement `ThemeService` | engagement §3, Q-79 | US-E07-12 |
| `THEME_INVALID` | 422 | engagement `ThemeService` | engagement §3 | US-E07-12 |
| `TIER_MULTIPLIER_INVALID` | 422 | wallet `TierAdminService` | nessuna | US-E04-09 |
| `TIER_NOT_ELIGIBLE` | 422 | reward `RedemptionService` | reward §3 | US-E05-05 |
| `TIER_THRESHOLDS_NOT_MONOTONIC` | 422 | wallet `TierAdminService` ×2 | wallet §3 | US-E04-09 |
| `VERSION_CONFLICT` | 409 | campaign, engagement ×5, gamification, member ×5, reward | docs/06 §4, Q-112 | US-E08-13 |
| `WEBHOOK_INVALID` | 422 | engagement `WebhookService` | engagement §5, Q-99 | US-E08-08 |

Codici **citati dalla specifica ma assenti dal codice**: `REFERRAL_SELF` (member §5; irraggiungibile, il codice nasce col membro), motivi di valutazione `NOT_LIVE` (F-CMP-09), `MEMBER_LIMIT_REACHED` ed `EXCLUSIVE_GROUP` (docs/12 M1/M3, Q-50: il codice usa `LIMIT`/`EXCLUSIVE`). Lato web: `SERVICE_ASLEEP` (proxy, non è un problem RFC 9457).

### 5.1 bis Codici d'errore della DLQ (`lh-error-code`)

| Codice | Chi | Ritentabile | Regola | Storie |
|---|---|---|---|---|
| `LOOP_GUARD` | ingestion ponte (`lhhop+1 > 3`) | no (1 tentativo) | docs/05 §7 | US-E01-09 |
| `DEMO_POISON` | campaign, solo profilo `demo` | no | docs/10 §8, Q-108 | US-E08-11, US-E09-03 |
| `INVALID_EFFECT` | reward, gamification ×2, engagement | no | nessuna | US-E05-16, US-E06-06, US-E06-14, US-E07-10 |
| `CONTEST_NOT_FOUND` | gamification `PlaysGrantHandler` | no | campaign §5 (errore «a valle in DLQ») | US-E06-06 |
| `BADGE_NOT_FOUND` | gamification `BadgeAwardHandler` | no | campaign §5 | US-E06-14 |
| `TEMPLATE_NOT_FOUND` | engagement `MessageSendHandler` | no | campaign §5 | US-E07-10 |
| `REWARD_NOT_FOUND` | reward `CouponIssueHandler` | no | campaign §5 | US-E05-16 |
| `COUPON_POOL_MISSING` | reward `CouponIssueHandler` | no | nessuna | US-E05-16 |
| `COUPON_POOL_EMPTY` | reward `CouponIssueHandler` | no | reward §5 | US-E05-16 |
| `<NomeClasseEccezione>` | qualunque consumer | sì (3 tentativi, 200 ms) | docs/04 §5, docs/12 M0 | US-E12-03 |

### 5.2 ingestion-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| ING-01 `IngestionService#validateForm` + `parseTime` (passo 1) | 400: `specversion` assente o ≠ `1.0` | ingestion §5.1, docs/05 §2 | US-E01-02 | TB-ING |
| ↳ | 400: `id`/`source`/`type`/`subject`/`time` assente o vuoto | ingestion §5.1 | US-E01-02 | TB-ING |
| ↳ | 400: `data` assente o `null` | ingestion §5.1 | US-E01-02 | TB-ING |
| ↳ | 400: `time` non RFC 3339 | ingestion §5.1 | US-E01-02 | TB-ING |
| ING-02 passo 2 fonte | `REJECTED/SOURCE_DISABLED`: fonte inesistente (anche `pos-legacy`, Q-129) | ingestion §5.2, Q-129 | US-E01-03 | TB-ING |
| ↳ | `REJECTED/SOURCE_DISABLED`: fonte disabilitata | F-ING-05 | US-E01-03 | TB-ING |
| ING-03 passo 3 tipo | `REJECTED/UNKNOWN_TYPE`: tipo inesistente o disabilitato | ingestion §5.3 | US-E01-03 | TB-ING |
| ↳ | `REJECTED/TYPE_NOT_ALLOWED`: `allowed_types` non vuoto e senza il tipo | ingestion §5.3 | US-E01-03 | TB-ING |
| ↳ | `allowed_types` vuoto ⇒ tutti i tipi ammessi | ingestion §2 | US-E01-03 | TB-ING |
| ING-04 passo 4 schema | `REJECTED/INVALID_DATA` con gli errori dello schema nel dettaglio | ingestion §5.4 | US-E01-03 | TB-ING |
| ↳ | tipo senza `data_schema` ⇒ nessuna validazione | nessuna | US-E01-03 | TB-ING |
| ING-05 passo 5 tempo | `REJECTED/INVALID_TIME`: `time` > adesso + 5 min | ingestion §5.5 | US-E01-03 | TB-ING |
| ↳ | `REJECTED/INVALID_TIME`: `time` < adesso − 30 giorni | ingestion §5.5 | US-E01-03 | TB-ING |
| ↳ | limiti esatti (+5 min, −30 gg) accettati (`isAfter`/`isBefore`) | nessuna (limite non specificato) | US-E01-03 | TB-ING |
| ING-06 passo 6 deduplica | `DUPLICATE` (riga salvata, nulla sul topic) se esiste un `ACCEPTED` con stessa fonte+id | F-ING-02 | US-E01-04 | TB-ING |
| ↳ | un precedente `REJECTED`/`UNMATCHED` con stessa fonte+id **non** fa duplicato | nessuna | US-E01-04 | TB-ING |
| ↳ | gara concorrente sull'insert ⇒ `DUPLICATE`, nessuna doppia pubblicazione | RNF-03 | US-E01-04 | TB-ING |
| ING-07 passo 7 membro | `member:<id>` / `external:<x>` / `email:<x>` (e-mail senza maiuscole) ⇒ indice | F-ING-03 | US-E01-01 | TB-ING |
| ↳ | subject senza prefisso o con prefisso sconosciuto ⇒ `UNMATCHED` | Q-255 | US-E01-05 | TB-ING |
| ↳ | `UNMATCHED` (parcheggiato): membro non trovato | F-ING-04 | US-E01-05 | TB-ING |
| ↳ | `REJECTED/MEMBER_NOT_ACTIVE`: stato ≠ `ACTIVE` (BLOCKED, INACTIVE, ANONYMIZED con `member:`; Q-128) | F-ING-03, docs/03 §2 | US-E01-05, US-E02-04 | TB-ING |
| ↳ | membro esplicito dell'abbinamento manuale al posto del subject | F-ING-04 | US-E01-07 | TB-ING |
| ING-08 passo 8 arricchimento | `ACCEPTED` 202: subject → `member:<id>`, `type` completo, `lhhop=0`, `lhcorrelationid=id`, outbox su `lh.actions.v1` | ingestion §5.8, docs/05 §2 | US-E01-01 | TB-ING |
| ING-09 `EventsController` `X-LH-Reprocess` | 403 se il ruolo ≠ ADMIN | Q-104, insight §3 | US-E08-11 | TB-ING |
| ↳ | azione accettata trovata ⇒ ripubblicata così com'era, 202 `ACCEPTED`, nessuna nuova riga | Q-104 | US-E08-11 | TB-ING |
| ↳ | azione non più presente (pulita) ⇒ pipeline normale | Q-104 | US-E08-11 | TB-ING |
| ING-10 `ActionReplayService#replay` | 400 se `id` o `source` mancano | nessuna | US-E08-11 | TB-ING |
| ING-11 `InboundResolutionService#retry` | 404 riga inesistente | ingestion §3 | US-E01-07 | TB-ING |
| ↳ | 409 `INBOUND_NOT_RETRYABLE`: stato `ACCEPTED`/`DUPLICATE` | ingestion §3 | US-E01-07 | TB-ING |
| ↳ | rivalutazione `ACCEPTED` ⇒ riga aggiornata *in place* + outbox (stesso id) | Q-118 | US-E01-07 | TB-ING |
| ↳ | rivalutazione ancora negativa ⇒ nuovo esito sulla stessa riga | Q-118 | US-E01-07 | TB-ING |
| ↳ | nel frattempo accettato altrove ⇒ `DUPLICATE`, niente ripubblicazione | Q-114 | US-E01-07 | TB-ING |
| ↳ | gara (riga già accettata) ⇒ 409 `INBOUND_NOT_RETRYABLE` | Q-118 | US-E01-07 | TB-ING |
| ↳ | audit `TRANSITION` con l'attore | Q-117 | US-E01-07, US-E08-07 | TB-ING |
| ING-12 `#match` | 422 `MEMBER_REQUIRED` (memberId vuoto) | nessuna | US-E01-07 | TB-ING |
| ↳ | 409 `INBOUND_NOT_UNMATCHED` | ingestion §3 | US-E01-07 | TB-ING |
| ↳ | 422 `MEMBER_NOT_FOUND` | nessuna | US-E01-07 | TB-ING |
| ↳ | 422 `MEMBER_NOT_ACTIVE` | docs/03 §2 | US-E01-07 | TB-ING |
| ↳ | abbinato ⇒ pipeline col membro esplicito (un altro passo può ancora fallire) | Q-118 | US-E01-07 | TB-ING |
| ING-13 `#autoMatch` (a `member.registered`) | nessun externalId né e-mail ⇒ 0 righe | Q-119 | US-E01-06 | TB-ING |
| ↳ | solo `UNMATCHED` degli ultimi 7 gg con subject `external:`/`email:`, max 100 | Q-115, Q-119 | US-E01-06 | TB-ING |
| ↳ | riga già risolta nel frattempo ⇒ saltata | nessuna | US-E01-06 | TB-ING |
| ↳ | esito `ACCEPTED` per il nuovo membro ⇒ `AUTO_MATCH`, audit come job | F-ING-04 | US-E01-06 | TB-ING |
| ↳ | altro esito (fonte spenta, membro non attivo) ⇒ riga invariata | Q-116 | US-E01-06 | TB-ING |
| ING-14 `FactsHandler#updateMemberIndex` | fatto senza membro o dati ⇒ ignorato | nessuna | US-E12-02 | TB-ING |
| ↳ | anonimizzazione ⇒ riferimenti personali cancellati da indice e righe | F-MBR-05, Q-128 | US-E02-05 | TB-ING |
| ↳ | `member.status.changed` ⇒ stato aggiornato (senza `newStatus` ⇒ invariato) | ingestion §4 | US-E02-04 | TB-ING |
| ↳ | `member.registered/updated` ⇒ upsert (stato assente ⇒ `ACTIVE`) | ingestion §4 | US-E02-01 | TB-ING |
| ING-15 `FactsHandler#bridge` (F-ING-08) | mappatura assente o disabilitata ⇒ fatto consumato, nessuna azione | docs/05 §7, BO-09 | US-E01-09, US-E01-10 | TB-ING |
| ↳ | `lhhop + 1 > 3` ⇒ `LoopGuardException` ⇒ DLQ `LOOP_GUARD` | docs/05 §2, §7 | US-E01-09 | TB-ING |
| ↳ | azione interna: nuovo id, `source=internal`, stesso subject/time/correlazione, `lhcausationid`=fatto, `lhhop+1`; riga `origin=INTERNAL` | docs/05 §7 | US-E01-09 | TB-ING |
| ↳ | nessun controllo dello stato del membro sull'azione interna | nessuna | US-E01-09 | TB-ING |
| ING-16 `InternalMappingsController#update` | 422 `INTERNAL_MAPPING_FORBIDDEN` (`wallet.points.*`, `wallet.spend.*`, `campaign.*`, `message.*`, `*.status.changed`) | docs/05 §7 | US-E01-10 | TB-ING |
| ↳ | 422 `ENABLED_REQUIRED` | nessuna | US-E01-10 | TB-ING |
| ↳ | 404 mappatura inesistente | nessuna | US-E01-10 | TB-ING |
| ↳ | abilitata/disabilitata + audit `UPDATE` | F-ING-08 | US-E01-10 | TB-ING |
| ING-17 `EventTypeService#create` | 422 `EVENT_TYPE_INVALID` (corpo mancante; codice non `a.b[.c[.d]]` o > 60; nome; categoria ∉ TRANSACTION/ENGAGEMENT/SERVICE; schema non `object`; `sampleData` non valido) | Q-89 | US-E01-12 | TB-ING |
| ↳ | 409 `EVENT_TYPE_EXISTS` | Q-89 | US-E01-12 | TB-ING |
| ↳ | tipo `CUSTOM` creato + audit | F-ING-06 | US-E01-12 | TB-ING |
| ING-18 `EventTypeService#update` | 404 | ingestion §3 | US-E01-12 | TB-ING |
| ↳ | 422 `EVENT_TYPE_IMMUTABLE_FIELD` (codice diverso) | ingestion §3 | US-E01-12 | TB-ING |
| ↳ | `CUSTOM`: stessa validazione della creazione | ingestion §3 | US-E01-12 | TB-ING |
| ↳ | `SYSTEM` e ruolo ≠ ADMIN ⇒ 403 | Q-89 | US-E01-12 | TB-ING |
| ↳ | `SYSTEM`: cambio di categoria/schema/esempio ⇒ 422 `EVENT_TYPE_SYSTEM_LOCKED` | ingestion §3 | US-E01-12 | TB-ING |
| ↳ | `SYSTEM`: nome, descrizione, icona, abilitazione aggiornati | ingestion §3 | US-E01-12 | TB-ING |
| ING-19 `TransactionsController` (F-ING-07) | 400 corpo assente; 400 campi mancanti (`source, orderId, memberRef`); `amount`/`currency` mancanti ⇒ `REJECTED/INVALID_DATA` (Q-269) | ingestion §3 | US-E01-08 | TB-ING |
| ↳ | 400 `kind` ≠ `PURCHASE`/`RETURN` | Q-49 | US-E01-08 | TB-ING |
| ↳ | `PURCHASE` (default) ⇒ `purchase.completed` con `id = txn-<orderId>` | ingestion §3 | US-E01-08 | TB-ING |
| ↳ | `RETURN` ⇒ `purchase.returned` con `id = txn-return-<orderId>` | Q-49 | US-E01-08 | TB-ING |
| ↳ | `occurredAt` assente ⇒ adesso | nessuna | US-E01-08 | TB-ING |
| ING-20 `SimulatorController#fire` (F-DEMO-03) | `count` limitato a 1…20 | ingestion §3, BO-28 | US-E11-05 | TB-ING |
| ↳ | fonte assente ⇒ `simulator`; `occurredAt` assente ⇒ adesso | ingestion §3 | US-E11-05 | TB-ING |
| ↳ | `data` assente ⇒ `sample_data` del tipo | ingestion §3 | US-E11-05 | TB-ING |
| ↳ | riga registrata con origine `SIMULATOR` (come gli scenari) | ingestion §2 | US-E11-05 | TB-ING |
| ING-21 `ScenarioService` (F-DEMO-04) | 404 scenario inesistente | ingestion §3 | US-E11-06 | TB-ING |
| ↳ | ritardo del passo limitato a 0…10 s | ingestion §5 | US-E11-06 | TB-ING |
| ↳ | `eventId` con `{run}` ⇒ duplicato solo dentro la stessa esecuzione | Q-130 | US-E11-06 | TB-ING |
| ↳ | `at`: `@last<GIORNO>Thh:mm` (Rome, ultima occorrenza non futura) · ISO · assente = adesso | docs/10 §8 | US-E11-06 | TB-ING |
| ↳ | passo ok se `ACCEPTED` o se esito = `expect` | ingestion §5 | US-E11-06 | TB-ING |
| ↳ | esecuzione `DONE`; eccezione (es. `at` senza orario) ⇒ `FAILED` | ingestion §2 | US-E11-06 | TB-ING |
| ING-22 Guardie | `POST /v1/inbound-events/{id}/retry`, `…/match` → ADMIN, CARE | docs/08 §2 `inbound.handle` | US-E01-07 | TB-ING |
| ↳ | `PUT /v1/internal-mappings/{factType}` → ADMIN | `program.config` | US-E01-10 | TB-ING |
| ↳ | `POST/PUT /v1/event-types` → ADMIN, MARKETING (+ tipo di sistema solo ADMIN) | `actiontype.custom`, Q-89 | US-E01-12 | TB-ING |
| ↳ | `POST /v1/demo/simulator/fire`, `POST /v1/demo/scenarios/{code}/run` → ADMIN, MARKETING, LEGAL, CARE | docs/06 §3 | US-E11-05, US-E11-06 | TB-ING |
| ↳ | `POST /v1/events`, `POST /v1/transactions` → nessuna guardia (fonti esterne) | docs/06 §2 | US-E01-01 | TB-ING |
| ↳ | `PUT /v1/sources/{code}` (ADMIN, audit) cambia abilitazione e tipi ammessi; ⛔ `POST /v1/sources` (nuova fonte) non esiste | F-ING-05, ingestion §3 | US-E01-11 | TB-ING |
| ING-23 enum | `InboundStatus`: `ACCEPTED`, `DUPLICATE`, `REJECTED`, `UNMATCHED` | ingestion §2 | US-E01-13 | TB-ING |
| ↳ | `RejectCode`: `SOURCE_DISABLED`, `UNKNOWN_TYPE`, `TYPE_NOT_ALLOWED`, `INVALID_DATA`, `INVALID_TIME`, `MEMBER_NOT_ACTIVE` | ingestion §5 | US-E01-03, US-E01-05 | TB-ING |
| ↳ | origine: `EXTERNAL`, `INTERNAL`, `SIMULATOR` | ingestion §2 | US-E01-13 | TB-ING |
| ↳ | risoluzione: `RETRY`, `MANUAL_MATCH`, `AUTO_MATCH` | Q-118 | US-E01-07 | TB-ING |
| ↳ | esecuzione scenario: `RUNNING`, `DONE`, `FAILED` | ingestion §2 | US-E11-06 | TB-ING |

### 5.3 member-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| MBR-01 `MemberService#create` | 400 e-mail assente | F-MBR-06 | US-E02-01, US-E10-08 | TB-GOV |
| ↳ | 409 `EMAIL_TAKEN` | docs/03 §2 | US-E02-01, US-E10-08 | TB-GOV |
| ↳ | creato `ACTIVE`, canale default `PORTAL`, nickname «Nome I.», proiezione BASE, fatto `member.registered` (snapshot completo) + audit | member §5, §7 | US-E02-01, US-E10-08 | TB-GOV |
| ↳ | ⚠ nessuna guardia di ruolo: anche ANALYST/MARKETING/LEGAL creano (docs/08 §2 `member.write` = ADMIN, CARE; l'endpoint serve anche al portale) | docs/06 §3 | US-E02-01, US-E08-06 | TB-GOV |
| MBR-02 `MemberService#update` (PATCH) | 404 · 409 `MEMBER_ANONYMIZED` · 409 `VERSION_CONFLICT` | F-MBR-01, docs/06 §4 | US-E02-02 | TB-GOV |
| ↳ | 409 `EMAIL_TAKEN` (nuova e-mail già usata) | docs/03 §2 | US-E02-02 | TB-GOV |
| ↳ | 422 `MEMBER_INVALID` (attributi/etichette non validi) | F-MBR-03 | US-E02-02, US-E02-07 | TB-GOV |
| ↳ | primo completamento del profilo ⇒ `profile_completed_at` + `member.profile.completed` (una sola volta) | F-MBR-07, member §5 | US-E10-09 | TB-GOV |
| ↳ | `member.updated` con snapshot completo | member §5 | US-E02-02 | TB-GOV |
| ↳ | PATCH di gestione solo ADMIN/CARE (`member.write`); il portale usa `PATCH /v1/portal/members/{id}` | docs/08 §2 | US-E02-02, US-E08-06 | TB-GOV |
| MBR-03 `#changeStatus` | 404 · 409 `MEMBER_ANONYMIZED` | F-MBR-04 | US-E02-04 | TB-GOV |
| ↳ | 400 destinazione ∉ {ACTIVE, INACTIVE, BLOCKED} | member §3 | US-E02-04 | TB-GOV |
| ↳ | stesso stato ⇒ nessun fatto | nessuna | US-E02-04 | TB-GOV |
| ↳ | cambio ⇒ `member.status.changed {previousStatus,newStatus,reason}` + audit; motivo facoltativo | F-MBR-04, Q-138 | US-E02-04 | TB-GOV |
| MBR-04 `#anonymize` | 422 `CONFIRM_MISMATCH` · 409 `MEMBER_ANONYMIZED` · 409 `VERSION_CONFLICT` · 404 | F-MBR-05, BO-03 | US-E02-05 | TB-GOV |
| ↳ | segnaposto nel nickname, nome/cognome/e-mail/telefono null, attributi e consensi cancellati; etichette e referral restano | docs/03 §2, Q-120, Q-121 | US-E02-05 | TB-GOV |
| MBR-05 `#resolveReferral` | codice vuoto ⇒ nessun legame | F-REF-01 | US-E06-16 | TB-GAM |
| ↳ | 422 `REFERRAL_CODE_INVALID`: codice inesistente | member §5 | US-E06-16, US-E10-08 | TB-GAM |
| ↳ | 422 `REFERRAL_CODE_INVALID`: invitante non `ACTIVE` | Q-61 | US-E06-16 | TB-GAM |
| ↳ | `REFERRAL_SELF` irraggiungibile (codice creato col membro) | member §5 | US-E06-16 | TB-GAM |
| MBR-06 `#uniqueReferralCode` | 8 caratteri `A-Z2-9` univoci | docs/03 §2 | US-E06-16 | TB-GAM |
| ↳ | tentativi esauriti ⇒ 409 `REFERRAL_CODE_EXHAUSTED` | nessuna | US-E06-16 | TB-GAM |
| MBR-07 `#search` | filtri `q, status, tier, label, segment`; 400 stato non valido | F-MBR-01 | US-E02-03 | TB-GOV |
| MBR-08 `ProfileRules` | profilo completo = firstName, lastName, email, phone, birthDate, city | docs/03 §2 | US-E10-09 | TB-GOV |
| ↳ | `missingFields[]` per il portale | member §3 | US-E10-09 | TB-GOV |
| MBR-09 `ReferralService#onAction` | tipo ≠ `referral.qualifying-action-type` ⇒ ignorato | docs/03 §8 | US-E06-17 | TB-GAM |
| ↳ | nessun legame aperto (o già completato) ⇒ nulla | F-REF-02 | US-E06-17 | TB-GAM |
| ↳ | prima azione qualificante ⇒ 2 fatti `referral.completed` (REFEREE sull'invitato, REFERRER sull'invitante) | F-REF-02, member §5 | US-E06-17 | TB-GAM |
| ↳ | invitante non più ACTIVE ⇒ fatto emesso comunque (il motore lo scarta con `NO_MEMBER`) | nessuna | US-E06-17 | TB-GAM |
| MBR-10 `MemberStatsHandler` | azioni `internal` non aggiornano ultima attività né acquisti | member §5 | US-E02-12 | TB-GOV |
| ↳ | `purchase.completed` ⇒ conteggi e importi 90 gg | docs/03 §10 | US-E02-12 | TB-GOV |
| MBR-11 `ActionLabels` | `ebill.activated` ⇒ etichetta `ebill`; `directdebit.activated` ⇒ `directdebit`; già presente ⇒ nulla; mai rimosse | Q-80 | US-E10-14, US-E02-07 | TB-GOV |
| MBR-12 `MemberProjectionHandler` | `tier.*`, `wallet.points.*` ⇒ `member_projection` (saldo = `balanceAfter`) | member §4, §7 | US-E02-12 | TB-GOV |
| MBR-13 `SegmentService#create` | 422 `INVALID_CODE` · `NAME_REQUIRED` · `INVALID_TYPE` · `INVALID_CRITERIA` | F-SEG-01/02 | US-E02-08 | TB-GOV |
| ↳ | STATIC con membri inesistenti ⇒ 422 `MEMBER_NOT_FOUND` | nessuna | US-E02-09 | TB-GOV |
| ↳ | 409 `CODE_TAKEN` | docs/06 §2 | US-E02-08 | TB-GOV |
| MBR-14 `SegmentService#update` | 409 `VERSION_CONFLICT` · 409 `SEGMENT_IMMUTABLE_FIELD` (codice/tipo) · 422 `INVALID_STATUS` | Q-88 | US-E02-10 | TB-GOV |
| ↳ | archiviazione ⇒ `left` per tutti i membri | Q-88 | US-E02-10 | TB-GOV |
| ↳ | criteri cambiati o riattivazione ⇒ ricalcolo | F-SEG-03 | US-E02-10 | TB-GOV |
| MBR-15 `#refresh`, `#replaceMembers` | 409 `SEGMENT_ARCHIVED` | Q-88 | US-E02-10 | TB-GOV |
| ↳ | 409 `SEGMENT_NOT_STATIC` (elenco manuale su DYNAMIC) | member §3 | US-E02-09 | TB-GOV |
| ↳ | `{entered, left, total}` + audit | member §3 | US-E02-08 | TB-GOV |
| MBR-16 `SegmentRefresher` | segmento archiviato ⇒ nessun cambio | nessuna | US-E02-11 | TB-GOV |
| ↳ | solo differenze ⇒ `member.segment.entered/left` | F-SEG-03, docs/03 §10 | US-E02-08, US-E02-11 | TB-GOV |
| ↳ | dopo seed/reset: riannuncio di tutte le appartenenze (+15 s) | Q-81 | US-E02-11 | TB-GOV |
| MBR-17 `SegmentCriteria` | criteri vuoti ⇒ nessun membro | Q-87 | US-E02-08 | TB-GOV |
| ↳ | gruppi `all/any/not` sui campi `tier, status, labels, city, age, registeredDaysAgo, balance.PTS, lifetimeEarned.PTS, lastActivityDaysAgo, actions.<t>.count30d/total, purchases.amount90d, attributes.*` | docs/03 §10, Q-87 | US-E02-08 | TB-GOV |
| ↳ | membri `ANONYMIZED` esclusi | Q-85 | US-E02-08 | TB-GOV |
| ↳ | `exists` su lista vuota ⇒ falso | nessuna | US-E02-08 | TB-GOV |
| MBR-18 Job `SegmentJobs` | ogni 15 min **solo se** ci sono variazioni (`jobs.enabled`) | member §5 | US-E02-11 | TB-GOV |
| MBR-19 `AttributeService` | 422 `ATTRIBUTE_DEFINITION_INVALID` | F-MBR-03 | US-E02-07 | TB-GOV |
| ↳ | 409 `ATTRIBUTE_IN_USE` (togliere o cambiare tipo con valori presenti) | Q-93 | US-E02-07 | TB-GOV |
| MBR-20 Guardie | `POST /v1/members/{id}/status` → ADMIN, CARE | `member.write` | US-E02-04 | TB-GOV |
| ↳ | `POST /v1/members/{id}/anonymize` → ADMIN | `member.anonymize` | US-E02-05 | TB-GOV |
| ↳ | `POST/PUT /v1/segments`, `…/refresh`, `…/members`, `PUT /v1/attribute-definitions` → MARKETING (+ ADMIN) | `segment.write` | US-E02-08…10, US-E02-07 | TB-GOV |
| ↳ | `POST /v1/demo/jobs/refresh-segments` → ADMIN | `demo.admin` | US-E11-08 | TB-GOV |
| ↳ | `POST /v1/members`, `POST /v1/segments/preview`, `PATCH /v1/portal/members/{id}` → nessuna guardia; `PATCH /v1/members/{id}` ⇒ ADMIN, CARE | ⚠ docs/08 §2 per il primo | US-E02-01, US-E02-02, US-E10-09 | TB-GOV |
| ↳ | ⛔ `POST /v1/demo/jobs/birthdays` assente (F-MBR-08, P2) | fuori perimetro | US-E02-13 | — |
| MBR-21 enum | `MemberStatus`: `ACTIVE`, `INACTIVE`, `BLOCKED`, `ANONYMIZED` | F-MBR-04, Q-139 | US-E02-04 | TB-GOV |
| ↳ | `MemberStatus.CLOSED` (mai assegnabile da API) | nessuna | — | TB-GOV |
| ↳ | segmento `STATIC`/`DYNAMIC`, `ACTIVE`/`ARCHIVED`; canale `PORTAL/APP/STORE/IMPORT`; ruolo referral `REFERRER/REFEREE` | member §2, docs/05 | US-E02-08, US-E06-17 | TB-GOV |

### 5.4 campaign-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| CMP-01 `CampaignEngine#evaluate` ingresso | snapshot assente o membro non `ACTIVE` ⇒ `NO_MEMBER`, nessun contatore | docs/03 §3.5.1 | US-E03-05 | TB-CMP |
| ↳ | ⛔ nessun ritentativo (campaign §5: «ritenta 3 volte» se lo snapshot manca) | campaign §5 | US-E03-05, US-E01-06 | TB-CMP |
| CMP-02 candidati | solo `LIVE` (cache) con `type` tra i trigger; la simulazione accetta anche bozze | docs/03 §3.5.2 | US-E03-04, US-E03-11 | TB-CMP |
| ↳ | ordine `priority` decrescente, poi `code` crescente | docs/03 §3.5.2 | US-E03-08 | TB-CMP |
| CMP-03 calendario (Europe/Rome) | `NOT_IN_SCHEDULE`: `time < startAt` | docs/03 §3.5.2.1 | US-E03-05 | TB-CMP |
| ↳ | `NOT_IN_SCHEDULE`: `time > endAt` (l'istante `endAt` è ancora dentro) | docs/03 §3.5.2.1 | US-E03-05 | TB-CMP |
| ↳ | `NOT_IN_SCHEDULE`: giorno ∉ `daysOfWeek` | docs/03 §3.2 | US-E03-05 | TB-CMP |
| ↳ | `NOT_IN_SCHEDULE`: ora ∉ `hours [da, a)` (fine esclusa: `[9, 18]` finisce alle 18:00) | Q-238 | US-E03-05 | TB-CMP |
| ↳ | calendario vuoto ⇒ sempre dentro | nessuna | US-E03-04 | TB-CMP |
| CMP-04 pubblico | `AUDIENCE`: tier fuori dall'elenco **o** nessun segmento dell'elenco (tier **e** segmento, Q-212); elenchi che restringono anche con `all=true` (Q-210); senza elenchi e `all` non vero ⇒ nessuno (Q-211) | docs/03 §3.5.2.2, F-CMP-06 | US-E03-05 | TB-CMP |
| ↳ | `all:true` o elenchi vuoti ⇒ tutti | docs/03 §3.2 | US-E03-04 | TB-CMP |
| CMP-05 condizioni | `CONDITION` con `failedConditions[{field,cmp,value,actual}]` | docs/03 §3.5.2.3, F-CMP-09 | US-E03-05, US-E03-06 | TB-CMP |
| CMP-06 gruppo esclusivo | `EXCLUSIVE`: gruppo già assegnato a una campagna di priorità maggiore | docs/03 §3.5.2.4, F-CMP-07, Q-50 | US-E03-08 | TB-CMP |
| CMP-07 effetti supportati | `EFFECT_NOT_SUPPORTED_YET`: tipo sconosciuto; `GRANT_PLAYS` senza `contestCode` o `count ≤ 0`; `ISSUE_COUPON` senza `rewardCode`/`rewardCodeField`; `AWARD_BADGE` senza `badgeCode`; `SEND_MESSAGE` senza `templateCode` o `params` non oggetto; `GRANT_POINTS` con modo sconosciuto; `effects` non array | docs/12 M1.3, Q-73 | US-E03-05, US-E03-10 | TB-CMP |
| CMP-08 limiti per membro | `LIMIT`: conteggio del periodo ≥ `max` (DAY, WEEK, MONTH, EDITION, ALWAYS; chiave periodo in Rome sul `time` dell'azione) | docs/03 §3.5.2.5, F-CMP-05 | US-E03-09 | TB-CMP |
| CMP-09 budget globale | `BUDGET`: punti decisi ≥ `global.maxPoints` | F-CMP-05 | US-E03-09 | TB-CMP |
| ↳ | `BUDGET`: attivazioni ≥ `global.maxMatches` | F-CMP-05 | US-E03-09 | TB-CMP |
| ↳ | l'ultima attivazione sotto soglia è ridotta al residuo del budget (e di `perMemberPoints`) | Q-237 | US-E03-09 | TB-CMP |
| CMP-10 tetto punti e cooldown | `LIMIT`: meno di `cooldownMinutes` dall'ultimo match del membro (Q-165) | F-CMP-05 | US-E03-09 | TB-CMP |
| ↳ | `LIMIT`: punti decisi dalla campagna per il membro ≥ `perMemberPoints` (Q-165) | F-CMP-05 | US-E03-09 | TB-CMP |
| CMP-11 esito complessivo | `MATCHED` (≥ 1 campagna) · `NO_MATCH` | campaign §2 | US-E03-04, US-E03-05 | TB-CMP |
| CMP-12 `computeBase` (GRANT_POINTS) | `FIXED` ⇒ `value` | docs/03 §3.4 | US-E03-07 | TB-CMP |
| ↳ | `PER_AMOUNT`: campo assente o non numerico ⇒ effetto scartato | docs/03 §3.4 | US-E03-07 | TB-CMP |
| ↳ | `PER_AMOUNT`: `unitStep ≤ 0` ⇒ campagna scartata `EFFECT_NOT_SUPPORTED_YET` | Q-228 | US-E03-07 | TB-CMP |
| ↳ | `PER_AMOUNT`: arrotondamento `FLOOR` (default) · `CEIL` · `ROUND`, su divisione decimale esatta | docs/03 §3.4 | US-E03-07 | TB-CMP |
| ↳ | `FROM_FIELD`: valore non intero ⇒ scartato | Q-44 | US-E03-07 | TB-CMP |
| ↳ | `LOOKUP`: campo assente ⇒ scartato; chiave assente dalla tabella ⇒ scartato | docs/03 §3.4 | US-E03-07 | TB-CMP |
| ↳ | `min`/`max` applicati; risultato ≤ 0 ⇒ scartato | docs/03 §3.4 | US-E03-07 | TB-CMP |
| CMP-13 moltiplicatori | fattore = prodotto dei `MULTIPLIER` di **altre** campagne, stessa valuta, `scope ALL_GRANTS` o `labels[]` dell'effetto in comune con le etichette della campagna dell'accredito | docs/03 §3.4, §3.5.3 | US-E03-08 | TB-CMP |
| ↳ | tetto `engine.maxMultiplier` (5) | docs/03 §3.5.3 | US-E03-08 | TB-CMP |
| ↳ | importo = floor(base × fattore); ≤ 0 ⇒ effetto scartato | docs/03 §3.5.3 | US-E03-08 | TB-CMP |
| ↳ | la campagna `MULTIPLIER` compare come scattata senza accrediti propri | campaign §2 | US-E03-08 | TB-CMP |
| CMP-14 effetti non monetari | `GRANT_PLAYS` ⇒ `plays.grant` (`count ≥ 1`) | docs/03 §3.4 | US-E03-10 | TB-CMP |
| ↳ | `ISSUE_COUPON`: `rewardCode` fisso o da `rewardCodeField`; non risolto ⇒ campagna scartata `EFFECT_NOT_SUPPORTED_YET`, limiti non consumati | docs/03 §3.4, Q-232 | US-E03-10, US-E06-08 | TB-CMP |
| ↳ | `AWARD_BADGE` ⇒ `badge.award`; `SEND_MESSAGE` ⇒ `message.send` (+ `params`) | docs/03 §3.4 | US-E03-10 | TB-CMP |
| CMP-15 `effectId` | `sha256(actionId + campaignCode + indice)[0..26)` | docs/03 §3.5.4 | US-E03-17 | TB-CMP |
| CMP-16 `ConditionEvaluator` | nodo vuoto ⇒ vero; `any` senza regole ⇒ vero | nessuna | US-E03-06 | TB-CMP |
| ↳ | `op` sconosciuto ⇒ trattato come `all` | nessuna | US-E03-06 | TB-CMP |
| ↳ | `not` ⇒ «non tutte vere insieme» (docs/08 la chiama NESSUNA) | Q-90 | US-E03-06 | TB-CMP |
| ↳ | `exists`/`nexists`; campo assente ⇒ foglia falsa (tranne `nexists`) | docs/03 §3.3 | US-E03-06 | TB-CMP |
| ↳ | array ⇒ vero se almeno un elemento soddisfa, anche con `in/nin` (`contains/ncontains` guardano la lista intera) | docs/03 §3.3 | US-E03-06 | TB-CMP |
| ↳ | tipi incompatibili (anche con le negazioni `neq/nin/ncontains`, senza ripiego testuale), `between` senza 2 valori, confronti numerici su non numeri ⇒ falso, mai eccezione | docs/03 §3.3, Q-91 | US-E03-06 | TB-CMP |
| ↳ | `context.dayOfWeek/hour/date` in Europe/Rome sul `time` dell'azione | docs/03 §3.3 | US-E03-06 | TB-CMP |
| ↳ | `context.source` = URN completo (`urn:loyaltyhub:source:ecommerce`) in valutazione reale e in simulazione (il codice semplice è normalizzato in URN); il costruttore suggerisce l'URN | docs/03 §3.3, docs/05 §2, BO-06 | US-E03-06, US-E03-11 | TB-CMP |
| ↳ | `history.actionCount` (precedenti), `daysSinceLastAction` (nessuna storia ⇒ assente); `member.age`, `registeredDaysAgo` senza dato ⇒ assente | docs/03 §3.3 | US-E03-06 | TB-CMP |
| CMP-17 `EvaluationService#evaluate` | `MATCHED` ⇒ consuma una volta ogni periodo dichiarato più la riga `ALWAYS` (punti e ultimo match del membro per `perMemberPoints`/`cooldownMinutes`), totali, contatore azioni | docs/03 §3.5.5 | US-E03-09, US-E03-13 | TB-CMP |
| ↳ | `NO_MATCH` con snapshot ⇒ solo contatore azioni (`history.*`) | nessuna | US-E03-06 | TB-CMP |
| ↳ | `NO_MEMBER` ⇒ niente contatori | docs/03 §3.5.1 | US-E03-05 | TB-CMP |
| ↳ | stessa transazione: `evaluation_log`, effetti in outbox (`points.grant` con `time` dell'azione), fatto `campaign.evaluated` | docs/03 §3.5.5, docs/05 §2 | US-E03-12, US-E12-01 | TB-CMP |
| ↳ | ⚠ limiti letti e poi incrementati (non `UPDATE … WHERE count < max`): sicuro solo per l'ordine per membro | docs/03 §3.5.2.5, RNF-04 | US-E12-04 | TB-CMP |
| CMP-18 `DemoPoison` | profilo `demo` e `data._poison=true` ⇒ `DEMO_POISON` non ritentabile ⇒ DLQ | docs/10 §8, Q-108 | US-E08-11, US-E09-03 | TB-CMP |
| ↳ | fuori dal profilo `demo` il flag è ignorato | docs/10 §8 | US-E08-11 | TB-CMP |
| CMP-19 `CampaignAdminService#validate` | 422 `CAMPAIGN_INVALID`: nessun trigger; nessun effetto; `MULTIPLIER.factor` ∉ [1.1, 5]; `GRANT_PLAYS` senza `contestCode`; `ISSUE_COUPON` senza `rewardCode` né `rewardCodeField`; `AWARD_BADGE` senza `badgeCode`; `SEND_MESSAGE` senza/formato `templateCode`, `params` non oggetto; `GRANT_POINTS.unitStep ≤ 0` (Q-228); chiavi di `audience` diverse da `all/tiers/segments` (Q-214); `endAt ≤ startAt`; date non ISO-8601 anche da sole (Q-243) | campaign §5 | US-E03-01, US-E03-02 | TB-CMP |
| ↳ | `POST /v1/campaigns/validate` ⇒ `{valid, errors[]}` senza salvare | campaign §3 | US-E03-01 | TB-CMP |
| CMP-20 `#create` | 400 `code` mancante · 422 `INVALID_CODE` (fuori da `^[A-Z][A-Z0-9-]{2,39}$`) · 409 `CODE_TAKEN` | docs/06 §2 | US-E03-01 | TB-CMP |
| ↳ | creata `DRAFT`, `priority` 100, pubblico «tutti», mai di sistema, `requiresLegal` dal corpo (default `false`) + audit | F-CMP-01 | US-E03-01 | TB-CMP |
| ↳ | creazione solo ADMIN/MARKETING: ANALYST/CARE/LEGAL ⇒ 403 `FORBIDDEN_ROLE` | docs/06 §3, docs/08 §2 `object.edit` | US-E03-01, US-E08-06 | TB-CMP |
| CMP-21 `#update` | 409 `CODE_IMMUTABLE` · 409 `CAMPAIGN_NOT_EDITABLE` (ENDED, ARCHIVED) | Q-51 | US-E03-02 | TB-CMP |
| ↳ | LIVE/PAUSED: modifica di trigger, pubblico, condizioni, effetti, limiti, `startAt`, gruppo, visibilità, etichette ⇒ 409 `CAMPAIGN_LIVE_LOCKED` | docs/03 §3.6, Q-51 | US-E03-02 | TB-CMP |
| ↳ | LIVE/PAUSED: nome, descrizioni, icona, priorità, `endAt` ammessi | docs/03 §3.6 | US-E03-02 | TB-CMP |
| ↳ | 409 `VERSION_CONFLICT` · ok ⇒ cache ricaricata + audit con diff | Q-112, F-AUD-01 | US-E03-02, US-E08-13 | TB-CMP |
| CMP-22 `#duplicate` | codice `<code>-COPY-n` (primo n libero; base accorciata perché resti entro 40 caratteri), `DRAFT`, mai di sistema, `requiresLegal` conservato | campaign §3, F-CMP-13 | US-E03-16 | TB-CMP |
| CMP-23 `#transition` | `ACTIVATE` sinonimo di `PUBLISH` | nessuna | US-E03-03 | TB-CMP |
| ↳ | `ARCHIVE` su campagna di sistema ⇒ 409 `SYSTEM_LOCKED` | campaign §3 | US-E03-15 | TB-CMP |
| ↳ | policy: `requiresLegal` o `limits.global.maxPoints` > soglia ⇒ LEGAL, altrimenti pubblicazione diretta (vedi CMN-04…07) | docs/06 §7, Q-08 | US-E03-03, US-E08-01 | TB-CMP |
| ↳ | `campaign.status.changed` + storico + audit (override marcato) | docs/03 §3.6, EVT-FACT-11 | US-E03-03 | TB-CMP |
| CMP-24 `#simulate` | 400 `action.type` mancante; 400 `action.time` non valido | campaign §3 | US-E03-11 | TB-CMP |
| ↳ | `campaignIds` ⇒ anche bozze; `memberOverride` (tier, segmenti, attributi); limiti solo letti; nessuna scrittura | F-CMP-08, docs/03 §3.5 | US-E03-11 | TB-CMP |
| CMP-25 `#portal` (PT-02) | solo `LIVE` + `visible_in_portal` + pubblico soddisfatto | F-CMP-11 | US-E03-14 | TB-CMP |
| ↳ | `rewardSummary`: FIXED «+N punti», PER_AMOUNT «1 punto ogni 1 €», MULTIPLIER «Punti ×2», GRANT_PLAYS «+1 giocata» | campaign §5 | US-E03-14 | TB-CMP |
| ↳ | `limitProgress` dal **primo** limite per membro; `endsAt` da `schedule.endAt` | F-CMP-11 | US-E03-14 | TB-CMP |
| CMP-26 `MemberSnapshotHandler` | `member.registered/updated` sovrascrive; `segment.entered/left`; `status.changed`; `tier.upgraded/downgraded`; anonimizzazione; senza membro ⇒ ignorato | campaign §4, member §5 | US-E02-12, US-E12-02 | TB-CMP |
| CMP-27 `CampaignTotalsHandler` | `wallet.points.earned` senza `campaignCode`/`amount` o `amount = 0` ⇒ ignorato; altrimenti `points_granted +=` | campaign §2 | US-E03-13 | TB-CMP |
| CMP-28 `#stats` / budget | nessun limite globale ⇒ budget `null`; residuo = max(0, max − consumato); serie 30 gg dal registro | F-CMP-10 | US-E03-13 | TB-CMP |
| CMP-29 Job | `CampaignCache.reload` ogni 30 s e a ogni scrittura | campaign §5 | US-E03-03 | TB-CMP |
| ↳ | fine automatica `LIVE/PAUSED` → `ENDED` a `endAt` superato (job al minuto con `loyaltyhub.jobs.enabled`; storico `END` di `system`, `campaign.status.changed`, audit `JOB`) | campaign §5 | US-E03-19 | TB-CMP |
| ↳ | ⛔ pulizia `evaluation_log` > 30 gg assente | campaign §2, RNF-07 | US-E09-06 | TB-CMP |
| CMP-30 Guardie | `PUT /v1/campaigns/{id}`, `POST …/duplicate` → ADMIN, MARKETING | `object.edit` | US-E03-02, US-E03-16 | TB-CMP |
| ↳ | `POST …/transitions` → controllo in `GovernedTransitions` (ANALYST/CARE 403) | docs/06 §3 | US-E08-04 | TB-CMP |
| ↳ | `…/validate`, `…/simulate` → nessuna guardia; `POST /v1/campaigns` ⇒ ADMIN, MARKETING | docs/06 §3 | US-E03-01, US-E03-11 | TB-CMP |
| CMP-31 enum | `CampaignStatus`: `DRAFT, IN_REVIEW, APPROVED, LIVE, PAUSED, ENDED, ARCHIVED` | docs/03 §3.6 | US-E03-03 | TB-CMP |
| ↳ | `Outcome`: `MATCHED, NO_MATCH, NO_MEMBER` | campaign §2 | US-E03-05 | TB-CMP |
| ↳ | `SkipReason`: `NOT_IN_SCHEDULE, AUDIENCE, CONDITION, EXCLUSIVE, LIMIT, BUDGET, EFFECT_NOT_SUPPORTED_YET` (⚠ F-CMP-09 elenca `NOT_LIVE`; docs/12 `MEMBER_LIMIT_REACHED`, `EXCLUSIVE_GROUP`) | docs/03 §3.5, Q-50 | US-E03-05 | TB-CMP |

### 5.5 wallet-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| WAL-01 `WalletService#applyGrant` | `effectId` assente o `memberId` assente ⇒ ignorato (log) | nessuna | US-E04-01 | TB-WAL |
| ↳ | `effectId` già nel ledger ⇒ nessun movimento | wallet §5, RNF-03 | US-E04-01, US-E12-02 | TB-WAL |
| ↳ | wallet assente ⇒ 2 wallet + tier BASE creati al volo | wallet §5 | US-E04-01 | TB-WAL |
| ↳ | `tierMultiplierApplies` e valuta PTS ⇒ floor(importo × moltiplicatore); STS mai moltiplicati | docs/03 §4.2, F-TIER-03 | US-E04-01 | TB-WAL |
| ↳ | importo finale ≤ 0 ⇒ nessun movimento | nessuna | US-E04-01 | TB-WAL |
| ↳ | `pendingDays > 0` ⇒ lotto `PENDING` (`availableAt = time + n gg`), saldo in attesa | docs/03 §4.2, F-WAL-05 | US-E04-02 | TB-WAL |
| ↳ | STS attivo ⇒ `periodSts +=` e verifica salita nello stesso commit | docs/03 §4.3 | US-E04-03 | TB-WAL |
| ↳ | movimento `EARN` con `metadata {baseAmount, tierCode, tierMultiplier, campaignMultiplier}`, lotto con scadenza da policy, fatto `wallet.points.earned` (`time` dell'azione) | docs/03 §4.2, docs/05 §2 | US-E04-01 | TB-WAL |
| ↳ | stato del membro non verificato (un BLOCKED tra valutazione e accredito riceve i punti) | nessuna | US-E04-01 | TB-WAL |
| ↳ | PTS e STS della stessa azione: il moltiplicatore dei PTS dipende dall'ordine degli effetti (PTS prima di STS ⇒ tier precedente) | nessuna | US-E04-03 | TB-WAL |
| WAL-02 `#applyTierUpgrade` | tier guadagnato con rank > attuale ⇒ salita al più alto raggiunto, storico `UPGRADE`, `tier.upgraded` | F-TIER-02 | US-E04-03 | TB-WAL |
| ↳ | rank ≤ attuale o `member_tier` assente ⇒ nulla | docs/03 §4.3 | US-E04-03 | TB-WAL |
| ↳ | salita da rilascio pending ⇒ fatto radice (nuova correlazione) | nessuna | US-E04-02 | TB-WAL |
| WAL-03 `ExpiryPolicy#expiresAt` | `ROLLING_MONTHS(n)` (default 12) ⇒ ultimo istante del mese di `earnedAt + n`, Europe/Rome | wallet §5 | US-E04-01, US-E04-10 | TB-WAL |
| ↳ | `END_OF_EDITION_PLUS_GRACE` ⇒ fine giorno di `redemptionGraceUntil`, altrimenti `endDate + graceDays` | docs/03 §4.1, Q-47 | US-E04-10 | TB-WAL |
| ↳ | nessuna edizione copre la data ⇒ il lotto non scade | Q-47 | US-E04-10 | TB-WAL |
| ↳ | `NEVER`, `EDITION` o policy assente ⇒ non scade | docs/03 §4.1 | US-E04-10 | TB-WAL |
| WAL-04 `#releasePending(asOf)` | lotti `PENDING` con `availableAt ≤ asOf` ⇒ `ACTIVE`, movimento `RELEASE`, `wallet.points.released` | F-WAL-05 | US-E04-02 | TB-WAL |
| ↳ | lotto STS rilasciato ⇒ `periodSts` + verifica salita | docs/03 §4.3 | US-E04-02 | TB-WAL |
| ↳ | nessun lotto dovuto ⇒ esito 0 | nessuna | US-E04-02 | TB-WAL |
| WAL-05 `#expirePoints(asOf)` | lotti `ACTIVE` con `expiresAt ≤ asOf` ⇒ `EXPIRED`, `wallet.points.expired` | F-WAL-06 | US-E04-04 | TB-WAL |
| ↳ | un movimento `EXPIRE` **per membro/valuta** col totale e un solo `wallet.points.expired` | docs/03 §4.2 | US-E04-04 | TB-WAL |
| WAL-06 `#expiryWarnings(asOf)` | lotti in scadenza entro 30 gg non ancora preavvisati ⇒ flag + `wallet.points.expiring` | F-WAL-06, wallet §5 | US-E04-05 | TB-WAL |
| ↳ | lotto già preavvisato ⇒ escluso | wallet §5 | US-E04-05 | TB-WAL |
| WAL-07 `#adjustBalance` (F-WAL-07) | 422 `CURRENCY_NOT_ADJUSTABLE` (≠ PTS) | Q-46 | US-E04-06 | TB-WAL |
| ↳ | 422 `INVALID_AMOUNT` (≤ 0) | nessuna | US-E04-06 | TB-WAL |
| ↳ | 422 `NOTE_TOO_SHORT` (< 10 caratteri dopo trim) | docs/03 §4.2 | US-E04-06 | TB-WAL |
| ↳ | 422 `INVALID_REASON` (∉ GOODWILL, CORRECTION, COMPLAINT, TEST) | Q-45 | US-E04-06 | TB-WAL |
| ↳ | 422 `INVALID_DIRECTION` | wallet §3 | US-E04-06 | TB-WAL |
| ↳ | 409 `MEMBER_ANONYMIZED`; BLOCKED/INACTIVE restano rettificabili | Q-127 | US-E04-06 | TB-WAL |
| ↳ | `CREDIT` ⇒ lotto `ACTIVE` + `ADJUST_CREDIT` | docs/03 §4.2 | US-E04-06 | TB-WAL |
| ↳ | `DEBIT` oltre il saldo attivo ⇒ 422 `INSUFFICIENT_BALANCE` | docs/03 §4.2 | US-E04-06 | TB-WAL |
| ↳ | `DEBIT` ⇒ consumo FIFO + `ADJUST_DEBIT` | docs/03 §4.2 | US-E04-06 | TB-WAL |
| ↳ | `wallet.points.adjusted` + audit `ADJUST` con saldo prima/dopo | F-WAL-07, F-AUD-01 | US-E04-06 | TB-WAL |
| WAL-08 `RedemptionPayments#spend` (F-WAL-08) | dati incompleti (membro, `redemptionId`, costo ≤ 0) ⇒ ignorato | nessuna | US-E04-07 | TB-WAL |
| ↳ | `SPEND` già registrata per la richiesta ⇒ nulla | wallet §5 | US-E04-07, US-E12-02 | TB-WAL |
| ↳ | membro non `ACTIVE` ⇒ `wallet.spend.rejected (MEMBER_NOT_ACTIVE)` | EVT-FACT-22 | US-E04-07, US-E05-07 | TB-WAL |
| ↳ | saldo attivo < costo ⇒ `wallet.spend.rejected (INSUFFICIENT_BALANCE, requested, available)`, nessun movimento | docs/03 §4.2 | US-E04-07, US-E05-07 | TB-WAL |
| ↳ | ok ⇒ `SPEND` + consumo FIFO + `wallet.points.spent` | F-WAL-04 | US-E04-07 | TB-WAL |
| WAL-09 `PointsLotRepository#consumeFifo` | ordine `expiresAt` crescente (null per ultimi), poi `earnedAt`; `lot_consumption` per lotto | docs/03 §4.2 | US-E04-07 | TB-WAL |
| WAL-10 `RedemptionPayments#refund` (F-RWD-07) | `refund ≠ true` o `redemptionId` assente ⇒ ignorato | EVT-FACT-44 | US-E04-08 | TB-WAL |
| ↳ | nessuna `SPEND` per la richiesta ⇒ nulla (log) | nessuna | US-E04-08 | TB-WAL |
| ↳ | `REFUND` già registrato ⇒ nulla | RNF-03 | US-E04-08 | TB-WAL |
| ↳ | tutto l'importo in un lotto nuovo; i lotti d'origine restano consumati | docs/03 §4.2 | US-E04-08 | TB-WAL |
| ↳ | scadenza del lotto nuovo `max(scadenza originaria più lontana, oggi + 30 gg)`; lotto consumato senza scadenza ⇒ nessuna; nessun consumo registrato ⇒ oggi + 30 gg | docs/03 §4.2 | US-E04-08 | TB-WAL |
| ↳ | movimento `REFUND` unico + `wallet.points.refunded` | docs/03 §4.2 | US-E04-08 | TB-WAL |
| WAL-11 `MemberLifecycleHandler` | `member.registered` ⇒ wallet PTS/STS + `member_tier` BASE | wallet §4 | US-E04-16 | TB-WAL |
| ↳ | `member.status.changed` ⇒ `member_status` (senza `newStatus` ⇒ `ACTIVE`) | wallet §4 | US-E04-16 | TB-WAL |
| WAL-12 `TierAdminService#update` | 404 · 422 `TIER_THRESHOLDS_NOT_MONOTONIC` (BASE ≠ 0, o soglie non crescenti col rank) · 422 `TIER_MULTIPLIER_INVALID` (≤ 0) | wallet §3 | US-E04-09 | TB-WAL |
| WAL-13 `CurrencyService#update` | 422 `INVALID_EXPIRY_POLICY`: `ROLLING_MONTHS` ∉ 1…60, `graceDays < 0`, tipo ∉ ROLLING_MONTHS/END_OF_EDITION_PLUS_GRACE/NEVER/EDITION, JSON non valido | docs/03 §4.1 | US-E04-10 | TB-WAL |
| ↳ | vale solo per i nuovi lotti | BO-08 | US-E04-10 | TB-WAL |
| WAL-14 `EditionService#create/update` | 422 `EDITION_FIELD_REQUIRED` · 409 `EDITION_EXISTS` · 422 `EDITION_DATES_INVALID` · 422 `EDITION_OVERLAP` · 404 | docs/03 §4.4 | US-E04-11 | TB-WAL |
| WAL-15 `EditionsController#close` | `dryRun` (default `true`) per ogni ruolo | BO-08, docs/08 §2 | US-E04-12 | TB-WAL |
| ↳ | `dryRun=false` e ruolo ≠ ADMIN ⇒ 403 | docs/08 §2 `edition.close` | US-E04-13 | TB-WAL |
| WAL-16 `EditionCloseBatchService` | 404 · 422 `EDITION_ALREADY_CLOSED` · 422 `EDITION_NOT_ACTIVE` | wallet §3 | US-E04-13 | TB-WAL |
| ↳ | solo membri `ACTIVE`, a pagine da 200 | docs/03 §4.3, wallet §5 | US-E04-12, US-E04-13 | TB-WAL |
| ↳ | `dryRun` ⇒ anteprima `{summary, members[]}` senza scritture | F-TIER-05 | US-E04-12 | TB-WAL |
| ↳ | `RETAINED` ⇒ storico `RETAIN` + `tier.retained` | F-TIER-04 | US-E04-13 | TB-WAL |
| ↳ | `DOWNGRADED` ⇒ storico `DOWNGRADE` + `tier.downgraded` | F-TIER-04 | US-E04-13 | TB-WAL |
| ↳ | `periodSts = 0` per tutti; edizione `CLOSED`; `edition.closed {retained, downgraded}` + audit | docs/03 §4.3 | US-E04-13 | TB-WAL |
| ↳ | prossima `PLANNED` (inizio più vicino) ⇒ `ACTIVE` | docs/03 §4.3 | US-E04-13 | TB-WAL |
| ↳ | nessuna `PLANNED` ⇒ nessuna edizione attiva | nessuna | US-E04-13 | TB-WAL |
| ↳ | accredito concorrente: conta per l'edizione vista dal lock | Q-48 | US-E04-13 | TB-WAL |
| WAL-17 `EditionCloseRule#computeNext` | `earned` = tier più alto con soglia ≤ `periodSts`; `floor` = rank attuale − 1 (min 0); nuovo = il più alto dei due | docs/03 §4.3 | US-E04-12 | TB-WAL |
| ↳ | mai salita in chiusura (nuovo > attuale ⇒ attuale) | docs/03 §4.3 | US-E04-12 | TB-WAL |
| ↳ | esito `RETAINED` (= attuale) · `DOWNGRADED` | docs/03 §4.3 | US-E04-12 | TB-WAL |
| ↳ | scala vuota ⇒ eccezione | nessuna | — | TB-WAL |
| WAL-18 `WalletQueryService` | prossimo tier = rank + 1; `missing = max(0, soglia − periodSts)`; `progressPct` tra le soglie | docs/03 §4.3 | US-E04-14 | TB-WAL |
| ↳ | livello massimo ⇒ `next` nullo, progresso 100 («livello massimo») | docs/03 §4.3 | US-E04-14 | TB-WAL |
| ↳ | `expiringSoon`: PTS in scadenza entro 30 gg + prima scadenza | wallet §3 | US-E04-14, US-E10-13 | TB-WAL |
| ↳ | ⛔ `keepWarning` (da ottobre, `periodSts` < soglia del tier attuale) assente | docs/03 §4.3, wallet §5 | US-E04-14 | TB-WAL |
| WAL-19 Job `WalletJobs` (`jobs.enabled`) | rilascio ogni ora · scadenze 02:00 · preavvisi 09:00 (cron senza `zone`: ora del container) | wallet §5, ADR-024 | US-E04-02, US-E04-04, US-E04-05 | TB-WAL |
| WAL-20 Guardie | `POST /v1/wallets/{id}/adjustments` → CARE, ADMIN | `points.adjust` | US-E04-06 | TB-WAL |
| ↳ | `PUT /v1/tiers/{code}`, `PUT /v1/currencies/{code}`, `POST/PUT /v1/editions` → ADMIN | `program.config` | US-E04-09…11 | TB-WAL |
| ↳ | `POST /v1/demo/jobs/expire-points`, `/release-pending`, `/expiry-warnings` → ADMIN | `demo.admin` | US-E11-08 | TB-WAL |
| WAL-21 enum | lotto: `PENDING, ACTIVE, EXHAUSTED, EXPIRED` | wallet §2 | US-E04-01, US-E04-07 | TB-WAL |
| ↳ | movimento: `EARN, SPEND, EXPIRE, ADJUST_CREDIT, ADJUST_DEBIT, REFUND, RELEASE` | docs/03 §4.2 | US-E10-10 | TB-WAL |
| ↳ | storico tier: `UPGRADE, DOWNGRADE, RETAIN, INITIAL` | wallet §2 | US-E04-15 | TB-WAL |
| ↳ | edizione: `PLANNED, ACTIVE, CLOSED`; origine movimento: `CAMPAIGN, REDEMPTION, MANUAL, SYSTEM` | docs/03 §4.4, wallet §2 | US-E04-11, US-E10-10 | TB-WAL |
| ↳ | motivo di rifiuto spesa: `INSUFFICIENT_BALANCE, MEMBER_NOT_ACTIVE` | EVT-FACT-22 | US-E05-07 | TB-WAL |

### 5.6 reward-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| RWD-01 `RedemptionService#request` (ordine dei controlli) | 400 `memberId`/`rewardCode` mancanti | docs/06 §2 | US-E05-05 | TB-RWD |
| ↳ | 422 `MEMBER_NOT_ACTIVE`: membro sconosciuto allo snapshot o non `ACTIVE` | docs/03 §5 | US-E05-05, US-E10-11 | TB-RWD |
| ↳ | 422 `REWARD_NOT_AVAILABLE`: inesistente, non `LIVE`, fuori validità o fuori segmento | docs/03 §5, F-RWD-04 | US-E05-05 | TB-RWD |
| ↳ | 422 `TIER_NOT_ELIGIBLE` | reward §3, §7 | US-E05-05 | TB-RWD |
| ↳ | 422 `SHIPPING_REQUIRED` (PHYSICAL senza spedizione) | reward §3 | US-E05-05, US-E10-05 | TB-RWD |
| ↳ | 422 `MEMBER_LIMIT_REACHED` (richieste attive ≥ limite per membro) | F-RWD-03 | US-E05-05 | TB-RWD |
| ↳ | 422 `REWARD_SOLD_OUT` (decremento atomico fallito) | reward §5, §7 | US-E05-05 | TB-RWD |
| ↳ | 202 `PENDING` + `reward.redemption.requested` (costo = soglia della fascia; `correlationId` della saga) | F-RWD-05, docs/03 §5 | US-E05-05, US-E10-04 | TB-RWD |
| RWD-02 `#onPointsSpent` | richiesta sconosciuta ⇒ ignorata (WARN, non DLQ) | reward §5 | US-E05-06 | TB-RWD |
| ↳ | `PENDING` ⇒ `CONFIRMED` + `reward.redemption.confirmed` + evasione | docs/03 §5 | US-E05-06 | TB-RWD |
| ↳ | `REJECTED`/`CANCELLED` ⇒ `reward.redemption.cancelled (LATE_SPEND, refund=true)` | reward §5 | US-E05-08, US-E05-09 | TB-RWD |
| ↳ | `CONFIRMED`/`FULFILLED` ⇒ nulla (rielaborazione) | reward §7 | US-E05-06, US-E12-02 | TB-RWD |
| RWD-03 `#fulfil` | `AUTO_COUPON` ⇒ coupon `ISSUED`, `coupon.issued (REDEMPTION)` + `reward.redemption.fulfilled` | reward §5 | US-E05-06 | TB-RWD |
| ↳ | `AUTO_COUPON` con pool vuoto ⇒ resta `CONFIRMED`, `needsAttention` | docs/03 §5 | US-E05-12 | TB-RWD |
| ↳ | `AUTO_COUPON` su premio senza pool ⇒ resta `CONFIRMED`, `needsAttention` | nessuna | US-E05-12 | TB-RWD |
| ↳ | `INSTANT` ⇒ `FULFILLED` subito | reward §5 | US-E05-06 | TB-RWD |
| ↳ | `MANUAL` (o premio non più trovato) ⇒ resta `CONFIRMED` in coda BO-13 | reward §5 | US-E05-10 | TB-RWD |
| RWD-04 `#onSpendRejected` | sconosciuta ⇒ ignorata; non `PENDING` ⇒ ignorata | reward §5 | US-E05-07 | TB-RWD |
| ↳ | `PENDING` ⇒ `REJECTED (motivo del wallet)`, stock ripristinato, `reward.redemption.rejected` | docs/03 §5 | US-E05-07, US-E10-06 | TB-RWD |
| RWD-05 `#timeoutPending(asOf)` | `PENDING` da più di 10 min ⇒ `REJECTED (TIMEOUT)`, stock ripristinato | docs/03 §5, reward §5 | US-E05-08 | TB-RWD |
| ↳ | già non `PENDING` al lock ⇒ saltata | nessuna | US-E05-08 | TB-RWD |
| RWD-06 `#cancelByMember` | 404 (anche richiesta di un altro membro) | reward §3 | US-E05-09 | TB-RWD |
| ↳ | 409 `REDEMPTION_NOT_CANCELLABLE` se non `PENDING` | reward §3 | US-E05-09 | TB-RWD |
| ↳ | `CANCELLED (MEMBER)`, stock ripristinato, `refund=false` | docs/03 §5 | US-E05-09 | TB-RWD |
| RWD-07 `#fulfilManually` | 404 · 409 `REDEMPTION_NOT_FULFILLABLE` (non `CONFIRMED` o evasione non MANUAL) | reward §3 | US-E05-10 | TB-RWD |
| ↳ | 422 `NOTE_REQUIRED` | reward §3 | US-E05-10 | TB-RWD |
| ↳ | `FULFILLED` con nota (+ «tracking …») + `reward.redemption.fulfilled` + audit | F-RWD-06 | US-E05-10, US-E10-05 | TB-RWD |
| RWD-08 `#cancelWithRefund` | 404 · 409 `REDEMPTION_NOT_CANCELLABLE` (non `CONFIRMED`, quindi anche `FULFILLED`) | F-RWD-07 | US-E05-11 | TB-RWD |
| ↳ | 422 `REASON_REQUIRED` | reward §3 | US-E05-11 | TB-RWD |
| ↳ | `CANCELLED`, stock ripristinato, coupon `ISSUED/AVAILABLE` ⇒ `VOID`, `needsAttention` spento, `refund=true` + audit | reward §5 | US-E05-11 | TB-RWD |
| RWD-09 `#retryFulfilment` | 404 | docs/06 §2 | US-E05-12 | TB-RWD |
| ↳ | 409 `REDEMPTION_NOT_RETRYABLE` (non `CONFIRMED` o senza `needsAttention`) | nessuna | US-E05-12 | TB-RWD |
| ↳ | pool ancora vuoto ⇒ 409 `COUPON_POOL_EMPTY` | reward §5 | US-E05-12 | TB-RWD |
| ↳ | ok ⇒ `FULFILLED` + audit | reward §5 | US-E05-12 | TB-RWD |
| RWD-10 `CouponService#use` (F-CPN-03) | 404 codice inesistente | reward §3 | US-E05-14 | TB-RWD |
| ↳ | 409 `COUPON_ALREADY_USED` · 409 `COUPON_VOID` · 409 `COUPON_NOT_ISSUED` (AVAILABLE) | reward §3 | US-E05-14 | TB-RWD |
| ↳ | 410 `COUPON_EXPIRED` (stato `EXPIRED` o `ISSUED` oltre la scadenza) | reward §3 | US-E05-14 | TB-RWD |
| ↳ | `ISSUED` valido ⇒ `USED` + `coupon.used` + audit | EVT-FACT-46 | US-E05-14 | TB-RWD |
| RWD-11 `CouponService#void` | 409 `COUPON_ALREADY_USED` · 409 `COUPON_VOID` | Q-52 | US-E05-15 | TB-RWD |
| ↳ | `VOID` + audit | BO-12 | US-E05-15 | TB-RWD |
| RWD-12 `#createPool` | 400 codice/nome · 422 `COUPON_PREFIX_INVALID` (2–10 `A-Z0-9`) · 422 `COUPON_VALIDITY_INVALID` (1…3650 gg) · 409 `CODE_TAKEN` | F-CPN-01 | US-E05-13 | TB-RWD |
| RWD-13 `#generate` | 422 `COUPON_COUNT_INVALID` (∉ 1…5000); codici `prefisso-XXXX-XXXX` | reward §3 | US-E05-13 | TB-RWD |
| RWD-14 `#import` | 422 `COUPON_IMPORT_EMPTY` · 422 `COUPON_COUNT_INVALID` (> 5000) | reward §3 | US-E05-13 | TB-RWD |
| ↳ | duplicati/nulli ⇒ `skipped[]`, gli altri `imported` | reward §3 | US-E05-13 | TB-RWD |
| RWD-15 `#issue` | nessun codice `AVAILABLE` ⇒ vuoto | docs/03 §5 | US-E05-12, US-E05-16 | TB-RWD |
| ↳ | `ISSUED`, scadenza = oggi + `validity_days`, `coupon.issued {origin}` | reward §5 | US-E05-06, US-E05-16 | TB-RWD |
| RWD-16 `CouponIssueHandler` (effetto `coupon.issue`) | DLQ `INVALID_EFFECT` (senza membro o dati) | nessuna | US-E05-16 | TB-RWD |
| ↳ | `effectId` già usato ⇒ nulla | reward §5 | US-E05-16 | TB-RWD |
| ↳ | DLQ `REWARD_NOT_FOUND` · `COUPON_POOL_MISSING` · `COUPON_POOL_EMPTY` (non ritentabili) | reward §5 | US-E05-16 | TB-RWD |
| ↳ | coupon `CAMPAIGN` emesso | F-CPN-02 | US-E05-16 | TB-RWD |
| RWD-17 `#expire(asOf)` · vista coupon | `ISSUED` scaduti ⇒ `EXPIRED` (job 02:30 Rome o BO-30) | reward §5 | US-E05-17 | TB-RWD |
| ↳ | in lettura un `ISSUED` scaduto appare `EXPIRED` prima del job | nessuna | US-E05-17 | TB-RWD |
| RWD-18 `PortalCatalogService` | `stockState`: illimitato ⇒ `AVAILABLE`; residuo ≤ 0 ⇒ `SOLD_OUT`; residuo × 10 < totale ⇒ `LOW`; altrimenti `AVAILABLE` | reward §3, BO-10 | US-E05-04 | TB-RWD |
| ↳ | tier non ammesso ⇒ visibile con `lockedByTier` | reward §3 | US-E05-04 | TB-RWD |
| ↳ | fuori segmento ⇒ escluso dal catalogo | reward §3 | US-E05-04 | TB-RWD |
| ↳ | `perMemberLimitReached` | reward §3 | US-E05-04 | TB-RWD |
| RWD-19 `CatalogAdminService#update` | 409 `CODE_IMMUTABLE` · 409 `VERSION_CONFLICT` | docs/06 §2 | US-E05-01 | TB-RWD |
| ↳ | `DRAFT`/`PAUSED` ⇒ tutto modificabile | reward §3 | US-E05-01 | TB-RWD |
| ↳ | `LIVE` ⇒ solo `stockTotal`, `validTo`, `imageUrl`; altro ⇒ 409 `REWARD_LIVE_LOCKED` | reward §3 | US-E05-01 | TB-RWD |
| ↳ | ⚠ `IN_REVIEW`/`APPROVED`/`ENDED`/`ARCHIVED` ⇒ 409 `REWARD_NOT_EDITABLE` (reward §3 ammette anche `REJECTED`, stato non più esistente) | reward §3 | US-E05-01 | TB-RWD |
| ↳ | cambio di `stockTotal` sposta il residuo | reward §3 | US-E05-01 | TB-RWD |
| RWD-20 `#create` / `#validate` | 400 codice mancante · 409 `CODE_TAKEN` | docs/06 §2 | US-E05-01 | TB-RWD |
| ↳ | 422 `REWARD_INVALID`: nome; tipo ∉ 5; evasione ∉ 3; fascia o categoria inesistente; `AUTO_COUPON` su non-COUPON; stock < 0; limite per membro < 1; `validTo ≤ validFrom` | F-RWD-01, F-RWD-03 | US-E05-01 | TB-RWD |
| RWD-21 fasce | 422 `BAND_INVALID` · 422 `BAND_THRESHOLD_DUPLICATE` · 409 `BAND_IN_USE` (eliminazione con premi) · 404 | reward §3 | US-E05-02 | TB-RWD |
| RWD-22 categorie | 422 `CATEGORY_INVALID` | F-RWD-01 | US-E05-03 | TB-RWD |
| RWD-23 `#transition` / `#duplicate` | policy `REWARD` ⇒ LEGAL sempre (vedi CMN-04…07); `reward.status.changed` | F-RWD-08, Q-53 | US-E05-01, US-E08-01 | TB-RWD |
| ↳ | duplicato `-COPY` in `DRAFT` | reward §3 | US-E05-01 | TB-RWD |
| RWD-24 Job | timeout ogni 60 s (interruttore proprio, acceso) | Q-55 | US-E05-08 | TB-RWD |
| ↳ | scadenza coupon 02:30 Europe/Rome (`jobs.enabled`) | reward §5 | US-E05-17 | TB-RWD |
| RWD-25 Guardie | `POST/PUT` categorie, fasce (anche `DELETE`), premi, `…/duplicate`, pool, `…/generate`, `…/import` → ADMIN, MARKETING | `object.edit` | US-E05-01…03, US-E05-13 | TB-RWD |
| ↳ | `POST /v1/rewards/{id}/transitions` → ADMIN, MARKETING, LEGAL (+ `GovernedTransitions`) | `object.approve` | US-E08-04 | TB-RWD |
| ↳ | `POST /v1/redemptions/{id}/fulfil`, `/cancel`, `/retry-fulfilment` → ADMIN, CARE | `redemption.handle` | US-E05-10…12 | TB-RWD |
| ↳ | `POST /v1/coupons/{code}/use` → ADMIN, MARKETING, LEGAL, CARE; `…/void` → ADMIN, CARE | Q-52 | US-E05-14, US-E05-15 | TB-RWD |
| ↳ | `POST /v1/demo/jobs/timeout-redemptions`, `/expire-coupons` → ADMIN | `demo.admin` | US-E11-08 | TB-RWD |
| ↳ | `POST /v1/portal/redemptions`, `…/cancel` → nessuna guardia (membro esplicito) | docs/06 §3 | US-E05-05, US-E05-09 | TB-RWD |
| RWD-26 enum | richiesta: `PENDING, CONFIRMED, FULFILLED, REJECTED, CANCELLED` | docs/03 §5 | US-E05-06 | TB-RWD |
| ↳ | coupon: `AVAILABLE, ISSUED, USED, EXPIRED, VOID`; origine `REDEMPTION, CAMPAIGN` | docs/03 §5 | US-E05-14 | TB-RWD |
| ↳ | premio: stati della macchina comune; tipo `PHYSICAL, COUPON, DIGITAL, DONATION, EXPERIENCE`; evasione `AUTO_COUPON, MANUAL, INSTANT` | reward §2 | US-E05-01 | TB-RWD |
| ↳ | motivi: `TIMEOUT`, `INSUFFICIENT_BALANCE`, `MEMBER_NOT_ACTIVE` (rifiuto); `MEMBER`, `LATE_SPEND`, testo dell'operatore (annullo) | reward §5 | US-E05-07…11 | TB-RWD |

### 5.7 gamification-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| GAM-01 `PlayService#play` (ordine dei controlli) | 422 `MEMBER_REQUIRED` | nessuna | US-E06-05 | TB-GAM |
| ↳ | 404 concorso inesistente | docs/06 §2 | US-E06-05 | TB-GAM |
| ↳ | 422 `MEMBER_NOT_ACTIVE` (anche membro sconosciuto) | docs/03 §2 | US-E06-05 | TB-GAM |
| ↳ | 422 `CONTEST_NOT_LIVE`: stato ≠ `LIVE` o adesso ∉ [`startAt`, `endAt`) | gamification §3 | US-E06-05 | TB-GAM |
| ↳ | 422 `DAILY_LIMIT_REACHED`: giocate di oggi (Rome) ≥ `maxPlaysPerMemberPerDay` | docs/03 §6 | US-E06-05 | TB-GAM |
| ↳ | 422 `NO_PLAYS_AVAILABLE` | gamification §3 | US-E06-05 | TB-GAM |
| ↳ | tipo `FREE_DAILY` se la gratuita è disponibile, altrimenti `CREDIT` | gamification §5 | US-E06-06 | TB-GAM |
| ↳ | `maxWinsPerMember` raggiunto ⇒ `LOSE` senza claim | docs/03 §6 | US-E06-05 | TB-GAM |
| ↳ | claim riuscito ⇒ `WIN`, `quantity_remaining −1`, `contest.played` + `contest.won` | F-IW-04 | US-E06-05, US-E06-08 | TB-GAM |
| ↳ | nessun istante maturo ⇒ `LOSE` + `contest.played` | F-IW-04 | US-E06-05 | TB-GAM |
| ↳ | premio `PHYSICAL` ⇒ consegna `PENDING`; altri `NA` | gamification §2 | US-E06-08 | TB-GAM |
| GAM-02 `#credits` | gratuita = `freePlayDaily` e non usata oggi (Rome) | docs/03 §6 | US-E06-06 | TB-GAM |
| ↳ | crediti = Σ `play_grant.count` − giocate `CREDIT`; disponibili = gratuita + crediti, limitati da (max − giocate di oggi) | docs/03 §6 | US-E06-06 | TB-GAM |
| GAM-03 `InstantRepository#claim` | primo `OPEN` con `instant_at ≤ now`, ordine `instant_at, id`, `FOR UPDATE SKIP LOCKED` | docs/03 §6 | US-E06-07 | TB-GAM |
| ↳ | lock consultivo per membro+concorso | gamification §5 | US-E06-07 | TB-GAM |
| GAM-04 `InstantGenerator` | `UNIFORM` su [`startAt`, `endAt`) | docs/03 §6 | US-E06-02 | TB-GAM |
| ↳ | `BUSINESS_HOURS`: solo 08–22 Europe/Rome, ricampionamento | docs/03 §6 | US-E06-02 | TB-GAM |
| ↳ | `endAt ≤ startAt` o nessun orario 08–22 nel periodo ⇒ eccezione | nessuna | US-E06-02 | TB-GAM |
| ↳ | stesso seme + parametri ⇒ stessi istanti (`SplittableRandom`) | gamification §5, §7 | US-E06-02 | TB-GAM |
| GAM-05 `ContestAdminService#create` | 422 `CONTEST_INVALID` (codice) · 409 `CODE_TAKEN`; seme dal codice se assente | F-IW-01, F-IW-03 | US-E06-01 | TB-GAM |
| GAM-06 `#update` | 409 `CODE_IMMUTABLE` · 409 `VERSION_CONFLICT` | docs/06 §2, Q-112 | US-E06-01 | TB-GAM |
| ↳ | 409 `CONTEST_NOT_EDITABLE` (ENDED, ARCHIVED) | nessuna | US-E06-01 | TB-GAM |
| ↳ | `LIVE`/`PAUSED`: premi, inizio, istanti, regole di gioco o regolamento ⇒ 409 `CONTEST_LIVE_LOCKED`; nome, descrizione, `endAt` ammessi (docs/03 §3.6) | gamification §3 | US-E06-01 | TB-GAM |
| ↳ | prima di `LIVE`: cambio di premi/periodo/distribuzione/seme ⇒ istanti cancellati | docs/03 §6 | US-E06-01 | TB-GAM |
| GAM-07 `#transition` | verso `LIVE` senza istanti ⇒ 422 `INSTANTS_NOT_GENERATED` | gamification §3 | US-E06-04 | TB-GAM |
| ↳ | policy `CONTEST` ⇒ LEGAL sempre (vedi CMN-04…07); `contest.status.changed` | docs/06 §7 | US-E06-04, US-E08-01 | TB-GAM |
| GAM-08 `#generateInstants` | 409 `INSTANTS_LOCKED` (da `LIVE` in poi) | docs/03 §6 | US-E06-02 | TB-GAM |
| ↳ | 422 `CONTEST_INVALID` (nessun premio) | nessuna | US-E06-02 | TB-GAM |
| ↳ | un istante per unità di premio + audit | F-IW-03 | US-E06-02 | TB-GAM |
| GAM-09 `#plantInstant` (demo) | 409 `CONTEST_NOT_LIVE` · 422 `PRIZE_NOT_FOUND` · 422 `NO_OPEN_INSTANT` | gamification §3 | US-E06-09 | TB-GAM |
| ↳ | istante a `min(now − 1 s, più vecchio aperto − 1 s)`, `planted=true`, montepremi invariato | Q-62 | US-E06-09, US-E10-07 | TB-GAM |
| GAM-10 `#closeContests(asOf)` | `LIVE` con `endAt ≤ asOf` ⇒ `ENDED`, istanti `OPEN` ⇒ `VOID`, audit | docs/03 §6, gamification §5 | US-E06-10 | TB-GAM |
| ↳ | `PAUSED` scaduti non chiusi | nessuna | US-E06-10 | TB-GAM |
| GAM-11 `#delivery` (premi fisici) | 404 · 409 `DELIVERY_NOT_APPLICABLE` (non WIN o non PHYSICAL) · 422 `DELIVERY_STATUS_INVALID` (∉ PENDING, DELIVERED) | F-IW-07 | US-E06-08 | TB-GAM |
| ↳ | aggiornata con nota + audit | gamification §3 | US-E06-08 | TB-GAM |
| GAM-12 `#duplicate` | `<code>-COPY-n`, premi pieni, seme dal nuovo codice, istanti da generare | Q-113 | US-E06-01 | TB-GAM |
| GAM-13 `PlaysGrantHandler` | DLQ `INVALID_EFFECT` · DLQ `CONTEST_NOT_FOUND` | campaign §5 | US-E06-06 | TB-GAM |
| ↳ | `effectId` già usato ⇒ nulla | RNF-03 | US-E06-06 | TB-GAM |
| ↳ | credito registrato + `contest.plays.granted` | EVT-FACT-50 | US-E06-06 | TB-GAM |
| GAM-14 `BadgeAwardHandler` · `BadgeService#award` | DLQ `INVALID_EFFECT` · DLQ `BADGE_NOT_FOUND` | campaign §5 | US-E06-14 | TB-GAM |
| ↳ | badge già posseduto ⇒ nessun fatto | nessuna | US-E06-14 | TB-GAM |
| ↳ | nuovo ⇒ `badge.awarded {origin ACHIEVEMENT/CAMPAIGN}` | F-ACH-03 | US-E06-14 | TB-GAM |
| GAM-15 `AchievementService#onAction` | membro assente, tipo non azione o membro non `ACTIVE` ⇒ ignorata | nessuna | US-E06-13 | TB-GAM |
| ↳ | filtro `data.*` non soddisfatto ⇒ saltato | docs/03 §8 | US-E06-13 | TB-GAM |
| ↳ | non ripetibile e già completato, o periodo già completato ⇒ saltato | docs/03 §8 | US-E06-13 | TB-GAM |
| ↳ | valore invariato ⇒ nessun fatto; cambiato ⇒ `achievement.progressed` | gamification §5 | US-E06-13 | TB-GAM |
| ↳ | ≥ traguardo ⇒ `achievement.completed` + badge collegato | F-ACH-03 | US-E06-13 | TB-GAM |
| ↳ | azioni interne contano solo se elencate in `action_types` | gamification §5 | US-E06-13 | TB-GAM |
| GAM-16 `AchievementRules` | `periodKey`: `MONTH` ⇒ `aaaa-mm`; `EDITION` ⇒ `ED-aaaa` | docs/03 §8, Q-59 | US-E06-13 | TB-GAM |
| ↳ | ⚠ ogni altro periodo (anche `DAY`, `WEEK` di docs/03 §8) ⇒ `EVER` | docs/03 §8 | US-E06-13 | TB-GAM |
| ↳ | `COUNT` +1 · `SUM` del campo (troncato, ≤ 0 ignorato) · `DISTINCT_TYPES` (tipo nuovo) | docs/03 §8 | US-E06-13 | TB-GAM |
| ↳ | `STREAK` (`DAY`/`WEEK`): stessa unità ⇒ invariato; unità successiva ⇒ +1; buco ⇒ 1 | docs/03 §8 | US-E06-13 | TB-GAM |
| ↳ | filtro `all`/`any`; campo assente o `null` ⇒ foglia falsa con ogni comparatore | docs/03 §3.3 | US-E06-13 | TB-GAM |
| GAM-17 `AchievementAdminService` | 422 `ACHIEVEMENT_INVALID` · 422 `BADGE_INVALID` · 409 `CODE_TAKEN` · 409 `CODE_IMMUTABLE` · 404 | F-ACH-01 | US-E06-12 | TB-GAM |
| GAM-18 `LeaderboardService` | `wallet.points.earned` con importo ≤ 0 ⇒ ignorato; classifiche PTS/STS della valuta ⇒ +importo effettivo | gamification §5 | US-E06-15 | TB-GAM |
| ↳ | `ACTION_COUNT` ⇒ +1 se il tipo è elencato | F-LDB-01 | US-E06-15 | TB-GAM |
| ↳ | 422 `LEADERBOARD_INVALID` (codice, nome, metrica, periodo, `ACTION_COUNT` senza tipi, top N ∉ 3…50, stato) · 409 `CODE_TAKEN` · 409 `CODE_IMMUTABLE` | F-LDB-01 | US-E06-15 | TB-GAM |
| ↳ | 409 `LEADERBOARD_LOCKED` (metrica o periodo cambiati) | nessuna | US-E06-15 | TB-GAM |
| ↳ | ranking: solo membri `ACTIVE`; parimerito ⇒ chi ha raggiunto prima; nome = nickname | docs/03 §8, gamification §5 | US-E06-15 | TB-GAM |
| GAM-19 `MemberSnapshotHandler` | nickname e stato da `member.registered/updated/status.changed`; anonimizzazione ripulisce la nota di consegna | gamification §4, Q-124 | US-E02-05, US-E06-15 | TB-GAM |
| GAM-20 Job | fine concorsi ogni 5 min (`jobs.enabled`) | gamification §5 | US-E06-10 | TB-GAM |
| GAM-21 Guardie | `POST/PUT` concorsi, obiettivi, badge, classifiche, `…/duplicate`, `…/instants/generate` → ADMIN, MARKETING | `object.edit` | US-E06-01, US-E06-02, US-E06-12, US-E06-15 | TB-GAM |
| ↳ | `POST /v1/contests/{id}/transitions` → ADMIN, MARKETING, LEGAL (+ `GovernedTransitions`) | `object.approve` | US-E06-04 | TB-GAM |
| ↳ | `GET /v1/contests/{id}/instants` → ADMIN, LEGAL (MARKETING 403, vede l'istogramma) | `instants.view` | US-E06-03 | TB-GAM |
| ↳ | `POST /v1/plays/{playId}/delivery` → ADMIN, CARE | `delivery.handle` | US-E06-08 | TB-GAM |
| ↳ | `POST /v1/demo/contests/{id}/plant-instant`, `/jobs/close-contests` → ADMIN | `demo.admin` | US-E06-09, US-E11-08 | TB-GAM |
| ↳ | `POST /v1/portal/contests/{code}/play` → nessuna guardia | docs/06 §3 | US-E06-05 | TB-GAM |
| GAM-22 enum | meccanica `WHEEL, SCRATCH, BOX` (docs/02/08/09: `GIFT`) · distribuzione `UNIFORM, BUSINESS_HOURS` | Q-56 | US-E06-01 | TB-GAM |
| ↳ | istante `OPEN, CLAIMED, VOID` · premio `POINTS, COUPON, PHYSICAL` | gamification §2 | US-E06-05 | TB-GAM |
| ↳ | giocata `FREE_DAILY, CREDIT` · esito `WIN, LOSE` · consegna `NA, PENDING, DELIVERED` | gamification §2 | US-E06-05, US-E06-08 | TB-GAM |
| ↳ | metrica `COUNT, SUM, DISTINCT_TYPES, STREAK` · classifica `PTS_EARNED, STS_EARNED, ACTION_COUNT` × `MONTH, EDITION, ALL_TIME` | gamification §2 | US-E06-13, US-E06-15 | TB-GAM |

### 5.8 engagement-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| ENG-01 `ContentSelection#exclusion` | `NOT_LIVE` · `OUT_OF_SCHEDULE` (`startAt` futuro o `endAt` passato) · `NOT_IN_AUDIENCE` | engagement §3, docs/03 §9 | US-E07-03, US-E07-05 | TB-ENG |
| ENG-02 `#inAudience` | pubblico vuoto ⇒ tutti | engagement §2 | US-E07-03 | TB-ENG |
| ↳ | ⚠ `tiers`, `segments`, `statuses` in **AND** tra loro (il pubblico delle campagne è tier **o** segmento) | nessuna | US-E07-03 | TB-ENG |
| ↳ | `registeredWithinDays`, `daysOfWeek` (Rome) | Q-71 | US-E07-03, US-E07-04 | TB-ENG |
| ENG-03 `#select` | ordine `priority` decrescente, poi `code`; limiti HOME_HERO 1, HOME_GRID 6, CATALOG_TOP 1, CONTEST 3, WIN 1 | docs/03 §9, Q-72 | US-E07-03 | TB-ENG |
| ENG-04 `#selectPopup` | mai visto ⇒ candidato; `ALWAYS` ⇒ candidato | docs/03 §9 | US-E07-04 | TB-ENG |
| ↳ | `ONCE_PER_DAY` visto prima di oggi (Rome) ⇒ candidato; visto oggi ⇒ `FREQUENCY` | docs/03 §9 | US-E07-04 | TB-ENG |
| ↳ | `ONCE` già visto ⇒ `FREQUENCY` | docs/03 §9 | US-E07-04 | TB-ENG |
| ↳ | al più 1; nessuno ⇒ 204; la vista si registra solo con `seen` | engagement §3, §5 | US-E07-04 | TB-ENG |
| ENG-05 `ContentService#create/update` | 422 `CONTENT_INVALID` · 409 `CODE_TAKEN` · 409 `CODE_IMMUTABLE` · 409 `KIND_IMMUTABLE` · 409 `CONTENT_NOT_EDITABLE` · 409 `VERSION_CONFLICT` | F-CNT-01, F-CNT-02 | US-E07-01 | TB-ENG |
| ENG-06 `#transition` | `PUBLISH` DRAFT→LIVE · `PAUSE` · `RESUME` · `END` · `ARCHIVE` (DRAFT/ENDED); altro ⇒ 409 `INVALID_TRANSITION` | engagement §3, docs/06 §7 | US-E07-02 | TB-ENG |
| ↳ | `content.status.changed` + audit | EVT-FACT-61 | US-E07-02 | TB-ENG |
| ENG-07 Job fine contenuti | `LIVE` con `endAt` passato ⇒ `ENDED` (ogni 10 min, `jobs.enabled`) | engagement §5 | US-E07-02 | TB-ENG |
| ENG-08 `FactHandler` | `message.delivered` ⇒ ignorato (niente regole né webhook) | engagement §5 | US-E07-09, US-E08-09 | TB-ENG |
| ↳ | snapshot membro da `member.*`, `tier.*`, `member.segment.*`; anonimizzazione | engagement §4, Q-125 | US-E02-05 | TB-ENG |
| ENG-09 `NotificationService` | regole abilitate per tipo di fatto; condizione su `data.*` falsa ⇒ saltata; template assente ⇒ saltata | docs/03 §9, F-MSG-01 | US-E07-08, US-E07-09 | TB-ENG |
| ENG-10 `InboxService#deliver` | membro `ANONYMIZED` ⇒ nessun messaggio; `BLOCKED` sì | Q-70 | US-E07-09 | TB-ENG |
| ↳ | duplicato (`memberId, sourceEventId, templateCode`) ⇒ nessun messaggio | docs/03 §9 | US-E07-09, US-E12-02 | TB-ENG |
| ↳ | nuovo ⇒ inbox + `message.delivered` | EVT-FACT-60 | US-E07-09 | TB-ENG |
| ↳ | `EMAIL_FAKE` ⇒ solo registro `/v1/messages`, non inbox né non letti | Q-67 | US-E07-09 | TB-ENG |
| ENG-11 `MessageSendHandler` | DLQ `INVALID_EFFECT` · DLQ `TEMPLATE_NOT_FOUND` | campaign §5 | US-E07-10 | TB-ENG |
| ↳ | deduplica su `effectId`; contesto = effetto con `params` sovrapposti | Q-68 | US-E07-10 | TB-ENG |
| ENG-12 `TemplateEngine` | percorso assente ⇒ stringa vuota + WARN | engagement §5 | US-E07-07 | TB-ENG |
| ↳ | `\|number` su non numero, `\|date` su non data ⇒ valore grezzo + WARN | engagement §5 | US-E07-07 | TB-ENG |
| ↳ | segnaposto con radice `data.`/`member.`/`event.` | Q-66 | US-E07-07 | TB-ENG |
| ENG-13 `TemplateAdminService` | 422 `TEMPLATE_INVALID` · 409 `CODE_TAKEN` · 409 `CODE_IMMUTABLE` · 409 `VERSION_CONFLICT` · 404; `render` su template salvato | F-MSG-02, Q-75 | US-E07-07 | TB-ENG |
| ENG-14 `RuleAdminService` | 422 `RULE_INVALID` · 409 `CODE_TAKEN` · 409 `CODE_IMMUTABLE` · 409 `VERSION_CONFLICT` · 404 | F-MSG-01, Q-69 | US-E07-08 | TB-ENG |
| ENG-15 `ThemeService#update` | 422 `THEME_INVALID` (nome > 40, colori non `#RRGGBB`, logo né `/…` né `https://…`) | engagement §3 | US-E07-12 | TB-ENG |
| ↳ | 422 `THEME_CONTRAST_TOO_LOW` (`night` su `primary` o su `bg` < 4,5:1) | engagement §3, Q-79 | US-E07-12 | TB-ENG |
| ↳ | 409 `VERSION_CONFLICT` · ok ⇒ `GET /v1/portal/theme` aggiornato | Q-79 | US-E07-12 | TB-ENG |
| ENG-16 `InboxService` portale | 400 `memberId` mancante · 404 messaggio inesistente o di un altro membro · `read-all` ⇒ `{marked, unread}` | engagement §3 | US-E07-11 | TB-ENG |
| ENG-17 `WebhookUrlPolicy` | vuoto · > lunghezza massima · non valido · non assoluto · credenziali · frammento ⇒ errore di campo | engagement §5 | US-E08-08 | TB-ENG |
| ↳ | `http://` ammesso solo verso localhost nel profilo `local`; altrimenti solo `https://` | engagement §5 | US-E08-08 | TB-ENG |
| ↳ | indirizzi privati, loopback, link-local, `.local`, `.internal` bloccati (tranne `local`) | Q-99 | US-E08-08 | TB-ENG |
| ENG-18 `WebhookService#validate/create/update` | 422 `WEBHOOK_INVALID` (codice, nome, URL, nessun tipo, `message.delivered`, tipo sconosciuto) · 409 `CODE_TAKEN` · 409 `CODE_IMMUTABLE` · 409 `VERSION_CONFLICT` | engagement §5 | US-E08-08 | TB-ENG |
| ↳ | `secret` restituito solo alla creazione | engagement §3 | US-E08-08 | TB-ENG |
| ENG-19 `WebhookService#enqueue` | evento non fatto o senza id ⇒ ignorato | nessuna | US-E08-09 | TB-ENG |
| ↳ | nessun webhook **abilitato** sottoscritto ⇒ nessuna consegna | F-WBH-01 | US-E08-09 | TB-ENG |
| ↳ | consegna `PENDING` per coppia evento×webhook (senza duplicati) | Q-97 | US-E08-09 | TB-ENG |
| ENG-20 `WebhookRetry#next` | 2xx ⇒ `OK` | engagement §5 | US-E08-09 | TB-ENG |
| ↳ | tentativo 1, 2, 3 fallito ⇒ `FAILED` + prossimo a 1, 5, 15 min | engagement §5 | US-E08-09 | TB-ENG |
| ↳ | 4° tentativo fallito ⇒ `GAVE_UP` | engagement §5, §7 | US-E08-09 | TB-ENG |
| ↳ | ogni non-2xx (anche 3xx: redirect non seguiti) è un fallimento; timeout 5 s | Q-98, engagement §5 | US-E08-09 | TB-ENG |
| ↳ | firma `X-LH-Signature: sha256=HMAC(secret, body)` + `X-LH-Event-Id`, `X-LH-Delivery-Id` | engagement §5 | US-E08-09 | TB-ENG |
| ENG-21 `#retry` manuale | 409 `DELIVERY_NOT_RETRYABLE` (non FAILED/GAVE_UP) · 409 `DELIVERY_BUSY` (tentativo in corso) · 404 | Q-98 | US-E08-10 | TB-ENG |
| ↳ | un solo tentativo; dopo `GAVE_UP` un fallimento resta `GAVE_UP` | Q-98 | US-E08-10 | TB-ENG |
| ENG-22 `#test` | evento di esempio del primo tipo sottoscritto, id/correlazione nuovi, 201 con la consegna | Q-100, Q-103 | US-E08-09 | TB-ENG |
| ENG-23 Job | dispatcher ogni 30 s (interruttore proprio, acceso) · pulizie 03:30 Rome (inbox 180 gg, pop-up 90, consegne 14) | Q-101, engagement §5 | US-E08-09, US-E09-06 | TB-ENG |
| ENG-24 Guardie | `POST/PUT` contenuti, `…/transitions`, `…/duplicate`, template, regole, `PUT /v1/theme` → ADMIN, MARKETING | `content.write` | US-E07-01, US-E07-07, US-E07-08, US-E07-12 | TB-ENG |
| ↳ | webhook (`POST/PUT/DELETE`, `…/test`, `…/retry`), `POST /v1/demo/jobs/deliver-webhooks` → ADMIN | `webhook.write`, Q-103 | US-E08-08…10 | TB-ENG |
| ↳ | `POST …/render` e gli endpoint `/v1/portal/**` → nessuna guardia | docs/06 §3 | US-E07-07, US-E07-11 | TB-ENG |
| ENG-25 enum | tipo `CARD, POPUP, BANNER` · posizione `HOME_HERO, HOME_GRID, CATALOG_TOP, CONTEST, WIN` · legame `NONE, CONTEST, CAMPAIGN, REWARD, PRIZE` | engagement §2 | US-E07-01 | TB-ENG |
| ↳ | frequenza `ONCE, ONCE_PER_DAY, ALWAYS` · stato `DRAFT, LIVE, PAUSED, ENDED, ARCHIVED` | engagement §2 | US-E07-02, US-E07-04 | TB-ENG |
| ↳ | canale `INAPP, EMAIL_FAKE` · categoria `POINTS, TIER, REWARD, GAME, PROGRAM` | engagement §2 | US-E07-07 | TB-ENG |
| ↳ | consegna `PENDING, OK, FAILED, GAVE_UP` · esclusione `NOT_IN_AUDIENCE, OUT_OF_SCHEDULE, NOT_LIVE, FREQUENCY` | engagement §2, §3 | US-E08-09, US-E07-05 | TB-ENG |

### 5.9 insight-service

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| INS-01 `DlqService#reprocess` | 404 · 409 `DLQ_NOT_OPEN` | Q-107 | US-E08-11 | — |
| ↳ | famiglia ≠ `ACTION` ⇒ 409 `NOT_REPROCESSABLE` | insight §5 | US-E08-11 | — |
| ↳ | ingestion non risponde `ACCEPTED` ⇒ 409 `REPROCESS_REJECTED` | nessuna | US-E08-11 | — |
| ↳ | ingestion irraggiungibile ⇒ 503 `DEPENDENCY_UNAVAILABLE`; errore HTTP di ingestion ⇒ ripropagato | docs/06 §2 | US-E08-11 | — |
| ↳ | `ACCEPTED` ⇒ `REPROCESSED` + audit (solo `lh.audit.v1`) | insight §5, Q-104, Q-109 | US-E08-11 | — |
| INS-02 `DlqService#discard` | 422 `NOTE_REQUIRED` (ogni famiglia) · 409 `DLQ_NOT_OPEN` · 404 | Q-110, Q-107 | US-E08-12 | — |
| ↳ | `DISCARDED` + audit | insight §5 | US-E08-12 | — |
| INS-03 `TraceService#trace` | nessun evento né DLQ ⇒ risposta `IN_PROGRESS` vuota (non 404) | nessuna | US-E09-03 | — |
| ↳ | voce DLQ `OPEN` o `DISCARDED` ⇒ `FAILED` | insight §5, Q-106 | US-E09-03 | — |
| ↳ | ultimo evento da ≥ 5 s senza DLQ ⇒ `COMPLETE`; altrimenti `IN_PROGRESS` | insight §5 | US-E09-03 | — |
| ↳ | voce `REPROCESSED` non fa fallire il tracciato | Q-106 | US-E09-03 | — |
| ↳ | albero per `causation_id`; `outcome {points, tierChange, messages, coupons, plays, dlq}` | F-INS-02 | US-E09-03 | — |
| INS-04 `EventIngestService` | evento già presente (`ON CONFLICT DO NOTHING`) ⇒ metriche non raddoppiate | insight §5, §7 | US-E09-04, US-E12-02 | — |
| ↳ | record DLQ ⇒ `dlq_entry` (non `event_store`) + flusso live | Q-111 | US-E08-11 | — |
| ↳ | audit ⇒ `audit_entry`; payload troncato a 8 KB | insight §5 | US-E08-07 | — |
| INS-05 `LiveEventHub` / `StreamController` | filtri `topics, memberId, types, correlationId` | insight §3 | US-E09-01 | — |
| ↳ | `Last-Event-ID` ⇒ rinvio degli ultimi ≤ 200 | insight §3, §7 | US-E09-01 | — |
| ↳ | heartbeat ogni 15 s; client lento/chiuso ⇒ disconnesso senza errore | insight §5, Q-26 | US-E09-01 | — |
| INS-06 `RetentionJob` | `event_store` 14 gg o 200 000 righe (il minore); `audit_entry` 180 gg; ogni ora | insight §5, RNF-07 | US-E09-06 | — |
| INS-07 `KpiService` | overview con delta sul periodo precedente; dati sintetici (`synthetic=true`) sommati ai reali | F-INS-03, F-INS-04 | US-E09-04 | — |
| ↳ | reset ⇒ event store, audit, DLQ svuotati e 90 gg sintetici rigenerati (seme fisso) | insight §6 | US-E11-07 | — |
| INS-08 controller di lettura | 400 parametri non validi (audit, eventi, tracciati) · 404 evento/voce/tracciato | docs/06 §2 | US-E09-07, US-E08-07 | — |
| ↳ | ⛔ `GET /v1/members/{memberId}/timeline` assente (scheda `timeline` di BO-03) | insight §3 | US-E09-05 | — |
| INS-09 Guardie | `POST /v1/dlq/{id}/reprocess`, `/discard` → ADMIN | `dlq.handle` | US-E08-11, US-E08-12 | — |
| INS-10 enum | famiglia `ACTION, EFFECT, FACT, AUDIT, DLQ` · voce DLQ `OPEN, REPROCESSED, DISCARDED` (BO-27: `NEW`, Q-105) · tracciato `COMPLETE, IN_PROGRESS, FAILED` | insight §2, §3 | US-E08-11, US-E09-03 | — |

### 5.10 libs/lh-common

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| CMN-01 `ActorContext#parse` | header assente o vuoto ⇒ `ANALYST:anonymous` | docs/06 §3 | US-E08-06 | TB-GOV |
| ↳ | senza `:` ⇒ ruolo dal testo, username `anonymous` | nessuna | US-E08-06 | TB-GOV |
| ↳ | ruolo sconosciuto ⇒ `ANALYST`; username vuoto ⇒ `anonymous` | nessuna | US-E08-06 | TB-GOV |
| CMN-02 `RequiresRoleInterceptor` | nessuna annotazione ⇒ passa | docs/06 §3 | US-E08-06 | TB-GOV |
| ↳ | `ADMIN` passa sempre | docs/06 §3 | US-E08-06 | TB-GOV |
| ↳ | annotazione vuota ⇒ tutti tranne `ANALYST` | docs/06 §3 | US-E08-06 | TB-GOV |
| ↳ | ruolo nell'elenco ⇒ passa; altrimenti 403 `FORBIDDEN_ROLE` | docs/08 §2 | US-E08-06 | TB-GOV |
| CMN-03 `GovernedTransitions#parse` | azione sconosciuta ⇒ 422 `INVALID_ACTION` | nessuna | US-E08-03 | TB-GOV |
| CMN-04 `GovernedTransitions#next` (ruolo) | `APPROVE`/`REJECT` con ruolo ≠ approvatore della policy e ≠ ADMIN ⇒ 403 | docs/06 §3, §7 | US-E08-04 | TB-GOV |
| ↳ | altre transizioni con ruolo ≠ ADMIN/MARKETING ⇒ 403 | docs/08 §2 `object.edit` | US-E08-04 | TB-GOV |
| ↳ | approvazione spenta + `SUBMIT` da `DRAFT` ⇒ `LIVE` diretto | docs/06 §7 (fino a M7) | US-E08-01 | TB-GOV |
| CMN-05 `ApprovalStateMachine#next` | `SUBMIT` DRAFT→IN_REVIEW · `APPROVE` IN_REVIEW→APPROVED · `REJECT` IN_REVIEW→DRAFT | docs/03 §3.6 | US-E08-01, US-E08-02 | TB-GOV |
| ↳ | `REJECT` senza commento ⇒ 422 `REJECT_COMMENT_REQUIRED` | docs/03 §3.6 | US-E08-02 | TB-GOV |
| ↳ | `PUBLISH` APPROVED→LIVE; DRAFT→LIVE se la policy non richiede approvazione | docs/03 §3.6 | US-E08-01 | TB-GOV |
| ↳ | `PUBLISH` da DRAFT con approvazione richiesta ⇒ 409 `APPROVAL_REQUIRED` | docs/06 §7 | US-E08-03 | TB-GOV |
| ↳ | `PAUSE` LIVE→PAUSED · `RESUME` PAUSED→LIVE · `END` LIVE/PAUSED→ENDED · `ARCHIVE` ENDED/DRAFT→ARCHIVED | docs/03 §3.6 | US-E03-03, US-E08-03 | TB-GOV |
| ↳ | ogni altra coppia stato × azione ⇒ 409 `INVALID_TRANSITION` | docs/06 §2 | US-E08-03 | TB-GOV |
| CMN-06 `#isOverride` | ADMIN che approva/rifiuta al posto di LEGAL ⇒ marcato «[override ADMIN]» in audit | docs/08 §2 | US-E08-04, US-E08-07 | TB-GOV |
| CMN-07 `ApprovalPolicy` | approvazione spenta ⇒ nessuna richiesta | docs/06 §7 | US-E08-01 | TB-GOV |
| ↳ | CONTEST, REWARD ⇒ LEGAL sempre | docs/06 §7 | US-E08-01 | TB-GOV |
| ↳ | CAMPAIGN ⇒ LEGAL se `requiresLegal` o `maxPoints` > 100 000; altrimenti diretta | docs/06 §7, Q-08 | US-E08-01 | TB-GOV |
| ↳ | CONTENT ⇒ mai | docs/06 §7 | US-E07-02 | TB-GOV |
| CMN-08 `EventRouter` | handler per `type` esatto; altrimenti per famiglia `…*` | docs/06 §5 | US-E12-02 | — |
| ↳ | `type` non registrato ⇒ ignorato senza `processed_event` | docs/05 §1 | US-E12-02 | — |
| ↳ | due handler per lo stesso tipo/famiglia ⇒ avvio fallito | nessuna | — | — |
| CMN-09 `IdempotentHandler` | `(consumer, eventId)` già presente ⇒ logica saltata | docs/06 §1, RNF-03 | US-E12-02 | — |
| ↳ | primo ⇒ `processed_event` + logica + outbox in una transazione | ADR-008 | US-E12-02 | — |
| CMN-10 `DlqRecords` / error handler | `LoopGuardException` ⇒ `LOOP_GUARD`, 1 tentativo | docs/05 §7 | US-E12-03 | — |
| ↳ | `NonRetryableEventException` ⇒ suo codice, 1 tentativo | docs/04 §5 | US-E12-03 | — |
| ↳ | altra eccezione ⇒ nome della classe, 3 tentativi (backoff 200 ms), poi `lh.dlq.v1` con header `lh-*` | docs/12 M0 | US-E12-03 | — |
| CMN-11 `OutboxWriter` / `OutboxRelay` / `OutboxCleanup` | scrittura solo in transazione; relay ogni 500 ms; Kafka giù ⇒ righe restano e partono al ritorno | RNF-05, docs/12 M0 | US-E12-01 | — |
| ↳ | outbox pubblicati > 24 h cancellati (ogni ora) | docs/06 §4 | US-E09-06 | — |
| ↳ | ⛔ pulizia `processed_event` > 14 gg assente | docs/06 §4, RNF-07 | US-E09-06 | — |
| CMN-12 `GlobalExceptionHandler` | `LhException` ⇒ problem `urn:loyaltyhub:problem:<suffix>` + `code` + `errors[]` | docs/06 §2 | US-E12-07 | — |
| ↳ | bean validation ⇒ 422 `VALIDATION` con `errors[]` | docs/06 §2 | US-E12-07 | — |
| ↳ | percorso non mappato ⇒ 404 `NOT_FOUND` | docs/06 §2 | US-E12-07 | — |
| ↳ | client SSE disconnesso ⇒ nessun corpo | nessuna | US-E09-01 | — |
| ↳ | altra eccezione ⇒ 500 `INTERNAL_ERROR` | nessuna | US-E12-07 | — |
| CMN-13 `DemoResetController` | `POST /v1/demo/reset` → ADMIN; tronca e ricarica i seed; audit `RESET` | docs/06 §10, F-DEMO-05 | US-E11-07 | — |
| ↳ | ⛔ `GET /v1/demo/info` assente | docs/06 §10, BO-30 | US-E11-07 | — |
| CMN-14 `SeedDates` / `BusinessCalendar` | espressioni `@now`, `@today±n`, `@som/@eom/@soy/@eoy`, `@lastWeekday`, `@lastSaturday`, `Thh:mm` in Europe/Rome | docs/10 §1 | US-E11-07 | — |
| ↳ | `periodKey` giorno/settimana/mese/edizione in Europe/Rome | docs/06 §1 | US-E03-09 | TB-CMP |

### 5.11 web (`web/lib`, `web/app/api`)

| Nodo decisionale | Rami / esiti | Regola di specifica | Storie | Testbook |
|---|---|---|---|---|
| WEB-01 `persona/cookie` | cookie assente o non valido ⇒ default (`marta.admin` ADMIN nel backoffice, `MBR-000002` nel portale) | docs/07 §4 | US-E11-02 | TB-WEB |
| ↳ | persona BO ⇒ `X-LH-Actor: RUOLO:username`; membro ⇒ `ANALYST:anonymous` | docs/07 §3 | US-E11-04 | TB-WEB |
| ↳ | username sconosciuto ⇒ ruolo `ANALYST` | nessuna | US-E11-02 | TB-WEB |
| WEB-02 `persona/permissions#can` | capacità non ammessa ⇒ azione nascosta o disabilitata con tooltip «Richiede uno dei ruoli…» | docs/08 §2, docs/07 §6 | US-E08-06 | TB-WEB |
| ↳ | `coupon.use` a ogni ruolo operativo, `coupon.void` ADMIN/CARE | Q-52 | US-E05-14 | TB-WEB |
| ↳ | `edition.close` assente dalla matrice web (anteprima per tutti, applica solo ADMIN lato server) | docs/08 §2 | US-E04-13 | TB-WEB |
| WEB-03 proxy `app/api/lh/[service]` | 502/503/504 o errore di rete ⇒ 503 `{type: SERVICE_ASLEEP, service}` | docs/07 §3 | US-E11-04, US-E10-12 | TB-WEB |
| ↳ | timeout 25 s; aggiunge `X-LH-Actor` dal cookie e `X-Correlation-Id` | docs/07 §3 | US-E11-04 | TB-WEB |
| ↳ | servizio sconosciuto ⇒ errore (codice non in `SERVICES`) | nessuna | US-E11-04 | TB-WEB |
| WEB-04 `api/status` (HUB-01) | ingressi attivi solo con ingestion, member, campaign, wallet `UP`; stato sconosciuto ⇒ bloccati | docs/07 §8, Q-133 | US-E11-01 | TB-WEB |
| ↳ | durante il risveglio ciò che non è `UP` appare `WAKING`; completo quando tutte le tessere sono `UP` | docs/07 §8 | US-E11-01 | TB-WEB |
| ↳ | polling 3 s in risveglio, 8 s a regime, fermo se il keep-alive è inattivo | docs/07 §8, Q-134 | US-E11-01 | TB-WEB |
| ↳ | Kafka `DOWN` > 2 min (non `SLEEPING`) ⇒ riquadro «riaccendi dalla console» | docs/07 §8, Q-135 | US-E11-01 | TB-WEB |
| WEB-05 `keepalive/keepAlive` | ogni 4 min `wake` solo se la scheda è visibile | docs/07 §8, F-DEMO-07 | US-E11-03 | TB-WEB |
| ↳ | 45 min senza interazione ⇒ fermo | docs/07 §8 | US-E11-03 | TB-WEB |
| ↳ | nuova interazione ⇒ riparte senza sveglia immediata | Q-132 | US-E11-03 | TB-WEB |
| WEB-06 `realtime/useLiveEvents` | SSE diretto a insight; 3 fallimenti ⇒ polling `/v1/events` ogni 3 s «live ridotto» | docs/07 §3 | US-E09-01 | TB-WEB |
| ↳ | buffer 500 righe; pausa con contatore dei nuovi | BO-24 | US-E09-01 | TB-WEB |
| WEB-07 attesa asincrona (`PendingContext`, PT-04) | «in arrivo…» fino al fatto atteso; timeout 20 s ⇒ «ci sta mettendo più del solito»; nessun aggiornamento ottimistico | docs/07 §7 | US-E10-02 | TB-WEB |
| ↳ | ⚠ `usePendingTrace`/`expectations.ts` di docs/07 §7 non esistono come moduli (logica nei componenti del portale) | docs/07 §2, §7 | US-E10-02 | TB-WEB |
| WEB-08 `reward/portal` | blocco richiesta in ordine: `SOLD_OUT` ⇒ «Esaurito» · tier ⇒ «Riservato a …» · limite ⇒ «Già richiesto» · saldo ⇒ «Ti mancano N punti» | docs/09 PT-04 | US-E05-04, US-E10-06 | TB-WEB |
| ↳ | etichette PT-13 per stato (`MEMBER` ⇒ «Annullata da te», `INSUFFICIENT_BALANCE` ⇒ «Punti non sufficienti»); saga conclusa = stato finale o `CONFIRMED` non coupon o `needsAttention` | docs/09 PT-13 | US-E10-04, US-E10-06 | TB-WEB |
| WEB-09 `inbound/inbound` | *Riprova* bloccata se non REJECTED/UNMATCHED o ruolo senza `inbound.handle`; *Abbina* se non UNMATCHED | BO-26 | US-E01-07 | TB-WEB |
| WEB-10 `dlq/dlq` | *Riprocessa*: solo ADMIN, voce aperta, famiglia ACTION; *Scarta*: ADMIN, aperta, nota + codice digitato | BO-27, docs/08 §3.5 | US-E08-11, US-E08-12 | TB-WEB |
| WEB-11 `approvals/queue` | serve approvazione? policy spenta ⇒ no; non campagna ⇒ sì; campagna ⇒ `requiresLegal` o budget > soglia; policy ignota ⇒ entrambe le vie | docs/06 §7, BO-21 | US-E08-05 | TB-WEB |
| WEB-12 `member/status`, `member/anonymized` | INACTIVE ⇒ *Blocca*/*Disattiva* disabilitati; ANONYMIZED ⇒ tutto disabilitato; conferma anonimizzazione digitando l'ID | Q-137, BO-03 | US-E02-04, US-E02-05 | TB-WEB |
| WEB-13 `demo/reset` | *Ripristina tutto* solo digitando `RESET` | BO-30 | US-E11-07 | TB-WEB |
| WEB-14 `gamification/play` | messaggi per `NO_PLAYS_AVAILABLE`, `DAILY_LIMIT_REACHED`, `CONTEST_NOT_LIVE`, `MEMBER_NOT_ACTIVE`; seguito vincita per `POINTS`/`COUPON`/`PHYSICAL` | docs/09 PT-06 | US-E06-05, US-E10-07 | TB-WEB |
| WEB-15 `campaign/conditions`, `campaign/describe` | profondità massima 3; avviso su gruppo NESSUNA con più regole; campi `data.*` = intersezione dei trigger; catalogo fisso `member/context/history` | BO-06, Q-90, Q-92 | US-E03-06 | TB-WEB |
| WEB-16 `content/links`, `messages/*` | link dei template solo `/portal…`; campanella ogni 30 s | Q-74, Q-76 | US-E07-07, US-E07-11 | TB-WEB |
| WEB-17 `member/profile`, `member/referral` | form di registrazione (nome, cognome, e-mail, consenso, codice da `?ref=`); «n di 10 inviti premiati» sull'anno solare | F-MBR-06, Q-61 | US-E10-08, US-E06-18 | TB-WEB |
| WEB-18 `nav` | voce di una milestone non realizzata ⇒ non compare | docs/08 §1, docs/12 §1.4 | US-E11-02 | TB-WEB |

### 5.12 Regole di specifica senza codice («regola non implementata»)

| # | Regola | Fonte | Nodo | Storia |
|---|---|---|---|---|
| 1 | creazione di una fonte da BO-09 — `POST /v1/sources` (abilita/disabilita e tipi ammessi: `PUT /v1/sources/{code}` realizzato) | F-ING-05, ingestion §3 | ING-22 | US-E01-11 |
| 2 | ritentare 3 volte un'azione il cui membro manca dallo snapshot, poi `NO_MEMBER` | campaign §5 | CMP-01 | US-E01-06, US-E03-05 |
| 3 | pulizia `evaluation_log` > 30 gg | campaign §2, RNF-07 | CMP-29 | US-E09-06 |
| 4 | pulizia `inbound_event` > 7 gg o > 20 000 righe | ingestion §2, RNF-07 | (nessun nodo: codice assente) | US-E09-06 |
| 5 | pulizia `processed_event` > 14 gg | docs/06 §4, RNF-07 | CMN-11 | US-E09-06 |
| 6 | `keepWarning` (avviso di mantenimento da ottobre) | docs/03 §4.3, wallet §3/§5, PT-01 | WAL-18 | US-E04-14, US-E10-01 |
| 7 | `GET /v1/members/{id}/timeline` (insight) e `GET /v1/members/{id}/gamification` (riepilogo per BO-03) | insight §3, gamification §3 | INS-08 | US-E09-05, US-E02-06 |
| 8 | `GET /v1/demo/info` | docs/06 §10, BO-30 | CMN-13 | US-E11-07 |

Fuori perimetro PoC (P2, non contate come lacune): F-ING-10 invio batch, F-MBR-08 compleanno (job e `POST /v1/demo/jobs/birthdays`), F-CMP-14 storno su reso.

### 5.13 Conteggi della foresta

| Modulo | Nodi | Righe | Foglie | Foglie con ≥ 1 storia | Foglie senza specifica | Rami ⚠ divergenti | Rami ⛔ |
|---|--:|--:|--:|--:|--:|--:|--:|
| 5.2 ingestion-service | 23 | 91 | 107 | 107 | 13 | 0 | 1 |
| 5.3 member-service | 21 | 61 | 84 | 83 | 7 | 1 | 1 |
| 5.4 campaign-service | 31 | 83 | 107 | 107 | 8 | 2 | 2 |
| 5.5 wallet-service | 21 | 80 | 110 | 109 | 11 | 0 | 1 |
| 5.6 reward-service | 26 | 79 | 116 | 116 | 5 | 1 | 0 |
| 5.7 gamification-service | 22 | 71 | 112 | 112 | 8 | 1 | 0 |
| 5.8 engagement-service | 25 | 55 | 121 | 121 | 2 | 1 | 0 |
| 5.9 insight-service | 10 | 25 | 40 | 40 | 2 | 0 | 1 |
| 5.10 libs/lh-common | 14 | 42 | 47 | 46 | 6 | 0 | 2 |
| 5.11 web (`web/lib`, `web/app/api`) | 18 | 32 | 35 | 35 | 2 | 1 | 0 |
| **Totale** | **211** | **619** | **879** | **876** | **64** | **7** | **8** |

Regole di conteggio: *nodo* = riga con identificativo (`ING-01`…); *foglia* = esito distinto: in una riga normale 1 + il numero di alternative separate da « · » nella colonna *Rami / esiti*; in una riga «enum» un valore per foglia; *senza specifica* = foglie della riga con *Regola* «nessuna»; ⚠ e ⛔ contano le righe che li riportano (le ⛔ includono la voce P2 del compleanno; l'elenco puntuale è in §5.12). Codici d'errore: **114** codici HTTP (§5.1: 108 specifici + 6 generici; **24** senza una regola di specifica che li nomini), **10** codici DLQ (§5.1 bis), **6** codici di rifiuto dell'ingresso (`RejectCode`) con 4 esiti (`InboundStatus`).

## 6. Matrice di copertura

Stato alla data del documento: docs/16 non ha ancora righe `TB-*` e `docs/testbook/` non esiste, quindi nessuna storia è `coperta`. `pianificata` = il dominio indicato esiste in docs/16 §2; `scoperta` = nessun dominio lo prevede; `fuori perimetro PoC` = feature P2.

### 6.1 Storia → feature → dominio → stato

| Storia | Feature | Dominio TB | Stato |
|---|---|---|---|
| US-E01-01 | F-ING-01, F-ING-03 | TB-ING | pianificata |
| US-E01-02 | F-ING-01 | TB-ING | pianificata |
| US-E01-03 | F-ING-01, F-ING-05, F-ING-06 | TB-ING | pianificata |
| US-E01-04 | F-ING-02 | TB-ING | pianificata |
| US-E01-05 | F-ING-03, F-ING-04, F-MBR-04 | TB-ING | pianificata |
| US-E01-06 | F-ING-04 | TB-ING (+ TB-CMP) | pianificata |
| US-E01-07 | F-ING-04, F-ING-09 | TB-ING, TB-WEB | pianificata |
| US-E01-08 | F-ING-07 | TB-ING | pianificata |
| US-E01-09 | F-ING-08, F-IW-06 | TB-ING | pianificata |
| US-E01-10 | F-ING-08 | TB-ING | pianificata |
| US-E01-11 | F-ING-05 | TB-ING | pianificata (⛔ `POST /v1/sources` assente) |
| US-E01-12 | F-ING-06 | TB-ING | pianificata |
| US-E01-13 | F-ING-09 | TB-ING, TB-WEB | pianificata |
| US-E01-14 | F-ING-10 | — | fuori perimetro PoC |
| US-E02-01 | F-MBR-01 | TB-GOV | pianificata |
| US-E02-02 | F-MBR-01, F-MBR-03 | TB-GOV | pianificata |
| US-E02-03 | F-MBR-01 | TB-GOV, TB-WEB | pianificata |
| US-E02-04 | F-MBR-04 | TB-GOV (+ ING/WAL/RWD/GAM) | pianificata |
| US-E02-05 | F-MBR-05 | TB-GOV | pianificata |
| US-E02-06 | F-MBR-02, F-WAL-02, F-TIER-06, F-CMP-09 | TB-WEB, TB-GOV | pianificata (⛔ 2 endpoint) |
| US-E02-07 | F-MBR-03 | TB-GOV | pianificata |
| US-E02-08 | F-SEG-02, F-SEG-03 | TB-GOV | pianificata |
| US-E02-09 | F-SEG-01 | TB-GOV | pianificata |
| US-E02-10 | F-SEG-02, F-SEG-03 | TB-GOV | pianificata |
| US-E02-11 | F-SEG-03 | TB-GOV | pianificata |
| US-E02-12 | F-MBR-02 (proiezione) | TB-GOV | pianificata |
| US-E02-13 | F-MBR-08 | — | fuori perimetro PoC |
| US-E03-01 | F-CMP-01 | TB-CMP | pianificata |
| US-E03-02 | F-CMP-01, F-CMP-02 | TB-CMP | pianificata |
| US-E03-03 | F-CMP-02 | TB-CMP (+ TB-GOV) | pianificata |
| US-E03-04 | F-CMP-03, F-CMP-04, F-CMP-05 | TB-CMP | pianificata |
| US-E03-05 | F-CMP-06, F-CMP-09 | TB-CMP | pianificata |
| US-E03-06 | F-CMP-03 | TB-CMP, TB-WEB | pianificata |
| US-E03-07 | F-CMP-04 | TB-CMP | pianificata |
| US-E03-08 | F-CMP-07 | TB-CMP | pianificata |
| US-E03-09 | F-CMP-05 | TB-CMP | pianificata |
| US-E03-10 | F-CMP-04 | TB-CMP | pianificata |
| US-E03-11 | F-CMP-08 | TB-CMP | pianificata |
| US-E03-12 | F-CMP-09 | TB-CMP | pianificata |
| US-E03-13 | F-CMP-10 | TB-CMP | pianificata |
| US-E03-14 | F-CMP-11 | TB-CMP, TB-WEB | pianificata |
| US-E03-15 | F-CMP-12 | TB-CMP | pianificata |
| US-E03-16 | F-CMP-13 | TB-CMP | pianificata |
| US-E03-17 | F-CMP-04 (idempotenza), RNF-03 | TB-CMP | pianificata |
| US-E03-18 | F-CMP-14 | — | fuori perimetro PoC |
| US-E03-19 | F-CMP-02 | TB-CMP | pianificata |
| US-E04-01 | F-WAL-01, F-WAL-02, F-WAL-03, F-TIER-03 | TB-WAL | pianificata |
| US-E04-02 | F-WAL-05 | TB-WAL | pianificata |
| US-E04-03 | F-TIER-02 | TB-WAL | pianificata |
| US-E04-04 | F-WAL-06 | TB-WAL | pianificata |
| US-E04-05 | F-WAL-06 | TB-WAL, TB-ENG | pianificata |
| US-E04-06 | F-WAL-07 | TB-WAL | pianificata |
| US-E04-07 | F-WAL-04, F-WAL-08 | TB-WAL | pianificata |
| US-E04-08 | F-WAL-08, F-RWD-07 | TB-WAL | pianificata |
| US-E04-09 | F-TIER-01 | TB-WAL | pianificata |
| US-E04-10 | F-WAL-03 | TB-WAL | pianificata |
| US-E04-11 | F-TIER-04 (edizioni) | TB-WAL | pianificata |
| US-E04-12 | F-TIER-05 | TB-WAL | pianificata |
| US-E04-13 | F-TIER-04 | TB-WAL | pianificata |
| US-E04-14 | F-WAL-01, F-WAL-09 | TB-WAL | pianificata (⛔ `keepWarning`) |
| US-E04-15 | F-TIER-06 | TB-WAL | pianificata |
| US-E04-16 | F-WAL-01, F-WAL-08 | TB-WAL | pianificata |
| US-E05-01 | F-RWD-01, F-RWD-03, F-RWD-04, F-RWD-08 | TB-RWD | pianificata |
| US-E05-02 | F-RWD-02 | TB-RWD | pianificata |
| US-E05-03 | F-RWD-01 | TB-RWD | pianificata |
| US-E05-04 | F-RWD-01…04 | TB-RWD, TB-WEB | pianificata |
| US-E05-05 | F-RWD-03, F-RWD-05 | TB-RWD | pianificata |
| US-E05-06 | F-RWD-05, F-RWD-06, F-CPN-02 | TB-RWD | pianificata |
| US-E05-07 | F-RWD-05 | TB-RWD | pianificata |
| US-E05-08 | F-RWD-05 | TB-RWD | pianificata |
| US-E05-09 | F-RWD-05 | TB-RWD | pianificata |
| US-E05-10 | F-RWD-06 | TB-RWD | pianificata |
| US-E05-11 | F-RWD-07 | TB-RWD | pianificata |
| US-E05-12 | F-RWD-06, F-CPN-02 | TB-RWD | pianificata |
| US-E05-13 | F-CPN-01 | TB-RWD | pianificata |
| US-E05-14 | F-CPN-03 | TB-RWD | pianificata |
| US-E05-15 | F-CPN-03 (annullo) | TB-RWD | pianificata |
| US-E05-16 | F-CPN-02, F-IW-06 | TB-RWD | pianificata |
| US-E05-17 | F-CPN-02 | TB-RWD | pianificata |
| US-E06-01 | F-IW-01, F-IW-02 | TB-GAM | pianificata |
| US-E06-02 | F-IW-03 | TB-GAM | pianificata |
| US-E06-03 | F-IW-07 | TB-GAM, TB-WEB | pianificata |
| US-E06-04 | F-IW-01 | TB-GAM | pianificata |
| US-E06-05 | F-IW-04 | TB-GAM | pianificata |
| US-E06-06 | F-IW-05 | TB-GAM | pianificata |
| US-E06-07 | F-IW-04 | TB-GAM | pianificata |
| US-E06-08 | F-IW-06, F-IW-07 | TB-GAM | pianificata |
| US-E06-09 | F-IW-08 | TB-GAM | pianificata |
| US-E06-10 | F-IW-01 | TB-GAM | pianificata |
| US-E06-11 | F-IW-07 | TB-GAM | pianificata |
| US-E06-12 | F-ACH-01, F-ACH-03 | TB-GAM | pianificata |
| US-E06-13 | F-ACH-01, F-ACH-02 | TB-GAM | pianificata |
| US-E06-14 | F-ACH-03 | TB-GAM | pianificata |
| US-E06-15 | F-LDB-01 | TB-GAM | pianificata |
| US-E06-16 | F-REF-01 | TB-GAM | pianificata |
| US-E06-17 | F-REF-02 | TB-GAM | pianificata |
| US-E06-18 | F-REF-01, F-REF-02 | TB-GAM, TB-WEB | pianificata |
| US-E07-01 | F-CNT-01, F-CNT-02 | TB-ENG | pianificata |
| US-E07-02 | F-CNT-01 | TB-ENG | pianificata |
| US-E07-03 | F-CNT-01 | TB-ENG | pianificata |
| US-E07-04 | F-CNT-02 | TB-ENG | pianificata |
| US-E07-05 | F-CNT-04 | TB-ENG | pianificata |
| US-E07-06 | F-CNT-03 | TB-ENG, TB-WEB | pianificata |
| US-E07-07 | F-MSG-02 | TB-ENG | pianificata |
| US-E07-08 | F-MSG-01 | TB-ENG | pianificata |
| US-E07-09 | F-MSG-01 | TB-ENG | pianificata |
| US-E07-10 | F-MSG-01, F-CMP-04 | TB-ENG | pianificata |
| US-E07-11 | F-MSG-01 | TB-ENG, TB-WEB | pianificata |
| US-E07-12 | F-THM-01 | TB-ENG, TB-WEB | pianificata |
| US-E08-01 | F-APR-01, F-APR-02, F-RWD-08 | TB-GOV | pianificata |
| US-E08-02 | F-APR-01 | TB-GOV | pianificata |
| US-E08-03 | F-APR-01 | TB-GOV | pianificata |
| US-E08-04 | F-APR-01, F-APR-02 | TB-GOV | pianificata |
| US-E08-05 | F-APR-03 | TB-GOV, TB-WEB | pianificata |
| US-E08-06 | F-DEMO-02 (ruoli), docs/08 §2 | TB-GOV, TB-WEB | pianificata |
| US-E08-07 | F-AUD-01 | — | **scoperta** |
| US-E08-08 | F-WBH-01 | TB-ENG | pianificata |
| US-E08-09 | F-WBH-01 | TB-ENG | pianificata |
| US-E08-10 | F-WBH-01 | TB-ENG | pianificata |
| US-E08-11 | F-INS-05 | — (lato ingestion TB-ING) | **scoperta** |
| US-E08-12 | F-INS-05 | — | **scoperta** |
| US-E08-13 | docs/06 §4 (versioni, M7.6) | TB-GOV | pianificata |
| US-E09-01 | F-INS-01 | TB-WEB (solo client) | pianificata |
| US-E09-02 | F-INS-06 | — | **scoperta** |
| US-E09-03 | F-INS-02 | — | **scoperta** |
| US-E09-04 | F-INS-03, F-INS-04, F-WAL-09 | — | **scoperta** |
| US-E09-05 | F-MBR-02 (timeline) | — | **scoperta** |
| US-E09-06 | RNF-07 | — | **scoperta** |
| US-E09-07 | F-INS-01 | — | **scoperta** |
| US-E10-01 | F-WAL-01, F-TIER-02, F-WAL-06, F-CNT-01, F-CNT-02 | TB-WEB | pianificata |
| US-E10-02 | docs/07 §7 | TB-WEB | pianificata |
| US-E10-03 | F-CMP-04, F-TIER-02, F-ING-08, F-ACH-02, F-MSG-01 | — | **scoperta** |
| US-E10-04 | F-RWD-05, F-RWD-06, F-CPN-02 | — | **scoperta** |
| US-E10-05 | F-RWD-05, F-RWD-06 | — | **scoperta** |
| US-E10-06 | F-RWD-05, F-WAL-08 | — | **scoperta** |
| US-E10-07 | F-IW-04, F-IW-06, F-IW-08, F-CNT-03 | — | **scoperta** |
| US-E10-08 | F-MBR-06, F-REF-01 | TB-GOV, TB-WEB | pianificata |
| US-E10-09 | F-MBR-07 | TB-GOV, TB-WEB | pianificata |
| US-E10-10 | F-WAL-02, F-TIER-03 | TB-WEB | pianificata |
| US-E10-11 | F-MBR-04 | TB-WEB | pianificata |
| US-E10-12 | RNF-06, docs/07 §6 | TB-WEB | pianificata |
| US-E10-13 | F-WAL-06 | — | **scoperta** |
| US-E10-14 | F-CMP-04, F-ACH-03, F-SEG-03, F-CNT-01 | — | **scoperta** |
| US-E10-15 | F-REF-01, F-REF-02 | — | **scoperta** |
| US-E10-16 | F-CMP-04, F-MBR-07, F-ACH-03 | — | **scoperta** |
| US-E11-01 | F-DEMO-01 | TB-WEB | pianificata |
| US-E11-02 | F-DEMO-02 | TB-WEB | pianificata |
| US-E11-03 | F-DEMO-07 | TB-WEB | pianificata |
| US-E11-04 | docs/07 §3 | TB-WEB | pianificata |
| US-E11-05 | F-DEMO-03 | TB-ING, TB-WEB | pianificata |
| US-E11-06 | F-DEMO-04 | TB-ING, TB-WEB | pianificata |
| US-E11-07 | F-DEMO-05 | — (conferma UI in TB-WEB) | **scoperta** |
| US-E11-08 | F-DEMO-06 | TB-WAL, TB-RWD, TB-GAM, TB-GOV | pianificata |
| US-E11-09 | docs/12 §4 | — | **scoperta** |
| US-E12-01 | RNF-05 | — | **scoperta** |
| US-E12-02 | RNF-03 | TB-ING/CMP/WAL/RWD/GAM/ENG | pianificata |
| US-E12-03 | docs/04 §5 | — | **scoperta** |
| US-E12-04 | RNF-04 | TB-WAL/RWD/GAM/CMP | pianificata |
| US-E12-05 | RNF-06 | — | **scoperta** |
| US-E12-06 | docs/05 §9 | — | **scoperta** |
| US-E12-07 | docs/06 §2 | tutti i domini | pianificata |
| US-E12-08 | RNF-01, RNF-02, RNF-08…10 | — | **scoperta** |

### 6.2 Storie `scoperte`

| Gruppo | Storie | Perché nessun dominio le copre |
|---|---|---|
| Osservabilità (insight) | US-E09-02, US-E09-03, US-E09-04, US-E09-05, US-E09-06, US-E09-07, US-E08-11, US-E08-12 | docs/16 §2 non ha un dominio per insight-service (F-INS-*) |
| Audit | US-E08-07 | F-AUD-01 non è nel perimetro dichiarato di TB-GOV (F-APR, F-MBR, F-SEG) |
| Percorsi tra servizi | US-E10-03, US-E10-04, US-E10-05, US-E10-06, US-E10-07, US-E10-13, US-E10-14, US-E10-15, US-E10-16, US-E11-09 | le tabelle per dominio provano i singoli passi, non la catena né il tracciato unico |
| Demo | US-E11-07 | reset e seed (F-DEMO-05, docs/10) senza dominio |
| Piattaforma | US-E12-01, US-E12-03, US-E12-05, US-E12-06, US-E12-08 | lh-common, contratti e RNF fuori dai domini funzionali |

Totale: **25** storie scoperte.

### 6.3 Verifiche di completezza

**Feature di docs/02 senza storia: nessuna.** Indice inverso (ogni `F-…` → storie):

| Area | Feature → storie |
|---|---|
| ING | 01 → E01-01…03 · 02 → E01-04 · 03 → E01-01, E01-05 · 04 → E01-05…07 · 05 → E01-03, E01-11 · 06 → E01-03, E01-12 · 07 → E01-08 · 08 → E01-09, E01-10 · 09 → E01-07, E01-13 · 10 → E01-14 (fuori perimetro) |
| MBR | 01 → E02-01…03 · 02 → E02-06, E02-12, E09-05 · 03 → E02-02, E02-07 · 04 → E02-04, E01-05, E10-11 · 05 → E02-05 · 06 → E10-08 · 07 → E10-09, E10-16 · 08 → E02-13 (fuori perimetro) |
| SEG | 01 → E02-09 · 02 → E02-08, E02-10 · 03 → E02-08, E02-10, E02-11, E10-14 |
| CMP | 01 → E03-01, E03-02 · 02 → E03-02, E03-03, E03-19 · 03 → E03-04, E03-06 · 04 → E03-04, E03-07, E03-10, E03-17 · 05 → E03-04, E03-09 · 06 → E03-05 · 07 → E03-08 · 08 → E03-11 · 09 → E03-05, E03-12, E02-06 · 10 → E03-13 · 11 → E03-14 · 12 → E03-15 · 13 → E03-16 · 14 → E03-18 (fuori perimetro) |
| WAL | 01 → E04-01, E04-14, E04-16 · 02 → E04-01, E10-10 · 03 → E04-01, E04-10 · 04 → E04-07 · 05 → E04-02 · 06 → E04-04, E04-05, E10-13 · 07 → E04-06 · 08 → E04-07, E04-08 · 09 → E04-14, E09-04 |
| TIER | 01 → E04-09 · 02 → E04-03, E10-03 · 03 → E04-01, E10-10 · 04 → E04-11, E04-13 · 05 → E04-12 · 06 → E04-15 |
| RWD | 01 → E05-01, E05-03, E05-04 · 02 → E05-02, E05-04 · 03 → E05-01, E05-04, E05-05 · 04 → E05-01, E05-04 · 05 → E05-05…09, E10-04…06 · 06 → E05-06, E05-10, E05-12 · 07 → E05-11, E04-08 · 08 → E05-01, E08-01 |
| CPN | 01 → E05-13 · 02 → E05-06, E05-12, E05-16, E05-17 · 03 → E05-14, E05-15 |
| IW | 01 → E06-01, E06-04, E06-10 · 02 → E06-01 · 03 → E06-02 · 04 → E06-05, E06-07 · 05 → E06-06 · 06 → E06-08, E01-09, E05-16 · 07 → E06-03, E06-08, E06-11 · 08 → E06-09, E10-07 |
| ACH · LDB · REF | ACH-01 → E06-12, E06-13 · ACH-02 → E06-13 · ACH-03 → E06-12, E06-14 · LDB-01 → E06-15 · REF-01 → E06-16, E06-18, E10-08 · REF-02 → E06-17, E06-18, E10-15 |
| CNT · MSG · THM · WBH | CNT-01 → E07-01…03 · CNT-02 → E07-01, E07-04 · CNT-03 → E07-06 · CNT-04 → E07-05 · MSG-01 → E07-08…11 · MSG-02 → E07-07 · THM-01 → E07-12 · WBH-01 → E08-08…10 |
| APR · AUD · INS | APR-01 → E08-01…04 · APR-02 → E08-01, E08-04 · APR-03 → E08-05 · AUD-01 → E08-07 · INS-01 → E09-01, E09-07 · INS-02 → E09-03 · INS-03 → E09-04 · INS-04 → E09-04 · INS-05 → E08-11, E08-12 · INS-06 → E09-02 |
| DEMO | 01 → E11-01 · 02 → E11-02, E08-06 · 03 → E11-05 · 04 → E11-06 · 05 → E11-07 · 06 → E11-08 · 07 → E11-03 |

Anche tutte le schermate sono raggiunte: BO-01…BO-30, PT-01…PT-14 e HUB-01 compaiono in almeno una storia.

**Foglie della foresta senza storia** (colonna *Storie* = «—»):

| Nodo | Foglia | Motivo |
|---|---|---|
| MBR-21 | `MemberStatus.CLOSED` | valore dell'enum non assegnabile da API e assente da docs/03 e dai contratti (Q-139): candidato alla rimozione |
| WAL-17 | scala dei livelli vuota ⇒ eccezione | condizione impossibile coi seed; solo difesa |
| CMN-08 | due handler per lo stesso tipo ⇒ avvio fallito | controllo di configurazione all'avvio, non un comportamento utente |

### 6.4 Conteggi

| Misura | Valore |
|---|--:|
| Epic | 12 |
| Storie utente | 162 |
| · `coperta` | 0 |
| · `pianificata` | 134 |
| · `scoperta` | 25 |
| · `fuori perimetro PoC` | 3 |
| Feature di docs/02 (`F-…`) | 100 |
| · associate ad almeno una storia | 100 |
| · senza storia | 0 |
| Schermate (BO-01…30, PT-01…14, HUB-01) senza storia | 0 |
| Nodi decisionali (§5.2–5.11) | 211 |
| Foglie (esiti) | 878 |
| · associate ad almeno una storia | 875 |
| · senza storia (§6.3) | 3 |
| · rami senza specifica | 64 |
| · rami ⚠ divergenti dalla specifica (righe) | 7 |
| Codici d'errore HTTP / DLQ / rifiuto ingresso | 114 / 10 / 6 |
| Regole di specifica senza codice (§5.12) | 10 (+ 3 feature P2 fuori perimetro) |

Dettaglio per modulo in §5.13. I conteggi sono ricavati contando le righe del documento: vanno rifatti a ogni modifica di §4–§6.

## 7. Indicazioni per il testbook

Le righe `TB-*` vanno scritte partendo dai **criteri** delle storie (oracolo = specifica) e dalle **foglie** della foresta (§5), una riga per foglia × dominio dei valori. Priorità e lacune per dominio:

| Dominio | Cosa aggiungere (oltre ai criteri delle storie) |
|---|---|
| **TB-ING** | ordine della pipeline con fallimenti multipli (una riga per coppia di passi); limiti esatti di `time` (+5 min, −30 gg, cambio d'ora); subject senza prefisso (ramo senza specifica); gara sull'insert; *Riprova*/*Abbina* per ogni stato × ruolo; abbinamento automatico: 7 gg ± 1 s, 100/101 righe, `member:` escluso; ponte: ognuna delle 9 mappature accesa/spenta, `lhhop` 2/3; `X-LH-Reprocess` × ruolo × payload presente/assente; origine `SIMULATOR` del simulatore; `PUT /v1/sources/{code}` × ruolo; ⛔ `POST /v1/sources` (righe in divergenza). |
| **TB-CMP** | tabella decisionale del motore (calendario × pubblico × condizioni × gruppo × limiti × budget) con l'ordine dei controlli; arrotondamenti PER_AMOUNT su valori limite (x,99 / x,50); moltiplicatori con tetto e con etichette; confini di periodo DAY/WEEK/MONTH/EDITION in Europe/Rome (mezzanotte, cambio d'ora, 31→1); `context.source` reale = simulazione (URN o codice); fine automatica a `endAt` (confine incluso); `cooldownMinutes` e `perMemberPoints` (`LIMIT`); ⛔ ritentativo `NO_MEMBER`; ⚠ nomi dei motivi (`NOT_LIVE`, `MEMBER_LIMIT_REACHED`, `EXCLUSIVE_GROUP`) da riallineare con docs/12; guardia su `POST /v1/campaigns` × ruolo. |
| **TB-WAL** | FIFO con lotti a pari scadenza, scadenza nulla, lotti PENDING esclusi; rimborso in un lotto nuovo con lotti d'origine scaduti, vicini alla scadenza o senza scadenza (docs/03 §4.2); un solo `EXPIRE` per membro/valuta con più lotti; chiusura edizione: matrice tier attuale (4) × tier guadagnato (4), membri non ACTIVE, nessuna edizione PLANNED; `ROLLING_MONTHS` a fine mese con guadagno a cavallo della mezzanotte UTC/Rome e 29 febbraio; ordine degli effetti PTS/STS nella salita (WAL-01); cron senza zona dei job; ⛔ `keepWarning`. |
| **TB-RWD** | ordine dei 7 controlli della richiesta (una riga per coppia di condizioni vere); stato × evento della saga (PENDING/CONFIRMED/FULFILLED/REJECTED/CANCELLED × spent/rejected/timeout/annullo membro/annullo CARE); `LOW` al 10 % esatto; coupon `ISSUED` scaduto prima del job (410); premio AUTO_COUPON senza pool (ramo senza specifica); ⚠ `REWARD_NOT_EDITABLE` per stato. |
| **TB-GAM** | ordine dei controlli della giocata; gratuita a cavallo della mezzanotte di Roma; `maxWinsPerMember`; concorrenza (50 giocate/1 istante; stesso membro in parallelo); `BUSINESS_HOURS` nei giorni del cambio d'ora; ⚠ periodi `DAY`/`WEEK` degli obiettivi trattati come `EVER`; filtro obiettivi con campo assente e `neq` (falso); `PAUSED` non chiusi dal job; classifiche con parimerito e membri non ACTIVE. |
| **TB-GOV** | tabella completa stato (7) × azione (8) × ruolo (5) × policy (richiesta sì/no) per CAMPAIGN, REWARD, CONTEST, più la tabella dei contenuti; override ADMIN in audit; approvazione spenta; matrice `@RequiresRole` endpoint × ruolo (§5, nodi «Guardie») con la guardia mancante su `POST /v1/members` (⚠, registrazione dal portale) e quelle aggiunte su `POST /v1/campaigns`, `PATCH /v1/members/{id}`; `X-LH-Actor` malformato; membri: stati × azioni, anonimizzazione propagata servizio per servizio; segmenti: criteri su ogni campo di docs/03 §10. |
| **TB-ENG** | pubblico dei contenuti (⚠ AND tra tier, segmenti, stati); frequenza pop-up × giorno di Roma; limiti per posizione; template con segnaposto assenti e formattatori su tipi sbagliati; deduplica inbox; ANONYMIZED/BLOCKED; webhook: URL policy per profilo, tabella dei 4 tentativi, 2xx/3xx/4xx/5xx/timeout, *Riprova* per stato. |
| **TB-WEB** | stati *loading/empty/error/degraded/forbidden/validation/stale* per ogni schermata (docs/07 §6); matrice capacità × ruolo in UI; ordine dei motivi di blocco in PT-04; `SERVICE_ASLEEP`; keep-alive e polling di stato; «in arrivo…» con timeout 20 s; personas di docs/10 (⚠ `seed/personas.json` assente). |

**Domini da aggiungere a docs/16 §2** (proposta):

| Nuovo dominio | Contenuto | Storie |
|---|---|---|
| **TB-INS** (insight e audit) | DLQ riprocessa/scarta per stato × famiglia × ruolo; tracciato `COMPLETE/IN_PROGRESS/FAILED` con i 5 s di quiete e le voci DLQ chiuse; SSE con `Last-Event-ID`; KPI senza doppi conteggi; retention; audit con diff e override | US-E08-07, US-E08-11, US-E08-12, US-E09-02…07 |
| **TB-E2E** (percorsi tra servizi) | una riga per percorso reale, oracolo = sequenza di fatti attesa in **un** tracciato + stato finale in ogni servizio + ciò che vede il portale: tier-up con bonus e messaggi; premio coupon; premio fisico con evasione CARE; saldo insufficiente; vincita garantita; scadenza con preavviso; cliente digitale con segmento e contenuto; referral; onboarding; smoke. Varianti reali da includere: **servizio addormentato a metà percorso** (wallet giù durante la saga: conferma entro 10 min / timeout / spesa tardiva con rimborso), **riconsegna** di un messaggio a metà catena, **reset** tra due esecuzioni, **ora di Roma** vicino alla mezzanotte e al cambio d'ora, **due azioni dello stesso membro ravvicinate** (limiti e salita di livello) | US-E10-03…07, US-E10-13…16, US-E11-09 |
| **TB-PLT** (piattaforma) | outbox con Kafka giù, retry e DLQ per tipo di errore, idempotenza generica, contratti evento, errori RFC 9457, reset idempotente dei seed, RNF misurabili | US-E11-07, US-E12-01, US-E12-03, US-E12-05, US-E12-06, US-E12-08 |

**Da registrare prima di scrivere le righe** (divergenze e buchi emersi in §5, da portare in docs/15 e poi nel registro di docs/16 §12): guardia di ruolo mancante su `POST /v1/members`; ⛔ ritentativo `NO_MEMBER`, `keepWarning`, creazione di fonti (`POST /v1/sources`), `timeline` e riepilogo gamification per BO-03, `/v1/demo/info`, pulizie `processed_event`/`inbound_event`/`evaluation_log`; ⚠ periodi `DAY`/`WEEK` degli obiettivi, AND nel pubblico dei contenuti, `MemberStatus.CLOSED`, `seed/personas.json`; nomi dei motivi in docs/12 (Q-50 esteso a `MEMBER_LIMIT_REACHED`).
