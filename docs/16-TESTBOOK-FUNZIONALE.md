# 16 — Testbook funzionale

Catalogo dei casi di prova **funzionali end-to-end** del PoC: per ogni regola delle specifiche, ogni ramo decisionale moltiplicato per i domini dei valori in ingresso. Non misura quante righe di codice sono eseguite (code coverage) ma **quante decisioni della specifica sono provate e con quali valori**.

- **Oracolo = la specifica** (`docs/02`, `docs/03`, `docs/05` e `contracts/`, `docs/servizi/*`, `docs/08`–`09`, `docs/10`), mai "quello che il codice restituisce oggi". Dove la specifica tace vale la scelta registrata in `docs/15` (citata col suo `Q-nn`).
- **Ogni riga è eseguita** da un test automatico che ne riporta l'ID (`TB-<DOM>-NNN`) nel nome o nel file CSV: un test rosso indica la riga di testbook violata.
- **Divergenza** = il codice non rispetta la specifica: il test resta com'è (asserisce la specifica) finché la divergenza non è risolta correggendo il codice o con una decisione registrata in `docs/15`; il registro è in fondo a questo documento.

Stato: in costruzione — le tabelle di dettaglio arrivano per dominio (bozze prodotte in parallelo, revisionate prima dell'inserimento). La completezza si misura contro `docs/17-EPIC-E-STORIE.md`: ogni storia deve avere almeno una riga, ogni esito della foresta delle decisioni almeno un caso.

## 1. Metodo

1. **Inventario delle regole.** Ogni regola o punto di decisione delle specifiche (es. «solo i membri `ACTIVE` accumulano») diventa una voce con i suoi riferimenti (`F-…`, `BO-…`, `PT-…`, `Q-…`). Anche i rami del codice (condizioni, eccezioni, uscite anticipate) sono mappati su una regola; un ramo senza regola è segnalato come *ramo senza specifica*, una regola senza codice come *regola non implementata*.
2. **Domini dei valori.** Per ogni ingresso: classi di equivalenza valide e non valide; valori limite (min−1, min, min+1, max−1, max, max+1); assente, `null`, vuoto, solo spazi, tipo sbagliato; valori speciali (0, negativi, molto grandi, decimali e arrotondamenti, testo lungo o unicode); ogni valore di un enumerato più uno sconosciuto; tempo (mezzanotte di Europe/Rome, cambio dell'ora di marzo e ottobre, fine mese, 29 febbraio, prima/all'istante/dopo inizio e fine); ruoli (`ADMIN`, `MARKETING`, `LEGAL`, `CARE`, `ANALYST`, intestazione `X-LH-Actor` assente o non valida); stati del membro; stati del ciclo di vita.
3. **Strategia di combinazione, dichiarata per ogni decisione.**
   - Tabella decisionale **completa** quando il prodotto dei domini (dopo il partizionamento) è ≤ 64.
   - Altrimenti **tutte le coppie** (pairwise) sulle classi valide **più** ogni classe non valida provata da sola (guasto singolo) **più** ogni valore limite provato da solo; la riduzione applicata è scritta.
   - **Macchine a stati**: ogni stato × ogni evento o azione, comprese le transizioni vietate col codice d'errore atteso, per ruolo e policy.
   - **Interazioni**: le regole che la specifica rende dipendenti (limiti × moltiplicatori × gruppi esclusivi, stock × limite per membro × saldo…) hanno una tabella combinata propria.
4. **Esecuzione.** Test parametrizzati guidati da dati (JUnit 5 `@ParameterizedTest` con CSV in `src/test/resources/testbook/<dominio>/`, vitest con tabelle `it.each` nel web); unit test per la logica pura, un solo contesto Spring per classe d'integrazione, test sull'hub consolidato solo per i flussi tra servizi.
5. **Revisione.** Ogni tabella è rivista prima dell'inserimento: l'atteso deriva dalla specifica citata; nessun caso duplicato; valori limite presenti per ogni dominio numerico e temporale; divergenze e ambiguità tracciate.

## 1bis. Esecuzione

Il testbook è eseguibile per intero con un comando e produce un rapporto riga per riga.

- **Convenzione.** Ogni caso eseguito ha un nome che inizia con l'ID della riga (`[TB-CMP-042] …`). Backend: classi `Testbook<Dominio><Tema>Test` (unit, surefire) e `Testbook<Dominio><Tema>IT` (integrazione, failsafe), parametrizzate da CSV in `src/test/resources/testbook/<dominio>/` (prima colonna `id`). Web: file `*.testbook.test.ts(x)` con `it.each`.
- **In locale:** `bash scripts/testbook.sh` → esegue solo i test `Testbook*` di tutti i moduli e i file `*.testbook.test.*` del web, poi `scripts/testbook-report.mjs` scrive `target/testbook/rapporto.md`. Variabili: `TESTBOOK_JAVA=0` / `TESTBOOK_WEB=0` per un solo lato, `TESTBOOK_STRICT=0` per avere il rapporto senza uscita d'errore.
- **In CI:** workflow `testbook` (manuale e ogni notte): rapporto nel riepilogo del job e come artefatto `testbook-rapporto`. Gli stessi test girano comunque in `ci.yml` a ogni push, quindi `main` resta verde solo se tutte le righe inserite sono verdi.
- **Il rapporto** incrocia le righe documentate (tabelle di questo documento e di `docs/testbook/*.md` che iniziano con un ID) con gli esiti JUnit: righe OK, **fallite** (divergenza o regressione), **non eseguite** (riga senza test) e **test senza riga** (caso che non appartiene al testbook). Uscita ≠ 0 se esiste una delle ultime tre.

## 2. Domini

| Dominio | Sezione | Servizi | Specifiche principali | Stato |
|---|---|---|---|---|
| Ingresso eventi | [§3](#3-tb-ing--ingresso-eventi) | ingestion | docs/servizi/ingestion-service.md §3, §5 · F-ING-* · contracts/events/action | **eseguibile** — 685 righe |
| Campagne | [§4](#4-tb-cmp--campagne) | campaign | docs/03 §3 · docs/servizi/campaign-service.md · F-CMP-* | **eseguibile** — 638 righe |
| Wallet e livelli | [§5](#5-tb-wal--wallet-e-livelli) | wallet | docs/03 §4 · docs/servizi/wallet-service.md · F-WAL-*, F-TIER-* | **eseguibile** — 373 righe |
| Premi e coupon | [§6](#6-tb-rwd--premi-e-coupon) | reward (+ wallet) | docs/servizi/reward-service.md · F-RWD-*, F-CPN-* | **eseguibile** — 567 righe |
| Gioco | [§7](#7-tb-gam--gioco) | gamification | docs/servizi/gamification-service.md · F-IW-*, F-ACH-*, F-LDB-*, F-REF-* | **eseguibile** — 710 righe |
| Governance e membri | [§8](#8-tb-gov--governance-e-membri) | tutti · member | docs/03 §3.6 · docs/06 §7 · docs/08 §2 · F-APR-*, F-MBR-*, F-SEG-* | **eseguibile** — 978 righe |
| Engagement | [§9](#9-tb-eng--engagement) | engagement | docs/servizi/engagement-service.md · F-CNT-*, F-MSG-*, F-WBH-01 | **eseguibile** — 804 righe |
| Interfaccia | [§10](#10-tb-web--interfaccia) | web | docs/07 · docs/08 · docs/09 | **eseguibile** — 741 righe |
| Percorsi end-to-end | [§10bis](#10bis-tb-e2e--percorsi-end-to-end) | hub (tutti) | docs/17 E10 e percorsi tra servizi · docs/10 §8 | **eseguibile** — 114 righe |
| Osservabilità e audit | [§10ter](#10ter-tb-ins--osservabilità-e-audit) | insight | docs/servizi/insight-service.md · F-INS-*, F-AUD-01 | **eseguibile** — 586 righe |
| Piattaforma | [§10quater](#10quater-tb-plt--piattaforma) | lh-common, hub | docs/04 · docs/05 · docs/06 · contracts/ | **eseguibile** — 563 righe |
| Distribuzione (Fase 2) | `TB-DIST` | immagine, chart, compose, appliance, CLI, aggiornamento | docs/18 §3.1, ADR-026, 037, 038 · F2-DIST-* | pianificata — M8.1, M8.3, M12 |
| Identità e accessi (Fase 2) | `TB-IAM` | Keycloak `idp`, BFF, resource server, client credentials | docs/18 §3.2, ADR-027 · F2-IAM-*, F2-SEC-06/07 | pianificata — M8.2 |
| Sicurezza applicativa (Fase 2) | `TB-SEC` | lh-common, tutti | docs/18 §3.10, ADR-042 · F2-SEC-08…12 | pianificata — M8.10, M8.11 |
| Governo e conformità (Fase 2) | `TB-GRC` | tutti, CLI | docs/18 §3.15, ADR-044 · F2-GRC-* | pianificata — M8.13, M12.6 |
| Esperienza data-driven (Fase 2) | `TB-EXP` | experience, cms, web | docs/18 §3.5–3.7, ADR-029…031, 039 · F2-EXP-*, F2-DS-* | pianificata — M10 |
| Multilingua (Fase 2) | `TB-I18N` | web, tutti | docs/18 §3.8, ADR-033 · F2-I18N-* | pianificata — M11 |
| Agente regolamento (Fase 2) | `TB-AST` | assistant | docs/18 §3.9, ADR-035 · F2-AST-* | pianificata — M14 |

## 3. TB-ING — Ingresso eventi
Documento completo: [`docs/testbook/TB-ING-ingresso.md`](testbook/TB-ING-ingresso.md) — 28 regole, 111 rami mappati,
**685 righe** in 18 aree. Test: `services/ingestion-service/src/test/java/io/loyaltyhub/ingestion/testbook/`
(`TestbookIngPipelineIT`, `TestbookIngResolutionIT`, `TestbookIngConfigIT`, `TestbookIngScenarioTimeTest`), dati in `testbook/ing/*.csv`.

- **Ordine della pipeline provato** (forma → fonte → tipo → dati → tempo → dedup → membro): 51 righe PIP con ogni guasto da
  solo, ogni coppia e le cascate. Tabelle complete: fonte × tipo (55), forma del subject × stato del membro (30),
  abbinamento automatico (48).
- **Divergenze trovate e corrette (46 righe, 14 cause):** schemi dei dati allineati ai contratti (seed e check-seed),
  400 per `data` non oggetto e per corpo illeggibile (lh-common), fonte riconosciuta per URN esatto, filtri
  `from`/`to`/`q` del monitor, storico demo di 40 ingressi (`seed/inbound-history.json`), `POST/PUT /v1/sources`,
  codici dei tipi custom e JSON Schema validato, origine e variazioni del simulatore, espressioni di tempo degli scenari.
- **Scelte registrate:** Q-255…Q-272.
- **Verifica a mutazione:** 8 mutazioni, tutte rilevate (tabella in §4 del documento).

## 4. TB-CMP — Campagne
Documento completo: [`docs/testbook/TB-CMP-campagne.md`](testbook/TB-CMP-campagne.md) — 37 regole, 250 punti di decisione,
**638 righe**. Test: `services/campaign-service/src/test/java/io/loyaltyhub/campaign/testbook/` (5 classi unitarie sul motore
puro, `TestbookCmpLifecycleIT`, `TestbookCmpSimulationIT`), dati in `testbook/cmp/*.csv`.

- **Tabelle complete:** pubblico (27), stato × azione del ciclo di vita (56), operazione × ruolo (35); riduzioni dichiarate
  per operatori (84 → 80) e ciclo di vita × attore × policy (1 176 → 105).
- **Divergenze trovate e corrette (23 righe, 12 cause):** tipi incompatibili nelle condizioni, `in`/`nin` sugli array,
  arrotondamento esatto di `PER_AMOUNT`, `labels` del moltiplicatore, validazione di coupon e badge, `requiresLegal` alla
  creazione, id o codice nel percorso, formato del codice e della copia, guardia di ruolo sulla creazione,
  `cooldownMinutes` e `perMemberPoints` (Q-165).
- **Scelte registrate:** Q-209…Q-254 (Q-209: conflitto BO-06/Q-51 sul codice in DRAFT, vince Q-51).
- **Verifica a mutazione:** 7 mutazioni, tutte rilevate.

## 5. TB-WAL — Wallet e livelli
Documento completo: [`docs/testbook/TB-WAL-wallet.md`](testbook/TB-WAL-wallet.md) — 32 regole, 97 rami di codice mappati,
**373 righe** (19 aree: POL, CLR, GRT, TUP, REL, WVW, API, MBR, LIA, JOB, EXP, WRN, SPD, REF, ADJ, CUR, TAD, EDN, ECL).
Test: `services/wallet-service/src/test/java/io/loyaltyhub/wallet/testbook/` (`TestbookWalPolicyTest`, `TestbookWalCloseRuleTest`,
`TestbookWalAccrualIT`, `TestbookWalSpendIT`, `TestbookWalAdminIT`), dati in `src/test/resources/testbook/wal/*.csv`.

- **Tabelle complete:** accredito (valuta × giorni di attesa × moltiplicatore × livello, 48 righe), chiusura edizione
  (livello corrente × classe STS, 32 righe). **Riduzioni:** rettifiche 48 → 12 all-pairs + 22 classi non valide + 8 limiti.
- **Divergenze trovate e corrette (18 righe, 11 cause):** scadenza di fine mese arrotondata al giorno dopo, un movimento
  EXPIRE per lotto invece che per membro e valuta, audit dei job assente, `keepWarning` assente, filtri e campi del
  registro, campi del portale, edizione successiva sbagliata alla chiusura, scadenza del rimborso (Q-160, supera Q-54).
- **Scelte registrate:** Q-140…Q-156 (28 righe); quattro non conservative, da decidere: Q-140, Q-147, Q-149, Q-151.
- **Verifica a mutazione:** 7 mutazioni, tutte rilevate (vedi §Copertura del documento).

## 6. TB-RWD — Premi e coupon
Documento completo: [`docs/testbook/TB-RWD-premi.md`](testbook/TB-RWD-premi.md) — 28 regole, 78 punti di decisione,
**567 righe**. Test: `services/reward-service/src/test/java/io/loyaltyhub/reward/` (`TestbookRwdLifecycleTest`,
`TestbookRwdRulesTest`, `TestbookRwd{Eligibility,Catalog,Saga,Coupon}IT`), dati in `testbook/rwd/*.csv`.

- **Tabelle complete:** saga (7 × 7), ciclo del coupon (7 × 3), ruoli; riduzioni dichiarate per visibilità (864 → 59) e
  ciclo di vita (560 → 99).
- **Divergenze trovate e corrette:** corpo mancante → 400 (lh-common), fine giornata del job coupon a precisione di
  microsecondi (un coupon che scade alle 00:00 del giorno dopo non scade prima).
- **Scelte registrate:** Q-273…Q-288 (Q-286…Q-288: conflitti tra fonti).
- **Verifica a mutazione:** 6 mutazioni, tutte rilevate.

## 7. TB-GAM — Gioco
Documento completo: [`docs/testbook/TB-GAM-gioco.md`](testbook/TB-GAM-gioco.md) — 47 regole, 78 nodi di decisione,
**710 righe**. Test: `services/gamification-service/src/test/java/io/loyaltyhub/gamification/` (4 classi unitarie e 7
d'integrazione `TestbookGam*IT` con un solo contesto), dati in `testbook/gam/*.csv`.

- **Tabelle complete:** stato del concorso × istante nel periodo (42), giocata gratuita × crediti × tetto giornaliero (36),
  ciclo di vita (56), azione × ruolo (56); riduzioni dichiarate per giocata (2 100 → 59), consegna (150 → 16), modifica LIVE (72 → 25).
- **Divergenze trovate e corrette (16 righe, 7 cause):** campi obbligatori mancanti → 422 invece di 500, campi sicuri di un
  concorso LIVE (`endAt` modificabile, regolamento bloccato), grammatica completa delle condizioni nei filtri degli obiettivi.
- **Scelte registrate:** Q-159, Q-167, Q-289…Q-297; non conservative: Q-294, Q-295 (tranne il comparatore sconosciuto, deciso: foglia falsa), Q-297.
- **Referral lato member-service:** coperto in TB-GOV (area REF).
- **Verifica a mutazione:** 11 mutazioni, tutte rilevate.

## 8. TB-GOV — Governance e membri
Documento completo: [`docs/testbook/TB-GOV-governance.md`](testbook/TB-GOV-governance.md) — 33 regole, 61 rami mappati,
**978 righe** in 25 aree. Test: `libs/lh-common/src/test/java/io/loyaltyhub/common/testbook/` (attore, transizioni, policy),
`services/member-service/src/test/java/io/loyaltyhub/member/testbook/` (criteri dei segmenti, attributi, `TestbookGovMemberIT`),
`deploy/hub/src/test/java/io/loyaltyhub/hub/TestbookGovHubIT.java` (righe tra servizi).

- **Tabelle complete:** stato × azione con policy accesa e spenta, ruolo × azione, guardia dell'attore, policy della campagna
  alla soglia di 100 000, stato del membro × stato di arrivo, endpoint × ruolo, tipo di attributo × valore, comparatore ×
  tipo, ogni cella della matrice capacità × ruolo di docs/08 §2, stato del membro × effetti negli altri servizi.
- **Divergenze trovate e corrette:** criteri dei segmenti con tipi incompatibili (falsi, docs/03 §3.3), attributo senza tipo
  → 422, corpo mancante → 400, storico approvazioni anche per i contenuti (`GET /v1/contents/{id}/approval-history`).
- **Referral lato member-service:** area REF (23 righe).
- **Scelte registrate:** Q-298…Q-310; non conservative: Q-298, Q-300, Q-303, Q-306.
- **Verifica a mutazione:** 9 mutazioni, tutte rilevate; lo sblocco di un membro senza CARE/ADMIN sopravviveva e ha
  portato 6 righe nuove (MRL-075…080; tabella in §15 del documento).

## 9. TB-ENG — Engagement
Documento completo: [`docs/testbook/TB-ENG-engagement.md`](testbook/TB-ENG-engagement.md) — 47 regole, 143 rami mappati,
**804 righe** (395 unitarie, 409 d'integrazione). Test: `services/engagement-service/src/test/java/io/loyaltyhub/engagement/`
(`TestbookEng*Test`, `TestbookEng*IT`), dati in `src/test/resources/testbook/engagement/*.csv`.

- **Tabelle complete:** selezione (60), pubblico (27+9), pop-up (24), ciclo di vita (50), ruoli (28), regole messaggi (60).
- **Divergenze trovate e corrette (7 righe):** confine «iscritti da < 7 giorni», campi non sicuri modificabili su un
  contenuto LIVE (ora `409 CONTENT_LIVE_LOCKED`), portale senza `memberId` (ora 400).
- **Scelte registrate:** Q-161, Q-170…Q-185; non conservative: Q-174, Q-180, Q-184 (Q-179 decisa conservativa, con Q-215).
- **Verifica a mutazione:** 8 mutazioni, tutte rilevate.

## 10. TB-WEB — Interfaccia
Documento completo: [`docs/testbook/TB-WEB-interfaccia.md`](testbook/TB-WEB-interfaccia.md) — 77 regole, 349 rami mappati,
**752 righe** in 21 aree. Test: file `web/**/*.testbook.test.ts(x)` accanto al codice, helper `web/test/testbook.ts`.

- **Oracoli dalla specifica:** matrice ruoli × capacità (docs/08 §2), barra laterale (docs/08 §1), barra del ciclo di vita
  (docs/08 §3.3) copiate nei test e confrontate col codice.
- **Divergenze trovate e corrette (33 righe, 23 cause):** etichette e contatore della navigazione, azioni vietate davvero
  disabilitate, dialogo per ogni transizione, colori degli stati, stati *loading/empty/error/degraded* di docs/07 §6,
  avvisi del portale (mantenimento livello, profilo sospeso), frase generata della campagna, formati di euro, zero e tempo.
- **Scelte registrate:** Q-186…Q-208; non conservative: Q-186, Q-206 (Q-197 decisa conservativa, con il cast tipizzato di Q-215); Q-208 decisa: fonti ammesse = regola `context.source` alla radice delle condizioni, editata in BO-06 «Quando».
- **Verifica a mutazione:** 26 mutazioni, tutte rilevate.

## 10bis. TB-E2E — Percorsi end-to-end
Documento completo: [`docs/testbook/TB-E2E-percorsi.md`](testbook/TB-E2E-percorsi.md) — 35 regole, 38 punti di decisione
(10 nell'hub, 28 passaggi tra servizi), **114 righe**. Test: `deploy/hub/src/test/java/io/loyaltyhub/hub/TestbookE2e*IT`
(percorsi, riscatti, affidabilità, persone di docs/10, tempo di business), dati in `deploy/hub/src/test/resources/testbook/e2e/`.

- **Oracolo di catena:** per ogni riga il numero esatto di eventi per tipo nella stessa correlazione (da `event_store`), un
  solo albero nel tracciato, `lhhop` crescente sulle azioni del ponte, e lo stato finale in ogni servizio toccato.
- **Varianti:** servizio addormentato a metà saga, riconsegna dell'intera catena, reset tra esecuzioni, mezzanotte e
  cambio dell'ora a Roma, azioni ravvicinate dello stesso membro, gestore che fallisce e poi riesce.
- **Divergenza trovata e corretta:** il reset della demo lasciava in `ingestion.member_index` i membri registrati dopo il
  seed (docs/10 §1.3): ora l'indice torna esattamente al seed.
- **Verifica a mutazione:** 5 mutazioni, tutte rilevate.

## 10ter. TB-INS — Osservabilità e audit
Documento completo: [`docs/testbook/TB-INS-insight.md`](testbook/TB-INS-insight.md) — 28 regole, circa 110 rami mappati,
**586 righe** in 37 aree. Test: `services/insight-service/src/test/java/io/loyaltyhub/insight/` (`TestbookIns*Test`,
`TestbookIns{Dlq,Trace,Kpi,Audit,Events,Stream,Store}IT`) e `deploy/hub/.../TestbookInsHubIT` per le righe tra servizi.

- **Riduzioni dichiarate:** filtri SSE 81 → 9 all-pairs (L9) + guasti singoli; DLQ stato × azione × famiglia × ruolo × nota
  3 200 → 99 righe; tabelle complete per stato del tracciato, riprocessabilità e ruoli di lettura.
- **Divergenze trovate e corrette (55 righe, 17 cause):** giorno di business in Europe/Rome, tutte le metriche e dimensioni
  di insight §2, `membersActive30d` e `membersTotal`, storico sintetico di riscatti/giocate/vincite (docs/10 §9), 400 per
  parametro mancante, esiti del tracciato, paginazione `{items,page}` su audit/eventi/tracciati (backoffice adeguato), stato
  della pipeline (volumi 1 h/24 h, ritardo, ultimo fatto per servizio), coda SSE per client con disconnessione del lento e
  consegna senza doppioni alla riconnessione, troncamento del payload a 8 KB, voce di audit `RESET`, servizio corretto
  nell'audit dell'hub, audit di modifica campagna con i soli campi cambiati.
- **Scelte registrate:** Q-312…Q-331 (tutte decise, conservative).
- **Verifica a mutazione:** 11 mutazioni, tutte rilevate.

## 10quater. TB-PLT — Piattaforma
Documento completo: [`docs/testbook/TB-PLT-piattaforma.md`](testbook/TB-PLT-piattaforma.md) — 37 regole, 172 rami
mappati, **563 righe** in 28 aree. Test: `libs/lh-common/src/test/java/io/loyaltyhub/common/testbook/`
(`TestbookPlt{Envelope,Clock,Config,Dlq,Routing,Errors}Test`, `TestbookPltRelayIT` con Postgres e Kafka in-JVM) e
`deploy/hub/src/test/java/io/loyaltyhub/hub/` (`TestbookPlt{Bus,HubConfig}Test`, `TestbookPlt{Api,Contract,FreeProfile}IT`).

- **Coperto:** outbox con bus/Kafka giù e poi su (ordine per chiave, nessun doppione), ritentativi e DLQ per tipo
  d'errore (`LOOP_GUARD`, 3 tentativi), conformità ai contratti di ogni `type` dichiarato (una riga per tipo), errori
  RFC 9457 per famiglia, limiti della paginazione su ogni elenco, matrice dell'attore, reset demo idempotente e
  concorrente, confini temporali di Roma, profilo free, variabili d'ambiente, requisiti non funzionali misurabili.
- **Riduzioni dichiarate:** proprietà del profilo free × servizio 120 → 15 righe; ruoli e forme dell'intestazione solo
  sull'endpoint ADMIN del reset (le altre guardie sono in TB-GOV).
- **Divergenze trovate (108 righe, 22 cause):** tutte corrette nel codice (503 col database giù, errori di forma di Spring,
  MDC, classificazione DLQ lungo le cause e `lh-attempts` veritiero, truststore SASL_SSL, cache della salute Kafka,
  `@eom` con scostamenti, variabili d'ambiente di docs/11 §8 e readiness con db e kafka, `lh_outbox_pending`, pulizia di
  `processed_event`, 400 per `size`/`page` non validi ovunque, ordine e completezza del reset, `GET /v1/demo/info`,
  limite di 60 eventi/min per IP, 12 schemi di contratto mancanti, 2 partizioni nell'hub, profilo free completo e
  listener `@Lazy(false)`, OpenAPI su `/v3/api-docs` con springdoc approvato: Q-341, ADR-046).
- **Scelte registrate:** Q-332…Q-342 (tutte decise; Q-341 approvata dall'owner: springdoc, ADR-046).
- **Verifica a mutazione:** 12 mutazioni (una per classe di test), tutte rilevate.

## 10quinquies. Domini di Fase 2 (pianificati)

Nati con l'adozione di Fase 2 (M8.0, `docs/18` Appendice B punti 9 e 14): stesso metodo di §1, righe `TB-<DOM>-NNN` in `docs/testbook/TB-<DOM>-*.md`, oracolo = `docs/18` e le ADR 026–045. Ogni dominio diventa *eseguibile* con le fette della sua milestone; fino ad allora non ha righe. `TB-SEC` è obbligatorio dalla fetta M8.11 (job `security`), `TB-GRC` dalla M8.13.

## 11. Copertura

| Dominio | Regole | Rami del codice mappati | Righe | Combinazioni ridotte | Divergenze aperte |
|---|---|---|---|---|---|
| _si compila all'inserimento di ogni dominio_ | | | | | |

## 12. Registro delle divergenze

| Riga | Specifica | Comportamento osservato | Causa (file:riga) | Esito |
|---|---|---|---|---|
| _nessuna registrata_ | | | | |
