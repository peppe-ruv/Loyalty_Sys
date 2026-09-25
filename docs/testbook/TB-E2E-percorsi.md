# TB-E2E — Percorsi end-to-end tra servizi (testbook funzionale)

Dominio **TB-E2E** del testbook (`docs/16`, §10bis): i percorsi reali del cliente e della demo che attraversano più servizi, provati nel **deployable consolidato** (`deploy/hub`, ADR-023) col bus in-process (profilo `inproc`, ADR-024). Ogni riga asserisce la **catena di eventi attesa in un solo tracciato** (stesso `lhcorrelationid`, un solo albero) e lo **stato finale in ogni servizio toccato**. Storie: `docs/17` E10 (US-E10-03…07, 13…16), E11 (US-E11-07, 09), E12 (US-E12-02…04 per le varianti), criteri di accettazione M1–M7 di `docs/12` che attraversano più servizi.

- **Oracolo** = specifica: `docs/03` (§3.5 motore, §4 wallet e tier, §5 saga, §6 instant win, §8 referral e obiettivi), `docs/05` (§2 envelope e tracciato, §7 ponte, §8 matrice dei consumi) e `contracts/`, `docs/servizi/*` (qui «reward §n», «wallet §n»…), `docs/10` (§2 personaggi, §4 campagne, §6 gioco, §8 scenari) e `seed/`, `docs/12`, le scelte di `docs/15` citate col loro `Q-nn`. Mai «quello che il codice restituisce oggi».
- **Catena attesa**: derivata dalla specifica sommando, per ogni evento, ciò che la matrice dei consumi (docs/05 §8) fa produrre a ogni consumer: il motore scrive sempre un `campaign.evaluated` per azione (docs/03 §3.5 p. 5) più un effetto per campagna scattata; il wallet un `wallet.points.earned` per accredito e `tier.upgraded` alla salita (docs/03 §4.3); gamification `achievement.completed` + `badge.awarded` (docs/03 §8); il ponte un'azione per fatto mappato (docs/05 §7); engagement un `message.delivered` per regola soddisfatta (engagement §6, `seed/notification-rules.json`). Notazione `A:`/`E:`/`F:` = azione/effetto/fatto, `=n` = numero esatto nel tracciato. `achievement.progressed` non è contato (emesso «al cambio di valore», la specifica non dice se anche al completamento). Gli eventi di audit non fanno parte della catena.
- **AMBIGUO** = la specifica tace e non c'è una scelta in docs/15: la riga asserisce il comportamento di oggi, il test porta `// TESTBOOK: ambiguo, vedi <ID>` (elenco in §22).
- **Divergenza** = il codice non rispetta la specifica: il test asserisce la specifica e fallisce (registro in §21).

## 0. Esecuzione

| Classe (`deploy/hub/src/test/java/io/loyaltyhub/hub/`) | «Oggi» (orologio) | Aree | CSV (`deploy/hub/src/test/resources/testbook/e2e/`) |
|---|---|---|---|
| `TestbookE2eJourneysIT` | gio 24/09/2026 12:00 Roma | REG, TIER-001…006, REF-001…005, DIG-001…003, IW-001…006, ANO | `registrazione.csv`, `livelli.csv`, `amico.csv`, `digitale.csv`, `vincita.csv`, `oblio.csv` |
| `TestbookE2eRedemptionIT` | gio 24/09/2026 | CPN-001…003, INS, PHY, SAG | `premi.csv`, `saga.csv` |
| `TestbookE2eReliabilityIT` | gio 24/09/2026 | RED, FAIL, CON | `riconsegna.csv`, `guasti.csv`, `concorrenza.csv` |
| `TestbookE2ePersonasIT` | gio 24/09/2026 | RST, CORE, TIER-007/008, REF-006, ONB, DIG-004, SMK, CPN-004, IW-007, EXP | `personaggi.csv` |
| `TestbookE2eBusinessTimeIT` | lun 02/11/2026 13:00 Roma | TIM | `tempo.csv` |

- Base comune `TestbookE2eSupportIT` (astratta, nessun caso). Ogni classe ha il **suo** contesto Spring (`HubApplication`, profili `demo,inproc`) e il suo Postgres incorporato; relay dell'outbox a 50 ms e ritentativi dei consumer 50 ms/50 ms (3 tentativi, Q-131, `src/test/resources/application.yml`) per tempi brevi.
- Ogni riga CSV è **un** caso JUnit: `DynamicTest` con nome `[<ID>] <descrizione>` e sorgente la riga del CSV. Un ID = un caso eseguito.
- **Orologio**: il bean `Clock` è sostituito da un orologio che parte da un istante fisso e avanza (`startingAt`): le date del seed (`@today…`) e dei casi sono deterministiche, senza orologio di parete. `TestbookE2eBusinessTimeIT` mette «oggi» dopo il cambio dell'ora di ottobre 2026, così tutto ottobre è nella finestra di ingresso di 30 giorni (ingestion §5.5).
- **Dati**: membri nuovi a ogni caso, iscritti dal portale (`POST /v1/members`, F-MBR-06) — nessuna dipendenza dallo stato mutabile del seed. I casi sui personaggi di docs/10 (classe Personas) partono da un **reset** della demo. Il saldo di partenza delle saghe si prepara con una rettifica `GOODWILL` (wallet §3).
- **Quiete invece di pause**: il caso attende che il bus in-process abbia consegnato tutto fino a una barriera FIFO, che l'outbox non abbia righe da pubblicare e che event store, DLQ, outbox e `processed_event` siano fermi tra due barriere.
- **Catena**: letta dall'event store di insight (`insight.event_store`, tutti i topic tranne la DLQ, Q-111) per `correlation_id`; forma controllata: una sola radice senza causa, ogni causa nel tracciato, azioni del ponte con fonte `internal` e `lhhop` = hop del fatto + 1, nessun hop > 3; più `GET /v1/traces/{id}` (BO-25): una radice e stato non `FAILED` (o `FAILED` dove atteso).
- **Varianti**: *riconsegna* = stesso record (chiave, valore, header `lh-*`) rimesso sul bus come farebbe il relay; *servizio addormentato* = riga `wallet.wallet` del membro bloccata da un'altra transazione (il consumer del wallet resta fermo sul messaggio, verificato con `pg_locks`); *guasto* = trigger SQL di prova che fa fallire le prossime N insert di una tabella (eccezione ritentabile), rimosso a fine caso; *azioni ravvicinate* = richieste HTTP parallele con partenza simultanea.
- **Precondizione IW** (membri nuovi): gli istanti `OPEN` di IW-AUTUNNO che maturerebbero entro domani sono spostati a +30 giorni, così vince solo l'istante piantato (US-E06-09) e senza istante piantato la giocata perde. Il caso IW-007 (Matteo) non la usa e prova Q-62 con gli istanti maturi del seed.
- Comando: `./mvnw -q -pl deploy/hub -am verify -Dtest='Testbook*' -Dit.test='Testbook*' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`; rapporto: `TESTBOOK_WEB=0 bash scripts/testbook.sh` (vedi §23 per il riconoscimento dell'ID `TB-E2E-…`).

## 1. Inventario delle regole

| Regola | Testo (sintesi) | Rif. spec | Aree |
|---|---|---|---|
| R-01 | Ponte interno: i 9 fatti mappati diventano l'azione corrispondente, nuovo id, fonte `internal`, stesso subject/time/correlazione, causa = fatto, `lhhop`+1; campi `data` omonimi copiati | docs/05 §7, F-ING-08 | REG, TIER, REF, IW, CPN, FAIL-004 |
| R-02 | Un tracciato per azione radice: tutti gli eventi derivati portano il `lhcorrelationid` della radice; un solo albero per causa | docs/05 §2, insight §5, docs/12 M2/M3/M5 | tutte |
| R-03 | Iscrizione: `member.registered` con snapshot completo, codice amico di 8 caratteri A-Z2-9, nickname «Nome I.»; wallet con tier BASE; snapshot in ogni servizio; `CMP-WELCOME` 100 PTS senza moltiplicatore; MSG-WELCOME | docs/03 §2, member §4–§5, wallet §4, docs/10 §4, engagement §6 | REG |
| R-04 | Abbinamento automatico alla registrazione: eventi `UNMATCHED` con subject `email:`/`external:` del nuovo membro accettati e pubblicati (ultimi 7 giorni, ≤ 100, solo se l'esito è ACCEPTED, mai `member:`) | F-ING-04, Q-115, Q-116, Q-119 | REG-002…005 |
| R-05 | Codice amico alla registrazione: lega invitato e invitante; codice inesistente o di un membro non ACTIVE ⇒ 422 `REFERRAL_CODE_INVALID`, nessun membro | F-REF-01, member §5, Q-61 | REG-006…008 |
| R-06 | E-mail univoca: seconda iscrizione con la stessa e-mail respinta, nessun fatto | docs/03 §2, US-E10-08 | REG-009 |
| R-07 | Salita immediata: dopo ogni accredito STS, se `periodSts` ≥ soglia di un rank superiore ⇒ il più alto raggiunto, un solo `tier.upgraded` | docs/03 §4.3, wallet §5 | TIER, CON-001, REF-004 |
| R-08 | Bonus di salita `CMP-TIER-UP-BONUS` LOOKUP su `newTier` (SILVER 200 · GOLD 500 · PLATINUM 1 000, senza moltiplicatore) dal ponte | docs/10 §4, docs/03 §3.4 | TIER, REF-004 |
| R-09 | Moltiplicatore di livello sui PTS con `tierMultiplierApplies`: `floor(base × moltiplicatore)` col livello al momento dell'accredito; STS mai moltiplicati | docs/03 §4.2, docs/10 §4 | TIER-006/008, CORE, REF-006, DIG-004, IW-002 |
| R-10 | `PER_AMOUNT` con arrotondamento per difetto | docs/03 §3.4 | TIER-005, ONB |
| R-11 | Il livello nuovo arriva agli snapshot (member proiezione, campaign, reward, engagement) e cambia ciò che il membro vede (catalogo) | docs/05 §8, reward §2, docs/10 §5 | TIER, TIER-007 |
| R-12 | Referral: alla prima azione qualificante (`purchase.completed`) dell'invitato due `referral.completed` (REFERRER/REFEREE) → ponte → `CMP-REFERRAL-REFERRER` 500 PTS + 250 STS (10 / edizione) e `CMP-REFERRAL-REFEREE` 200 PTS; nessun altro ai successivi; MSG-REFERRAL-DONE all'invitante | docs/03 §8, member §5, docs/10 §4, US-E06-17 | REF |
| R-13 | Membro non ACTIVE nello snapshot del motore ⇒ `NO_MEMBER`, nessun effetto | docs/03 §3.5 p. 1 | REF-005 |
| R-14 | Etichette `ebill`/`directdebit` dalle azioni + `member.updated`; segmenti al ricalcolo (solo differenze) → pubblico dei contenuti | Q-80, docs/03 §10, docs/10 §3, §7, docs/12 M6 | DIG |
| R-15 | Limiti per membro (1 / sempre, 1 / giorno, 3 / giorno, 10 / edizione): contatori atomici; superato ⇒ `LIMIT` in `skipped[]`, nessun effetto | docs/03 §3.5 p. 5, Q-158 | DIG-003, CON-002, SMK-002, ONB-002, TIM, REF-004 |
| R-16 | Obiettivi e badge: `ACH-FIRST-PURCHASE`, `ACH-3-PURCHASES-MONTH` (una per mese), `ACH-BIG-SPENDER` (SUM ≥ 1 000 nell'edizione), `ACH-DIGITAL`, `ACH-STREAK-7` → `badge.awarded` → ponte → `CMP-BADGE-BONUS` 100 PTS | docs/03 §8, docs/10 §6, gamification §7 | TIER, REG, DIG, CON, TIM-013, ONB |
| R-17 | Instant win: giocata sincrona; vincita → `contest.won` → ponte `instantwin.won` → campagne di sistema (punti senza moltiplicatore, coupon via `coupon.issue`); PHYSICAL consegnato a mano (PENDING → DELIVERED); LOSE senza istante maturo; nessuna giocata ⇒ 422 | docs/03 §6, gamification §3, §7, US-E06-05/08 | IW |
| R-18 | Istante piantato: la prossima giocata vince **quel** premio anche con istanti maturi di altri premi | US-E06-09, Q-62, gamification §3 | IW |
| R-19 | Crediti: `CMP-SURVEY` → `plays.grant` → `contest.plays.granted`; prima la gratuita giornaliera, poi i crediti | docs/03 §6, gamification §5 | IW-005, TIER-008 |
| R-20 | Anonimizzazione: nome → «Membro anonimo» (nickname), e-mail/telefono null; ogni servizio cancella i dati personali dal proprio snapshot; movimenti conservati; dopo: azione `member:` REJECTED `MEMBER_NOT_ACTIVE`, `email:` UNMATCHED, richiesta 422, rettifica 409, giocata 422; solo ADMIN | docs/03 §2, M7.5, Q-120, Q-126, Q-127, Q-128 | ANO |
| R-21 | Saga: richiesta 202 PENDING con stock prenotato → wallet spende (FIFO, tutto o niente) → CONFIRMED → AUTO_COUPON: coupon ISSUED + FULFILLED; INSTANT: FULFILLED; MANUAL: resta CONFIRMED; conferma → ponte `reward.redeemed` | docs/03 §5, reward §5, wallet §5, M4.6 | CPN, PHY, SAG |
| R-22 | Saldo insufficiente ⇒ `wallet.spend.rejected` (requested, available) → REJECTED col motivo, stock ripristinato, nessun movimento | docs/03 §4.2, §5, reward §5 | INS, CON-003 |
| R-23 | Premio fisico: indirizzo obbligatorio (422 `SHIPPING_REQUIRED`, nessun evento); evasione CARE con nota → FULFILLED; annullo CARE da CONFIRMED → `cancelled` refund → `wallet.points.refunded`, stock +1; annullo di una FULFILLED ⇒ 409 | reward §3, §5, F-RWD-06/07 | PHY |
| R-24 | Rimborso in un lotto nuovo con scadenza = max(scadenza più lontana dei lotti consumati, oggi + 30 gg) | docs/03 §4.2, Q-160 | PHY-003 |
| R-25 | Timeout: PENDING da più di 10 min ⇒ REJECTED (TIMEOUT), stock ripristinato; spesa arrivata dopo ⇒ `cancelled` con `refund=true` (compensazione); wallet fermo e risveglio entro 10 min ⇒ CONFIRMED | docs/03 §5, reward §5, docs/12 M4, Q-55 | SAG |
| R-26 | Messaggi dai fatti secondo le regole del seed (condizioni `currency=PTS`, `origin=CAMPAIGN`, `role=REFERRER`), deduplica | engagement §5–§6, `seed/notification-rules.json` | tutte (colonna messaggi) |
| R-27 | Consumer idempotenti: la riconsegna di qualunque messaggio non cambia lo stato né produce messaggi a valle; `effectId` = idempotenza di dominio anche con un nuovo id CloudEvents | docs/04 §5, docs/06 §9, docs/03 §3.5 p. 4, RNF-03, US-E12-02 | RED |
| R-28 | Ritentativi: 3 tentativi per gli errori ritentabili, poi DLQ con `errorCode`; non ritentabile ⇒ DLQ al primo; il consumer prosegue; tracciato FAILED se esiste DLQ | docs/04 §5, docs/12 M0, Q-131, insight §5 | FAIL |
| R-29 | Azioni ravvicinate dello stesso membro: nessun doppio effetto su salita, badge, limiti e saldo (lock di riga, contatori atomici, tutto o niente) | RNF-04, docs/03 §3.5, wallet §5, US-E12-04 | CON |
| R-30 | Tempo di business: `time` dell'azione su Europe/Rome per giorno della settimana, periodi dei limiti, mesi delle classifiche e degli obiettivi, serie | docs/03 §3.1, §3.3, §8 | TIM |
| R-31 | Scadenza `ROLLING_MONTHS(12)`: ultimo istante (23:59:59.999999) del mese di `earned_at` + 12 mesi a Roma | wallet §5, docs/10 §3 | TIM-009/010 |
| R-32 | Job scadenze e preavvisi con `asOf` (data pura = fine giornata di Roma): un preavviso per lotto; un `EXPIRE` per membro/valuta; fatti e proiezione | docs/03 §4.2, wallet §5, Q-156, docs/12 M3 | EXP |
| R-33 | Reset demo (ADMIN): ogni servizio torna esattamente allo stato di docs/10; due reset = stesso stato (semi fissi) | docs/10 §1.3, F-DEMO-05, US-E11-07 | RST |
| R-34 | Scenari guidati e fumo: passi con esito atteso, `DUPLICATE` nello stesso run, raffica senza perdite, `SCN-SMOKE` +5 entro 15 s o `LIMIT` | docs/10 §8, docs/12 M2 e §4, US-E11-06/09, Q-63, Q-130 | CORE, SMK, ONB, TIER-007/008, REF-006, DIG-004 |
| R-35 | Core loop M1: acquisto → EARN PTS e STS di `CMP-PURCHASE-BASE` con la correlazione dell'azione; sabato ×2; membro BLOCKED ⇒ REJECTED senza pubblicazione | docs/12 M1, docs/10 §4 | CORE |

## 2. Rami del codice → regola

Punti di decisione dell'hub (`deploy/hub/src/main`) e dei gestori che fanno da cerniera tra i servizi nei percorsi. I rami interni a un solo servizio sono inventariati nel dominio del servizio (TB-ING, TB-CMP, TB-WAL, TB-RWD, TB-GAM, TB-ENG).

| N. | Punto di decisione (file) | Ramo | Regola | Righe |
|---|---|---|---|---|
| H-01 | `hub/bus/HubInProcessBus.publish` | topic senza sottoscrittori ⇒ scartato | come Kafka senza consumer (docs/05 §1) | non provato qui (TB-PLT) |
| H-02 | `HubInProcessBus.publish` | una copia per sottoscrizione (gruppo) | docs/05 §1 (un gruppo per servizio) | tutte |
| H-03 | `HubInProcessBus.deliver` | successo al primo tentativo | R-28 | tutte |
| H-04 | `HubInProcessBus.deliver` | errore ritentabile con tentativi residui ⇒ attesa e nuovo tentativo | R-28, Q-131 | FAIL-001, 002, 004 |
| H-05 | `HubInProcessBus.deliver` | ultimo tentativo fallito o errore non ritentabile ⇒ DLQ | R-28 | FAIL-003, 005 |
| H-06 | `HubInProcessBus.deadLetter` | record della DLQ che fallisce ⇒ scartato senza nuova DLQ | **ramo senza specifica** | non provato |
| H-07 | `HubInProcessBus.deadLetter` | DLQ con header originali + `lh-*` | R-28, docs/05 §1 | FAIL-003, 005 |
| H-08 | `hub/bus/HubInProcessMessaging.register` | metodo senza `@KafkaListener` ignorato; uno per topic dichiarato | docs/05 §8 | tutte (avvio) |
| H-09 | `HubInProcessMessaging.invoke` | eccezione del listener rilanciata al bus | R-28 | FAIL-* |
| H-10 | `HubInProcessMessaging.invoke` | `Error` incapsulato in `IllegalStateException` | **ramo senza specifica** | non provato |
| X-01 | ingestion `messaging/FactsHandler.updateMemberIndex` | registered/updated ⇒ indice; status.changed ⇒ stato; anonimizzazione ⇒ cancellazione | R-03, R-20 | REG-001, REF-005, REG-008, ANO-001, ANO-003 |
| X-02 | `FactsHandler.updateMemberIndex` | solo a `member.registered` ⇒ abbinamento automatico | R-04, Q-119 | REG-002…005 |
| X-03 | ingestion `application/InboundResolutionService.autoMatch` | riga UNMATCHED rivalutata: ACCEPTED dello stesso membro ⇒ accettata e pubblicata; altrimenti resta | R-04, Q-116 | REG-002…005 |
| X-04 | `FactsHandler.bridge` | mappatura abilitata ⇒ azione interna (hop+1) | R-01 | TIER, REF, IW, CPN, DIG, FAIL-004 |
| X-05 | `FactsHandler.bridge` | mappatura assente/disabilitata; `lhhop` > 3 ⇒ `LOOP_GUARD` | R-01 | non provato qui (TB-ING) |
| X-06 | `FactsHandler.bridge` | copia **tutto** `data` del fatto (non solo i campi omonimi del contratto d'azione) | **ramo senza specifica** (docs/05 §3 «campi omonimi») | REG-001 (solo `channel`) |
| X-07 | campaign `application/EvaluationService.evaluate` | effetti `points.grant` · `plays.grant` · `coupon.issue` · `badge.award` · `message.send`; `campaign.evaluated` sempre | R-02, docs/03 §3.4 | tutte; `plays.grant` IW-005, TIER-008; `coupon.issue` IW-003; `badge.award`/`message.send` non usati dal seed nei percorsi |
| X-08 | campaign `engine/CampaignEngine` | snapshot assente o non ACTIVE ⇒ `NO_MEMBER` | R-13 | REF-005 |
| X-09 | `CampaignEngine` (limiti, condizioni) | `LIMIT` · `CONDITION` · `AUDIENCE` | R-15, R-30 | DIG-003, CON-002, SMK-002, ONB-002, TIM, IW-004, REF-004 |
| X-10 | campaign `engine/PeriodKeys` | periodo del limite su Europe/Rome | R-30 | TIM-005…008, 011…013 |
| X-11 | wallet `application/WalletService` (accredito) | `effectId` già applicato ⇒ niente | R-27 | RED-002, RED-016 |
| X-12 | `WalletService` (salita) | rank guadagnato > attuale ⇒ `tier.upgraded`; altrimenti niente | R-07 | TIER, TIER-006/008, CON-001 |
| X-13 | wallet `application/RedemptionPayments.spend` | già spesa ⇒ niente; membro non ACTIVE ⇒ rifiuto; saldo insufficiente ⇒ `spend.rejected`; altrimenti SPEND FIFO | R-21, R-22, R-27 | RED-008, INS, CON-003, CPN (non ACTIVE: non raggiungibile nei percorsi, fermato prima da reward — ANO-004) |
| X-14 | `RedemptionPayments.refund` | `refund=false` ⇒ niente; nessuna spesa ⇒ niente (WARN); già rimborsata ⇒ niente; altrimenti lotto nuovo | R-23, R-24, R-27 | PHY-003, SAG-002, SAG-004, RED-015 |
| X-15 | reward `application/RedemptionService.onPointsSpent` | PENDING ⇒ CONFIRMED + evasione; REJECTED/CANCELLED ⇒ `cancelled` refund (compensazione); CONFIRMED/FULFILLED ⇒ niente; richiesta sconosciuta ⇒ WARN | R-21, R-25, R-27 | CPN, SAG-002, SAG-004 (**ramo senza specifica** per CANCELLED: reward §5 descrive solo il timeout), RED-009 |
| X-16 | `RedemptionService.onSpendRejected` | PENDING ⇒ REJECTED; altro stato ⇒ niente | R-22 | INS, CON-003 |
| X-17 | `RedemptionService.fulfil` | AUTO_COUPON ⇒ coupon; INSTANT ⇒ FULFILLED; MANUAL ⇒ attesa; pool vuoto ⇒ `needsAttention` | R-21 | CPN-001…003, PHY-002 (pool vuoto: TB-RWD) |
| X-18 | `RedemptionService.timeoutPending` | solo PENDING da più di 10 min | R-25 | SAG-002, 003, 005 |
| X-19 | `RedemptionService.cancelByMember` / `cancelWithRefund` / `fulfilManually` | stato ammesso ⇒ transizione; altrimenti 409 | R-23, R-25 | SAG-004, PHY-002…004 |
| X-20 | reward `messaging/CouponIssueHandler` | effetto già emesso ⇒ niente; altrimenti coupon del pool del premio | R-17, R-27 | IW-003, RED-012 |
| X-21 | member `application/ReferralService.onAction` | tipo non qualificante ⇒ niente; referral già completato o senza invitante ⇒ niente; altrimenti due fatti | R-12 | REF-001…004 |
| X-22 | member `application/MemberService.applyActionLabels` | etichette cambiate ⇒ `member.updated`; invariate o membro anonimizzato ⇒ niente | R-14, Q-80 | DIG-001, DIG-003 |
| X-23 | `MemberService.anonymize` | solo ADMIN; conferma; già anonimizzato ⇒ 409; fatti `status.changed` + `updated` | R-20 | ANO-001, ANO-007 |
| X-24 | gamification `application/PlayService.play` | WIN (claim) · LOSE · `NO_PLAYS_AVAILABLE` · `MEMBER_NOT_ACTIVE` | R-17, R-18, R-20 | IW, ANO-006 |
| X-25 | gamification `ContestAdminService.updateDelivery` | solo vincite PHYSICAL; PENDING/DELIVERED | R-17 | IW-004 |
| X-26 | engagement `messaging/FactHandler` | regole con condizione; `message.delivered` mai oggetto di regole; anonimizzazione ⇒ snapshot ripulito | R-26, R-20 | tutte (conteggi esatti), ANO-001 |
| X-27 | lh-common `demo/DemoResetController` + ogni `DemoResettable` | solo ADMIN; reset di ogni servizio | R-33 | RST |
| X-28 | ingestion `demo/DemoSeeder.resetToSeed` | indice membri: upsert dei membri del seed, **senza** cancellare gli altri | R-33 | RST-004 (**divergenza**, §21) |

## 3. Domini dei valori e strategia di combinazione

| Decisione | Ingressi e classi | Strategia |
|---|---|---|
| Salita di livello (TIER) | importo del primo acquisto: 999 (soglia − 1) · 1 000 (= SILVER) · 3 000 (= GOLD, salto di rank) · 6 999,99 (limite con decimali, arrotondamento) · 7 000 (= PLATINUM); acquisto successivo col nuovo livello | valori limite presi da soli (una riga per limite); la tabella completa soglia × moltiplicatore è in TB-WAL |
| Abbinamento automatico (REG) | subject e-mail: uguale · maiuscole diverse · altra e-mail; eventi parcheggiati 1 · 2 | tabella completa delle classi valide (3 × 1) + molteplicità 2 da sola; finestra 7 gg / 100 righe e `external:` in TB-ING (l'iscrizione non accetta `externalId`) |
| Codice amico (REG) | assente · valido ACTIVE · inesistente · di un BLOCKED | tabella completa (4) |
| Referral (REF) | prima azione qualificante sì/no · ordinale del completamento 1 · 2 · 11 · stato dell'invitante ACTIVE/BLOCKED | una riga per classe; limite 10/edizione al valore max+1 (11°) con i 10 precedenti eseguiti |
| Vincita (IW) | premio POINTS · POINTS con membro SILVER · COUPON · PHYSICAL · nessun istante (LOSE) · nessuna giocata | tabella completa dei tipi di premio (docs/10 §6) + classi non valide da sole |
| Anonimizzazione (ANO) | dopo: azione `member:` · `email:` · richiesta · rettifica · giocata; ruolo ADMIN/CARE | una riga per canale d'ingresso (guasto singolo); ruolo non ammesso da solo |
| Saga (CPN, INS, PHY, SAG) | saldo: 100 · 499 (costo − 1) · 500 (= costo) · 600; evasione AUTO_COUPON · INSTANT · MANUAL; eventi dopo la richiesta: nessuno · evasione CARE · annullo CARE · annullo dopo evasione · wallet fermo + (risveglio · timeout a 10 min + 1 s · timeout a 10 min − 1 s · annullo del membro) · timeout su FULFILLED | macchina a stati dei percorsi: ogni stato raggiungibile con l'evento del percorso (la tabella completa stato × evento è in TB-RWD-SAG); limiti di saldo e di timeout presi da soli |
| Riconsegna (RED) | ogni tipo di messaggio a metà catena dei percorsi (azione esterna, azione interna, effetto, fatto di wallet/gamification/reward/member) + nuovo id con stesso `effectId` + catena intera | una riga per consumer significativo del percorso (US-E12-02) |
| Guasto (FAIL) | tentativi falliti 0 (tutte le altre righe) · 1 · 2 (= max − 1 ritentativi) · 3 (= max) nel wallet; 1 nel ponte; errore non ritentabile | valori limite del numero di tentativi (Q-131) da soli |
| Azioni ravvicinate (CON) | due acquisti che insieme superano la soglia · quattro acquisti col limite 3 · due richieste col saldo per una · due azioni dell'obiettivo `DISTINCT_TYPES` | una riga per risorsa condivisa (livello, contatore, saldo, progresso) |
| Tempo (TIM) | istante: venerdì 23:59:59 / sabato 00:00:00 · domenica 23:59:59 / lunedì 00:00:00 nel giorno del cambio dell'ora · ore doppie 02:30 CEST/CET · mezzanotte che cade in due date UTC · fine mese 31/10 23:59:59 / 01/11 00:00:00; limite 3/giorno e 1/giorno prima/dopo mezzanotte; serie di 7 giorni di Roma non consecutivi in UTC | ogni confine preso da solo (prima/all'istante), più le combinazioni limite × giorno di 25 ore; il cambio dell'ora di marzo non è raggiungibile con lo stesso orologio (finestra di 30 gg): coperto a livello unitario in TB-CMP/TB-WAL |
| Reset (RST) | reset ADMIN · doppio · tra due esecuzioni · con membro nuovo · MARKETING · senza intestazione | tabella completa (6 classi, 5 righe: ADMIN coperto da tutte) |

## 4. REG — Iscrizione e abbinamento automatico (US-E10-08, US-E01-06, US-E06-16)

Classe `TestbookE2eJourneysIT` · `registrazione.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-REG-001 | Iscrizione PORTAL senza codice | catena `F:member.registered=1 A:member.registered=1 F:campaign.evaluated=1 E:points.grant=1 F:wallet.points.earned=1 F:message.delivered=2` in un albero (radice = fatto, azione `internal` hop 1 con `channel` copiato); EARN 100 `CMP-WELCOME`; inbox MSG-WELCOME + MSG-POINTS-EARNED; PTS 100, STS 0, BASE; codice amico `[A-Z2-9]{8}`; nickname «Nome P.»; indice ingestion (e-mail minuscola, ACTIVE), snapshot campaign/reward/gamification/engagement, proiezione member 100 | R-01, R-02, R-03 | registrazione.csv |
| TB-E2E-REG-002 | Acquisto 200 € `email:x` → UNMATCHED; iscrizione con `x` | UNMATCHED non pubblicato; alla registrazione riga ACCEPTED con membro e risoluzione `AUTO_MATCH`; catena del primo acquisto (3 azioni, 3 valutazioni, 3 accrediti, badge BDG-FIRST); EARN 200 PTS/200 STS + 100 bonus; PTS 400 | R-04, R-16 | registrazione.csv |
| TB-E2E-REG-003 | Come REG-002 con subject in maiuscolo | abbinato (indice `email_lower`): stesso esito di REG-002 | R-04, ingestion §2 | registrazione.csv |
| TB-E2E-REG-004 | Due acquisti parcheggiati (100 € e 50 €) per la stessa e-mail | entrambi abbinati; catene sommate `A:purchase.completed=2 … F:achievement.completed=1 F:badge.awarded=1 …`; un solo BDG-FIRST; PTS 350 | R-04, R-16 | registrazione.csv |
| TB-E2E-REG-005 | Acquisto parcheggiato per un'altra e-mail; iscrizione | la riga resta UNMATCHED; nessuna pubblicazione; PTS 100 | R-04, Q-116 | registrazione.csv |
| TB-E2E-REG-006 | Iscrizione col codice di un membro nuovo ACTIVE | catena come REG-001; `referred_by` = invitante; PT-11 dell'invitante: 1 invitato, 0 completati; nessun `referral.completed`, invitante a 100 | R-05, R-12 | registrazione.csv |
| TB-E2E-REG-007 | Codice `ZZZZ9999` inesistente | 422 `REFERRAL_CODE_INVALID`; nessun membro creato; nessun `member.registered` | R-05 | registrazione.csv |
| TB-E2E-REG-008 | Codice di un membro BLOCKED | 422 `REFERRAL_CODE_INVALID` (Q-61); nessun membro né fatto | R-05, Q-61 | registrazione.csv |
| TB-E2E-REG-009 | E-mail già iscritta | errore 4xx (**AMBIGUO**: codice e stato non fissati; oggi 409 `EMAIL_TAKEN`); nessun secondo membro, nessun fatto | R-06 | registrazione.csv |

## 5. TIER — Salita di livello (US-E10-03, US-E04-03)

Membro nuovo BASE, acquisto feriale (giovedì 11:00 Roma). Classe `TestbookE2eJourneysIT` · `livelli.csv` (TIER-001…006); `TestbookE2ePersonasIT` · `personaggi.csv` (TIER-007/008). In ogni riga: tier finale uguale in wallet, proiezione member, snapshot campaign/reward/engagement; `tier_history` con una sola `UPGRADE` (0 se BASE); azione del ponte causata dal fatto `tier.upgraded` con lo stesso `newTier`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-TIER-001 | 999 € (soglia − 1) | nessuna salita; catena del primo acquisto; EARN 999/999 + BDG-FIRST 100; PTS 1 199, STS 999, BASE | R-07, R-16 | livelli.csv |
| TB-E2E-TIER-002 | 1 000 € (= soglia SILVER) | `tier.upgraded` BASE→SILVER; catena `A:purchase.completed=1 A:achievement.completed=2 A:badge.awarded=2 A:tier.upgraded=1 F:campaign.evaluated=6 E:points.grant=5 F:wallet.points.earned=5 F:tier.upgraded=1 F:achievement.completed=2 F:badge.awarded=2 F:message.delivered=7`; PTS dell'acquisto a ×1 (accreditati prima degli STS); +200 bonus; BDG-FIRST e BDG-SPENDER; PTS 1 500, STS 1 000 | R-07, R-08, R-09, R-16 | livelli.csv |
| TB-E2E-TIER-003 | 3 000 € | BASE→GOLD con una sola salita; +500; PTS 3 800 | R-07, R-08 | livelli.csv |
| TB-E2E-TIER-004 | 7 000 € | BASE→PLATINUM; +1 000; PTS 8 300 | R-07, R-08 | livelli.csv |
| TB-E2E-TIER-005 | 6 999,99 € | 6 999 PTS e STS (per difetto) ⇒ GOLD, non PLATINUM; PTS 7 799 | R-07, R-10 | livelli.csv |
| TB-E2E-TIER-006 | 1 000 € poi 100 € | secondo acquisto 125 PTS (×1,25) e 100 STS; nessuna seconda salita; PTS 1 625, STS 1 100 | R-09, R-07 | livelli.csv |
| TB-E2E-TIER-007 | `SCN-TIER-UP` dopo reset (Giulia SILVER 2 880 STS, tris 2/3) | +162 PTS e +130 STS → GOLD (azione interna hop 1) → +500; ACH-3-PURCHASES-MONTH → BDG-TRIS → +100; catena `A:purchase.completed=1 A:tier.upgraded=1 A:achievement.completed=1 A:badge.awarded=1 F:campaign.evaluated=4 E:points.grant=4 F:wallet.points.earned=4 F:tier.upgraded=1 F:achievement.completed=1 F:badge.awarded=1 F:message.delivered=5`; MSG-TIER-UP; Giulia 4 002/3 010 GOLD; RWD-WEEKEND non più bloccato nel suo catalogo | R-07…R-11, R-16, docs/12 M3 | personaggi.csv |
| TB-E2E-TIER-008 | `SCN-TIER-UP` due volte nello stesso giorno | seconda: 195 PTS (GOLD ×1,5), 130 STS, nessuna salita (una sola UPGRADE a GOLD), nessun secondo BDG-TRIS; giocata GOLD (`plays.grant` di CMP-GOLD-PURCHASE-PLAY); Giulia 4 197/3 140 | R-09, R-15, R-16, US-E10-03 crit. 3 | personaggi.csv |

## 6. REF — Porta un amico (US-E10-15, US-E06-17)

Invitante e invitato nuovi (BASE). Classe `TestbookE2eJourneysIT` · `amico.csv`; REF-006 in `personaggi.csv`. Nei fatti `referral.completed`: ruolo REFEREE sull'invitato, REFERRER sull'invitante.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-REF-001 | Primo acquisto 60 € dell'invitato | catena `A:purchase.completed=1 A:referral.completed=2 A:achievement.completed=1 A:badge.awarded=1 F:campaign.evaluated=5 E:points.grant=6 F:wallet.points.earned=6 F:referral.completed=2 F:achievement.completed=1 F:badge.awarded=1 F:message.delivered=6`; invitante 600 PTS / 250 STS, invitato 460; MSG-REFERRAL-DONE all'invitante; 1 completato | R-12, R-16 | amico.csv |
| TB-E2E-REF-002 | Secondo acquisto 40 € | nessun `referral.completed`; catena del solo acquisto; invitante invariato; invitato 500 | R-12 | amico.csv |
| TB-E2E-REF-003 | Prima un accesso all'app, poi l'acquisto | l'accesso non qualifica (0 completati, catena del solo accesso); l'acquisto completa come REF-001; invitato 465 | R-12 | amico.csv |
| TB-E2E-REF-004 | 11 invitati completati in sequenza | 10 premi REFERRER, l'11° `LIMIT`; salita a SILVER al 4° (+200), dal 5° 625 PTS; invitante 6 050 PTS, 2 500 STS, SILVER; catena dell'11° senza gli effetti dell'invitante; 11 completati | R-12, R-15, R-07, R-09, US-E06-17 crit. 3 | amico.csv |
| TB-E2E-REF-005 | Invitante BLOCKED prima dell'acquisto | due `referral.completed` e due azioni; valutazione dell'azione REFERRER `NO_MEMBER`; accrediti solo all'invitato (460); invitante 100 | R-12, R-13 | amico.csv |
| TB-E2E-REF-006 | `SCN-REFERRAL` dopo reset (Elisa, Marco SILVER) | catena come REF-001; Marco +625 PTS (500 × 1,25, docs/10 §4 «PTS col moltiplicatore») e +250 STS → 2 475/1 670; Elisa 645/45; 1 completato | R-12, docs/10 §8 | personaggi.csv |

## 7. DIG — Cliente che diventa digitale (US-E10-14)

Classe `TestbookE2eJourneysIT` · `digitale.csv`; DIG-004 in `personaggi.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-DIG-001 | Membro nuovo: `ebill.activated` poi `directdebit.activated` | catene `A:ebill.activated=1 F:campaign.evaluated=1 E:points.grant=2 F:wallet.points.earned=2 F:member.updated=1 F:message.delivered=1` e `A:directdebit.activated=1 A:achievement.completed=1 A:badge.awarded=1 F:campaign.evaluated=3 E:points.grant=3 F:wallet.points.earned=3 F:member.updated=1 F:achievement.completed=1 F:badge.awarded=1 F:message.delivered=3`; +300/+150, +400/+200, BDG-DIGITAL +100; etichette in member e nello snapshot campaign; PTS 900, STS 350 | R-14, R-16, Q-80 | digitale.csv |
| TB-E2E-DIG-002 | Ricalcolo segmenti prima e dopo | prima: SEG-NOT-EBILL, HOME_GRID con CNT-EBILL; dopo: SEG-DIGITAL, fuori da SEG-NOT-EBILL, un `segment.entered` e un `segment.left`, snapshot engagement aggiornato, HOME_GRID con CNT-DIGITAL-THANKS e senza CNT-EBILL | R-14, docs/12 M6 | digitale.csv |
| TB-E2E-DIG-003 | Attivazioni ripetute (nuovi id) | `LIMIT` su CMP-EBILL e CMP-DIRECT-DEBIT; catene solo azione + valutazione; movimenti, badge e anagrafica invariati | R-15, US-E10-14 crit. 3 | digitale.csv |
| TB-E2E-DIG-004 | `SCN-DIGITAL` dopo reset (Marco SILVER) | +375/+150 e +500/+200 (moltiplicatore), +100 badge; dopo il ricalcolo CNT-DIGITAL-THANKS al posto di CNT-EBILL; Marco 2 825/1 770 | R-09, R-14, docs/12 M6 | personaggi.csv |

## 8. IW — Vincita istantanea (US-E10-07, US-E06-05/08/09)

Classe `TestbookE2eJourneysIT` · `vincita.csv` (precondizione IW del §0); IW-007 in `personaggi.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-IW-001 | Istante PTS-50 piantato, giocata gratuita di un membro nuovo | WIN PTS-50; catena `F:contest.played=1 F:contest.won=1 A:instantwin.won=1 F:campaign.evaluated=1 E:points.grant=1 F:wallet.points.earned=1 F:message.delivered=2`; EARN 50 `CMP-IW-PRIZE-POINTS`; MSG-CONTEST-WON | R-17, R-18 | vincita.csv |
| TB-E2E-IW-002 | PTS-100 per un membro SILVER | +100 esatti (senza moltiplicatore) | R-17, docs/10 §4 | vincita.csv |
| TB-E2E-IW-003 | COFFEE | `instantwin.won` COUPON → `E:coupon.issue=1 F:coupon.issued=1` (origin CAMPAIGN); coupon `CAF-XXXX-XXXX` ISSUED nel portale; MSG-COUPON-GIFT; saldo invariato | R-17, F-CPN-02 | vincita.csv |
| TB-E2E-IW-004 | POWERBANK | PHYSICAL: nessun effetto (entrambe le campagne di sistema `CONDITION`); consegna PENDING → DELIVERED da CARE | R-17, US-E06-08 | vincita.csv |
| TB-E2E-IW-005 | Sondaggio (+80, un credito) e due giocate senza istanti maturi | catena del sondaggio con `E:plays.grant=1 F:contest.plays.granted=1`; due LOSE con giocate rimaste 1 e 0; catena `F:contest.played=1` | R-17, R-19 | vincita.csv |
| TB-E2E-IW-006 | Giocata gratuita usata, nessun credito | 422 `NO_PLAYS_AVAILABLE`; nessun nuovo fatto di gioco | R-17 | vincita.csv |
| TB-E2E-IW-007 | Dopo reset: istante PTS-50 piantato con istanti maturi di altri premi nel seed; giocata di Matteo | WIN proprio di PTS-50 (Q-62); catena come IW-001 entro 10 s; Matteo 1 170 | R-18, Q-62, gamification §7 | personaggi.csv |

## 9. ANO — Anonimizzazione (US-E02-05, M7.5)

Membro nuovo con un acquisto. Classe `TestbookE2eJourneysIT` · `oblio.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-ANO-001 | ADMIN anonimizza | `member.status.changed` (ANONYMIZED) e `member.updated`; member: nickname «Membro anonimo», nome ed e-mail null; indice ingestion ANONYMIZED senza e-mail; snapshot campaign, wallet, reward (senza nome), gamification («Membro anonimo»), engagement (senza nome) ANONYMIZED; inbox e event store senza nome né e-mail; movimenti e tracciati conservati; fuori dalla classifica | R-20, Q-120, Q-126 | oblio.csv |
| TB-E2E-ANO-002 | Poi acquisto `member:<id>` | 202 REJECTED `MEMBER_NOT_ACTIVE`; niente sul topic; movimenti invariati | R-20 | oblio.csv |
| TB-E2E-ANO-003 | Poi acquisto `email:<vecchia>` | 202 UNMATCHED (Q-128); niente sul topic | R-20, Q-128 | oblio.csv |
| TB-E2E-ANO-004 | Poi richiesta premio | 422 `MEMBER_NOT_ACTIVE` | R-20 | oblio.csv |
| TB-E2E-ANO-005 | Poi rettifica CARE | 409 `MEMBER_ANONYMIZED` (Q-127) | R-20, Q-127 | oblio.csv |
| TB-E2E-ANO-006 | Poi giocata | 422 `MEMBER_NOT_ACTIVE` | R-20 | oblio.csv |
| TB-E2E-ANO-007 | CARE chiede l'anonimizzazione | 403; membro ACTIVE con e-mail; nessun fatto | R-20, docs/08 §2 | oblio.csv |

## 10. CPN, INS, PHY — Premi (US-E10-04, US-E10-05, US-E10-06)

Membro nuovo con saldo preparato. Classe `TestbookE2eRedemptionIT` · `premi.csv`; CPN-004 in `personaggi.csv`. Nelle righe con conferma, l'azione `reward.redeemed` nasce dal fatto `confirmed` con `rewardCode` e `pointsCost` copiati (M4.6).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-CPN-001 | RWD-COFFEE-5, saldo 600 | 202 PENDING → FULFILLED; catena `F:reward.redemption.requested=1 F:wallet.points.spent=1 F:reward.redemption.confirmed=1 F:coupon.issued=1 F:reward.redemption.fulfilled=1 A:reward.redeemed=1 F:campaign.evaluated=1 F:message.delivered=2`; coupon CAF- ISSUED origine REDEMPTION con scadenza; SPEND 500; saldo 100; stock −1; MSG-REWARD-CONFIRMED + MSG-REWARD-READY | R-21, R-26 | premi.csv |
| TB-E2E-CPN-002 | Saldo = costo (500) | FULFILLED; saldo 0 | R-21, R-22 | premi.csv |
| TB-E2E-CPN-003 | RWD-DONATION-TREE (INSTANT) | FULFILLED senza codice né `coupon.issued`; stock illimitato invariato | R-21 | premi.csv |
| TB-E2E-CPN-004 | Dopo reset: Davide RWD-SHOP-10 | FULFILLED entro 10 s, codice SHP10-; catena come CPN-001; Davide 10 800; stock −1 | R-21, reward §7 | personaggi.csv |
| TB-E2E-INS-001 | Saldo 499 (costo − 1) | REJECTED `INSUFFICIENT_BALANCE`; catena `F:reward.redemption.requested=1 F:wallet.spend.rejected=1 F:reward.redemption.rejected=1 F:message.delivered=1` con `requested` 500 e `available` 499; stock ripristinato; nessuna spesa; MSG-REWARD-REJECTED | R-22 | premi.csv |
| TB-E2E-INS-002 | Solo benvenuto (100) | come INS-001 con `available` 100 | R-22 | premi.csv |
| TB-E2E-PHY-001 | RWD-BORRACCIA senza indirizzo | 422 `SHIPPING_REQUIRED`; nessuna richiesta, nessun evento; stock invariato | R-23 | premi.csv |
| TB-E2E-PHY-002 | Con indirizzo; evasione CARE con nota | CONFIRMED in attesa; poi FULFILLED col fatto `fulfilled` (nota); catena `… A:reward.redeemed=1 F:campaign.evaluated=1 F:reward.redemption.fulfilled=1 F:message.delivered=2`; saldo 0; stock −1 | R-23 | premi.csv |
| TB-E2E-PHY-003 | Con indirizzo; annullo CARE da CONFIRMED | CANCELLED; `cancelled` refund=true → `wallet.points.refunded` 1 500 nello stesso tracciato; SPEND + REFUND; saldo 1 500; stock ripristinato; lotto di rimborso con scadenza = la più lontana dei lotti consumati (oltre oggi + 30 gg) | R-23, R-24, Q-160 | premi.csv |
| TB-E2E-PHY-004 | Annullo dopo l'evasione | 409 `REDEMPTION_NOT_CANCELLABLE`; stato di tutti i servizi invariato | R-23 | premi.csv |

## 11. SAG — Wallet addormentato a metà saga e timeout (docs/12 M4)

Membro nuovo con 500 PTS, RWD-COFFEE-5. Classe `TestbookE2eRedemptionIT` · `saga.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-SAG-001 | Wallet fermo alla richiesta; risveglio | PENDING mentre il consumer è fermo; al risveglio FULFILLED con coupon; catena come CPN-001; saldo 0 | R-21, R-25, docs/12 M4 | saga.csv |
| TB-E2E-SAG-002 | Wallet fermo; job timeout con `asOf` = richiesta + 10 min + 1 s; risveglio | REJECTED `TIMEOUT` prima del risveglio, stock ripristinato; dopo: spesa tardiva compensata (`cancelled` refund=true) → rimborso; catena `F:reward.redemption.requested=1 F:reward.redemption.rejected=1 F:wallet.points.spent=1 F:reward.redemption.cancelled=1 F:wallet.points.refunded=1 F:message.delivered=1`; SPEND + REFUND, saldo 500; nessun coupon | R-25, reward §5 | saga.csv |
| TB-E2E-SAG-003 | Wallet fermo; job timeout con `asOf` = richiesta + 9 min 59 s | resta PENDING (non «più di 10 min»); al risveglio FULFILLED | R-25 | saga.csv |
| TB-E2E-SAG-004 | Wallet fermo; il membro annulla (PENDING) | CANCELLED (refund=false), stock ripristinato; dopo il risveglio nessun addebito netto (ogni SPEND ha il suo REFUND), saldo 500 (**AMBIGUO** sul meccanismo: reward §5 descrive la compensazione solo dopo il timeout) | R-25, docs/03 §5 | saga.csv |
| TB-E2E-SAG-005 | Richiesta FULFILLED; job timeout con `asOf` + 1 h | nessun effetto (solo le PENDING) | R-25 | saga.csv |

## 12. RED — Riconsegna a metà catena (US-E12-02)

Classe `TestbookE2eReliabilityIT` · `riconsegna.csv`. Oracolo di ogni riga: fotografia di tutte le tabelle dei membri del percorso (wallet, campaign, member, gamification, reward, engagement, ingestion, stock dei premi) identica prima e dopo; nessun nuovo evento nel tracciato (salvo la copia col nuovo id in RED-016); nessuna nuova voce DLQ.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-RED-001 | acquisto 1 000 € → riconsegna `purchase.completed` | nulla cambia (campaign, gamification, member, insight) | R-27 | riconsegna.csv |
| TB-E2E-RED-002 | → `points.grant` PTS | un solo EARN | R-27 | riconsegna.csv |
| TB-E2E-RED-003 | → `wallet.points.earned` PTS | nessun messaggio, punteggio o proiezione doppi | R-27 | riconsegna.csv |
| TB-E2E-RED-004 | → fatto `tier.upgraded` | nessuna seconda azione del ponte, nessun MSG-TIER-UP doppio | R-27 | riconsegna.csv |
| TB-E2E-RED-005 | → azione `tier.upgraded` | nessun secondo bonus | R-27 | riconsegna.csv |
| TB-E2E-RED-006 | → `badge.awarded` | nessun secondo bonus badge | R-27 | riconsegna.csv |
| TB-E2E-RED-007 | → `achievement.completed` | nessuna seconda azione | R-27 | riconsegna.csv |
| TB-E2E-RED-008 | premio → `reward.redemption.requested` | nessuna seconda spesa | R-27, wallet §5 | riconsegna.csv |
| TB-E2E-RED-009 | premio → `wallet.points.spent` | nessun secondo coupon né conferma | R-27, reward §7 | riconsegna.csv |
| TB-E2E-RED-010 | premio → `reward.redemption.confirmed` | nessuna seconda `reward.redeemed` | R-27 | riconsegna.csv |
| TB-E2E-RED-011 | vincita coupon → `contest.won` | nessuna seconda `instantwin.won` | R-27 | riconsegna.csv |
| TB-E2E-RED-012 | vincita coupon → `coupon.issue` | nessun secondo coupon | R-27, reward §5 | riconsegna.csv |
| TB-E2E-RED-013 | amico → `referral.completed` REFERRER | nessun secondo premio | R-27 | riconsegna.csv |
| TB-E2E-RED-014 | iscrizione → `member.registered` | nessun secondo benvenuto, abbinamento o messaggio | R-27 | riconsegna.csv |
| TB-E2E-RED-015 | annullo PHY → `reward.redemption.cancelled` | un solo rimborso | R-27 | riconsegna.csv |
| TB-E2E-RED-016 | acquisto → `points.grant` PTS con **nuovo id** CloudEvents, stesso `effectId` | un solo movimento; nel tracciato solo la copia in più | R-27, docs/03 §3.5 p. 4 | riconsegna.csv |
| TB-E2E-RED-017 | acquisto → tutta la catena riconsegnata in ordine | stato identico | R-27 | riconsegna.csv |

## 13. FAIL — Handler che fallisce e riprova (US-E12-03)

Classe `TestbookE2eReliabilityIT` · `guasti.csv`. Guasto = le prossime N insert del membro falliscono (errore ritentabile).

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-FAIL-001 | wallet: 1 fallimento sul primo accredito dell'acquisto 200 € | ritentato; catena completa del primo acquisto; PTS 400, STS 200; nessuna DLQ; tracciato non FAILED | R-28 | guasti.csv |
| TB-E2E-FAIL-002 | wallet: 2 fallimenti (terzo tentativo = ultimo) | come FAIL-001 | R-28, Q-131 | guasti.csv |
| TB-E2E-FAIL-003 | wallet: 3 fallimenti | DLQ dopo 3 tentativi (`lh-wallet`, tipo `effect.points.grant`, `errorCode`); il consumer prosegue (STS e bonus accreditati): catena con `F:wallet.points.earned=2`; PTS 200; tracciato FAILED | R-28, docs/12 M0, insight §5 | guasti.csv |
| TB-E2E-FAIL-004 | ponte: 1 fallimento sul fatto `tier.upgraded` (acquisto 1 000 €) | ritentato; una sola azione interna e un solo bonus (catena di TIER-002); PTS 1 500 | R-28, R-01 | guasti.csv |
| TB-E2E-FAIL-005 | errore non ritentabile nel motore (flag demo `_poison`) | DLQ al primo tentativo (`lh-campaign`, `DEMO_POISON`); gli altri consumer elaborano (statistiche member); catena `A:app.login.daily=1`; tracciato FAILED | R-28, docs/10 §8 | guasti.csv |

## 14. CON — Azioni ravvicinate dello stesso membro (US-E12-04)

Classe `TestbookE2eReliabilityIT` · `concorrenza.csv`. Richieste HTTP in parallelo; catene dei tracciati sommate.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-CON-001 | Due acquisti da 600 € insieme | una sola salita a SILVER (una UPGRADE), un bonus, BDG-FIRST e BDG-SPENDER una volta; PTS 1 700, STS 1 200 | R-29, R-07 | concorrenza.csv |
| TB-E2E-CON-002 | Quattro acquisti da 10 € insieme | CMP-PURCHASE-BASE: 3 MATCH e 1 `LIMIT`; BDG-FIRST e BDG-TRIS una volta; PTS 330, STS 30 | R-29, R-15 | concorrenza.csv |
| TB-E2E-CON-003 | Due richieste RWD-COFFEE-5 insieme, saldo 500 | una FULFILLED e una REJECTED `INSUFFICIENT_BALANCE`; una sola SPEND; saldo 0 | R-29, R-22 | concorrenza.csv |
| TB-E2E-CON-004 | `ebill` e `directdebit` insieme | ACH-DIGITAL completato una volta, un BDG-DIGITAL, un +100; PTS 900, STS 350 | R-29, R-16 | concorrenza.csv |

## 15. CORE — Core loop e scenari (docs/12 M1–M2)

Dopo reset. Classe `TestbookE2ePersonasIT` · `personaggi.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-CORE-001 | Marco SILVER, 130 € mercoledì 10:00 | EARN 162 PTS e 130 STS `CMP-PURCHASE-BASE` con correlazione = id dell'azione; `campaignMultiplier` 1 | R-35, R-09 | personaggi.csv |
| TB-E2E-CORE-002 | Marco, 130 € sabato 10:00 | `campaignMultiplier` 2; 325 PTS | R-35 | personaggi.csv |
| TB-E2E-CORE-003 | Roberto BLOCKED, acquisto | REJECTED `MEMBER_NOT_ACTIVE`; niente sul topic; riga nel monitor (BO-26) | R-35 | personaggi.csv |
| TB-E2E-CORE-004 | `SCN-DUPLICATE` | secondo passo DUPLICATE; catena del solo primo invio; EARN 43/35 una volta | R-34, Q-130 | personaggi.csv |
| TB-E2E-CORE-005 | `SCN-WEEKEND-BURST` | 12 azioni ACCEPTED e 12 valutazioni; almeno un ×2; almeno un `LIMIT` di CMP-PURCHASE-BASE | R-34, docs/12 M2 | personaggi.csv |

## 16. ONB, SMK — Primo giorno e prova di fumo (US-E10-16, US-E11-09)

Dopo reset. Classe `TestbookE2ePersonasIT` · `personaggi.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-ONB-001 | `SCN-ONBOARDING` (Anna BASE) | +5 (`CMP-APP-DAILY`), +150 (`CMP-PROFILE`, passo simulato Q-63), acquisto 24,90 € → 24/24 e BDG-FIRST → +100 in un solo tracciato; Anna 379/24; profilo ancora incompleto | R-34, R-16, Q-63 | personaggi.csv |
| TB-E2E-ONB-002 | `SCN-ONBOARDING` due volte | accesso e profilo `LIMIT`; acquisto +24/+24 senza badge; Anna 403/48 | R-15, R-34 | personaggi.csv |
| TB-E2E-SMK-001 | `SCN-SMOKE` | Marco +5 entro 15 s; catena fino a `wallet.points.earned` | R-34, docs/12 §4 | personaggi.csv |
| TB-E2E-SMK-002 | `SCN-SMOKE` due volte | seconda: `campaign.evaluated` con CMP-APP-DAILY `LIMIT`, nessun punto | R-34, R-15 | personaggi.csv |

## 17. EXP — Punti in scadenza (US-E10-13)

Dopo reset (Chiara: lotto di 1 900 PTS con scadenza `@today+12d`). Classe `TestbookE2ePersonasIT` · `personaggi.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-EXP-001 | Preavvisi con `asOf` = oggi | un `wallet.points.expiring` di 1 900 per Chiara; un MSG-POINTS-EXPIRING in più | R-32 | personaggi.csv |
| TB-E2E-EXP-002 | Preavvisi due volte | ancora un solo preavviso e un solo messaggio | R-32 | personaggi.csv |
| TB-E2E-EXP-003 | Scadenze con `asOf` = oggi + 11 gg | nessun EXPIRE; Chiara 2 400 | R-32, Q-156 | personaggi.csv |
| TB-E2E-EXP-004 | Scadenze + 11 gg poi + 12 gg (data pura = fine giornata) | un EXPIRE PTS 1 900; `wallet.points.expired` 1 900 con `balanceAfter` 500; proiezione member 500; portale 500 | R-32, Q-156 | personaggi.csv |
| TB-E2E-EXP-005 | Scadenze con `asOf` = oggi + 31 gg | come EXP-004 (docs/12 M3) | R-32 | personaggi.csv |

## 18. RST — Reset della demo (US-E11-07)

Classe `TestbookE2ePersonasIT` · `personaggi.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-RST-001 | Reset ADMIN | i 12 membri a stato/livello/PTS/STS di docs/10 §2 (tabella nel CSV) | R-33 | personaggi.csv |
| TB-E2E-RST-002 | Due reset consecutivi | stessi codici coupon (e stato), stessi istanti vincenti, stessi saldi, stessi membri e richieste | R-33, docs/10 §1.3 | personaggi.csv |
| TB-E2E-RST-003 | `SCN-TIER-UP`, reset, `SCN-TIER-UP` | fra le due Giulia torna a SILVER 3 240/2 880; stessi movimenti, catena, saldi e messaggi | R-33 | personaggi.csv |
| TB-E2E-RST-004 | Membro nuovo iscritto, poi reset | sparisce da ogni servizio: member, wallet, indice ingestion, snapshot campaign/reward/gamification/engagement, classifiche, inbox — **divergenza** (§21) | R-33 | personaggi.csv |
| TB-E2E-RST-005 | Reset con MARKETING e senza intestazione | 403 entrambi; il membro nuovo esiste ancora | R-33, docs/08 §2 | personaggi.csv |

## 19. TIM — Tempo di business su Europe/Rome

«Oggi» = lunedì 02/11/2026. Membro nuovo per riga; acquisti da 100 € (feriale 100 PTS, weekend 200). Colonne del CSV: eventi (ora locale di Roma o istante UTC con `Z`), atteso sull'ultimo evento, scadenza del lotto, punteggi del mese. Classe `TestbookE2eBusinessTimeIT` · `tempo.csv`.

| ID | condizioni/valori | atteso (da spec) | rif. spec | test |
|---|---|---|---|---|
| TB-E2E-TIM-001 | ven 30/10 23:59:59 | 100 PTS; CMP-WEEKEND-X2 `CONDITION` | R-30 | tempo.csv |
| TB-E2E-TIM-002 | sab 31/10 00:00:00 (UTC venerdì) | ×2: 200 PTS | R-30 | tempo.csv |
| TB-E2E-TIM-003 | dom 25/10 23:59:59 CET (giorno del cambio) | 200 PTS | R-30 | tempo.csv |
| TB-E2E-TIM-004 | lun 26/10 00:00:00 CET (UTC domenica) | 100 PTS; `CONDITION` | R-30 | tempo.csv |
| TB-E2E-TIM-005 | 25/10: 00:05 CEST, 02:30 CEST, 02:30 CET, 23:55 CET | tre pagati, il quarto `LIMIT` | R-30, R-15 | tempo.csv |
| TB-E2E-TIM-006 | tre il 25/10 + lun 26/10 00:05 CET | il quarto pagato (nuovo giorno) | R-30, R-15 | tempo.csv |
| TB-E2E-TIM-007 | mar 27/10 00:00:00, 08:00, 12:00, 23:59:59 | il quarto `LIMIT` (un giorno di Roma su due date UTC) | R-30, R-15 | tempo.csv |
| TB-E2E-TIM-008 | tre il 27/10 + mer 28/10 00:00:00 | il quarto pagato | R-30, R-15 | tempo.csv |
| TB-E2E-TIM-009 | sab 31/10 23:59:59 CET | lotto in scadenza 31/10/2027 23:59:59.999999 Roma; classifica 2026-10 con i 200 dell'acquisto e ACH-3 nel periodo 2026-10; il bonus BDG-FIRST cade nel mese di elaborazione (2026-11) con il benvenuto (**AMBIGUO**) | R-30, R-31 | tempo.csv |
| TB-E2E-TIM-010 | dom 01/11 00:00:00 CET (UTC 31/10) | lotto in scadenza 30/11/2027 23:59:59.999999 Roma; classifica 2026-11 = 400; ACH-3 in 2026-11 | R-30, R-31 | tempo.csv |
| TB-E2E-TIM-011 | accessi lun 26/10 00:00:00 e 23:59:59 | il secondo `LIMIT` (CMP-APP-DAILY) | R-30, R-15 | tempo.csv |
| TB-E2E-TIM-012 | accessi lun 26/10 23:59:59 e mar 27/10 00:00:00 | entrambi +5 | R-30 | tempo.csv |
| TB-E2E-TIM-013 | accessi in 7 giorni di Roma consecutivi 22…28/10 alternando 00:30 e 23:30 (date UTC non consecutive) | ACH-STREAK-7 → BDG-STREAK → +100 dal ponte nell'ultimo tracciato; PTS 235 | R-30, R-16 | tempo.csv |

## 20. Storie di docs/17 coperte

| Storia | Righe |
|---|---|
| US-E10-03 | TIER-001…008 |
| US-E10-04 | CPN-001…004 |
| US-E10-05 | PHY-001…004 |
| US-E10-06 | INS-001, INS-002, CON-003 |
| US-E10-07 | IW-001…007 |
| US-E10-08 | REG-001, REG-006…009 |
| US-E10-13 | EXP-001…005 |
| US-E10-14 | DIG-001…004, CON-004 |
| US-E10-15 | REF-001…006, REG-006 |
| US-E10-16 | ONB-001, ONB-002 |
| US-E11-07 | RST-001…005 |
| US-E11-09 | SMK-001, SMK-002 |
| US-E01-06 | REG-002…005 |
| US-E02-05 | ANO-001…007 |
| US-E12-02 | RED-001…017 |
| US-E12-03 | FAIL-003, FAIL-005 |
| US-E12-04 | CON-001…004 |
| docs/12 M1–M7 (tra servizi) | CORE-001…005, TIER-007, EXP-005, SAG-001…002, CPN-004, IW-007, DIG-004, ANO-001 |

## 21. Registro delle divergenze

| N. | Riga | Specifica | Comportamento osservato | Causa (file:riga) | Esito |
|---|---|---|---|---|---|
| D-1 | TB-E2E-RST-004 | docs/10 §1.3: «`POST /v1/demo/reset` riporta ogni servizio esattamente a questo stato»; US-E11-07 crit. 1 | Dopo il reset il membro iscritto dopo il seed è sparito da member, wallet e da tutti gli snapshot, ma resta in `ingestion.member_index` (ACTIVE, con l'e-mail): un'azione `member:<id>` o `email:<x>` viene ancora risolta e accettata dall'ingresso per un membro che non esiste più | `services/ingestion-service/src/main/java/io/loyaltyhub/ingestion/demo/DemoSeeder.java:75-78` (`resetToSeed` chiama `seedMembers()`, righe 122-130, che fa solo *upsert* dei membri del seed; `MemberIndexRepository.deleteAll()` esiste ma non è chiamato) | aperta |

## 22. Ambiguità

Righe che asseriscono la scelta di oggi (commento `// TESTBOOK: ambiguo, vedi <ID>` nel test); da registrare in docs/15:

- **TB-E2E-REG-009** — e-mail già iscritta: US-E10-08 dice «errore sul campo» senza stato né codice; oggi 409 `EMAIL_TAKEN` (il test verifica 4xx e l'assenza di effetti).
- **TB-E2E-TIER-001** (asserzione accessoria in TIER-001…006) — livello nello snapshot di engagement di un membro senza fatti `tier.*`: oggi nullo (campaign e reward: BASE); il test lo legge come BASE. Un contenuto col pubblico `tiers:[BASE]` escluderebbe un membro appena iscritto.
- **TB-E2E-SAG-004** — annullo del membro mentre il wallet dorme: reward §5 descrive la compensazione della spesa tardiva solo dopo il timeout; oggi vale anche dopo l'annullo (`cancelled` LATE_SPEND con rimborso). Il test verifica solo l'invariante (nessun addebito netto) e lo stato finale.
- **TB-E2E-TIM-009** — `time` del bonus badge: docs/05 §2 lega il `time` dei derivati a quello della radice «quando rappresentano la stessa data di business (accrediti)»; oggi `badge.awarded` (e quindi il bonus) porta l'istante di elaborazione, così un acquisto delle 23:59:59 dell'ultimo giorno del mese mette il bonus nella classifica del mese dopo.
- **Catene (tutte)** — `achievement.progressed` non è contato: gamification §5 lo emette «solo al cambio di valore» senza dire se anche al completamento.

## 23. Note per l'esecuzione e regole non implementate

- **Rapporto**: `scripts/testbook-report.mjs` riconosce gli ID con `TB-[A-Z]{3}…`; il dominio `E2E` contiene una cifra, quindi oggi le righe `TB-E2E-…` non sono né contate né abbinate agli esiti. Serve `TB-[A-Z0-9]{3}` nelle tre espressioni dello script (righe 10, 21, 42); il rapporto riportato con questo documento è stato prodotto con quella correzione applicata a una copia locale dello script.
- **Regola non implementata (candidata)**: docs/05 §3 EVT-ACT-20 prevede `data.referred (bool)` nell'azione `member.registered`, ma il ponte copia i campi omonimi del fatto, che ha `referredBy` e non `referred`: il campo non compare (contraddizione interna di docs/05, non provata come riga).
- **Non provati qui** (rami di un solo servizio, coperti nei domini): mappatura del ponte disabilitata e `LOOP_GUARD` (TB-ING), pool coupon vuoto e `needsAttention` (TB-RWD), cambio dell'ora di marzo (TB-CMP/TB-WAL, fuori dalla finestra di 30 gg con un solo orologio), bus senza sottoscrittori e DLQ della DLQ (TB-PLT).

## 24. Copertura

| Voce | Valore |
|---|---|
| Regole inventariate | 35 (R-01…R-35) |
| Rami del codice mappati | 38 punti di decisione (§2: 10 dell'hub, 28 cerniere tra servizi); 31 provati da almeno una riga |
| Righe di testbook | 114 (REG 9 · TIER 8 · REF 6 · DIG 4 · IW 7 · ANO 7 · CPN 4 · INS 2 · PHY 4 · SAG 5 · RED 17 · FAIL 5 · CON 4 · CORE 5 · ONB 2 · SMK 2 · EXP 5 · RST 5 · TIM 13) |
| Combinazioni ridotte | TIER: soglie × moltiplicatori → 5 limiti presi da soli (tabella completa in TB-WAL); REG abbinamento 3 classi complete + molteplicità; SAG: stato × evento 7 × 7 (TB-RWD) → 5 percorsi col wallet fermo + limiti del timeout; TIM: confini di mezzanotte/cambio dell'ora/fine mese presi da soli (13), marzo escluso; RED: un messaggio per consumer (15) + nuovo id + catena intera |
| Rami senza specifica | 4 (H-06, H-10, X-06, X-15 per l'annullo del membro) |
| Regole non implementate | 1 candidata (`referred` in EVT-ACT-20, §23) |
| Righe ambigue | 4 (§22) + la convenzione sui `achievement.progressed` |
| Divergenze aperte | 1 (§21: TB-E2E-RST-004, reset di ingestion) |
| Verifica a mutazione | 5 mutazioni, tutte rilevate: periodo dei limiti in UTC (`PeriodKeys`) → 6 righe TIM; niente compensazione della spesa tardiva (`RedemptionService`) → SAG-002, SAG-004; bus senza ritentativi (`HubInProcessBus`) → FAIL-001…004; senza idempotenza su `effectId` (`WalletService`) → RED-016; salita anche a pari livello (`WalletService`) → 19 righe tra TIER, REF, DIG, REG, ONB, CORE |
