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
| Governance e membri | [§8](#8-tb-gov--governance-e-membri) | tutti · member | docs/03 §3.6 · docs/06 §7 · docs/08 §2 · F-APR-*, F-MBR-*, F-SEG-* | **eseguibile** — 972 righe |
| Engagement | [§9](#9-tb-eng--engagement) | engagement | docs/servizi/engagement-service.md · F-CNT-*, F-MSG-*, F-WBH-01 | **eseguibile** — 804 righe |
| Interfaccia | [§10](#10-tb-web--interfaccia) | web | docs/07 · docs/08 · docs/09 | **eseguibile** — 741 righe |
| Percorsi end-to-end | [§10bis](#10bis-tb-e2e--percorsi-end-to-end) | hub (tutti) | docs/17 E10 e percorsi tra servizi · docs/10 §8 | in preparazione |
| Osservabilità e audit | [§10ter](#10ter-tb-ins--osservabilità-e-audit) | insight | docs/servizi/insight-service.md · F-INS-*, F-AUD-01 | in preparazione |
| Piattaforma | [§10quater](#10quater-tb-plt--piattaforma) | lh-common, hub | docs/04 · docs/05 · docs/06 · contracts/ | in preparazione |

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
- **Verifica a mutazione:** non eseguita per questo dominio (bloccata dal controllo dei permessi dell'agente); le
  divergenze corrette hanno comunque fatto il percorso rosso → verde.

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
- **Scelte registrate:** Q-159, Q-167, Q-289…Q-297; non conservative: Q-294, Q-295, Q-297.
- **Referral lato member-service:** coperto in TB-GOV (area REF).
- **Verifica a mutazione:** 11 mutazioni, tutte rilevate.

## 8. TB-GOV — Governance e membri
Documento completo: [`docs/testbook/TB-GOV-governance.md`](testbook/TB-GOV-governance.md) — 33 regole, 61 rami mappati,
**972 righe** in 25 aree. Test: `libs/lh-common/src/test/java/io/loyaltyhub/common/testbook/` (attore, transizioni, policy),
`services/member-service/src/test/java/io/loyaltyhub/member/testbook/` (criteri dei segmenti, attributi, `TestbookGovMemberIT`),
`deploy/hub/src/test/java/io/loyaltyhub/hub/TestbookGovHubIT.java` (righe tra servizi).

- **Tabelle complete:** stato × azione con policy accesa e spenta, ruolo × azione, guardia dell'attore, policy della campagna
  alla soglia di 100 000, stato del membro × stato di arrivo, endpoint × ruolo, tipo di attributo × valore, comparatore ×
  tipo, ogni cella della matrice capacità × ruolo di docs/08 §2, stato del membro × effetti negli altri servizi.
- **Divergenze trovate e corrette:** criteri dei segmenti con tipi incompatibili (falsi, docs/03 §3.3), attributo senza tipo
  → 422, corpo mancante → 400, storico approvazioni anche per i contenuti (`GET /v1/contents/{id}/approval-history`).
- **Referral lato member-service:** area REF (23 righe).
- **Scelte registrate:** Q-298…Q-310; non conservative: Q-298, Q-300, Q-303, Q-306.
- **Verifica a mutazione:** non eseguita (bloccata dal controllo dei permessi dell'agente); le divergenze corrette hanno
  fatto il percorso rosso → verde.

## 9. TB-ENG — Engagement
Documento completo: [`docs/testbook/TB-ENG-engagement.md`](testbook/TB-ENG-engagement.md) — 47 regole, 143 rami mappati,
**804 righe** (395 unitarie, 409 d'integrazione). Test: `services/engagement-service/src/test/java/io/loyaltyhub/engagement/`
(`TestbookEng*Test`, `TestbookEng*IT`), dati in `src/test/resources/testbook/engagement/*.csv`.

- **Tabelle complete:** selezione (60), pubblico (27+9), pop-up (24), ciclo di vita (50), ruoli (28), regole messaggi (60).
- **Divergenze trovate e corrette (7 righe):** confine «iscritti da < 7 giorni», campi non sicuri modificabili su un
  contenuto LIVE (ora `409 CONTENT_LIVE_LOCKED`), portale senza `memberId` (ora 400).
- **Scelte registrate:** Q-161, Q-170…Q-185; non conservative: Q-174, Q-179, Q-180, Q-184.
- **Verifica a mutazione:** 8 mutazioni, tutte rilevate.

## 10. TB-WEB — Interfaccia
Documento completo: [`docs/testbook/TB-WEB-interfaccia.md`](testbook/TB-WEB-interfaccia.md) — 77 regole, 349 rami mappati,
**741 righe** in 21 aree. Test: file `web/**/*.testbook.test.ts(x)` accanto al codice, helper `web/test/testbook.ts`.

- **Oracoli dalla specifica:** matrice ruoli × capacità (docs/08 §2), barra laterale (docs/08 §1), barra del ciclo di vita
  (docs/08 §3.3) copiate nei test e confrontate col codice.
- **Divergenze trovate e corrette (33 righe, 23 cause):** etichette e contatore della navigazione, azioni vietate davvero
  disabilitate, dialogo per ogni transizione, colori degli stati, stati *loading/empty/error/degraded* di docs/07 §6,
  avvisi del portale (mantenimento livello, profilo sospeso), frase generata della campagna, formati di euro, zero e tempo.
- **Scelte registrate:** Q-186…Q-208; non conservative: Q-186, Q-197, Q-206; Q-208 = fonti ammesse non esposte da campaign.
- **Verifica a mutazione:** 26 mutazioni, tutte rilevate.

## 10bis. TB-E2E — Percorsi end-to-end
_Da scrivere: una riga per percorso reale (docs/17 E10, E11), con la catena di eventi attesa nello stesso tracciato e lo stato finale in ogni servizio; varianti con servizio addormentato a metà saga, riconsegna, reset tra esecuzioni, mezzanotte e cambio dell'ora a Roma, due azioni ravvicinate dello stesso membro._

## 10ter. TB-INS — Osservabilità e audit
_Da scrivere: DLQ riprocessa/scarta, regole di stato dei tracciati, ripresa SSE con `Last-Event-ID`, KPI senza doppi conteggi, conservazione, audit (F-AUD-01)._

## 10quater. TB-PLT — Piattaforma
_Da scrivere: outbox con Kafka giù, ritentativi e DLQ per tipo d'errore, conformità dei contratti evento, errori RFC 9457, reset idempotente, requisiti non funzionali._

## 11. Copertura

| Dominio | Regole | Rami del codice mappati | Righe | Combinazioni ridotte | Divergenze aperte |
|---|---|---|---|---|---|
| _si compila all'inserimento di ogni dominio_ | | | | | |

## 12. Registro delle divergenze

| Riga | Specifica | Comportamento osservato | Causa (file:riga) | Esito |
|---|---|---|---|---|
| _nessuna registrata_ | | | | |
