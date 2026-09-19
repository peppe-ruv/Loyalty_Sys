# 12 — Piano di sviluppo

Otto milestone, ciascuna **dimostrabile da sola**. Si lavora a **fette verticali**: mai "tutto il backend poi tutto il frontend". Una milestone è chiusa quando i suoi criteri sono verdi, la *Definizione di fatto* di `CLAUDE.md §6` è rispettata e `docs/14` è aggiornato.

## 1. Regole di lavoro per Claude Code
1. Una sessione = una **fetta** (tabella della milestone). All'inizio: leggere `CLAUDE.md`, `docs/14`, i documenti indicati dalla fetta. Alla fine: test verdi, `docs/14` aggiornato, commit con ID.
2. Ordine dentro una fetta: migrazione → dominio + unit test → casi d'uso → messaggistica + test d'integrazione → API → seed → tipi e hook frontend → schermata → stati di `docs/07 §6`.
3. Dubbio o conflitto tra specifiche → voce `Q-nn` in `docs/15`, scelta conservativa, marcatore `// SPEC-GAP: Q-nn`. Mai inventare confini, topic o tipi evento.
4. Niente anticipi: una schermata la cui milestone non è arrivata **non compare** nel menu.
5. Ogni nuovo `type` evento: schema + esempio in `contracts/` **prima** del codice.

## 2. Vista d'insieme

| M | Titolo | Risultato visibile | Servizi | Schermate |
|---|---|---|---|---|
| M0 | Fondamenta | repo che compila, servizio archetipo che parla con Kafka e DB, shell web con Demo Hub | `lh-common`, archetipo | HUB-01 (stato) |
| M1 | Core loop | un'azione simulata diventa punti sul portale — **anche online** | ingestion, member, campaign, wallet | BO-02, 03 (base), 05, 06, 09 (lettura), 26, 28, 30 (stato + reset) · PT-01, 02, 07, 14 |
| M2 | Visibilità | flusso live, tracciati, dashboard, audit, scenari | insight | BO-01, 22, 24, 25, 29 · rail eventi |
| M3 | Punti adulti | lotti, scadenze, pending, tier, edizioni, ponte interno | wallet, ingestion, campaign | BO-07, 08, 09 (ponte), 30 (job) · PT-08 |
| M4 | Premi | catalogo a fasce, richiesta premio (saga), coupon | reward, wallet | BO-10–13 · PT-03, 04, 13 |
| M5 | Gioco | instant win, obiettivi, badge, classifiche, referral, registrazione | gamification, member | BO-14–17 · PT-05, 06, 09, 10, 11 |
| M6 | Contenuti | CMS, pop-up, messaggi, tema, segmenti, attributi, tipi custom | engagement, member, ingestion | BO-04, 18–20 · PT-12, contenuti in PT-01/03/05/06 |
| M7 | Governance | approvazioni, webhook, DLQ riprocessa, non abbinati, anonimizzazione | tutti | BO-21, 23, 27 |

## 3. Milestone

### M0 — Fondamenta
| Fetta | Contenuto | Leggi |
|---|---|---|
| M0.1 | Monorepo: parent POM (Java 25, Boot 4.1.x, gestione versioni), wrapper, `.editorconfig`, `LICENSE`, `.gitignore`, `.dockerignore`, struttura cartelle di `CLAUDE.md §3` | `CLAUDE.md`, `docs/04 §6–7` |
| M0.2 | `libs/lh-common`: envelope CloudEvents, `OutboxWriter`/`OutboxRelay`, `processed_event`, `EventRouter`, retry + DLQ, errori RFC 9457, `X-LH-Actor` + `@RequiresRole`, `SeedLoader` + `SeedDates`, configurazione Kafka `PLAINTEXT/SSL_PEM/SASL_SSL` | `docs/05 §2`, `docs/06` |
| M0.3 | `contracts/events/`: envelope + schemi ed esempi degli eventi di M1; test di contratto | `docs/05` |
| M0.4 | `deploy/docker-compose.yml`, profilo `local` che crea i 5 topic | `docs/11 §9` |
| M0.5 | **Servizio archetipo** = `ingestion-service` ridotto: `POST /v1/events` → outbox → `lh.actions.v1`; un consumer di prova; Flyway; Actuator; Dockerfile | `docs/servizi/ingestion-service.md` |
| M0.6 | `web/`: Next.js, token, font, shell delle tre aree, proxy `/api/lh`, cookie persona, `/api/demo/status|wake`, HUB-01 con pannello stato | `docs/07` |
| M0.7 | CI (`backend`, `web`, `seed`, `contracts`), `scripts/check-seed.mjs` (scheletro), `seed/_schemas/` | `docs/11 §10`, `docs/10 §11` |

**Accettazione**
- *Dato* il compose attivo, *quando* invio un CloudEvent valido a `POST /v1/events`, *allora* ricevo `202` e il messaggio è sul topic con chiave = `subject` entro 2 s.
- *Dato* lo stesso `id` inviato due volte, *allora* la seconda risposta è `202` con `status=DUPLICATE` e sul topic c'è un solo messaggio.
- *Dato* un consumer che lancia sempre eccezione, *allora* dopo 3 tentativi il messaggio è su `lh.dlq.v1` con `errorCode` e il consumer prosegue.
- *Dato* Kafka irraggiungibile durante una scrittura, *allora* la riga resta in `outbox` e viene pubblicata al ritorno di Kafka (nessuna perdita).
- *Dato* il Demo Hub aperto con servizi spenti, *allora* ogni tessera mostra `DOWN/SLEEPING` senza errori in pagina.
- Immagine Docker dell'archetipo: parte con `-m 512m` e RSS ≤ 450 MB dopo 2 minuti.

**Verifica**: `./mvnw verify` · `pnpm lint typecheck test build` · `docker compose --profile all up` · CI verde.

**Prompt**
> Leggi `CLAUDE.md`, `docs/04`, `docs/05`, `docs/06`. Realizza la fetta **M0.2** (`libs/lh-common`) esattamente come in `docs/06 §1`. Scrivi prima i test (Testcontainers Kafka + Postgres) per outbox, idempotenza e DLQ; poi l'implementazione. Non creare nessun servizio in questa sessione. Alla fine aggiorna `docs/14`.

### M1 — Core loop (e primo deploy)
| Fetta | Contenuto |
|---|---|
| M1.1 | ingestion completo per M1: fonti, tipi azione con JSON Schema, validazione, risoluzione membro da `member_index`, esiti, seed |
| M1.2 | member: anagrafica, stati, ricerca, `member.registered/updated/status.changed`, seed 12 membri |
| M1.3 | campaign: modello, ciclo di vita (senza approvazione), condizioni (`data/member/context`), effetti `GRANT_POINTS` (`FIXED`, `PER_AMOUNT`) e `MULTIPLIER`, limiti per membro, calendario, pubblico per tier, `campaign.evaluated` + registro valutazioni, **simulazione** (`POST /campaigns/{id}/simulate`), elenco "come guadagnare" per il portale; seed completo (le campagne con effetti non ancora supportati restano caricate ma **non valutate**, con motivo `EFFECT_NOT_SUPPORTED_YET` nel log) |
| M1.4 | wallet: wallet per valuta, `points.grant` idempotente, ledger, saldo, API portale. `F-TIER-03` è **anticipata in forma minima**: il moltiplicatore si legge dalle tabelle `tier`/`member_tier` popolate dal seed; gestione dei livelli, salita e storico restano in M3 |
| M1.5 | BO-02, BO-03 (Panoramica, Movimenti, Azioni), BO-05, BO-06 (editor + simulazione), BO-09 (sola lettura: tipi e fonti), BO-26, BO-28, BO-30 (stato + reset orchestrato) |
| M1.6 | PT-01 (tessera, saldo, ultimi movimenti), PT-02, PT-07, PT-14 con schema "in elaborazione" a **polling** (l'SSE arriva in M2) |
| M1.7 | `POST /v1/demo/reset` nei 4 servizi, `scripts/smoke.sh`, `wake.sh` |
| M1.8 | **Deploy**: Aiven (manuale), Neon, Render (4 servizi), Vercel — `docs/11 §2` |

**Accettazione**
- *Dato* Marco (SILVER), *quando* dal simulatore invio `purchase.completed` di 130 € in un giorno feriale, *allora* entro 8 s (p95, servizi svegli) il ledger ha `EARN 162 PTS` e `EARN 130 STS` con `campaignCode=CMP-PURCHASE-BASE` e lo stesso `correlationId` dell'azione.
- *Stesso acquisto di sabato* → `campaignMultiplier=2`, 325 PTS (130 × 2 × 1,25 arrotondato per difetto).
- *Quarto acquisto nello stesso giorno* → `campaign.evaluated.skipped[]` con `reason=MEMBER_LIMIT_REACHED`, nessun movimento.
- *Membro `BLOCKED`* (Roberto) → ingestion `REJECTED (MEMBER_NOT_ACTIVE)`, niente sul topic; la riga compare in BO-26.
- *Simulazione* di `CMP-WEEKEND-X2` con un evento di martedì → `NO_MATCH`, con la condizione di calendario marcata ✗; nessun evento emesso.
- *Portale*: dopo l'invio da PT-14 compare "in arrivo…", poi il saldo sale con count-up; nessun aggiornamento ottimistico.
- *Servizio wallet fermo* → PT-01 mostra la sezione saldo *degraded*, il resto funziona; al riavvio l'arretrato viene elaborato e il saldo è corretto (RNF-06).
- *Reset* → i 12 membri tornano allo stato di `docs/10`; `check-seed` verde.
- **Online**: dal Demo Hub pubblico, "Accendi la demo" porta 6/10 verdi (4 servizi + Kafka + DB) e `smoke.sh` passa.

**Prompt**
> Leggi `CLAUDE.md`, `docs/14`, `docs/servizi/wallet-service.md`, `docs/03 §4`, `docs/05`. Realizza la fetta **M1.4**. Limiti: niente lotti, scadenze, tier-up né edizioni (sono M3) — ma le tabelle `points_lot` e `member_tier` si creano già ora secondo la scheda, così M3 non richiede migrazioni distruttive. Test d'integrazione obbligatori: accredito, idempotenza su `effect_id`, membro senza wallet.

### M2 — Visibilità
Fette: **M2.1** insight ingest + event store + retention · **M2.2** SSE + `lib/realtime` + rail eventi + BO-24 · **M2.3** tracciati + BO-25 + passaggio di `usePendingTrace` da polling a SSE · **M2.4** `metric_daily`, storico sintetico, BO-01 · **M2.5** audit end-to-end (`lh.audit.v1` da tutti i servizi esistenti) + BO-22 · **M2.6** statistiche campagna in BO-05 (F-CMP-10) · **M2.7** scenari (`seed/scenarios.json`, esecutore in ingestion) + BO-29 · **M2.8** deploy di insight (con CORS per l'SSE).

**Accettazione**
- Un acquisto produce ≥ 4 righe nel rail entro 3 s, colorate per topic, con lo stesso `correlationId`.
- BO-25 mostra l'albero azione → valutazione → 2 effetti → 2 fatti, con tempi e l'esito "+162 PTS, +130 STS" (i messaggi si aggiungono in M6).
- `SCN-WEEKEND-BURST` eseguito da BO-29: 12 azioni, avanzamento visibile, nessuna perdita (12 valutazioni nell'event store).
- `SCN-DUPLICATE` → BO-26 mostra `DUPLICATE`; un solo movimento.
- `SCN-POISON` → voce DLQ, tracciato `FAILED`.
- Modifica di una campagna da BO-06 → riga in BO-22 con diff prima/dopo e attore `MARKETING:luca.marketing`.
- BO-01 dopo un reset: nessun grafico vuoto.
- SSE interrotto → dopo 3 tentativi l'UI passa a polling e lo dichiara.

### M3 — Punti adulti
Fette: **M3.1** lotti, scadenza `ROLLING_MONTHS`, `pendingDays` e rilascio (il consumo FIFO arriva con la spesa, in M4) · **M3.2** job (scadenze, preavvisi, rilascio) con `asOf` + sezione *Job* di BO-30 · **M3.3** tier: salita immediata, `tier.upgraded`, moltiplicatore da tabella, BO-07, PT-08 (livello) · **M3.4** edizioni e chiusura con discesa morbida (`dryRun`), BO-08 · **M3.5** **ponte interno** in ingestion (`internal_mapping`, `lhhop`, `LOOP_GUARD`), scheda *Ponte* di BO-09, campagna `CMP-TIER-UP-BONUS` · **M3.6** rettifiche manuali (BO-03) · **M3.7** cumulabilità (priorità, gruppo esclusivo — F-CMP-07), modalità `LOOKUP` e `FROM_FIELD`, spazio `history.*`; la simulazione li copre · **M3.8** `POST /v1/transactions` (F-ING-07, P1) · **M3.9** passività e `/v1/liability`.

**Accettazione**
- `SCN-TIER-UP`: Giulia passa a GOLD; il fatto `tier.upgraded` rientra come azione (`lhhop=1`, `source=internal`) e `CMP-TIER-UP-BONUS` accredita 500 PTS; nel tracciato è **un solo albero**.
- Catena artificiale con `lhhop` > 3 → DLQ `LOOP_GUARD`.
- Job scadenze con `asOf` = +31 giorni → Chiara perde 1 900 PTS (`EXPIRE`), fatto `wallet.points.expired`, saldo aggiornato nel portale.
- Chiusura edizione in `dryRun` come da `wallet-service.md §7` (Stefano → SILVER).
- Due campagne nello stesso `exclusiveGroup` → scatta solo quella a priorità più alta; l'altra è in `skipped[]` con `reason=EXCLUSIVE_GROUP`.
- Campagna `LIVE`: i campi bloccati non sono modificabili né da UI né da API (`409`).

### M4 — Premi
Fette: **M4.1** reward: categorie, fasce, premi, stock, visibilità per tier, BO-10/11 · **M4.2** pool coupon, generazione con seme, BO-12 · **M4.3** saga richiesta premio (reward ↔ wallet), timeout, compensazione · **M4.4** evasione manuale, annullo con rimborso, BO-13 · **M4.5** PT-03, PT-04, PT-13 · **M4.6** ponte `reward.redemption.confirmed` → azione `reward.redeemed`.

**Accettazione**: i 7 punti di `reward-service.md §7` + spesa **FIFO** sui lotti come da `wallet-service.md §7` + E2E n. 2 di `docs/09 §4` + tracciato della saga leggibile in BO-25 (richiesta → spesa → conferma → coupon → messaggio) + wallet fermo durante la richiesta → `PENDING`, poi `CONFIRMED` al riavvio se entro 10 min, altrimenti `REJECTED (TIMEOUT)` con stock ripristinato.

### M5 — Gioco
Fette: **M5.1** concorsi, montepremi, generatore istanti con seme, BO-14 · **M5.2** giocata (claim atomico), crediti, effetto `GRANT_PLAYS`, PT-05/06 con ruota · **M5.3** consegna vincite via ponte + campagne di sistema, effetto `ISSUE_COUPON` (reward) · **M5.4** obiettivi (4 metriche), badge, effetto `AWARD_BADGE`, BO-15, PT-09 · **M5.5** classifiche, BO-16, PT-10 · **M5.6** referral, registrazione dal portale (`/portal/join`), BO-17, PT-11 · **M5.7** *pianta un istante*.

**Accettazione**: i 7 punti di `gamification-service.md §7` + `SCN-REFERRAL` + `SCN-ONBOARDING` (catena acquisto → obiettivo → badge → bonus in un solo tracciato) + E2E n. 3.

### M6 — Contenuti
Fette: **M6.0** engagement: regole di notifica, template, inbox, consumo dell'effetto `message.send` · **M6.1** contenuti + selezione per posizionamento + BO-18 con anteprima in cornice telefono · **M6.2** pop-up e frequenze · **M6.3** card vincita in PT-06 · **M6.4** PT-12, BO-19 completo, effetto `SEND_MESSAGE` · **M6.5** tema a runtime, BO-20 · **M6.6** segmenti dinamici, ricalcolo, pubblico di campagne/premi/contenuti, BO-04 · **M6.7** attributi personalizzati e tipi azione **custom** (BO-09) utilizzabili nel costruttore di condizioni.

**Accettazione**: i 5 punti di `engagement-service.md §7` + E2E n. 1 completo (notifica di salita di livello in PT-12) + un tipo azione custom creato da BO-09 è inviabile da BO-28, selezionabile in BO-06 e produce punti senza ridistribuire nulla + un contenuto riservato a `SEG-DIGITAL` compare a Marco dopo `SCN-DIGITAL` e ricalcolo.

### M7 — Governance
Fette: **M7.1** `LH_APPROVAL_ENABLED=true`, policy, BO-21, transizioni per ruolo · **M7.2** webhook con HMAC e ritenti, BO-23 · **M7.3** DLQ *riprocessa/scarta*, BO-27 · **M7.4** eventi non abbinati: abbina e reinvia (BO-26) · **M7.5** anonimizzazione membro (propagata via `member.updated`/`status.changed`: ogni servizio cancella i dati personali dal proprio snapshot) · **M7.6** versioni e duplica per campagne/premi/concorsi.

**Accettazione**
- `luca.marketing` non può portare un concorso a `LIVE`: solo *Invia in revisione*; `elena.legal` approva con commento; storico visibile; tutto in audit.
- Rifiuto senza commento → `422`.
- Webhook verso endpoint che fallisce → 3 ritenti, `GAVE_UP`, *Riprova* manuale funziona; firma verificata da uno script d'esempio in `deploy/webhook-receiver/`.
- Anonimizzazione di un membro di prova: nessun servizio espone più nome/e-mail (test che interroga tutte le API di gestione); i movimenti restano.

## 4. Test E2E e fumo
- `scripts/smoke.sh <base>`: sveglia → `SCN-SMOKE` (`app.login.daily` per Marco, `docs/10 §8`) → attende +5 PTS sul saldo (da M2: `wallet.points.earned` sul tracciato) ≤ 15 s a servizi svegli → esce ≠ 0 se fallisce. Lo scenario usa un `id` evento nuovo a ogni esecuzione; se il limite giornaliero di `CMP-APP-DAILY` è già scattato, lo script esegue prima il reset di campaign e wallet **solo in locale/CI**, mentre in demo verifica `campaign.evaluated` (scattata o `MEMBER_LIMIT_REACHED`) come prova che la pipeline è viva.
- Playwright: i 3 percorsi di `docs/09 §4`, più "cambio persona → permessi" (MARKETING non vede la Console demo; LEGAL vede gli istanti).

## 5. Rischi di piano
| Rischio | Segnale | Risposta |
|---|---|---|
| Avvio JVM troppo lento su 0,1 CPU | > 180 s a servizio | cache AOT/CDS (`docs/11 §5`); ridurre auto-configurazioni; ultima risorsa: accorpare insight+engagement in un solo *deployable* mantenendo i moduli separati (nuova ADR) |
| 750 ore/mese insufficienti | demo spesso accesa | secondo workspace o `LH_JOBS_ENABLED=false` + spegnimento dei servizi non necessari alla demo del giorno |
| Kafka gratuito ritirato | avviso del fornitore | piano B di `docs/11 §3` |
| Minuti di build Render esauriti | build in coda | piano B immagini GHCR |
| Deriva delle specifiche | `SPEC-GAP` che si accumulano | revisione di `docs/15` a ogni chiusura di milestone |
