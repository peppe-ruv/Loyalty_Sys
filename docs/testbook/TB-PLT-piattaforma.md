# TB-PLT — Piattaforma (lh-common, hub)

Testbook funzionale della piattaforma comune: envelope e contratti degli eventi, outbox e relay, consumo idempotente,
ritentativi e DLQ, errori RFC 9457, paginazione, identità simulata, reset e informazioni della demo, tempo di business,
configurazione dei client Kafka e dei profili, variabili d'ambiente, salute e metriche, requisiti non funzionali
misurabili. Metodo ed esecuzione: docs/16 §1 e §1bis.

- **Fonti (oracolo).** docs/04 §5 (affidabilità), docs/05 e `contracts/events/` (envelope, tipi, chiavi, topic),
  docs/06 (lh-common, API, errori, paginazione, identità, persistenza, Kafka, profili, salute, endpoint demo), docs/10
  §1 (date relative, reset), docs/11 (variabili, profilo free, JVM, sicurezza della demo), docs/13 (ADR-003, 004, 008,
  009, 015, 023, 024, 025), docs/02 RNF-01…10, docs/08 BO-30, docs/17 E11–E12 (storie scoperte US-E11-07, US-E12-01…08).
  Dove la specifica tace valgono le scelte di docs/15 (Q-131, Q-261, Q-267, Q-298, Q-317, Q-323 e le nuove Q-332…Q-342).
- **Test.** `libs/lh-common/src/test/java/io/loyaltyhub/common/testbook/` — unitari `TestbookPltEnvelopeTest`,
  `TestbookPltClockTest`, `TestbookPltConfigTest`, `TestbookPltDlqTest`, `TestbookPltRoutingTest`,
  `TestbookPltErrorsTest`; d'integrazione `TestbookPltRelayIT` (Postgres Zonky + Kafka in-JVM, SPEC-GAP Q-40).
  `deploy/hub/src/test/java/io/loyaltyhub/hub/` — unitari `TestbookPltBusTest`, `TestbookPltHubConfigTest`; d'integrazione
  `TestbookPltApiIT`, `TestbookPltContractIT` (profilo `demo,inproc`, un contesto per classe, orologio fisso che avanza),
  `TestbookPltFreeProfileIT` (profilo `demo` su Kafka in-JVM con inizializzazione lazy). Dati: `testbook/plt/*.csv` di
  ciascun modulo; ogni caso si chiama `[TB-PLT-<AREA>-NNN] descrizione`.
- **Esito.** 563 righe in 28 aree; 22 cause di divergenza trovate: 21 corrette nel codice (registro in §25), una
  aperta perché richiede una dipendenza nuova (OpenAPI, Q-341). Nessuna riga AMBIGUO: ogni silenzio della specifica è
  deciso nell'opzione conservativa e registrato in docs/15 (§26).

## 1. Inventario delle regole

| R | Regola | Fonte | Codice | Aree |
|---|---|---|---|---|
| R01 | Attributi dell'envelope CloudEvents 1.0 | docs/05 §2 | `LhEvent`, `LhEventFactory`, `LhSource` | ENV, CTR |
| R02 | Propagazione di correlazione, causa, hop e attore; `time` di business per gli accrediti | docs/05 §2; docs/06 §1 | `LhEventFactory#childOf/childSameBusinessTime/childForSubject` | ENV |
| R03 | Ponte fatti → azioni: nuovo id, `source` internal, stesso subject/time/correlazione, causa = fatto, hop + 1 | docs/05 §7; ADR-003 | `LhEventFactory#bridgeAction`, ingestion `FactsHandler#bridge` | ENV, LOP |
| R04 | Guardia anti-ciclo: `lhhop` > 3 ⇒ DLQ `LOOP_GUARD` senza ritentativi | docs/05 §7 | `LoopGuardException`, `DlqRecords` | LOP, DLR, DLK, BUS |
| R05 | Cinque topic per famiglia, nomi configurabili, chiave `memberId` o id aggregato | docs/05 §1; ADR-004 | `LoyaltyHubProperties#topicFor`, `LhEvent#partitionKey`, `OutboxWriter` | TOP, HCF |
| R06 | Audit: tipo unico, `lh.audit.v1`, chiave `entityType:entityId`, `lhactor` obbligatorio, azioni ammesse | docs/05 §6 | `AuditPublisher`, `AuditEntry` | TOP, CTR |
| R07 | Uno schema e un esempio per ogni `type`; ogni evento prodotto valida | docs/05 §9; ADR-009 | `contracts/events/**`, produttori di tutti i servizi | CTR |
| R08 | Pubblicazione solo dall'outbox scritta nella transazione del cambiamento | RNF-05; ADR-008 | `OutboxWriter` | REL |
| R09 | Relay: `SKIP LOCKED`, lotti da 100, `published_at`, chiave e header `lh-*`, content-type CloudEvents | docs/04 §5; docs/05 §2 | `OutboxRelay` | REL |
| R10 | Kafka giù ⇒ nessuna perdita; ordine per chiave | RNF-04; RNF-05; docs/12 M0 | `OutboxRelay`, `OutboxWriter` (`clock_timestamp`) | REL, NFR |
| R11 | Consumo idempotente: `processed_event` + logica + outbox in una transazione | docs/06 §1; RNF-03 | `IdempotentHandler`, `ProcessedEvents` | ITX, IDM |
| R12 | `type` non gestito ignorato senza errore e senza `processed_event` | docs/05 §1 | `EventRouter#route` | IDM, BRT |
| R13 | 3 tentativi (1 s, 5 s), non ritentabili (validazione, deserializzazione) subito in DLQ | docs/04 §5; Q-131 | `DlqRecords`, `LhKafkaConfiguration#lhErrorHandler`, `HubInProcessBus` | DLR, DLK, BUS, BRT |
| R14 | Header DLQ `lh-original-topic`, `lh-consumer`, `lh-error-class`, `lh-error-message`, `lh-attempts` | docs/04 §5 | `DlqRecords#headers` | DLR, DLK, BUS |
| R15 | Pulizie: outbox pubblicata > 24 h, `processed_event` > 14 giorni | docs/06 §4; RNF-07; Q-335 | `OutboxCleanup`, `ProcessedEventCleanup` | REL |
| R16 | Metriche `lh_events_consumed_total`, `lh_events_published_total`, `lh_outbox_pending`, `lh_handler_seconds` | docs/06 §8 | `LhMetrics`, `EventRouter`, `OutboxRelay` | REL, IDM, HLR |
| R17 | Salute: componenti `db` e `kafka`; kafka con timeout 3 s e cache 30 s; liveness senza dipendenze, readiness con db e kafka | docs/06 §8; docs/11 §6 | `LhKafkaHealthIndicator`, `LhEnvironmentAliases` | HLT, HLR, VAR |
| R18 | Client Kafka: `acks=all` idempotente, gruppo `lh-<servizio>`, `earliest`, ack manuale, 50 record, concorrenza 2 | docs/06 §5; docs/05 §1 | `LhKafkaConfiguration` | KCF |
| R19 | Sicurezza PLAINTEXT / SSL_PEM / SASL_SSL, PEM in base64 senza file | docs/06 §5; docs/11 §3; ADR-025 | `LhKafkaSecurity` | KCF |
| R20 | Topic 5 × 2 con retention 3 giorni, creati solo nel profilo local | docs/05 §1; docs/11 §3 | `LhKafkaConfiguration.LocalTopics` | KCF |
| R21 | Errori `application/problem+json` per famiglia, `code` stabile, `detail` italiano | docs/06 §2 | `GlobalExceptionHandler`, `LhException` | ERR, HER |
| R22 | Elenchi `{items, page}`, `size` massimo 100 | docs/06 §2; Q-332 | `PageResponse`, `PageParams`, 7 servizi | PAG |
| R23 | `X-LH-Actor` canonico, altrimenti ANALYST; `@RequiresRole` | docs/06 §3; Q-261; Q-298 | `ActorContext`, `ActorFilter`, `RequiresRoleInterceptor` | ERR, ACT |
| R24 | `/v1/demo/**` solo ADMIN; portale senza intestazione | docs/06 §3 | `DemoResetController`, controller del portale | ACT, INF |
| R25 | Reset: tronca e ricarica i seed, idempotente, audit `RESET`, insight per primo | docs/06 §10; docs/10 §1.3; ADR-015; BO-30 | `DemoResetController`, `DemoResettable` di 8 servizi | RST |
| R26 | `GET /v1/demo/info`: profili, versione, conteggi, ultimo reset | docs/06 §10; BO-30 | `DemoResetController#info` | INF |
| R27 | Espressioni di data relative dei seed in Europe/Rome | docs/10 §1.2; Q-267; Q-336; Q-337 | `SeedDates` | CLK |
| R28 | Calendario di business Europe/Rome, chiavi di periodo, edizione | docs/06 §1; docs/10 §1.2 | `BusinessCalendar` | CAL |
| R29 | Variabili d'ambiente della matrice di docs/11 §8 | docs/11 §8; Q-131; Q-340 | `LhEnvironmentAliases` | VAR |
| R30 | Profilo free: lazy init, virtual thread, Tomcat 20, JMX spento, log JSON, Hikari | docs/06 §6; docs/11 §4, §6; RNF-10 | `application-free.yml` degli 8 servizi | FRE, FRP |
| R31 | `@Lazy(false)` su listener e scheduler | docs/06 §5 | classi `*Listener`, `*Jobs`, bean di lh-common | HCF, FRP |
| R32 | MDC dei log: `eventId`, `eventType`, `correlationId`, `memberId` | docs/06 §8; RNF-10 | `EventRouter#route` | IDM |
| R33 | Latenza azione → movimento p50 ≤ 3 s, p95 ≤ 8 s | RNF-02 | pipeline intera | NFR |
| R34 | Limite di 60 eventi al minuto per IP in ingestion | docs/11 §11; Q-339 | ingestion `IngressRateLimitFilter` | RLM |
| R35 | Bus in-process: fan-out per gruppo, asincrono, FIFO, stessi ritentativi e DLQ | ADR-024 | `HubInProcessBus`, `HubInProcessMessaging` | BUS |
| R36 | OpenAPI su `/v3/api-docs` | docs/06 §2 | — (**regola non implementata**, Q-341) | HLR |
| R37 | Hub su broker reale: 5 topic × 2 partizioni, retention 3 giorni, mai in inproc | docs/05 §1; ADR-023; ADR-025 | `HubKafkaTopics` | HCF |

## 2. Rami del codice mappati

Inventario dei punti di decisione di `libs/lh-common/src/main`, `deploy/hub/src/main` e dei rami di piattaforma nei
servizi (reset, paginazione, limite di frequenza). *Senza specifica* = ramo che nessuna fonte prevede: resta com'è se
innocuo (colonna «esito»).

| Classe · ramo | Regola | Area | Esito |
|---|---|---|---|
| `LhEventFactory#newRoot` (2 firme) · `childOf` · `childSameBusinessTime` · `childForSubject` · `bridgeAction` · correlazione del genitore assente | R01–R03 | ENV | coperti |
| `LhEvent#memberId` membro/altro · `partitionKey` membro/subject · `hopOrZero` nullo/valorizzato | R05, R02 | ENV, TOP | coperti |
| `LhFamily#of` assente o senza prefisso · famiglia nota · ignota | R05 | TOP | coperti |
| `LhSource#schemaForType` con/senza prefisso | R01 | ENV | coperti |
| `OutboxWriter#write` famiglia nulla ⇒ `IllegalArgumentException` · topic/chiave espliciti · JSON non serializzabile | R05, R08 | TOP, REL | coperti (serializzazione: difesa) |
| `OutboxRelay#publishBatch` lotto vuoto · invio riuscito ⇒ `published_at` · errore ⇒ rollback del lotto · header nulli omessi · `lh_outbox_pending` | R09, R10, R16 | REL | coperti; rinvio delle righe già inviate nel lotto fallito = consegna almeno una volta (ADR-008) |
| `OutboxCleanup` · `ProcessedEventCleanup` (nuovo) | R15 | REL | coperti |
| `EventRouter` costruttore: famiglia `.*` · doppione di tipo · doppione di famiglia; `route`: senza handler · esatto · famiglia · `type` nullo · MDC | R11, R12, R32 | IDM | coperti; «avvio fallito coi doppioni» senza specifica, innocuo |
| `IdempotentHandler` prima volta/duplicato · `ProcessedEvents#markProcessed` 1/0 | R11 | IDM, ITX | coperti |
| `DlqRecords#unwrap` · `errorCode` LOOP_GUARD/codice/classe · `retryable` (catena delle cause, tipi non ritentabili) · `attemptsFor` · `headers` · troncamento messaggio e stack | R13, R14 | DLR | coperti; `lh-error-retryable`, `lh-error-stack` e troncamenti additivi, senza specifica |
| `LhKafkaConfiguration` gruppo con/senza prefisso · gruppo dall'eccezione o di default · funzione di backoff ritentabile/no · `autoStartup` · `LocalTopics` | R13, R18, R20 | KCF, DLK | coperti |
| `LhKafkaSecurity` PLAINTEXT · SSL_PEM · SASL_SSL SCRAM/PLAIN · CA per SASL · base64 vuoto | R19 | KCF | coperti; base64 vuoto ⇒ PEM vuoto senza specifica |
| `LhKafkaHealthIndicator` in-process · in cache · UP · DOWN | R17 | HLT | coperti |
| `GlobalExceptionHandler` `LhException` con/senza `errors` · validazione · corpo illeggibile · parametro non convertibile · parametro assente · percorso non mappato · dipendenza giù · errore 4xx di Spring (404/altro) · SSE chiuso · imprevisto | R21 | ERR, HER | coperti; 500 `internal` e 410 `gone` senza specifica (decisi in Q-333); SSE chiuso senza corpo, senza specifica, coperto in TB-INS |
| `PageParams#of` page < 0 · size < 1 · size > 100 · valido; `PageResponse#of` size ≤ 0 | R22 | PAG | coperti |
| `ActorContext#parse` vuota · canonica nota · canonica ignota · non canonica; `RequiresRoleInterceptor` non handler · senza annotazione · ADMIN · annotazione vuota · ruolo elencato/no | R23 | ERR, ACT | coperti (parsing puro in TB-GOV ACT) |
| `DemoResetController#reset` con/senza audit · serializzato; `#info` ambiente/JDBC assenti · versione da proprietà/jar/«sviluppo» | R24–R26 | RST, INF | coperti; «sviluppo» senza specifica (Q-338) |
| `SeedDates#resolve` vuota · non `@` · suffisso ora · 9 parole chiave · `last<Giorno>` · scostamenti d/h/M/y · fine mese con M/y · testo estraneo | R27 | CLK | coperti; `last<qualunque giorno>` senza specifica (Q-337) |
| `BusinessCalendar#periodKey` 6 periodi · `currentEditionCode` · `lastWeekday` | R28 | CAL | coperti |
| `AuditPublisher#callerService` servizio/codice comune · `record`/`recordJob` | R06 | TOP | coperti; ricavo del servizio dal package senza specifica (ADR-023) |
| `LhEnvironmentAliases` variabile valorizzata/vuota/sostituita dal nome alternativo · default di salute con/senza sonde | R17, R29 | VAR | coperti |
| `HubInProcessBus#publish` senza consumatori · fan-out; `deliver` successo · ritentativo · non ritentabile · tentativi finiti · DLQ del consumatore della DLQ | R35, R13 | BUS | coperti; scarto del fallimento sulla DLQ senza specifica, innocuo (niente ciclo) |
| `HubInProcessMessaging#invoke` argomenti per tipo · eccezione del listener propagata | R35 | BUS, BRT | coperti |
| `HubKafkaTopics` · `HubDatabase` (una migrazione per schema) | R37 | HCF | coperti (HubDatabase da ogni IT dell'hub) |
| ingestion `IngressRateLimitFilter` metodo/percorso/limite 0 · loopback · finestra piena · `X-Forwarded-For` | R34 | RLM | coperti |
| reset dei servizi: ingestion (ingressi, fonti, tipi, ponte, scenari), campaign (registro valutazioni), wallet (consumi, livelli, edizioni), insight per primo | R25 | RST | coperti |

**Conteggio.** 37 regole; 172 rami di codice mappati (26 gruppi della tabella); 9 rami senza specifica, tutti innocui
e lasciati come sono o decisi in docs/15.

## 3. ENV — Envelope CloudEvents e propagazione

**Regola.** Ogni evento prodotto è un CloudEvent 1.0 con `specversion` 1.0, `id` ULID di 26 caratteri, `source` `urn:loyaltyhub:source:<fonte>` per le azioni e `urn:loyaltyhub:service:<servizio>` per effetti, fatti e audit, `datacontenttype` application/json, `dataschema` `urn:loyaltyhub:schema:<famiglia>.<nome>:1`, `lhtenant` aurora, `lhcorrelationid` dell'azione radice, `lhcausationid` del genitore, `lhhop` 0 per le radici e +1 solo al ponte, `lhactor` propagato; `time` = data di business per gli accrediti, istante di elaborazione altrimenti (docs/05 §2, §7; docs/06 §1 `LhEventFactory`). I consumatori tollerano campi sconosciuti (docs/05 §9).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| genitore | con/ senza `lhcorrelationid`, `lhhop` 0/2/null, attore presente/assente | — |
| fabbrica | `newRoot`, `childOf`, `childSameBusinessTime`, `childForSubject`, `bridgeAction` | — |
| JSON | campi nulli, `time`, `lhhop` | campi ed estensioni sconosciuti |
| id nello stesso ms | 1…50 | — |

**Strategia di combinazione.** una riga per attributo × metodo della fabbrica (i metodi non si combinano: ognuno è una decisione a sé); valori limite di `lhhop` al ponte 0, 2 (→ 3, ultimo ammesso) e assente; 50 id nello stesso millisecondo per la monotonia.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-ENV-001 | radice: specversion 1.0 (`root.specversion`) | 1.0 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-002 | radice: id ULID di 26 caratteri Crockford (`root.idUlid`) | 26:true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-003 | radice di un servizio: source urn:loyaltyhub:service:<servizio> (`root.sourceService`) | urn:loyaltyhub:service:campaign | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-004 | radice con fonte esplicita: source urn:loyaltyhub:source:<fonte> (`root.sourceExplicit`) | urn:loyaltyhub:source:ecommerce | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-005 | radice: time = istante dell'orologio (UTC) (`root.time`) | 2026-09-24T10:00:00Z | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-006 | radice: datacontenttype application/json (`root.datacontenttype`) | application/json | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-007 | dataschema di un'azione (`root.dataschemaAction`) | urn:loyaltyhub:schema:action.purchase.completed:1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-008 | dataschema di un fatto (`root.dataschemaFact`) | urn:loyaltyhub:schema:fact.wallet.points.earned:1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-009 | dataschema dell'audit (`root.dataschemaAudit`) | urn:loyaltyhub:schema:audit.entry:1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-010 | radice: lhtenant aurora (ADR-013) (`root.tenant`) | aurora | ADR-013 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-011 | radice: lhcorrelationid = id dell'azione radice (`root.correlation`) | true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-012 | radice: lhcausationid assente (`root.causation`) | null | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-013 | radice: lhhop 0 (`root.hop`) | 0 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-014 | radice senza attore: lhactor assente (facoltativo) (`root.actorAbsent`) | null | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-015 | radice da backoffice: lhactor RUOLO:username (`root.actorPresent`) | MARKETING:luca.marketing | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-016 | figlio: stessa correlazione del genitore (`child.correlation`) | 01J8ZK3A0000000000000CORR0 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-017 | figlio: lhcausationid = id del genitore (`child.causation`) | 01J8ZK3V7Q2M9T4B6N8R0XWYCD | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-018 | figlio (effetto o fatto): lhhop del genitore invariato (+1 solo al ponte) (`child.hop`) | 2 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-019 | figlio: stesso subject (`child.subject`) | member:MBR-000003 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-020 | figlio non contabile: time = istante di elaborazione (`child.time`) | 2026-09-24T10:00:00Z | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-021 | accredito: time = time dell'azione radice (stessa data di business) (`child.sameBusinessTime`) | 2026-09-18T10:15:00Z | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-022 | figlio: lhactor propagato (docs/06 §1) (`child.actor`) | CARE:paolo.care | docs/06 §1 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-023 | figlio: source = servizio che lo produce (`child.source`) | urn:loyaltyhub:service:campaign | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-024 | figlio: dataschema del proprio type (`child.dataschema`) | urn:loyaltyhub:schema:effect.points.grant:1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-025 | figlio: nuovo id ULID diverso dal genitore (`child.newId`) | true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-026 | genitore senza lhcorrelationid: correlazione = id del genitore (`child.parentWithoutCorrelation`) | 01J8ZK3V7Q2M9T4B6N8R0XWYCD | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-027 | genitore senza lhhop: figlio con lhhop 0 (`child.parentWithoutHop`) | 0 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-028 | figlio per un altro membro (referral): subject del secondo membro (`childForSubject.subject`) | member:MBR-000002 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-029 | figlio per un altro membro: stessa correlazione (`childForSubject.correlation`) | 01J8ZK3A0000000000000CORR0 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-030 | figlio per un altro membro: causa = genitore (`childForSubject.causation`) | 01J8ZK3V7Q2M9T4B6N8R0XWYCD | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-031 | ponte: source urn:loyaltyhub:source:internal (`bridge.source`) | urn:loyaltyhub:source:internal | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-032 | ponte da fatto con lhhop 0: azione con lhhop 1 (`bridge.hop0`) | 1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-033 | ponte da fatto con lhhop 2: azione con lhhop 3 (`bridge.hop2`) | 3 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-034 | ponte da fatto senza lhhop: azione con lhhop 1 (`bridge.hopNull`) | 1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-035 | ponte: stesso time del fatto (`bridge.time`) | 2026-09-18T10:15:00Z | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-036 | ponte: stesso subject del fatto (`bridge.subject`) | member:MBR-000003 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-037 | ponte: lhcausationid = id del fatto (`bridge.causation`) | 01J8ZK3V7Q2M9T4B6N8R0XWYCD | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-038 | ponte: stessa correlazione del fatto (`bridge.correlation`) | 01J8ZK3A0000000000000CORR0 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-039 | ponte: nuovo id ULID (`bridge.newId`) | true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-040 | ponte: dataschema dell'azione generata (`bridge.dataschema`) | urn:loyaltyhub:schema:action.tier.upgraded:1 | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-041 | ponte: lhactor del fatto propagato (`bridge.actor`) | ADMIN:marta.admin | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-042 | 50 id nello stesso millisecondo: ULID distinti e crescenti (`ulid.monotonic`) | true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-043 | JSON: attributi nulli omessi (lhcausationid e lhactor) (`json.nullsOmitted`) | true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-044 | JSON: time RFC 3339 UTC (`json.timeRfc3339`) | 2026-09-24T10:00:00Z | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-045 | JSON: campi ed estensioni sconosciuti tollerati in lettura (docs/05 §9) (`json.unknownTolerated`) | io.loyaltyhub.action.app.login.daily | docs/05 §9 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-046 | JSON: lhhop numerico (`json.hopAsNumber`) | true | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-047 | radice valida contro envelope.schema.json (`schema.root`) | [] | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-048 | figlio valido contro envelope.schema.json (`schema.child`) | [] | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-049 | azione del ponte valida contro envelope.schema.json (`schema.bridge`) | [] | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-ENV-050 | voce di audit valida contro envelope.schema.json (`schema.audit`) | [] | docs/05 §2 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |


## 4. TOP — Topic, famiglie e chiavi

**Regola.** Cinque topic per famiglia semantica, nomi configurabili (ADR-004, docs/05 §1): azioni, effetti, fatti, audit, DLQ. Chiave = `memberId`, oppure l'id aggregato per i fatti di configurazione (`edition:<code>`); l'audit va su `lh.audit.v1` con chiave `entityType:entityId` e `lhactor` sempre valorizzato (attore della richiesta, `ANALYST:anonymous` senza intestazione, `system` per i job) e `action` in CREATE…RESET (docs/05 §6). Un `type` fuori dalle famiglie non entra nell'outbox.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| famiglia | ACTION, EFFECT, FACT, AUDIT | tipo breve, famiglia `dlq`, assente |
| subject | `member:<id>`, `<entità>:<id>` | — |
| attore dell'audit | RUOLO:username, assente, job | — |

**Strategia di combinazione.** tabella completa famiglia → topic (4 + DLQ); ogni classe non valida da sola.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-TOP-001 | famiglia ACTION su lh.actions.v1 (`topic.ACTION`) | lh.actions.v1 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-002 | famiglia EFFECT su lh.effects.v1 (`topic.EFFECT`) | lh.effects.v1 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-003 | famiglia FACT su lh.facts.v1 (`topic.FACT`) | lh.facts.v1 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-004 | famiglia AUDIT su lh.audit.v1 (`topic.AUDIT`) | lh.audit.v1 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-005 | esattamente 5 topic con i nomi di default (ADR-004) (`topic.all`) | lh.actions.v1 lh.effects.v1 lh.facts.v1 lh.audit.v1 lh.dlq.v1 | ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-006 | topic DLQ lh.dlq.v1 (`topic.dlq`) | lh.dlq.v1 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-007 | nome di topic da configurazione (loyaltyhub.topics.*) (`topic.custom`) | x.actions.v9 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-008 | famiglia da type completo di azione (`family.action`) | ACTION | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-009 | famiglia dell'audit (`family.audit`) | AUDIT | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-010 | type breve: nessuna famiglia (`family.short`) | null | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-011 | famiglia sconosciuta (dlq): nessuna famiglia (`family.unknown`) | null | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-012 | type assente: nessuna famiglia (`family.null`) | null | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-013 | outbox di un type senza famiglia: rifiutato (`writer.unknownFamily`) | ERR:IllegalArgumentException | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-014 | outbox di un effetto: topic della famiglia e chiave memberId (`writer.familyTopic`) | lh.effects.v1 MBR-000003 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-015 | chiave di un evento di membro = memberId (`key.member`) | MBR-000003 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-016 | chiave di un fatto di configurazione = <entityType>:<id> (`key.config`) | edition:E2026 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-017 | fatto di configurazione: nessun memberId (`key.memberId.config`) | null | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-018 | audit su lh.audit.v1 con chiave entityType:entityId (`audit.topicKey`) | lh.audit.v1 WALLET:MBR-000004 | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-019 | audit: type unico io.loyaltyhub.audit.entry (`audit.type`) | io.loyaltyhub.audit.entry | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-020 | audit da backoffice: lhactor dell'intestazione (`audit.actorRequest`) | MARKETING:luca.marketing | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-021 | audit senza intestazione: lhactor ANALYST:anonymous (mai assente) (`audit.actorAnonymous`) | ANALYST:anonymous | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-022 | audit di un job: lhactor system (`audit.actorJob`) | system | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-023 | audit da codice comune: service = loyaltyhub.service (`audit.service`) | wallet urn:loyaltyhub:service:wallet | docs/05 §1, §6; ADR-004 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |
| TB-PLT-TOP-024 | audit: azioni ammesse (docs/05 §6) (`audit.actions`) | CREATE UPDATE DELETE TRANSITION ADJUST JOB RESET | docs/05 §6 | `TestbookPltEnvelopeTest#envelope` · `envelope.csv` |


## 5. CTR — Conformità ai contratti di ogni `type`

**Regola.** Per ogni `type` del catalogo di docs/05 §3–§6 (e i fatti analoghi `reward/contest.status.changed`): esiste `contracts/events/<famiglia>/<nome>.schema.json` con un esempio valido contro envelope e schema (docs/05 §9, ADR-009); ogni evento realmente prodotto dai flussi del PoC (event store di insight) valida contro envelope e schema, sta sul topic della sua famiglia, ha `dataschema` del proprio tipo e `:1`, `lhtenant` aurora, `source` `urn:loyaltyhub:source:*` per le azioni e `urn:loyaltyhub:service:*` altrimenti, `id` ULID per gli eventi dei servizi e delle azioni interne (`lhhop` 1…3), `subject` di membro o di configurazione, audit con `lhactor`.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| type | i 64 del catalogo | `member.birthday` (P2, docs/01 §4): nessun produttore |

**Strategia di combinazione.** una riga per `type` (64). I flussi che producono gli eventi girano una volta prima delle righe: le 10 azioni esterne dalle rispettive fonti con i dati d'esempio del seed, gli scenari SCN-TIER-UP, SCN-REFERRAL, SCN-ONBOARDING, SCN-DIGITAL, SCN-POISON, anagrafica (modifica, stato, segmento statico), una campagna con punti in attesa + badge + messaggio e il rilascio, rettifica, preavvisi e scadenze con `asOf`, saghe di premio (riuscita, rifiutata, annullata), uso di coupon, vincite piantate, transizioni di premio, concorso, campagna, contenuto, chiusura dell'edizione. I tipi P2 hanno la riga «contratto valido; nessun produttore».

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-CTR-001 | `io.loyaltyhub.action.purchase.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-002 | `io.loyaltyhub.action.purchase.returned` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-003 | `io.loyaltyhub.action.ebill.activated` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-004 | `io.loyaltyhub.action.directdebit.activated` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-005 | `io.loyaltyhub.action.selfreading.submitted` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-006 | `io.loyaltyhub.action.app.login.daily` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-007 | `io.loyaltyhub.action.survey.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-008 | `io.loyaltyhub.action.quiz.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-009 | `io.loyaltyhub.action.review.submitted` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-010 | `io.loyaltyhub.action.newsletter.subscribed` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-011 | `io.loyaltyhub.action.member.registered` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-012 | `io.loyaltyhub.action.member.profile.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-013 | `io.loyaltyhub.action.member.birthday` (chiave: membro) | contratto valido; nessun produttore (P2 fuori perimetro) — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-014 | `io.loyaltyhub.action.tier.upgraded` (chiave: membro) | contratto valido e ogni evento prodotto conforme — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-015 | `io.loyaltyhub.action.instantwin.won` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-016 | `io.loyaltyhub.action.achievement.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-017 | `io.loyaltyhub.action.badge.awarded` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-018 | `io.loyaltyhub.action.referral.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-019 | `io.loyaltyhub.action.reward.redeemed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-020 | `io.loyaltyhub.effect.points.grant` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-021 | `io.loyaltyhub.effect.plays.grant` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-022 | `io.loyaltyhub.effect.coupon.issue` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-023 | `io.loyaltyhub.effect.badge.award` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-024 | `io.loyaltyhub.effect.message.send` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-025 | `io.loyaltyhub.fact.member.registered` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-026 | `io.loyaltyhub.fact.member.updated` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-027 | `io.loyaltyhub.fact.member.status.changed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-028 | `io.loyaltyhub.fact.member.profile.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-029 | `io.loyaltyhub.fact.member.birthday` (chiave: membro) | contratto valido; nessun produttore (P2 fuori perimetro) — divergenza corretta D-19 | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-030 | `io.loyaltyhub.fact.member.segment.entered` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-031 | `io.loyaltyhub.fact.member.segment.left` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-032 | `io.loyaltyhub.fact.referral.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-033 | `io.loyaltyhub.fact.campaign.evaluated` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-034 | `io.loyaltyhub.fact.campaign.status.changed` (chiave: config) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-035 | `io.loyaltyhub.fact.wallet.points.earned` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-036 | `io.loyaltyhub.fact.wallet.points.spent` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-037 | `io.loyaltyhub.fact.wallet.spend.rejected` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-038 | `io.loyaltyhub.fact.wallet.points.refunded` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-039 | `io.loyaltyhub.fact.wallet.points.expired` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-040 | `io.loyaltyhub.fact.wallet.points.expiring` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-041 | `io.loyaltyhub.fact.wallet.points.adjusted` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-042 | `io.loyaltyhub.fact.wallet.points.released` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-043 | `io.loyaltyhub.fact.tier.upgraded` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-044 | `io.loyaltyhub.fact.tier.downgraded` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-045 | `io.loyaltyhub.fact.tier.retained` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-046 | `io.loyaltyhub.fact.edition.closed` (chiave: config) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-047 | `io.loyaltyhub.fact.reward.redemption.requested` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-048 | `io.loyaltyhub.fact.reward.redemption.confirmed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-049 | `io.loyaltyhub.fact.reward.redemption.fulfilled` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-050 | `io.loyaltyhub.fact.reward.redemption.rejected` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-051 | `io.loyaltyhub.fact.reward.redemption.cancelled` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-052 | `io.loyaltyhub.fact.coupon.issued` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-053 | `io.loyaltyhub.fact.coupon.used` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-054 | `io.loyaltyhub.fact.contest.plays.granted` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-055 | `io.loyaltyhub.fact.contest.played` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-056 | `io.loyaltyhub.fact.contest.won` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-057 | `io.loyaltyhub.fact.achievement.progressed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-058 | `io.loyaltyhub.fact.achievement.completed` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-059 | `io.loyaltyhub.fact.badge.awarded` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-060 | `io.loyaltyhub.fact.message.delivered` (chiave: membro) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-061 | `io.loyaltyhub.fact.content.status.changed` (chiave: config) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-062 | `io.loyaltyhub.fact.reward.status.changed` (chiave: config) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-063 | `io.loyaltyhub.fact.contest.status.changed` (chiave: config) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |
| TB-PLT-CTR-064 | `io.loyaltyhub.audit.entry` (chiave: audit) | contratto valido e ogni evento prodotto conforme | docs/05 §9; ADR-009 | `TestbookPltContractIT#contracts` · `contratti.csv` |


## 6. REL — Outbox e relay

**Regola.** Si pubblica solo dall'outbox scritta nella transazione del cambiamento di stato (RNF-05, ADR-008); il relay (500 ms, `FOR UPDATE SKIP LOCKED`, lotti da 100) invia su Kafka con chiave e header `lh-type`, `lh-correlation-id` (più causa, hop, attore) e `content-type: application/cloudevents+json`, poi marca `published_at`; con Kafka irraggiungibile la riga resta e parte al ritorno (docs/12 M0); l'ordine per chiave è quello di scrittura (RNF-04); le righe pubblicate da oltre 24 h e i `processed_event` oltre 14 giorni si cancellano (docs/06 §4, RNF-07; Q-335); `lh_outbox_pending` e `lh_events_published_total` (docs/06 §8).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| righe nel lotto | 99, 100 | 101 (oltre il lotto) |
| broker | raggiungibile | irraggiungibile, perso al 3° invio di 5 |
| relay concorrenti | 1 | 2 su 200 righe |
| età della riga pubblicata | 23 h 59 min | 24 h 1 min; mai pubblicata da 10 giorni |
| età di `processed_event` | 13 g 23 h | 14 g 1 min |

**Strategia di combinazione.** ogni classe e ogni limite da solo (le decisioni sono indipendenti: lotto, broker, concorrenza, età); il guasto a metà lotto è combinato con il consumo idempotente per provare «nessun duplicato visibile, ordine conservato».

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-REL-001 | riga dell'outbox pubblicata sul topic della famiglia con chiave memberId e marcata (`publish`) | topic=lh.effects.v1 chiave=memberId valore=envelope pubblicata=true | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-002 | header lh-* e content-type CloudEvents di un evento figlio (`headersChild`) | lh-type=io.loyaltyhub.effect.points.grant lh-correlation-id=ok lh-causation-id=ok lh-hop=2 lh-actor=CARE:paolo.care content-type=application/cloudevents+json | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-003 | evento radice: niente header di causa né di attore (`headersRoot`) | lh-causation-id=assente lh-actor=assente lh-hop=0 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-004 | audit su lh.audit.v1 con chiave entityType:entityId (`audit`) | topic=lh.audit.v1 chiave=entityType:entityId | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-005 | transazione del cambiamento di stato annullata: nessuna riga e nessuna pubblicazione (RNF-05) (`rollback`) | outbox=0 ricevuti=0 | RNF-05 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-006 | 20 eventi dello stesso membro in una transazione: pubblicati nell'ordine di scrittura (RNF-04) (`orderOneTx`) | ordine=0..19 | RNF-04 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-007 | 15 eventi dello stesso membro in tre transazioni: ordine di scrittura (`orderAcrossTx`) | ordine=0..14 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-008 | 99 righe: tutte in un giro (lotto 100) (`batch99`) | dopo un giro=99 in attesa=0 dopo due giri in attesa=0 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-009 | 100 righe: tutte in un giro (`batch100`) | dopo un giro=100 in attesa=0 dopo due giri in attesa=0 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-010 | 101 righe: 100 al primo giro e 1 al secondo (`batch101`) | dopo un giro=100 in attesa=1 dopo due giri in attesa=0 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-011 | broker irraggiungibile: il relay fallisce e la riga resta in outbox (nessuna perdita) (`brokerDown`) | eccezione=OutboxPublishException in attesa=1 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-012 | broker di nuovo raggiungibile dopo due giri falliti: la riga parte una volta (`brokerBack`) | in attesa=0 ricevuti=1 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-013 | guasto al terzo invio di 5: lotto annullato e ripubblicato; il consumer idempotente elabora ogni evento una volta e in ordine (`midBatch`) | in attesa dopo il guasto=5 consegne=7 elaborati=0 1 2 3 4 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-014 | due relay concorrenti su 200 righe (SKIP LOCKED): ogni riga pubblicata una sola volta (`twoRelays`) | in attesa=0 messaggi=200 distinti=200 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-015 | outbox vuota: giro senza errori (`empty`) | in attesa=0 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-016 | lh_outbox_pending = righe da pubblicare (docs/06 §8) (`pendingGauge`) | prima=3.0 dopo=0.0 — divergenza corretta D-12 | docs/06 §8 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-017 | lh_events_published_total per type (`publishedMetric`) | lh_events_published_total=2.0 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-018 | riga pubblicata da 24 h e 1 min: cancellata dalla pulizia (`outboxCleanOld`) | riga cancellata | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-019 | riga pubblicata da 23 h 59 min: conservata (`outboxCleanRecent`) | riga conservata | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-020 | riga mai pubblicata da 10 giorni: conservata (mai persa) (`outboxCleanUnpublished`) | riga conservata | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-021 | processed_event di 14 giorni e 1 minuto: cancellato (RNF-07 docs/06 §4) (`processedOld`) | riga cancellata — divergenza corretta D-13 | RNF-07 docs/06 §4 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-REL-022 | processed_event di 13 giorni e 23 ore: conservato (`processedRecent`) | riga conservata | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |


## 7. ITX — Consumo idempotente transazionale

**Regola.** `processed_event` + logica + outbox nella stessa transazione: un errore della logica annulla anche la marcatura (il ritentativo rielabora), un doppio invio — anche concorrente — non riesegue la logica (docs/06 §1, RNF-03).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| consegne | 1 | 2 in sequenza, 2 concorrenti; logica che fallisce |

**Strategia di combinazione.** ogni classe da sola, su Postgres reale.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-ITX-001 | logica che fallisce dopo aver scritto l'effetto: processed_event e outbox annullati; il ritentativo li scrive una volta (`idmRollback`) | dopo il guasto processed=0 outbox=0; dopo il ritentativo processed=1 outbox=1 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-ITX-002 | doppia consegna in due transazioni: logica ed effetto una volta (RNF-03) (`idmDuplicate`) | esiti=[true, false] effetti=1 | RNF-03 | `TestbookPltRelayIT#relay` · `relay.csv` |
| TB-PLT-ITX-003 | doppia consegna concorrente dello stesso evento: logica ed effetto una volta (`idmConcurrent`) | eseguiti=1 effetti=1 | docs/04 §5; ADR-008 | `TestbookPltRelayIT#relay` · `relay.csv` |


## 8. IDM — Instradamento per `type` e contesto dei log

**Regola.** `EventRouter` instrada per `type` esatto, altrimenti per famiglia `…*`; un `type` non registrato è ignorato senza errore e senza `processed_event` (docs/05 §1); due handler per lo stesso tipo fanno fallire l'avvio; metriche `lh_events_consumed_total{type}` e `lh_handler_seconds{type}`; durante l'elaborazione l'MDC porta `eventId`, `eventType`, `correlationId`, `memberId` (docs/06 §8, RNF-10) e dopo è ripulito.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| type | esatto, di famiglia | non registrato, prefisso diverso, assente |
| consumer | 1 | 2 sullo stesso id |
| handler | 1 per tipo | 2 per tipo o per famiglia |

**Strategia di combinazione.** tabella completa esatto/famiglia × registrato/no (4) più le classi non valide da sole.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-IDM-001 | type registrato: logica eseguita una volta dal suo handler (`exact.first`) | true [E1] [] | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-002 | doppio invio dello stesso id: logica non rieseguita (RNF-03) (`exact.duplicate`) | false [E1] | RNF-03 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-003 | due id diversi dello stesso type: entrambi elaborati (`exact.twoIds`) | [E1, E2] | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-004 | stesso id per due consumer diversi: ciascuno lo elabora (`perConsumer`) | true [E1, E1] | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-005 | type non gestito: ignorato senza errore e senza processed_event (docs/05 §1) (`unknown.ignored`) | false 0 | docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-006 | type non gestito: il router non lo dichiara (`unknown.handles`) | false | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-007 | handler di famiglia io.loyaltyhub.action.* riceve un'azione senza handler esatto (`family.catches`) | true [E3] | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-008 | handler esatto prevale su quello di famiglia (`family.exactWins`) | [E4] [] | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-009 | handler di famiglia non riceve altre famiglie (`family.otherFamily`) | false | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-010 | handler di famiglia non riceve un prefisso diverso (actionx) (`family.prefixOnly`) | false | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-011 | type assente: ignorato (`type.null`) | false 0 | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-012 | due handler per lo stesso type: avvio fallito (`config.duplicateType`) | ERR:IllegalStateException | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-013 | due handler per la stessa famiglia: avvio fallito (`config.duplicateFamily`) | ERR:IllegalStateException | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-014 | metrica lh_events_consumed_total per type (anche i duplicati; nessuna serie per i type ignorati) (`metrics.consumed`) | 2.0 0 | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-015 | metrica lh_handler_seconds per type (`metrics.handlerTime`) | 1 | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-016 | errore della logica: propagato al contenitore (ritentativi e DLQ) (`logic.throws`) | ERR:IllegalStateException | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-017 | contesto di log durante la logica: eventId eventType correlationId memberId (docs/06 §8) (`mdc.during`) | E11 io.loyaltyhub.action.purchase.completed CORR-1 MBR-000003 — divergenza corretta D-04 | docs/06 §8 | `TestbookPltRoutingTest#routing` · `routing.csv` |
| TB-PLT-IDM-018 | contesto di log ripulito dopo l'evento (`mdc.after`) | true | docs/06 §5; docs/05 §1 | `TestbookPltRoutingTest#routing` · `routing.csv` |


## 9. DLR — Regole dei record DLQ

**Regola.** 3 tentativi (1 s, 5 s: Q-131) per gli errori ritentabili, poi `lh.dlq.v1`; gli errori non ritentabili — validazione, deserializzazione (docs/04 §5), `NonRetryableEventException`, `LOOP_GUARD` (docs/05 §7) — al primo tentativo. Header `lh-original-topic`, `lh-consumer`, `lh-error-class`, `lh-error-message`, `lh-attempts` (docs/04 §5) più `lh-error-code`; `lh-attempts` = tentativi fatti con i ritardi configurati. Q-334: la catena delle cause decide ritentabilità e codice.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| errore | generico, DB giù (transitorio) | non ritentabile, LOOP_GUARD, JSON illeggibile, deserializzazione/conversione/ClassCast di Spring, avvolto |
| ritardi configurati | 2 (3 tentativi) | 3 (4 tentativi) |
| messaggio | assente, 1000 caratteri | 1001 caratteri |
| stack | 1 frame, con causa | 40 frame |

**Strategia di combinazione.** tabella completa tipo d'errore × campo (codice, ritentabile, tentativi) sulle classi rappresentative; limiti di troncamento da soli.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-DLR-001 | errore generico: codice = nome semplice della classe (`state` · code) | IllegalStateException | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-002 | errore generico: ritentabile (`state` · retryable) | true | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-003 | errore generico: 3 tentativi prima della DLQ (Q-131) (`state` · attempts) | 3 | Q-131 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-004 | errore non ritentabile: codice dichiarato (`nonRetryable` · code) | COUPON_POOL_EMPTY | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-005 | errore non ritentabile: nessun ritentativo (`nonRetryable` · retryable) | false | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-006 | errore non ritentabile: 1 tentativo (`nonRetryable` · attempts) | 1 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-007 | guardia anti-ciclo: codice LOOP_GUARD (docs/05 §7) (`loopGuard` · code) | LOOP_GUARD | docs/05 §7 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-008 | guardia anti-ciclo: non ritentabile (`loopGuard` · retryable) | false | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-009 | guardia anti-ciclo: 1 tentativo (`loopGuard` · attempts) | 1 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-010 | JSON illeggibile (deserializzazione): non ritentabile (docs/04 §5) (`json` · retryable) | false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-011 | JSON illeggibile: 1 tentativo (`json` · attempts) | 1 — divergenza corretta D-05 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-012 | DeserializationException di Spring Kafka: non ritentabile (`deserialization` · retryable) | false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-013 | MessageConversionException: non ritentabile (`conversion` · retryable) | false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-014 | ClassCastException: non ritentabile (come l'error handler Kafka) (`classCast` · retryable) | false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-015 | ConversionFailedException: non ritentabile (`conversionFailed` · retryable) | false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-016 | database irraggiungibile: ritentabile (transitorio) (`dbDown` · retryable) | true | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-017 | eccezione avvolta dal contenitore Kafka: vale quella interna (`wrappedListener` · code) | INVALID_EFFECT | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-018 | doppio involucro del contenitore: vale quella più interna (`wrappedTwice` · code) | IllegalStateException | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-019 | causa non ritentabile dentro un'altra eccezione: non ritentabile (Q-334) (`causeNonRetryable` · retryable) | false — divergenza corretta D-06 | Q-334 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-020 | causa non ritentabile dentro un'altra eccezione: codice della causa (Q-334) (`causeNonRetryable` · code) | TEMPLATE_NOT_FOUND — divergenza corretta D-06 | Q-334 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-021 | causa LOOP_GUARD dentro un'altra eccezione: LOOP_GUARD (Q-334) (`causeLoopGuard` · code) | LOOP_GUARD — divergenza corretta D-06 | Q-334 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-022 | causa JSON illeggibile dentro un'altra eccezione: non ritentabile (Q-334) (`causeJson` · retryable) | false — divergenza corretta D-06 | Q-334 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-023 | ritardi configurati 3: errore generico con 4 tentativi (`state` · attempts4) | 4 — divergenza corretta D-07 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-024 | ritardi configurati 3: non ritentabile resta a 1 tentativo (`nonRetryable` · attempts4) | 1 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-025 | header lh-* del record DLQ (docs/04 §5 + lh-error-code) (`state` · headerNames) | lh-error-code lh-original-topic lh-consumer lh-error-class lh-error-message lh-attempts lh-error-retryable lh-error-stack | docs/04 §5 + lh-error-code | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-026 | lh-error-class = nome completo della classe (`state` · class) | java.lang.IllegalStateException | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-027 | lh-original-topic = topic d'origine (`state` · originalTopic) | lh.facts.v1 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-028 | lh-consumer = gruppo consumer (`state` · consumer) | lh-wallet | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-029 | lh-attempts = tentativi fatti (`state` · attemptsHeader) | 3 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-030 | lh-error-retryable per un non ritentabile (`nonRetryable` · retryableHeader) | false | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-031 | messaggio assente: lh-error-message vuoto (`nullMessage` · message) |  | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-032 | messaggio di 1000 caratteri: intero (`message1000` · messageLength) | 1000 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-033 | messaggio di 1001 caratteri: troncato a 1000 più «…» (`message1001` · messageLength) | 1001 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-034 | stack di 40 frame: al più 12 frame (`deepStack` · stackFrames) | 12 | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-035 | stack di 40 frame: riga «... 28 altri» (`deepStack` · stackTail) | ... 28 altri | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-036 | stack di 1 frame: nessuna riga di coda (`shallowStack` · stackTail) | - | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-037 | stack con causa: riga «Caused by:» (`withCause` · stackCause) | Caused by: java.lang.IllegalArgumentException: interno | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |
| TB-PLT-DLR-038 | stack limitato a 4000 caratteri più «…» (`deepStack` · stackMax) | true | docs/04 §5 | `TestbookPltDlqTest#rules` · `dlq-rules.csv` |


## 10. DLK — Ritentativi e DLQ su Kafka reale

**Regola.** L'error handler di `LhKafkaConfiguration` con Kafka in-JVM e il contenitore vero (concorrenza 2, ack manuale): stesse regole di §9 viste dal consumatore — invocazioni, un solo record DLQ con chiave, valore e header originali (`lh-type`) più gli header `lh-*`, e il record successivo sulla stessa partizione elaborato (docs/12 M0).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| guasti prima del successo | 1, 2 | 3 (= tentativi), sempre |
| ritardi | 50 50 | 50 (2 tentativi), 50 50 50 (4 tentativi) |

**Strategia di combinazione.** ogni tipo d'errore con guasto permanente; valori limite dei guasti 1, 2, 3 con 3 tentativi disponibili; ritardi 1 e 3.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-DLK-001 | errore ritentabile persistente: 3 tentativi poi DLQ con header lh-* e il consumer prosegue (`state`, guasti 99, ritardi 50 50) | invocazioni=3 dlq=si tentativi=3 codice=IllegalStateException ritentabile=true topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-002 | errore non ritentabile: subito in DLQ col suo codice (`nonRetryable`, guasti 99, ritardi 50 50) | invocazioni=1 dlq=si tentativi=1 codice=COUPON_POOL_EMPTY ritentabile=false topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-003 | guardia anti-ciclo: subito in DLQ con LOOP_GUARD (`loopGuard`, guasti 99, ritardi 50 50) | invocazioni=1 dlq=si tentativi=1 codice=LOOP_GUARD ritentabile=false topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-004 | JSON illeggibile (deserializzazione): subito in DLQ (docs/04 §5) (`json`, guasti 99, ritardi 50 50) | invocazioni=1 dlq=si tentativi=1 codice=StreamReadException ritentabile=false topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato — divergenza corretta D-05 | docs/04 §5 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-005 | ClassCastException: un tentativo e header coerente (`classCast`, guasti 99, ritardi 50 50) | invocazioni=1 dlq=si tentativi=1 codice=ClassCastException ritentabile=false topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato — divergenza corretta D-05 | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-006 | non ritentabile avvolto in un'altra eccezione: subito in DLQ col codice della causa (Q-334) (`wrapped`, guasti 99, ritardi 50 50) | invocazioni=1 dlq=si tentativi=1 codice=TEMPLATE_NOT_FOUND ritentabile=false topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato — divergenza corretta D-06 | Q-334 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-007 | due guasti poi successo al terzo tentativo: nessuna DLQ (`state`, guasti 2, ritardi 50 50) | invocazioni=3 dlq=no successivo=elaborato | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-008 | un guasto poi successo: nessuna DLQ (`state`, guasti 1, ritardi 50 50) | invocazioni=2 dlq=no successivo=elaborato | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-009 | tre guasti con tre tentativi disponibili: DLQ al terzo (`state`, guasti 3, ritardi 50 50) | invocazioni=3 dlq=si tentativi=3 codice=IllegalStateException ritentabile=true topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-010 | tre ritardi configurati: 4 tentativi e lh-attempts=4 (`state`, guasti 99, ritardi 50 50 50) | invocazioni=4 dlq=si tentativi=4 codice=IllegalStateException ritentabile=true topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato — divergenza corretta D-07 | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |
| TB-PLT-DLK-011 | un solo ritardo configurato: 2 tentativi e lh-attempts=2 (`state`, guasti 99, ritardi 50) | invocazioni=2 dlq=si tentativi=2 codice=IllegalStateException ritentabile=true topic=origine consumer=gruppo chiave=originale valore=poison lh-type=io.loyaltyhub.fact.probe successivo=elaborato — divergenza corretta D-07 | docs/04 §5; Q-131 | `TestbookPltRelayIT#dlq` · `dlq-kafka.csv` |


## 11. BUS — Bus in-process dell'hub

**Regola.** Nel profilo `inproc` il bus sostituisce il broker (ADR-024): una copia per gruppo consumer, consegna asincrona FIFO, header conservati, ritentativi e DLQ con le stesse regole e gli stessi header di lh-common; un consumatore della DLQ che fallisce non rimanda in DLQ (niente ciclo, ramo senza specifica).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| sottoscrizioni | 1, 2 gruppi | nessuna |
| errore | come §9 | consumatore DLQ che fallisce |

**Strategia di combinazione.** ogni proprietà da sola; tipi d'errore come §10.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-BUS-001 | fan-out: una copia a ogni gruppo consumer del topic (`fanout`) | lh-wallet=1 lh-campaign=1 | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-002 | topic senza consumatori: pubblicazione senza errori (come Kafka) (`noSubscribers`) | nessun errore | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-003 | 50 record su due chiavi: consegna nell'ordine di pubblicazione (`order`) | ordine di pubblicazione | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-004 | header e chiave del record arrivano al consumatore (`headers`) | io.loyaltyhub.fact.probe MBR-1 | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-005 | consegna asincrona fuori dalla transazione del relay (`async`) | durante la pubblicazione=0 dopo=1 | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-006 | errore ritentabile: 3 tentativi poi DLQ (Q-131) (`retryable`) | tentativi=3 dlq=[lh-wallet:IllegalStateException:3:true] | Q-131 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-007 | errore non ritentabile: 1 tentativo e DLQ col suo codice (`nonRetryable`) | tentativi=1 dlq=[lh-wallet:COUPON_POOL_EMPTY:1:false] | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-008 | guardia anti-ciclo: 1 tentativo e DLQ LOOP_GUARD (`loopGuard`) | tentativi=1 dlq=[lh-wallet:LOOP_GUARD:1:false] | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-009 | JSON illeggibile: 1 tentativo e DLQ (docs/04 §5) (`json`) | tentativi=1 dlq=[lh-wallet:StreamReadException:1:false] — divergenza corretta D-05 | docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-010 | tre ritardi configurati: 4 tentativi e lh-attempts=4 (`backoff3`) | tentativi=4 dlq=[lh-wallet:IllegalStateException:4:true] | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-011 | due guasti poi successo: nessuna DLQ (`recovers`) | tentativi=3 dlq=[] | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-012 | record DLQ: chiave valore e header originali più il topic d'origine (`dlqRecord`) | chiave=MBR-9 valore=poison lh-type=io.loyaltyhub.fact.probe topic=lh.facts.v1 | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-013 | messaggio velenoso per un consumer: gli altri gruppi e i record successivi proseguono (`otherContinues`) | campaign:poison wallet:next campaign:next | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |
| TB-PLT-BUS-014 | consumatore della DLQ che fallisce: nessun nuovo record DLQ (niente ciclo) (`dlqConsumerFails`) | tentativi del consumer DLQ=3 voci DLQ=1 | ADR-024; docs/04 §5 | `TestbookPltBusTest#bus` · `bus.csv` |


## 12. BRT — Messaggi illeggibili nell'hub

**Regola.** Un valore non JSON è un errore di deserializzazione: ogni consumer lo manda in DLQ al primo tentativo e gli altri proseguono; un JSON valido senza `type` è ignorato (docs/04 §5, docs/05 §1).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| valore | JSON senza type | non JSON, JSON troncato |
| topic | fatti, effetti, azioni | — |

**Strategia di combinazione.** un topic per classe non valida; consumer diversi sullo stesso messaggio.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-BRT-001 | fatto non JSON: il wallet lo manda in DLQ al primo tentativo (non ritentabile) (lh.facts.v1, consumer lh-wallet) | lh-wallet:1:false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltContractIT#unreadable` · `bus-illeggibili.csv` |
| TB-PLT-BRT-002 | fatto non JSON: anche campaign in DLQ al primo tentativo e gli altri consumer proseguono (lh.facts.v1, consumer lh-campaign) | lh-campaign:1:false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltContractIT#unreadable` · `bus-illeggibili.csv` |
| TB-PLT-BRT-003 | effetto non JSON: DLQ del wallet al primo tentativo (lh.effects.v1, consumer lh-wallet) | lh-wallet:1:false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltContractIT#unreadable` · `bus-illeggibili.csv` |
| TB-PLT-BRT-004 | azione JSON troncata: DLQ di campaign al primo tentativo (lh.actions.v1, consumer lh-campaign) | lh-campaign:1:false — divergenza corretta D-05 | docs/04 §5 | `TestbookPltContractIT#unreadable` · `bus-illeggibili.csv` |
| TB-PLT-BRT-005 | JSON valido senza type: ignorato senza errore né DLQ (docs/05 §1) (lh.facts.v1, consumer lh-wallet) | nessuna voce DLQ | docs/05 §1 | `TestbookPltContractIT#unreadable` · `bus-illeggibili.csv` |


## 13. LOP — Guardia anti-ciclo del ponte

**Regola.** Il ponte genera l'azione con `lhhop` + 1; oltre 3 il fatto va in DLQ `LOOP_GUARD` senza ritentativi; i fatti mai mappabili (`wallet.points.*`…) non generano azioni né errori (docs/05 §7, ADR-003).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| lhhop del fatto | 0, 1, 2 | 3 (limite), 7 |
| fatto | mappabile | mai mappabile |

**Strategia di combinazione.** valori limite 2/3 e ogni classe da sola.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-LOP-001 | `fact.badge.awarded` con lhhop 0 | azioni=[1] dlq=[] | docs/05 §7 | `TestbookPltContractIT#loopGuard` · `loop-guard.csv` |
| TB-PLT-LOP-002 | `fact.badge.awarded` con lhhop 1 | azioni=[2] dlq=[] | docs/05 §7 | `TestbookPltContractIT#loopGuard` · `loop-guard.csv` |
| TB-PLT-LOP-003 | `fact.badge.awarded` con lhhop 2 | azioni=[3] dlq=[] | docs/05 §7 | `TestbookPltContractIT#loopGuard` · `loop-guard.csv` |
| TB-PLT-LOP-004 | `fact.badge.awarded` con lhhop 3 | azioni=[] dlq=[lh-ingestion:LOOP_GUARD:1] | docs/05 §7 | `TestbookPltContractIT#loopGuard` · `loop-guard.csv` |
| TB-PLT-LOP-005 | `fact.badge.awarded` con lhhop 7 | azioni=[] dlq=[lh-ingestion:LOOP_GUARD:1] | docs/05 §7 | `TestbookPltContractIT#loopGuard` · `loop-guard.csv` |
| TB-PLT-LOP-006 | `fact.wallet.points.earned` con lhhop 3 | azioni=[] dlq=[] | docs/05 §7 | `TestbookPltContractIT#loopGuard` · `loop-guard.csv` |


## 14. ERR — Errori RFC 9457 (livello web)

**Regola.** `application/problem+json` con `type` `urn:loyaltyhub:problem:<suffisso>`, `title`, `status`, `detail` in italiano, `code` stabile, `instance`, `errors[]` di campo (docs/06 §2): 400 `bad-request`, 403 `forbidden-role`, 404 `not-found`, 409 `conflict`, 422 `validation`, 503 `dependency-unavailable` (anche DB irraggiungibile), 500 `internal` senza dettagli interni; Q-333 per 405/415 e titoli.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| famiglia | 400, 403, 404, 409, 410, 422, 500, 503 | 405, 415 (Q-333) |
| causa 400 | JSON malformato, corpo assente, tipo sbagliato, parametro non convertibile o assente | — |
| attore | MARKETING, ADMIN | assente, CARE su ADMIN, minuscolo |

**Strategia di combinazione.** una riga per famiglia e per causa; controllo trasversale su ogni riga: nessun messaggio interno nel corpo.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-ERR-001 | 400 parametri errati: bad-request (GET /v1/probe/lh/badRequest) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-002 | 403 ruolo non ammesso: forbidden-role (GET /v1/probe/lh/forbidden) | 403 forbidden-role FORBIDDEN_ROLE | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-003 | 404 risorsa inesistente: not-found (GET /v1/probe/lh/notFound) | 404 not-found NOT_FOUND | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-004 | 409 transizione non valida: conflict col codice specifico (GET /v1/probe/lh/conflict) | 409 conflict INVALID_TRANSITION | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-005 | 422 regola di business con errors[] di campo (GET /v1/probe/lh/validation) | 422 validation REWARD_OUT_OF_STOCK | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-006 | 422 senza campi: errors assente (GET /v1/probe/lh/validationNoFields) | 422 validation SHIPPING_REQUIRED | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-007 | 410 risorsa non più utilizzabile: gone (titolo in italiano; Q-333) (GET /v1/probe/lh/gone) | 410 gone COUPON_EXPIRED — divergenza corretta D-03 | titolo in italiano; Q-333 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-008 | 503 dipendenza non disponibile: dependency-unavailable (GET /v1/probe/lh/dependency) | 503 dependency-unavailable DEPENDENCY_UNAVAILABLE | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-009 | 500 eccezione imprevista: INTERNAL_ERROR senza dettagli interni (GET /v1/probe/unexpected) | 500 internal INTERNAL_ERROR | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-010 | database irraggiungibile: 503 dependency-unavailable (docs/06 §2) (GET /v1/probe/db-down) | 503 dependency-unavailable DEPENDENCY_UNAVAILABLE — divergenza corretta D-01 | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-011 | transazione non avviabile (DB giù): 503 dependency-unavailable (GET /v1/probe/tx-down) | 503 dependency-unavailable DEPENDENCY_UNAVAILABLE — divergenza corretta D-01 | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-012 | JSON malformato: 400 senza il messaggio del parser (POST /v1/probe/body) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-013 | corpo assente: 400 (POST /v1/probe/body) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-014 | campo del tipo sbagliato nel corpo: 400 (POST /v1/probe/body) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-015 | parametro non convertibile: 400 col nome del parametro (GET /v1/probe/param?n=abc) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-016 | parametro obbligatorio assente: 400 (GET /v1/probe/param) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-017 | validazione dei campi: 422 VALIDATION con errors[] per campo (POST /v1/probe/bean) | 422 validation VALIDATION | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-018 | metodo non ammesso: 405 problem bad-request (Q-333) (POST /v1/probe/only-get) | 405 bad-request BAD_REQUEST — divergenza corretta D-02 | Q-333 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-019 | tipo di contenuto non supportato: 415 problem bad-request (Q-333) (POST /v1/probe/body) | 415 bad-request BAD_REQUEST — divergenza corretta D-02 | Q-333 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-020 | scrittura senza intestazione (ANALYST:anonymous): 403 (POST /v1/probe/write) | 403 forbidden-role FORBIDDEN_ROLE | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-021 | scrittura da MARKETING: ammessa (POST /v1/probe/write) | 200 | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-022 | endpoint ADMIN chiamato da CARE: 403 coi ruoli richiesti (POST /v1/probe/admin) | 403 forbidden-role FORBIDDEN_ROLE | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-023 | endpoint ADMIN chiamato da ADMIN: ammesso (POST /v1/probe/admin) | 200 | docs/06 §2 | `TestbookPltErrorsTest#problem` · `errors.csv` |
| TB-PLT-ERR-024 | endpoint ADMIN con ruolo minuscolo: ANALYST quindi 403 (Q-298) (POST /v1/probe/admin) | 403 forbidden-role FORBIDDEN_ROLE | Q-298 | `TestbookPltErrorsTest#problem` · `errors.csv` |


## 15. HER — Errori RFC 9457 sulle API vere dell'hub

**Regola.** Le stesse famiglie viste su endpoint reali dei servizi, con `content-type` problem e `instance` = percorso (docs/06 §2); errore imprevisto del database su una scrittura → 500 senza dettagli.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| endpoint | member, wallet, campaign | percorso inesistente |

**Strategia di combinazione.** una riga per famiglia.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-HER-001 | percorso non mappato: 404 not-found (GET /v1/percorso-inesistente) | 404 not-found NOT_FOUND | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-002 | membro inesistente: 404 not-found (GET /v1/members/MBR-999999) | 404 not-found NOT_FOUND | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-003 | JSON malformato su un'API vera: 400 bad-request (POST /v1/members) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-004 | parametro di query non numerico: 400 (GET /v1/members?page=abc) | 400 bad-request BAD_REQUEST | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-005 | scrittura senza X-LH-Actor (ANALYST): 403 forbidden-role (POST /v1/wallets/MBR-000002/adjustments) | 403 forbidden-role FORBIDDEN_ROLE | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-006 | transizione non valida (RESUME su LIVE): 409 conflict (POST /v1/campaigns/CMP-SURVEY/transitions) | 409 conflict INVALID_TRANSITION | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-007 | regola di business violata: 422 validation col codice del servizio (POST /v1/wallets/MBR-000002/adjustments) | 422 validation CURRENCY_NOT_ADJUSTABLE | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-008 | errore imprevisto del database su una scrittura: 500 INTERNAL_ERROR senza dettagli (POST /v1/members) | 500 internal INTERNAL_ERROR | docs/06 §2 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-009 | metodo non ammesso: 405 bad-request (Q-333) (DELETE /v1/members) | 405 bad-request BAD_REQUEST — divergenza corretta D-02 | Q-333 | `TestbookPltApiIT#errors` · `errors-hub.csv` |
| TB-PLT-HER-010 | tipo di contenuto non supportato: 415 bad-request (Q-333) (POST /v1/members) | 415 bad-request BAD_REQUEST — divergenza corretta D-02 | Q-333 | `TestbookPltApiIT#errors` · `errors-hub.csv` |


## 16. PAG — Paginazione `{items, page}`

**Regola.** Elenchi con `?page=0&size=20`, risposta `{items, page{number, size, totalItems, totalPages}}`, `size` massimo 100 (docs/06 §2); Q-332: `size` < 1 o `page` < 0 ⇒ 400 `BAD_REQUEST` in ogni servizio (estende Q-317/Q-323), `size` > 100 ⇒ 100; `totalPages` = ⌈totalItems/size⌉.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| size | 1, 100, default | 0, 101 |
| page | 0 | −1 |
| elenco | 10 elenchi paginati di member, reward, engagement, gamification, insight | — |

**Strategia di combinazione.** tabella completa elenco × classe (10 × 6 = 60 ≤ 64).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-PAG-001 | GET /v1/members size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-002 | GET /v1/members size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-003 | GET /v1/members size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-004 | GET /v1/members size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-005 | GET /v1/members page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-006 | GET /v1/members (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-007 | GET /v1/segments size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-008 | GET /v1/segments size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-009 | GET /v1/segments size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-010 | GET /v1/segments size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-011 | GET /v1/segments page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-012 | GET /v1/segments (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-013 | GET /v1/segments/SEG-DIGITAL/members size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-014 | GET /v1/segments/SEG-DIGITAL/members size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-015 | GET /v1/segments/SEG-DIGITAL/members size=101 | 200 size=100 — divergenza corretta D-14 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-016 | GET /v1/segments/SEG-DIGITAL/members size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-017 | GET /v1/segments/SEG-DIGITAL/members page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-018 | GET /v1/segments/SEG-DIGITAL/members (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-019 | GET /v1/redemptions size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-020 | GET /v1/redemptions size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-021 | GET /v1/redemptions size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-022 | GET /v1/redemptions size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-023 | GET /v1/redemptions page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-024 | GET /v1/redemptions (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-025 | GET /v1/coupon-pools/POOL-CAF/coupons size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-026 | GET /v1/coupon-pools/POOL-CAF/coupons size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-027 | GET /v1/coupon-pools/POOL-CAF/coupons size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-028 | GET /v1/coupon-pools/POOL-CAF/coupons size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-029 | GET /v1/coupon-pools/POOL-CAF/coupons page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-030 | GET /v1/coupon-pools/POOL-CAF/coupons (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-031 | GET /v1/messages size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-032 | GET /v1/messages size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-033 | GET /v1/messages size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-034 | GET /v1/messages size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-035 | GET /v1/messages page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-036 | GET /v1/messages (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-037 | GET /v1/portal/inbox?memberId=MBR-000002 size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-038 | GET /v1/portal/inbox?memberId=MBR-000002 size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-039 | GET /v1/portal/inbox?memberId=MBR-000002 size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-040 | GET /v1/portal/inbox?memberId=MBR-000002 size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-041 | GET /v1/portal/inbox?memberId=MBR-000002 page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-042 | GET /v1/portal/inbox?memberId=MBR-000002 (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-043 | GET /v1/webhooks/WH-CRM-DEMO/deliveries size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-044 | GET /v1/webhooks/WH-CRM-DEMO/deliveries size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-045 | GET /v1/webhooks/WH-CRM-DEMO/deliveries size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-046 | GET /v1/webhooks/WH-CRM-DEMO/deliveries size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-047 | GET /v1/webhooks/WH-CRM-DEMO/deliveries page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-048 | GET /v1/webhooks/WH-CRM-DEMO/deliveries (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-049 | GET /v1/contests/IW-AUTUNNO/instants size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-050 | GET /v1/contests/IW-AUTUNNO/instants size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-051 | GET /v1/contests/IW-AUTUNNO/instants size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-052 | GET /v1/contests/IW-AUTUNNO/instants size=0 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-053 | GET /v1/contests/IW-AUTUNNO/instants page=-1 | 400 BAD_REQUEST — divergenza corretta D-14 | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-054 | GET /v1/contests/IW-AUTUNNO/instants (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-055 | GET /v1/events size=1 | 200 size=1 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-056 | GET /v1/events size=100 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-057 | GET /v1/events size=101 | 200 size=100 | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-058 | GET /v1/events size=0 | 400 BAD_REQUEST | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-059 | GET /v1/events page=-1 | 400 BAD_REQUEST | Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |
| TB-PLT-PAG-060 | GET /v1/events (senza parametri) | 200 default | docs/06 §2; Q-332 | `TestbookPltApiIT#pagination` · `pagination.csv` |


## 17. ACT — Intestazione `X-LH-Actor` sugli endpoint

**Regola.** Vale solo la forma canonica `RUOLO:username` (ruolo noto maiuscolo, un `:`, username non vuoto); ogni altra forma vale ANALYST (Q-261, Q-298); `/v1/demo/**` solo ADMIN (docs/06 §3); il portale non richiede intestazione. Il parsing puro è già in TB-GOV (ACT); qui la catena HTTP completa filtro → guardia → audit.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| intestazione | 5 ruoli canonici, username qualsiasi | assente, vuota, minuscolo, senza `:`, `RUOLO:`, `:user`, più `:`, ruolo ignoto, spazio dopo `:` |

**Strategia di combinazione.** ogni ruolo e ogni forma non canonica da sola sull'endpoint ADMIN del reset; due righe sul portale.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-ACT-001 | `ASSENTE` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-002 | `VUOTA` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-003 | `ANALYST:sara.analyst` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-004 | `CARE:paolo.care` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-005 | `MARKETING:luca.marketing` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-006 | `LEGAL:elena.legal` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-007 | `ADMIN:marta.admin` su POST /v1/demo/reset | 200 ADMIN:marta.admin | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-008 | `admin:marta.admin` su POST /v1/demo/reset | 403 | Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-009 | `ADMIN` su POST /v1/demo/reset | 403 | Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-010 | `ADMIN:` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-011 | `:marta.admin` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-012 | `ADMIN:marta:extra` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-013 | `PIRATA:marta.admin` su POST /v1/demo/reset | 403 | Q-261 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-014 | `«ADMIN: marta.admin»` su POST /v1/demo/reset | 403 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-015 | `ADMIN:ops.notturno` su POST /v1/demo/reset | 200 ADMIN:ops.notturno | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-016 | `ASSENTE` su /v1/portal/wallets/MBR-000002 | 200 | docs/06 §3 | `TestbookPltApiIT#actor` · `actor-hub.csv` |
| TB-PLT-ACT-017 | `PIRATA:x` su /v1/portal/wallets/MBR-000002 | 200 | docs/06 §3; Q-298 | `TestbookPltApiIT#actor` · `actor-hub.csv` |


## 18. RST e INF — Reset della demo e `GET /v1/demo/info`

**Regola.** `POST /v1/demo/reset` (ADMIN) tronca le tabelle del servizio e ricarica i seed, idempotente: due reset danno lo stesso stato, stessi codici coupon e istanti (docs/06 §10, docs/10 §1.3, ADR-015); voce di audit `RESET` con l'attore; insight per primo (BO-30); Q-338: reset concorrenti serializzati. `GET /v1/demo/info`: profili, versione, conteggi, ultimo reset (docs/06 §10, BO-30). Oracolo di stato: impronta del seed (righe per tabella degli 8 schemi e proiezioni con chiavi naturali).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| sequenza | 1 reset, 2 consecutivi | 2 concorrenti; dopo attività su tutti i servizi |
| ruolo | ADMIN | MARKETING (INF-003; ACT per il reset) |

**Strategia di combinazione.** ogni sequenza da sola; l'attività prima del reset tocca ogni servizio (membro, acquisto, rettifica, saga, scenario, giocata, webhook, tipo custom, campagna, premio, contenuto, edizione, fonte).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-INF-001 | GET /v1/demo/info prima di ogni reset: ultimo reset assente | `lastReset` assente — divergenza corretta D-17 | docs/06 §10 | `TestbookPltApiIT#infoBeforeReset` · `info-before.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-RST-001 | reset: risposta OK con tutti i componenti e insight per primo (BO-30) (`response`) | OK primo=insight componenti=[campaign, engagement, gamification, ingestion, insight, insight-synthetic, member, reward, wallet] — divergenza corretta D-15 | BO-30 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-002 | due reset consecutivi: stesso stato (ADR-015) (`twice`) | stato uguale all'impronta del seed | ADR-015 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-003 | attività dopo il seed (membro nuovo acquisto rettifica richiesta premio scenario giocata webhook tipo custom campagna premio contenuto edizione fonte) poi reset: di nuovo il seed (`afterActivity`) | stato uguale all'impronta del seed — divergenza corretta D-16 | docs/06 §10; ADR-015 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-004 | dopo il reset stessi codici coupon e stessi istanti vincenti (docs/10 §1.3) (`codes`) | stato uguale all'impronta del seed | docs/10 §1.3 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-005 | dopo il reset i 12 membri di docs/10 §2 con i loro stati (`members`) | MBR-000001=ACTIVE MBR-000002=ACTIVE MBR-000003=ACTIVE MBR-000004=ACTIVE MBR-000005=ACTIVE MBR-000006=ACTIVE MBR-000007=ACTIVE MBR-000008=BLOCKED MBR-000009=ACTIVE MBR-000010=ACTIVE MBR-000011=ACTIVE MBR-000012=ANONYMIZED | docs/06 §10; ADR-015 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-006 | audit RESET dopo il reset: una voce con l'attore (docs/06 §10) (`audit`) | 1 ADMIN:marta.admin DEMO hub | docs/06 §10 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-007 | due reset concorrenti: entrambi 200 e stato del seed (Q-338) (`concurrent`) | stato uguale all'impronta del seed — divergenza corretta D-16 | Q-338 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-008 | dopo il reset nessuna riga dell'outbox resta da pubblicare (`outbox`) | stato uguale all'impronta del seed | docs/06 §10; ADR-015 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-RST-009 | dopo il reset la pipeline accredita un acquisto (`pipeline`) | stato uguale all'impronta del seed | docs/06 §10; ADR-015 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-INF-002 | GET /v1/demo/info dopo il reset: servizio profili versione conteggi e ultimo reset (docs/06 §10) (`info`) | hub profili=[demo, inproc] versione=presente membri=12 — divergenza corretta D-17 | docs/06 §10 | `TestbookPltApiIT#reset` · `reset.csv` |
| TB-PLT-INF-003 | GET /v1/demo/info da MARKETING: 403 (/v1/demo/** solo ADMIN) (`infoForbidden`) | 403 FORBIDDEN_ROLE — divergenza corretta D-17 | docs/06 §10; ADR-015 | `TestbookPltApiIT#reset` · `reset.csv` |


## 19. HLT, HLR e FRP — Salute, metriche e avvio

**Regola.** Actuator `health` con i componenti `db` e `kafka` (docs/06 §8); `kafka` = `describeCluster` con timeout 3 s e cache 30 s, `in-process` nel profilo inproc; liveness senza dipendenze esterne, readiness con db e kafka (docs/11 §6); metriche `lh_*`; OpenAPI su `/v3/api-docs` (docs/06 §2). Avvio nel profilo `demo,inproc` e, su broker reale, con l'inizializzazione lazy del profilo free.

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| broker | in-process, reale | irraggiungibile |
| lettura ripetuta | entro 30 s | — |

**Strategia di combinazione.** ogni componente da solo.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-HLT-001 | salute kafka nel profilo inproc: UP in-process (`health.inproc`) | UP in-process | docs/06 §8 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-HLT-002 | salute kafka con broker irraggiungibile: DOWN senza eccezione (`health.down`) | DOWN broker | docs/06 §8 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-HLT-003 | salute kafka: describeCluster con timeout 3 s (docs/06 §8) (`health.timeout`) | ≈3 s — divergenza corretta D-09 | docs/06 §8 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-HLT-004 | salute kafka: esito in cache 30 s (docs/06 §8) (`health.cached`) | in cache — divergenza corretta D-09 | docs/06 §8 | `TestbookPltConfigTest#config` · `config.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-HLR-001 | avvio in profilo demo+inproc: salute UP con componenti db e kafka (in-process) (`health`) | UP db=UP kafka=UP in-process | docs/06 §8; docs/11 §6 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-002 | liveness senza dipendenze esterne (docs/11 §6) (`liveness`) | UP livenessState | docs/11 §6 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-003 | readiness con db e kafka (docs/11 §6) (`readiness`) | UP db,kafka,readinessState — divergenza corretta D-11 | docs/11 §6 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-004 | metrica lh_outbox_pending esposta (docs/06 §8) (`metric` lh_outbox_pending) | 200 — divergenza corretta D-12 | docs/06 §8 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-005 | metrica lh_events_published_total esposta (`metric` lh_events_published_total) | 200 | docs/06 §8; docs/11 §6 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-006 | metrica lh_events_consumed_total esposta (`metric` lh_events_consumed_total) | 200 | docs/06 §8; docs/11 §6 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-007 | metrica lh_handler_seconds esposta (`metric` lh_handler_seconds) | 200 | docs/06 §8; docs/11 §6 | `TestbookPltApiIT#health` · `health.csv` |
| TB-PLT-HLR-008 | OpenAPI su /v3/api-docs (docs/06 §2) (`openapi`) | 404 registrato (docs/06 §2 vuole 200) — **DIVERGENZA aperta D-22**, `SPEC-GAP: Q-341` | docs/06 §2 | `TestbookPltApiIT#health` · `health.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-FRP-001 | avvio con inizializzazione lazy su broker Kafka reale: salute UP e kafka in modalità broker (`health`) | UP kafka=UP broker | docs/06 §5, §6; ADR-025 | `TestbookPltFreeProfileIT#freeProfile` · `free-profile.csv` |
| TB-PLT-FRP-002 | inizializzazione lazy: acquisto di 130 € di un SILVER diventa +162 PTS (listener e relay attivi) (`purchase`) | ACCEPTED +162 | docs/06 §5, §6; ADR-025 | `TestbookPltFreeProfileIT#freeProfile` · `free-profile.csv` |
| TB-PLT-FRP-003 | inizializzazione lazy: iscrizione dal portale e bonus di benvenuto (ponte interno e campagna) (`welcome`) | benvenuto=100 | docs/06 §5, §6; ADR-025 | `TestbookPltFreeProfileIT#freeProfile` · `free-profile.csv` |


## 20. KCF — Client Kafka, sicurezza e topic locali

**Regola.** Produttore `acks=all` idempotente; consumatore `lh-<servizio>`, `earliest`, ack manuale, `max.poll.records=50`, concorrenza 2 (docs/06 §5, docs/05 §1); sicurezza PLAINTEXT, SSL_PEM (PEM in base64 da env, nessun file) e SASL_SSL (SCRAM o PLAIN, con la CA di `KAFKA_SSL_CA_B64`: docs/11 §3, ADR-025); ritardi di default 1 s e 5 s (Q-131); topic 5 × 2 partizioni, retention 3 giorni, solo nel profilo local (docs/11 §3).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| sicurezza | PLAINTEXT, SSL_PEM, SASL_SSL SCRAM/PLAIN | base64 vuoto o con spazi; SASL senza CA |
| ritardi | 1000 5000 | nessuno, assenti |

**Strategia di combinazione.** una riga per proprietà; tabella completa dei protocolli.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-KCF-001 | produttore: acks=all (`producer.acks`) | all | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-002 | produttore idempotente (`producer.idempotence`) | true | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-003 | produttore: chiave e valore stringa (CloudEvent JSON) (`producer.serializers`) | StringSerializer StringSerializer | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-004 | produttore: bootstrap da configurazione (`producer.bootstrap`) | broker.example:9092 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-005 | consumatore: gruppo lh-<servizio> (`consumer.group`) | lh-wallet | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-006 | consumatore: servizio già prefissato lh- non raddoppia il prefisso (`consumer.groupPrefixed`) | lh-hub | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-007 | consumatore: auto.offset.reset=earliest (RNF-06) (`consumer.offsetReset`) | earliest | RNF-06 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-008 | consumatore: commit automatico spento (ack manuale) (`consumer.autoCommit`) | false | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-009 | consumatore: max.poll.records=50 (`consumer.maxPoll`) | 50 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-010 | consumatore: chiave e valore stringa (`consumer.deserializers`) | StringDeserializer StringDeserializer | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-011 | listener: concorrenza 2 (2 partizioni) (`container.concurrency`) | 2 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-012 | listener: ack MANUAL_IMMEDIATE dopo il commit DB (`container.ackMode`) | MANUAL_IMMEDIATE | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-013 | listener: avvio automatico acceso di default (`container.autoStartupOn`) | true | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-014 | listener: auto-startup=false rispettato (profilo inproc) (`container.autoStartupOff`) | false | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-015 | ritardi di default 1 s e 5 s: 3 tentativi (Q-131) (`retry.default`) | [1000, 5000] | Q-131 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-016 | sequenza di backoff 1000 poi 5000 poi stop (`retry.sequence`) | 1000 5000 STOP | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-017 | nessun ritardo configurato: stop subito (1 tentativo) (`retry.empty`) | STOP | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-018 | ritardi assenti (null): stop subito (`retry.null`) | STOP | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-019 | sicurezza PLAINTEXT di default (`security.plaintext`) | PLAINTEXT | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-020 | SSL_PEM: protocollo SSL (`security.sslPem.protocol`) | SSL | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-021 | SSL_PEM: truststore e keystore di tipo PEM (nessun file su disco) (`security.sslPem.types`) | PEM PEM | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-022 | SSL_PEM: CA certificato e chiave decodificati dal base64 (`security.sslPem.values`) | -----CA----- -----CERT----- -----KEY----- | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-023 | SSL_PEM: base64 con spazi e a capo ai bordi accettato (`security.sslPem.trimmed`) | CA | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-024 | SSL_PEM: base64 assente o vuoto = PEM vuoto (`security.sslPem.blank`) | [] | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-025 | SASL_SSL SCRAM: modulo di login SCRAM (`security.sasl.scram`) | org.apache.kafka.common.security.scram.ScramLoginModule required username="lh-user" password="s3gr3t0"; | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-026 | SASL_SSL PLAIN: modulo di login PLAIN (`security.sasl.plain`) | org.apache.kafka.common.security.plain.PlainLoginModule required username="lh-user" password="s3gr3t0"; | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-027 | SASL_SSL: protocollo e meccanismo (`security.sasl.protocol`) | SASL_SSL SCRAM-SHA-256 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-028 | meccanismo SASL di default SCRAM-SHA-256 (`security.defaultMechanism`) | SCRAM-SHA-256 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-029 | SASL_SSL con KAFKA_SSL_CA_B64: truststore PEM con la CA (docs/11 §3 ADR-025) (`security.sasl.truststore`) | PEM -----CA----- — divergenza corretta D-08 | docs/11 §3 ADR-025 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-030 | topic creati solo nel profilo local (`local.profile`) | local | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-031 | profilo local: i 5 topic (`local.names`) | lh.actions.v1 lh.effects.v1 lh.facts.v1 lh.audit.v1 lh.dlq.v1 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-032 | profilo local: 2 partizioni per topic (`local.partitions`) | 2 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-033 | profilo local: retention 3 giorni (`local.retention`) | 259200000 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |
| TB-PLT-KCF-034 | metrica lh_events_dlq_total per codice d'errore (`metrics.dlq`) | 1.0 | docs/06 §5 | `TestbookPltConfigTest#config` · `config.csv` |


## 21. VAR — Variabili d'ambiente (docs/11 §8)

**Regola.** Ogni servizio legge le variabili della matrice di docs/11 §8 (`LH_CONSUMER_RETRY_BACKOFF_MS`, `LH_JOBS_ENABLED`, `LH_APPROVAL_ENABLED`, `KAFKA_*`, `DB_USER`, `DB_URL_DIRECT`) con la precedenza dell'ambiente; il nome alternativo già in uso (`DB_USERNAME`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` di ADR-025) prevale; default comuni di salute (liveness senza dipendenze, readiness con db e kafka) solo con le sonde accese (Q-340).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| variabile | valorizzata | assente, vuota, in conflitto col nome alternativo |
| configurazione del servizio | assente | presente (l'ambiente prevale) |

**Strategia di combinazione.** una riga per variabile; ogni classe non valida da sola.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-VAR-001 | `LH_CONSUMER_RETRY_BACKOFF_MS=200,300` → `bind:retryBackoffMs` | [200, 300] — divergenza corretta D-11 | docs/11 §8 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-002 | `-` → `bind:retryBackoffMs` | [1000, 5000] | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-003 | `LH_CONSUMER_RETRY_BACKOFF_MS=200,300` con `loyaltyhub.consumer.retry-backoff-ms=50,50` → `bind:retryBackoffMs` | [200, 300] — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-004 | `LH_JOBS_ENABLED=true` → `loyaltyhub.jobs.enabled` | true — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-005 | `LH_JOBS_ENABLED=true` con `loyaltyhub.jobs.enabled=false` → `loyaltyhub.jobs.enabled` | true — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-006 | `-` con `loyaltyhub.jobs.enabled=false` → `loyaltyhub.jobs.enabled` | false | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-007 | `LH_JOBS_ENABLED=` con `loyaltyhub.jobs.enabled=false` → `loyaltyhub.jobs.enabled` | false | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-008 | `LH_APPROVAL_ENABLED=false` → `loyaltyhub.approval.enabled` | false — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-009 | `KAFKA_SECURITY=SSL_PEM` → `bind:security` | SSL_PEM — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-010 | `KAFKA_SSL_CA_B64=Q0E=` → `loyaltyhub.kafka.ssl.ca-b64` | Q0E= — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-011 | `KAFKA_SSL_CERT_B64=Q0VSVA==` → `loyaltyhub.kafka.ssl.cert-b64` | Q0VSVA== — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-012 | `KAFKA_SSL_KEY_B64=S0VZ` → `loyaltyhub.kafka.ssl.key-b64` | S0VZ — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-013 | `KAFKA_SASL_USERNAME=lh-user` → `loyaltyhub.kafka.sasl.username` | lh-user — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-014 | `KAFKA_SASL_PASSWORD=s3gr3t0` → `loyaltyhub.kafka.sasl.password` | s3gr3t0 — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-015 | `KAFKA_SASL_MECHANISM=PLAIN` → `loyaltyhub.kafka.sasl.mechanism` | PLAIN — divergenza corretta D-11 | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-016 | `KAFKA_BOOTSTRAP=kafka.example:9093` con `spring.kafka.bootstrap-servers=localhost:9092` → `spring.kafka.bootstrap-servers` | kafka.example:9093 — divergenza corretta D-11 | docs/11 §8 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-017 | `KAFKA_BOOTSTRAP=kafka.example:9093;SPRING_KAFKA_BOOTSTRAP_SERVERS=aiven.example:1234` con `spring.kafka.bootstrap-servers=localhost:9092` → `spring.kafka.bootstrap-servers` | aiven.example:1234 | ADR-025 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-018 | `DB_USER=lh_app` con `spring.datasource.username=loyaltyhub` → `spring.datasource.username` | lh_app — divergenza corretta D-11 | docs/11 §8 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-019 | `DB_USER=lh_app;DB_USERNAME=lh_owner` con `spring.datasource.username=lh_owner` → `spring.datasource.username` | lh_owner | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-020 | `DB_URL_DIRECT=jdbc:postgresql://direct.example/loyaltyhub` → `spring.flyway.url` | jdbc:postgresql://direct.example/loyaltyhub — divergenza corretta D-11 | docs/06 §4 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-021 | `-` → `spring.flyway.url` | null | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-022 | `-` con `management.endpoint.health.probes.enabled=true` → `management.endpoint.health.group.readiness.include` | readinessState,db,kafka — divergenza corretta D-11 | docs/11 §6 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-023 | `-` con `management.endpoint.health.probes.enabled=true;management.endpoint.health.group.readiness.include=readinessState` → `management.endpoint.health.group.readiness.include` | readinessState | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-024 | `-` con `management.endpoint.health.probes.enabled=true` → `management.endpoint.health.group.liveness.include` | livenessState — divergenza corretta D-11 | docs/11 §6 | `TestbookPltConfigTest#environment` · `env.csv` |
| TB-PLT-VAR-025 | `-` con `management.endpoint.health.probes.enabled=false` → `management.endpoint.health.group.readiness.include` | null | docs/11 §8; Q-340 | `TestbookPltConfigTest#environment` · `env.csv` |


## 22. HCF e FRE — Hub su broker reale e profilo free

**Regola.** L'hub su broker reale dichiara i 5 topic con 2 partizioni e retention 3 giorni (docs/05 §1, ADR-004, ADR-025), mai nel profilo inproc; listener e scheduler `@Lazy(false)` (docs/06 §5); profilo free in ogni servizio: virtual thread, Tomcat 20, lazy init, JMX spento, log JSON, Hikari `max-lifetime` 10 min e avvio col DB in risveglio (docs/06 §6, docs/11 §4, §6, RNF-10); pool comune 4/0/60 s/20 s (docs/06 §4).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| servizio | gli 8 | profilo assente (insight) |

**Strategia di combinazione.** riduzione dichiarata: proprietà × servizio (15 × 8 = 120) → una riga per proprietà che controlla tutti gli 8 servizi insieme (i valori attesi sono identici per ogni servizio: un servizio discordante fa fallire la riga e compare nel messaggio).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-HCF-001 | hub su broker reale: i 5 topic (ADR-004) (`topics.names`) | lh.actions.v1 lh.effects.v1 lh.facts.v1 lh.audit.v1 lh.dlq.v1 | ADR-004 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-002 | hub su broker reale: 2 partizioni per topic (docs/05 §1) (`topics.partitions`) | 2 — divergenza corretta D-20 | docs/05 §1 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-003 | hub su broker reale: 1 replica (broker singolo) (`topics.replicas`) | 1 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-004 | hub su broker reale: retention 3 giorni (`topics.retention`) | 259200000 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-005 | topic dichiarati solo fuori dal profilo inproc (ADR-024) (`topics.profile`) | !inproc | ADR-024 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-006 | ogni componente con @KafkaListener ha @Lazy(false) (docs/06 §5) (`lazy.listeners`) | nessuno — divergenza corretta D-21 | docs/06 §5 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-007 | ogni componente con @Scheduled ha @Lazy(false) (docs/06 §5) (`lazy.schedulers`) | nessuno — divergenza corretta D-21 | docs/06 §5 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-HCF-008 | ogni @Bean con metodi @Scheduled o @KafkaListener ha @Lazy(false) (`lazy.beanMethods`) | nessuno — divergenza corretta D-21 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-001 | profilo free presente in tutti gli 8 servizi (docs/06 §6) (`free:exists`) | presente — divergenza corretta D-21 | docs/06 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-002 | profilo free: virtual thread (`free:spring.threads.virtual.enabled`) | true — divergenza corretta D-21 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-003 | profilo free: Tomcat threads.max=20 (`free:server.tomcat.threads.max`) | 20 — divergenza corretta D-21 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-004 | profilo free: inizializzazione lazy (docs/06 §6 docs/11 §6) (`free:spring.main.lazy-initialization`) | true — divergenza corretta D-21 | docs/06 §6 docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-005 | profilo free: JMX spento (`free:spring.jmx.enabled`) | false — divergenza corretta D-21 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-006 | profilo free: log JSON (RNF-10) (`free:logging.structured.format.console`) | ecs — divergenza corretta D-21 | RNF-10 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-007 | profilo free: Hikari max-lifetime 10 minuti (docs/11 §4) (`free:spring.datasource.hikari.max-lifetime`) | 600000 — divergenza corretta D-21 | docs/11 §4 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-008 | profilo free: avvio anche col DB in risveglio (initialization-fail-timeout -1) (`free:spring.datasource.hikari.initialization-fail-timeout`) | -1 — divergenza corretta D-21 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-009 | pool Hikari: maximum-pool-size 4 (docs/06 §4) (`base:spring.datasource.hikari.maximum-pool-size`) | 4 | docs/06 §4 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-010 | pool Hikari: minimum-idle 0 (`base:spring.datasource.hikari.minimum-idle`) | 0 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-011 | pool Hikari: idle-timeout 60 s (`base:spring.datasource.hikari.idle-timeout`) | 60000 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-012 | pool Hikari: connection-timeout 20 s (`base:spring.datasource.hikari.connection-timeout`) | 20000 | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-013 | sonde liveness e readiness accese in ogni servizio (`base:management.endpoint.health.probes.enabled`) | true | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-014 | bootstrap Kafka dall'ambiente (`base:spring.kafka.bootstrap-servers`) | ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092} | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |
| TB-PLT-FRE-015 | sicurezza Kafka dall'ambiente (KAFKA_SECURITY) (`base:loyaltyhub.kafka.security`) | ${KAFKA_SECURITY:PLAINTEXT} | docs/06 §4–§6; docs/11 §6 | `TestbookPltHubConfigTest#config` · `hub-config.csv` |


## 23. CLK e CAL — Tempo di business

**Regola.** Espressioni di data dei seed risolte in Europe/Rome (docs/10 §1.2): `@now`, `@today`, scostamenti d/h/M/y, `@som/@eom/@soy/@eoy` combinabili (Q-336: `@eom+1M` = fine del mese successivo), `@lastWeekday`, `@lastSaturday` (strettamente prima di oggi), suffisso `Thh:mm` (ora inesistente in avanti, doppia = prima occorrenza: Q-267); chiavi di periodo e codice edizione dal calendario di Roma (docs/06 §1).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| orologio | metà giornata | 23:59:59/00:00 di Roma, cambio d'ora di marzo e ottobre, 29 febbraio, 31 gennaio, capodanno |
| espressione | grammatica di docs/10 | parola ignota, unità ignota, senza numero, testo estraneo, maiuscolo, assente/vuota, ora 24:00/minuti 60 |
| periodo | DAY, WEEK, MONTH, YEAR, EDITION, ALL_TIME | — |

**Strategia di combinazione.** una riga per parola chiave e scostamento; ogni confine temporale e ogni classe non valida da sola; settimana ISO ai quattro confini d'anno.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-CLK-001 | `@now` alle 2026-09-24T10:00:00Z — @now = istante corrente | 2026-09-24T10:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-002 | `@today` alle 2026-09-24T10:00:00Z — @today = oggi alle 00:00 di Roma (ora legale) | 2026-09-23T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-003 | `@today-20d` alle 2026-09-24T10:00:00Z — @today-20d | 2026-09-03T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-004 | `@now-3h` alle 2026-09-24T10:00:00Z — @now-3h | 2026-09-24T07:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-005 | `@today+2M` alle 2026-09-24T10:00:00Z — @today+2M attraversa il cambio d'ora: resta mezzanotte locale (CET) | 2026-11-23T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-006 | `@today-1y` alle 2026-09-24T10:00:00Z — @today-1y | 2025-09-23T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-007 | `@som` alle 2026-09-24T10:00:00Z — @som = primo del mese alle 00:00 | 2026-08-31T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-008 | `@eom` alle 2026-09-24T10:00:00Z — @eom = ultimo giorno del mese alle 23:59:59 | 2026-09-30T21:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-009 | `@soy` alle 2026-09-24T10:00:00Z — @soy = 1 gennaio alle 00:00 (CET) | 2025-12-31T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-010 | `@eoy` alle 2026-09-24T10:00:00Z — @eoy = 31 dicembre alle 23:59:59 (CET) | 2026-12-31T22:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-011 | `@eom+1M` alle 2026-09-24T10:00:00Z — @eom+1M da settembre (30 gg): fine di ottobre (31 gg) (Q-336) | 2026-10-31T22:59:59Z — divergenza corretta D-10 | Q-336 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-012 | `@eom+1M` alle 2027-02-10T10:00:00Z — @eom+1M da febbraio 2027: fine di marzo (Q-336) | 2027-03-31T21:59:59Z — divergenza corretta D-10 | Q-336 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-013 | `@eom+1M` alle 2026-01-31T10:00:00Z — @eom+1M da gennaio: fine di febbraio (non bisestile) | 2026-02-28T22:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-014 | `@eom-1M` alle 2026-03-31T10:00:00Z — @eom-1M da marzo: fine di febbraio | 2026-02-28T22:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-015 | `@som+1M` alle 2026-09-24T10:00:00Z — @som+1M | 2026-09-30T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-016 | `@soy+10M+29dT23:59` alle 2026-09-24T10:00:00Z — @soy+10M+29dT23:59 (offset multipli e ora del seed) | 2026-11-30T22:59:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-017 | `@soy+11M+23dT23:59` alle 2026-09-24T10:00:00Z — @soy+11M+23dT23:59 | 2026-12-24T22:59:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-018 | `@today-3dT18:45` alle 2026-09-24T10:00:00Z — @today-3dT18:45 (ora del giorno) | 2026-09-21T16:45:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-019 | `@today-1d-2h` alle 2026-09-24T10:00:00Z — @today-1d-2h (giorni poi ore) | 2026-09-22T20:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-020 | `@lastWeekday` alle 2026-09-24T10:00:00Z — @lastWeekday di giovedì = mercoledì | 2026-09-22T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-021 | `@lastWeekdayT10:30` alle 2026-09-24T10:00:00Z — @lastWeekdayT10:30 | 2026-09-23T08:30:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-022 | `@lastWeekday` alle 2026-09-28T10:00:00Z — @lastWeekday di lunedì = venerdì | 2026-09-24T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-023 | `@lastWeekday` alle 2026-09-27T10:00:00Z — @lastWeekday di domenica = venerdì | 2026-09-24T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-024 | `@lastSaturday` alle 2026-09-24T10:00:00Z — @lastSaturday di giovedì | 2026-09-18T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-025 | `@lastSaturday` alle 2026-09-26T10:00:00Z — @lastSaturday di sabato = sabato precedente (strettamente prima di oggi) | 2026-09-18T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-026 | `@lastSaturday` alle 2026-09-27T10:00:00Z — @lastSaturday di domenica = ieri | 2026-09-25T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-027 | `@lastSaturdayT10:05` alle 2026-09-24T10:00:00Z — @lastSaturdayT10:05 | 2026-09-19T08:05:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-028 | `@lastSunday` alle 2026-09-24T10:00:00Z — @lastSunday (giorno non in docs/10: esteso a ogni giorno; Q-337) | 2026-09-19T22:00:00Z | giorno non in docs/10: esteso a ogni giorno; Q-337 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-029 | `@lastFunday` alle 2026-09-24T10:00:00Z — @lastFunday: giorno sconosciuto | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-030 | `@tomorrow` alle 2026-09-24T10:00:00Z — @tomorrow: parola chiave sconosciuta | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-031 | `@TODAY` alle 2026-09-24T10:00:00Z — @TODAY maiuscolo: sconosciuta | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-032 | `@today+2x` alle 2026-09-24T10:00:00Z — @today+2x: unità sconosciuta | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-033 | `@today+` alle 2026-09-24T10:00:00Z — @today+ senza numero | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-034 | `null` alle 2026-09-24T10:00:00Z — espressione assente | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-035 | `empty` alle 2026-09-24T10:00:00Z — espressione vuota | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-036 | `spaces` alle 2026-09-24T10:00:00Z — espressione di soli spazi | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-037 | `2026-09-18T10:15:00Z` alle 2026-09-24T10:00:00Z — valore ISO-8601 senza @: letto così com'è | 2026-09-18T10:15:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-038 | `2026-09-18` alle 2026-09-24T10:00:00Z — data senza ora e senza @: non valida | ERR:DateTimeParseException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-039 | `@todayT24:00` alle 2026-09-24T10:00:00Z — ora 24:00 non valida | ERR:DateTimeException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-040 | `@todayT09:60` alle 2026-09-24T10:00:00Z — minuti 60 non validi | ERR:DateTimeException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-041 | `@todayT9:30` alle 2026-09-24T10:00:00Z — ora con una cifra non riconosciuta | ERR:IllegalArgumentException | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-042 | `@today` alle 2026-09-24T21:59:59Z — alle 23:59:59 di Roma @today è ancora oggi | 2026-09-23T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-043 | `@today` alle 2026-09-24T22:00:00Z — alle 00:00:00 di Roma @today è il giorno dopo | 2026-09-24T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-044 | `@today` alle 2026-03-29T00:30:00Z — giorno del cambio d'ora di marzo: @today alle 00:00 CET | 2026-03-28T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-045 | `@today+1d` alle 2026-03-29T00:30:00Z — cambio d'ora di marzo: @today+1d = 00:00 CEST del giorno dopo (giorno di 23 h) | 2026-03-29T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-046 | `@todayT02:30` alle 2026-03-29T00:30:00Z — cambio d'ora di marzo: ora inesistente 02:30 spostata alle 03:30 CEST (Q-267) | 2026-03-29T01:30:00Z | Q-267 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-047 | `@now+1h` alle 2026-03-29T00:30:00Z — cambio d'ora di marzo: @now+1h dalle 01:30 CET | 2026-03-29T01:30:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-048 | `@now+1d` alle 2026-03-29T00:30:00Z — cambio d'ora di marzo: @now+1d mantiene l'ora locale (23 h dopo) | 2026-03-29T23:30:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-049 | `@today` alle 2026-10-25T08:00:00Z — cambio d'ora di ottobre: @today alle 00:00 CEST | 2026-10-24T22:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-050 | `@todayT02:30` alle 2026-10-25T08:00:00Z — cambio d'ora di ottobre: ora doppia 02:30 = prima occorrenza CEST (Q-267) | 2026-10-25T00:30:00Z | Q-267 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-051 | `@today+1d` alle 2026-10-25T08:00:00Z — cambio d'ora di ottobre: @today+1d = 00:00 CET (giorno di 25 h) | 2026-10-25T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-052 | `@eom` alle 2028-02-10T10:00:00Z — @eom a febbraio bisestile = 29 febbraio 23:59:59 | 2028-02-29T22:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-053 | `@eom` alle 2027-02-10T10:00:00Z — @eom a febbraio non bisestile = 28 febbraio | 2027-02-28T22:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-054 | `@today+1y` alle 2028-02-29T10:00:00Z — dal 29 febbraio @today+1y = 28 febbraio | 2029-02-27T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-055 | `@today-1d` alle 2028-02-29T10:00:00Z — dal 29 febbraio @today-1d | 2028-02-27T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-056 | `@today+1M` alle 2026-01-31T10:00:00Z — dal 31 gennaio @today+1M = 28 febbraio | 2026-02-27T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-057 | `@soy` alle 2025-12-31T23:30:00Z — alle 00:30 del 1 gennaio a Roma @soy è l'anno nuovo | 2025-12-31T23:00:00Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-058 | `@eoy` alle 2025-12-31T23:30:00Z — alle 00:30 del 1 gennaio a Roma @eoy è la fine dell'anno nuovo | 2026-12-31T22:59:59Z | docs/10 §1.2 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |
| TB-PLT-CLK-059 | `@today-1dx+2d` alle 2026-09-24T10:00:00Z — testo estraneo tra due scostamenti: non valido (Q-336) | ERR:IllegalArgumentException — divergenza corretta D-10 | Q-336 | `TestbookPltClockTest#seedDates` · `seed-dates.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-CAL-001 | zone alle 2026-09-24T10:00:00Z — fuso di business Europe/Rome | Europe/Rome | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-002 | DAY alle 2026-09-24T21:59:59Z — DAY alle 23:59:59 di Roma: giorno corrente | 2026-09-24 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-003 | DAY alle 2026-09-24T22:00:00Z — DAY alle 00:00:00 di Roma: giorno dopo | 2026-09-25 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-004 | WEEK alle 2026-09-24T10:00:00Z — WEEK ISO a metà anno | 2026-W39 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-005 | WEEK alle 2026-09-27T21:59:59Z — WEEK domenica 23:59:59 di Roma: stessa settimana | 2026-W39 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-006 | WEEK alle 2026-09-27T22:00:00Z — WEEK lunedì 00:00:00 di Roma: settimana nuova | 2026-W40 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-007 | WEEK alle 2026-12-31T12:00:00Z — WEEK 31 dicembre 2026 = 2026-W53 | 2026-W53 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-008 | WEEK alle 2027-01-01T12:00:00Z — WEEK 1 gennaio 2027 = ancora 2026-W53 (anno della settimana) | 2026-W53 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-009 | WEEK alle 2027-01-04T12:00:00Z — WEEK 4 gennaio 2027 = 2027-W01 | 2027-W01 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-010 | WEEK alle 2024-12-30T12:00:00Z — WEEK 30 dicembre 2024 = 2025-W01 | 2025-W01 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-011 | MONTH alle 2026-09-30T21:59:59Z — MONTH alle 23:59:59 del 30 settembre a Roma | 2026-09 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-012 | MONTH alle 2026-09-30T22:00:00Z — MONTH alle 00:00 del 1 ottobre a Roma | 2026-10 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-013 | YEAR alle 2026-12-31T22:59:59Z — YEAR alle 23:59:59 del 31 dicembre a Roma | 2026 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-014 | YEAR alle 2026-12-31T23:00:00Z — YEAR alle 00:00 del 1 gennaio a Roma (ancora 31/12 in UTC) | 2027 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-015 | EDITION alle 2026-12-31T23:00:00Z — EDITION = anno solare di Roma | 2027 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-016 | edition alle 2026-12-31T23:00:00Z — codice edizione E<anno> di Roma | E2027 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-017 | edition alle 2026-09-24T10:00:00Z — codice edizione a metà anno | E2026 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-018 | ALL_TIME alle 2026-09-24T10:00:00Z — ALL_TIME | ALL | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-019 | lastWeekday alle 2026-09-28T10:00:00Z — ultimo feriale di lunedì = venerdì | 2026-09-25 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-020 | today alle 2026-03-29T00:30:00Z — oggi nel giorno del cambio d'ora di marzo | 2026-03-29 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-021 | DAY alle 2026-10-25T22:30:00Z — DAY nel giorno del cambio d'ora di ottobre alle 23:30 CET | 2026-10-25 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |
| TB-PLT-CAL-022 | DAY alle 2028-02-29T10:00:00Z — DAY il 29 febbraio | 2028-02-29 | docs/06 §1 | `TestbookPltClockTest#calendar` · `calendar.csv` |


## 24. NFR e RLM — Requisiti non funzionali misurabili e limite di frequenza

**Regola.** Latenza azione → movimento p50 ≤ 3 s, p95 ≤ 8 s a servizi svegli (RNF-02); ordine per membro (RNF-04); al più 60 eventi al minuto per IP su `POST /v1/events` e `/v1/transactions` (docs/11 §11; Q-339: primo indirizzo di `X-Forwarded-For`, loopback esente, 429 `RATE_LIMITED` con `Retry-After`).

**Domini dei valori.**

| ingresso | classi valide | classi non valide / limiti |
|---|---|---|
| eventi nello stesso minuto | 60 | 61 |
| indirizzo | diverso, catena di proxy | loopback |

**Strategia di combinazione.** valori limite 60/61 e ogni classe da sola.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-NFR-001 | latenza azione → movimento a servizi svegli su 10 azioni: p50 ≤ 3 s e p95 ≤ 8 s (RNF-02) | entro i limiti | RNF-02 | `TestbookPltApiIT#nonFunctional` · `nfr.csv` |
| TB-PLT-NFR-002 | 10 azioni ravvicinate dello stesso membro valutate nell'ordine d'ingresso (RNF-04) | stesso ordine | RNF-04 | `TestbookPltApiIT#nonFunctional` · `nfr.csv` |

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-PLT-RLM-001 | 60 eventi da `203.0.113.10` | accettati=60 limitati=0 | docs/11 §11 | `TestbookPltApiIT#rateLimit` · `rate-limit.csv` |
| TB-PLT-RLM-002 | 1 eventi da `203.0.113.10` | accettati=0 limitati=1 — divergenza corretta D-18 | Q-339 | `TestbookPltApiIT#rateLimit` · `rate-limit.csv` |
| TB-PLT-RLM-003 | 1 eventi da `203.0.113.11` | accettati=1 limitati=0 | docs/11 §11; Q-339 | `TestbookPltApiIT#rateLimit` · `rate-limit.csv` |
| TB-PLT-RLM-004 | 61 eventi da `203.0.113.12, 10.0.0.1` | accettati=60 limitati=1 — divergenza corretta D-18 | docs/11 §11; Q-339 | `TestbookPltApiIT#rateLimit` · `rate-limit.csv` |
| TB-PLT-RLM-005 | 61 eventi da `loopback` | accettati=61 limitati=0 | Q-339 | `TestbookPltApiIT#rateLimit` · `rate-limit.csv` |

## 25. Registro delle divergenze

Righe che fallivano contro la specifica prima della correzione (108 righe, 22 cause). Ogni test asserisce la specifica;
la correzione è nel codice di produzione (o nei contratti, D-19).

| Causa | Righe | Specifica | Comportamento osservato | Causa (file:riga prima della correzione) | Esito |
|---|---|---|---|---|---|
| D-01 | ERR-010, ERR-011 | docs/06 §2: 503 `dependency-unavailable` se il DB non è raggiungibile | 500 `INTERNAL_ERROR` | `GlobalExceptionHandler.java:98` (gestore generico) | corretta: gestore per `DataAccessResourceFailureException`/`CannotCreateTransactionException` |
| D-02 | ERR-018, ERR-019, HER-009, HER-010 | docs/06 §2: errori del client, mai 500 (Q-333) | 405/415 resi come 500 `INTERNAL_ERROR` | `GlobalExceptionHandler.java:98` | corretta: errori `ErrorResponse` 4xx di Spring con il loro stato, famiglia `bad-request` |
| D-03 | ERR-007 | Q-333: titoli in italiano | `title` «Gone» | `GlobalExceptionHandler.java:125` (`getReasonPhrase`) | corretta |
| D-04 | IDM-017 | docs/06 §8, RNF-10: MDC con `eventId`, `eventType`, `correlationId`, `memberId` | MDC vuoto durante gli handler | `EventRouter.java:60-63` | corretta |
| D-05 | DLR-010…015, DLK-004, DLK-005, BUS-009, BRT-001…004 | docs/04 §5: deserializzazione non ritentabile | JSON illeggibile ritentato 3 volte; `lh-attempts` 3 per errori che Spring Kafka non ritenta | `DlqRecords.java:48-50` (`retryable`) | corretta (Q-334) |
| D-06 | DLR-019…022, DLK-006 | Q-334: la causa non ritentabile decide | errore avvolto ritentato col codice dell'involucro | `DlqRecords.java:37-50` | corretta |
| D-07 | DLR-023, DLK-010, DLK-011 | docs/04 §5: `lh-attempts` = tentativi fatti | sempre 3 qualunque ritardo configurato | `LhKafkaConfiguration.java:106` (`attemptsFor(cause)`) | corretta |
| D-08 | KCF-029 | docs/11 §3, ADR-025: SASL_SSL con la CA di `KAFKA_SSL_CA_B64` | truststore assente | `LhKafkaSecurity.java:34-42` | corretta |
| D-09 | HLT-003, HLT-004 | docs/06 §8: timeout 3 s, cache 30 s | timeout 2 s, nessuna cache | `LhKafkaHealthIndicator.java:23, 35` | corretta |
| D-10 | CLK-011, CLK-012, CLK-059 | docs/10 §1.2 «combinabili» (Q-336) | `@eom+1M` dal 30 settembre = 30 ottobre; testo estraneo tra scostamenti accettato | `SeedDates.java:98-116` | corretta |
| D-11 | VAR-001, 003…005, 008…016, 018, 020, 022, 024; HLR-003 | docs/11 §8 e §6 | variabili della matrice non lette; readiness senza db e kafka | nessun codice (**regola non implementata**) | corretta: `LhEnvironmentAliases` (Q-340) |
| D-12 | REL-016, HLR-004 | docs/06 §8: `lh_outbox_pending` | metrica assente | nessun codice (**regola non implementata**) | corretta: gauge letto alla raccolta |
| D-13 | REL-021 | docs/06 §4, RNF-07: `processed_event` > 14 giorni | nessuna pulizia | nessun codice (**regola non implementata**, docs/17 §5.12 n. 5) | corretta: `ProcessedEventCleanup` (Q-335) |
| D-14 | PAG-004, 005, 010, 011, 015…017, 022, 023, 028, 029, 034, 035, 040, 041, 046, 047, 052, 053 | docs/06 §2 (Q-332) | `size=0`/`page=-1` corretti in silenzio (200); membri di un segmento fino a 200 | `MemberService.java:125`, `SegmentService.java:71, 84`, `WebhookService.java:187`, `InboxService.java:113, 124`, `ContestController.java:156`, `RedemptionService.java:383`, `CouponService.java:195` | corretta: `PageParams` di lh-common; l'editor BO-04 carica l'elenco statico a pagine da 100 |
| D-15 | RST-001 | BO-30: insight per primo | ingestion per primo (ordine della scansione) | `InsightReset.java:21`, `InsightSyntheticSeeder.java:33` (nessun ordine) | corretta: `@Order` |
| D-16 | RST-003, RST-007 | docs/06 §10, docs/10 §1.3: il reset tronca le tabelle del servizio | restavano ingressi reali, tipi e fonti creati da BO-09, registro valutazioni, consumi dei lotti (orfani), edizioni create | `InboundHistorySeeder.java:67`, `DemoSeeder.java:76-80`, `CampaignSeeder.java:75`, `PointsLotRepository.java:177`, `WalletSeeder.java:99-115` | corretta |
| D-17 | INF-001…003 | docs/06 §10, BO-30: `GET /v1/demo/info` | 404 | nessun codice (**regola non implementata**, docs/17 §5.12 n. 8) | corretta (Q-338) |
| D-18 | RLM-002, RLM-004 | docs/11 §11: 60 eventi/min per IP | nessun limite | nessun codice (**regola non implementata**) | corretta: `IngressRateLimitFilter` (Q-339) |
| D-19 | CTR-002…005, 007…011, 013, 014, 029 | docs/05 §9: uno schema e un esempio per ogni `type` | 12 schemi ed esempi assenti | `contracts/events/` | corretta: schemi ed esempi aggiunti (Q-342) |
| D-20 | HCF-002 | docs/05 §1, ADR-004: 2 partizioni | 1 partizione sul broker reale dell'hub | `HubKafkaTopics.java:26` | corretta |
| D-21 | HCF-006…008, FRE-001…008 | docs/06 §5, §6; docs/11 §4, §6: profilo free completo, listener e scheduler `@Lazy(false)` | 23 listener/job e 3 bean schedulati senza `@Lazy(false)`; lazy init, JMX, log JSON, Hikari `max-lifetime`/avvio col DB in risveglio assenti; insight senza profilo free | `application-free.yml` di 7 servizi; insight senza file; classi `*Listener`, `*Jobs`, `LhCommonAutoConfiguration.java:117, 126` | corretta; FRP-001…003 provano l'avvio lazy su broker reale |
| D-22 | HLR-008 | docs/06 §2: OpenAPI su `/v3/api-docs` | 404 | nessuna dipendenza springdoc (**regola non implementata**) | **aperta**: serve una dipendenza nuova (Q-341) |

## 26. Scelte registrate

docs/15 Q-332…Q-342 (tutte DECISA, opzione conservativa, tranne Q-341 APERTA); Q-168 superata da Q-338. Riepilogo:
Q-332 paginazione 400 per `size` < 1 e `page` < 0 · Q-333 errori di forma di Spring e titoli · Q-334 classificazione
DLQ lungo la catena delle cause · Q-335 `processed_event` 14 giorni · Q-336 `@eom` con scostamenti e grammatica stretta ·
Q-337 `@last<Giorno>` · Q-338 reset serializzati e forma di `/v1/demo/info` · Q-339 limite di frequenza · Q-340 variabili
d'ambiente e sonde · Q-341 OpenAPI · Q-342 schemi aggiunti ai contratti. Valgono anche Q-131 (3 tentativi), Q-261 e
Q-298 (attore), Q-267 (ora inesistente/doppia), Q-317/Q-323 (paginazione di insight), Q-40 (Kafka e Postgres in-JVM).

## 27. Verifica a mutazione

Per ogni classe di test una regola di produzione è stata rotta di proposito, il test è stato eseguito, ha fallito
sulle righe attese, e il codice è stato ripristinato (`git diff` finale senza la mutazione).

| Classe di test | Mutazione (regola rotta) | Righe fallite | Esito |
|---|---|---|---|
| `TestbookPltEnvelopeTest` | `LhEventFactory.bridgeAction`: `lhhop` del fatto non incrementato (docs/05 §7) | ENV-032, ENV-033, ENV-034 | rilevata, ripristinata |
| `TestbookPltClockTest` | `SeedDates.applyOffsets`: `@eom` + mesi senza aggancio alla fine del mese (Q-336) | CLK-011, CLK-012 | rilevata, ripristinata |
| `TestbookPltConfigTest` | `LhKafkaHealthIndicator`: cache della salute Kafka a 0 (docs/06 §8) | HLT-004 | rilevata, ripristinata |
| `TestbookPltDlqTest` | `DlqRecords.retryable`: elenco dei tipi non ritentabili ignorato (docs/04 §5, Q-334) | DLR-010…015, DLR-022 | rilevata, ripristinata |
| `TestbookPltRoutingTest` | `EventRouter`: MDC non valorizzato (docs/06 §8) | IDM-017 | rilevata, ripristinata |
| `TestbookPltErrorsTest` | `GlobalExceptionHandler`: gestore del database irraggiungibile tolto (docs/06 §2) | ERR-010, ERR-011 | rilevata, ripristinata |
| `TestbookPltRelayIT` | `OutboxRelay`: lotto letto `ORDER BY created_at DESC` (ADR-008, ordine per chiave) | REL-006, REL-007, REL-013 | rilevata, ripristinata |
| `TestbookPltBusTest` | `HubInProcessBus`: tentativi = ritardi (senza il primo) (Q-131) | BUS-006, BUS-010, BUS-011, BUS-014 | rilevata, ripristinata |
| `TestbookPltHubConfigTest` | `HubKafkaTopics`: 1 partizione invece di 2 (ADR-004) | HCF-002 | rilevata, ripristinata |
| `TestbookPltHubConfigTest` + `TestbookPltFreeProfileIT` | `EffectsListener` di wallet senza `@Lazy(false)` (docs/06 §5) | HCF-006 (unitario); FRP-002, FRP-003 (broker reale) | rilevata, ripristinata |
| `TestbookPltApiIT` | `PageParams.of`: `size` = 0 accettato (Q-332) | PAG-004, 010, 016, 022, 028, 034, 040, 046, 052 (più HLR-008, già rossa: D-22) | rilevata, ripristinata |
| `TestbookPltContractIT` | `FactsHandler` di ingestion: guardia anti-ciclo spostata di un passo (`> MAX_HOP + 1`) | LOP-004 | rilevata, ripristinata |

Dopo ogni mutazione il file è stato ricopiato dalla copia di sicurezza; l'impronta di `git diff` è rimasta identica a
quella precedente alle mutazioni.

## 28. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate | 37 (§1) |
| Rami del codice mappati | 172 in 26 gruppi (§2) |
| Rami senza specifica | 9 (innocui: mantenuti o decisi in Q-333, Q-337, Q-338) |
| Regole senza codice | 8 prima delle correzioni (variabili d'ambiente, `lh_outbox_pending`, pulizia `processed_event`, `/v1/demo/info`, limite di frequenza, 12 contratti, profilo free lazy, OpenAPI); 1 dopo (OpenAPI, Q-341); `member.birthday` P2 fuori perimetro |
| Righe | 563 (lh-common 332: ENV 50, TOP 24, CLK 59, CAL 22, KCF 34, HLT 4, VAR 25, DLR 38, IDM 18, ERR 24, REL 22, ITX 3, DLK 11; hub 231: CTR 64, LOP 6, BRT 5, BUS 14, HER 10, PAG 60, ACT 17, RST 9, INF 3, HLR 8, FRP 3, NFR 2, RLM 5, HCF 8, FRE 15) |
| Tabelle complete | famiglia → topic, elenco × classe di paginazione (60), tipo d'errore × campo DLQ, protocolli di sicurezza, un `type` per riga (64) |
| Riduzioni | proprietà del profilo free × servizio 120 → 15 righe (una riga controlla gli 8 servizi); ruoli e forme dell'intestazione solo sull'endpoint ADMIN del reset (le altre guardie sono in TB-GOV) |
| Divergenze | 22 cause, 108 righe: 21 corrette, 1 aperta (D-22, Q-341) |
| Non automatizzabili | RNF-01 (RSS ≤ 450 MB su 512 MB, docs/11 §6), tempo di avvio a freddo su 0,1 CPU (Q-20), RNF-06 oltre la retention (Q-25), RNF-08/09 (web, TB-WEB): richiedono il container reale o il browser |
