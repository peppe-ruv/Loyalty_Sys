# 02 — Catalogo funzionale

Ogni riga è una feature verificabile. Colonne: **Pri** (P0 PoC · P1 affinamento · P2 target) · **Svc** servizio proprietario · **UI** schermate (`BO-xx` backoffice, `PT-xx` portale, vedi `docs/08`, `docs/09`) · **M** milestone (`docs/12`). Le regole di dettaglio sono in `docs/03`; qui c'è il *cosa*.

Sigle servizi: `ING` ingestion · `MBR` member · `CMP` campaign · `WAL` wallet · `RWD` reward · `GAM` gamification · `ENG` engagement · `INS` insight.

## 1. Feature per area

### Ingresso azioni (`F-ING`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-ING-01 | Ricezione CloudEvents | `POST /v1/events` accetta un CloudEvent JSON; valida envelope, fonte abilitata, tipo noto, schema `data`; risponde `202` con esito | P0 | ING | BO-26 | M1 |
| F-ING-02 | Deduplica | stesso `source`+`id` già visto → esito `DUPLICATE`, nessuna pubblicazione | P0 | ING | BO-26 | M1 |
| F-ING-03 | Risoluzione membro | `subject` = `member:<id>` oppure `external:<externalId>` oppure `email:<email>`; risolto su indice locale; membro non attivo → `REJECTED` | P0 | ING | BO-26 | M1 |
| F-ING-04 | Eventi non abbinati | membro sconosciuto → `UNMATCHED`, parcheggiato; abbinamento manuale o automatico alla registrazione del membro | P1 | ING | BO-26 | M7 |
| F-ING-05 | Registro fonti | fonti con codice, nome, tipi ammessi, stato; fonte disabilitata → `REJECTED` | P0 | ING | BO-09 | M1 |
| F-ING-06 | Tipi azione e schemi | tipi di sistema (seed) e **custom** creati da backoffice con JSON Schema dei campi; i campi alimentano il costruttore di condizioni | P0 | ING | BO-09, BO-06 | M1 (custom: M6) |
| F-ING-07 | Transazioni d'acquisto | `POST /v1/transactions` (ordine con righe) convertito in azione `purchase.completed`; reso → `purchase.returned` | P1 | ING | BO-26 | M3 |
| F-ING-08 | Ponte azioni interne | fatti selezionati (`tier.upgraded`, `contest.won`, `badge.awarded`, …) ripubblicati come azioni su `lh.actions.v1`, fonte `internal`, con anti-loop | P0 | ING | BO-09 | M3 |
| F-ING-09 | Monitor ingressi | elenco eventi ricevuti con esito, filtro, dettaglio payload, *riprova* | P0 | ING | BO-26 | M1 |
| F-ING-10 | Invio batch | `POST /v1/events/batch` fino a 100 eventi, esito per elemento | P2 | ING | — | — |

### Membri (`F-MBR`) e segmenti (`F-SEG`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-MBR-01 | Anagrafica membro | creazione, modifica, ricerca (nome, e-mail, ID, externalId), filtri per stato/tier/etichette | P0 | MBR | BO-02 | M1 |
| F-MBR-02 | Scheda 360° | unica vista con profilo, saldi, tier e progresso, movimenti, azioni ricevute con spiegazione, richieste premio, coupon, giocate, badge, messaggi, tracciati | P0 | MBR + altri | BO-03 | M1→M6 |
| F-MBR-03 | Attributi custom ed etichette | coppie chiave/valore tipizzate + etichette libere, usabili in segmenti e condizioni | P1 | MBR | BO-03 | M6 |
| F-MBR-04 | Stati del membro | `ACTIVE`, `INACTIVE`, `BLOCKED`, `ANONYMIZED`; solo `ACTIVE` accumula e spende | P0 | MBR | BO-03 | M1 |
| F-MBR-05 | Anonimizzazione | sostituisce i dati personali con segnaposto, conserva i movimenti | P1 | MBR | BO-03 | M7 |
| F-MBR-06 | Registrazione dal portale | form minimo (nome, e-mail, codice amico opzionale) → nuovo membro + azione `member.registered` | P1 | MBR | PT-08, HUB-01 | M5 |
| F-MBR-07 | Completamento profilo | quando tutti i campi richiesti sono valorizzati → fatto `member.profile.completed` (una volta) | P1 | MBR | PT-08 | M5 |
| F-MBR-08 | Compleanno | job giornaliero → fatto `member.birthday` | P2 | MBR | — | — |
| F-SEG-01 | Segmenti statici | elenco manuale di membri | P1 | MBR | BO-04 | M6 |
| F-SEG-02 | Segmenti dinamici | criteri su tier, stato, anzianità, attributi, etichette, saldo, attività, acquisti nel periodo; anteprima conteggio + campione | P1 | MBR | BO-04 | M6 |
| F-SEG-03 | Ricalcolo e fatti | ricalcolo su richiesta e periodico; ingressi/uscite emessi come fatti e usati da campagne, premi, contenuti | P1 | MBR | BO-04 | M6 |

### Campagne e motore regole (`F-CMP`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-CMP-01 | CRUD campagne | nome, descrizione, tipi azione trigger, pubblico, condizioni, effetti, limiti, calendario, priorità | P0 | CMP | BO-05, BO-06 | M1 |
| F-CMP-02 | Ciclo di vita | `DRAFT → (IN_REVIEW → APPROVED) → LIVE ⇄ PAUSED → ENDED → ARCHIVED`; solo `LIVE` e dentro calendario valuta | P0 | CMP | BO-05 | M1 |
| F-CMP-03 | Costruttore condizioni | albero `all/any/not` su campi `data.*`, `member.*`, `context.*`, `history.*` | P0 | CMP | BO-06 | M1 |
| F-CMP-04 | Effetti | `GRANT_POINTS` (fisso, per importo, da campo, da tabella), `MULTIPLIER`, `GRANT_PLAYS`, `ISSUE_COUPON`, `AWARD_BADGE`, `SEND_MESSAGE` | P0 | CMP | BO-06 | M1 (punti) → M5 |
| F-CMP-05 | Limiti | per membro (n volte per giorno/settimana/mese/edizione/sempre), tetto punti per membro, budget globale, cooldown | P0 | CMP | BO-06 | M1 |
| F-CMP-06 | Pubblico | tutti, per tier, per segmento | P0 tier · P1 segmenti | CMP | BO-06 | M1 / M6 |
| F-CMP-07 | Cumulabilità | priorità, gruppo esclusivo (vince la prima), moltiplicatori applicati ai punti delle altre campagne | P0 | CMP | BO-06 | M3 |
| F-CMP-08 | Simulazione | data un'azione di prova e un membro, mostra campagne che scattano, condizioni fallite, effetti risultanti; non scrive nulla | P0 | CMP | BO-06, BO-28 | M1 |
| F-CMP-09 | Registro valutazioni | per ogni azione: esito per campagna con motivo (`NOT_LIVE`, `AUDIENCE`, `CONDITION`, `LIMIT`, `BUDGET`, `EXCLUSIVE`) | P0 | CMP | BO-03, BO-25 | M1 |
| F-CMP-10 | Statistiche campagna | attivazioni, membri unici, punti erogati, budget residuo | P1 | CMP | BO-05 | M2 |
| F-CMP-11 | "Come guadagnare" | elenco campagne visibili al membro con progresso sui limiti ("2 di 5 questo mese") | P0 | CMP | PT-02 | M1 |
| F-CMP-12 | Campagne di sistema | campagne seed non eliminabili che consegnano i premi in punti/coupon degli instant win | P0 | CMP | BO-05 | M5 |
| F-CMP-13 | Duplica campagna | copia in `DRAFT` | P1 | CMP | BO-05 | M7 |
| F-CMP-14 | Storno su reso | `purchase.returned` revoca i punti dell'acquisto originario | P2 | CMP+WAL | — | — |

### Wallet e punti (`F-WAL`), tier (`F-TIER`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-WAL-01 | Doppia valuta | `PTS` spendibile, `STS` non spendibile; saldi separati | P0 | WAL | BO-03, PT-01 | M1 |
| F-WAL-02 | Libro mastro | ogni variazione è un movimento immutabile con causale, origine, campagna, azione, attore | P0 | WAL | BO-03, PT-07 | M1 |
| F-WAL-03 | Lotti e scadenza | ogni accredito crea un lotto con scadenza da policy (`ROLLING_MONTHS` o `END_OF_EDITION_PLUS_GRACE`) | P0 | WAL | BO-08, PT-07 | M3 |
| F-WAL-04 | Spesa FIFO | la spesa consuma i lotti con scadenza più vicina | P0 | WAL | — | M4 |
| F-WAL-05 | Punti in attesa | accredito con `pendingDays` → lotto `PENDING`, rilasciato da job | P1 | WAL | PT-07 | M3 |
| F-WAL-06 | Scadenza | job giornaliero scade i lotti; preavviso a 30 giorni (fatto `wallet.points.expiring`) | P0 | WAL | BO-30, PT-01 | M3 |
| F-WAL-07 | Rettifiche manuali | accredito/addebito con motivo obbligatorio; solo `CARE`/`ADMIN`; sempre in audit | P0 | WAL | BO-03 | M3 |
| F-WAL-08 | Saga di spesa | reagisce a `reward.redemption.requested`: spende o rifiuta; rimborso su annullamento | P0 | WAL | — | M4 |
| F-WAL-09 | Passività | punti in circolazione per valuta e per mese di scadenza | P1 | WAL | BO-01, BO-08 | M3 |
| F-TIER-01 | Definizione livelli | codice, nome, soglia `STS`, moltiplicatore `PTS`, vantaggi testuali, colore | P0 | WAL | BO-07, PT-08 | M3 |
| F-TIER-02 | Salita immediata | al superamento soglia nell'edizione corrente | P0 | WAL | PT-01 | M3 |
| F-TIER-03 | Moltiplicatore di livello | applicato ai `PTS` delle campagne che lo prevedono; dettaglio nel movimento | P0 | WAL | PT-07 | M3 |
| F-TIER-04 | Chiusura edizione con discesa morbida | a fine anno: nuovo livello = max(livello guadagnato nell'anno, livello attuale − 1); azzeramento `STS` di periodo | P0 | WAL | BO-08, BO-30 | M3 |
| F-TIER-05 | Anteprima chiusura | *dry-run*: chi sale/resta/scende, prima di applicare | P0 | WAL | BO-08 | M3 |
| F-TIER-06 | Storico livelli | per membro | P1 | WAL | BO-03 | M3 |

### Premi (`F-RWD`) e coupon (`F-CPN`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-RWD-01 | Catalogo premi | tipo (fisico, coupon sconto, digitale, donazione, esperienza), categoria, immagine, termini, validità | P0 | RWD | BO-10, PT-03 | M4 |
| F-RWD-02 | Fasce premi | il costo di un premio è la soglia della sua fascia; il portale raggruppa per fascia e mostra cosa è raggiungibile | P0 | RWD | BO-11, PT-03 | M4 |
| F-RWD-03 | Disponibilità | stock totale/residuo, limite per membro, esaurito | P0 | RWD | BO-10, PT-04 | M4 |
| F-RWD-04 | Visibilità | per tier e segmento | P0 tier · P1 segmenti | RWD | BO-10 | M4 / M6 |
| F-RWD-05 | Richiesta premio | `PENDING → CONFIRMED → FULFILLED` oppure `REJECTED`/`CANCELLED`; prenota stock, attende il wallet, conferma | P0 | RWD | PT-04, PT-13, BO-13 | M4 |
| F-RWD-06 | Evasione | automatica (coupon) o manuale (fisico: `CARE` segna spedito con nota) | P0 | RWD | BO-13 | M4 |
| F-RWD-07 | Annullamento con rimborso | da `CARE`/`ADMIN` prima dell'evasione; punti restituiti, stock ripristinato | P1 | RWD+WAL | BO-13 | M4 |
| F-RWD-08 | Ciclo di vita premio | come campagne, con approvazione | P0 | RWD | BO-10 | M4 (M7 approv.) |
| F-CPN-01 | Pool di coupon | codici generati (prefisso + casuale) o importati; stato per codice | P0 | RWD | BO-12 | M4 |
| F-CPN-02 | Emissione | da richiesta premio o da effetto `ISSUE_COUPON`; scadenza per codice | P0 | RWD | PT-13 | M4 / M5 |
| F-CPN-03 | Utilizzo | `POST /v1/coupons/{code}/use` simula la cassa; codice già usato/scaduto → errore | P1 | RWD | BO-12, PT-13 | M4 |

### Gioco: instant win (`F-IW`), obiettivi (`F-ACH`), classifiche (`F-LDB`), referral (`F-REF`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-IW-01 | Concorso | periodo, meccanica visiva (ruota, gratta, pacco), giocata gratuita giornaliera, limite giocate/giorno, regolamento | P0 | GAM | BO-14, PT-05 | M5 |
| F-IW-02 | Montepremi | premi con tipo (punti, coupon, fisico), quantità totale/residua | P0 | GAM | BO-14 | M5 |
| F-IW-03 | Istanti vincenti pre-generati | un istante per unità di premio, distribuiti nel periodo (uniforme o fasce orarie), con seme riproducibile | P0 | GAM | BO-14 | M5 |
| F-IW-04 | Giocata | consuma un credito; vince se esiste un istante scaduto non reclamato (claim atomico), altrimenti perde | P0 | GAM | PT-06 | M5 |
| F-IW-05 | Crediti di gioco | da effetto `GRANT_PLAYS` o giocata gratuita giornaliera | P0 | GAM | PT-05 | M5 |
| F-IW-06 | Vincita come azione interna | `contest.won` → azione `instantwin.won` → campagne di sistema consegnano punti/coupon | P0 | GAM+ING+CMP | BO-25 | M5 |
| F-IW-07 | Vincitori e report | elenco vincite, stato consegna, export CSV; istanti visibili solo a `ADMIN`/`LEGAL` | P0 | GAM | BO-14 | M5 |
| F-IW-08 | Aiuto demo | "pianta un istante adesso" per garantire una vincita in demo (solo profilo `demo`) | P0 | GAM | BO-14, BO-30 | M5 |
| F-ACH-01 | Obiettivi | metrica (conteggio, somma di un campo, tipi distinti, serie consecutiva), traguardo, periodo, ripetibilità | P0 | GAM | BO-15, PT-09 | M5 |
| F-ACH-02 | Progresso | aggiornato a ogni azione pertinente; visibile come barra | P0 | GAM | PT-09 | M5 |
| F-ACH-03 | Badge | assegnati al completamento o da effetto `AWARD_BADGE`; `badge.awarded` rientra come azione | P0 | GAM | BO-15, PT-09 | M5 |
| F-LDB-01 | Classifiche | metrica (PTS guadagnati, STS, conteggio azioni), periodo, top N, posizione del membro; nickname per privacy | P1 | GAM | BO-16, PT-10 | M5 |
| F-REF-01 | Codice amico | ogni membro ha un codice; la registrazione con codice crea il legame | P1 | MBR | PT-11 | M5 |
| F-REF-02 | Completamento referral | alla prima azione qualificante dell'invitato → `referral.completed` per invitante e invitato; le campagne premiano | P1 | MBR+CMP | BO-17, PT-11 | M5 |

### Contenuti e comunicazione (`F-CNT`, `F-MSG`, `F-THM`, `F-WBH`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-CNT-01 | Card | titolo, testo, immagine, CTA, posizionamento (`HOME_HERO`, `HOME_GRID`, `CATALOG_TOP`, `CONTEST`, `WIN`), collegamento a concorso/campagna/premio, pubblico, calendario, priorità | P0 | ENG | BO-18, PT-01 | M6 |
| F-CNT-02 | Pop-up | come card + frequenza (`ONCE`, `ONCE_PER_DAY`, `ALWAYS`), chiudibile; il portale mostra al più un pop-up per visita | P0 | ENG | BO-18, PT-01 | M6 |
| F-CNT-03 | Card vincita | contenuto mostrato all'esito di una giocata, per premio | P0 | ENG | BO-18, PT-06 | M6 |
| F-CNT-04 | Anteprima | anteprima fedele del contenuto come lo vedrà un membro scelto | P1 | ENG | BO-18 | M6 |
| F-MSG-01 | Inbox in-app | messaggi generati dai fatti (punti, tier, vincite, premi, scadenze) tramite regole template | P0 | ENG | PT-12, BO-19 | M6 |
| F-MSG-02 | Template | titolo/testo con segnaposto `{{…}}`, icona, link; canale `INAPP` reale, `EMAIL_FAKE` solo anteprima | P0 | ENG | BO-19 | M6 |
| F-THM-01 | Tema del portale | nome programma, logo, colori, testi hero; applicato dal portale a runtime | P1 | ENG | BO-20, PT-* | M6 |
| F-WBH-01 | Webhook in uscita | sottoscrizione a tipi di fatto, firma HMAC, ritentativi, registro consegne | P1 | ENG | BO-23 | M7 |

### Governance e osservabilità (`F-APR`, `F-AUD`, `F-INS`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-APR-01 | Workflow di approvazione | per tipo oggetto (campagna, premio, concorso, contenuto): invio in revisione, approvazione/rifiuto con commento, storico | P0 | tutti i proprietari | BO-21 | M7 |
| F-APR-02 | Policy | tabella tipo oggetto → ruolo approvatore (`LEGAL` per concorsi e premi; campagne sopra soglia punti) | P1 | tutti | BO-21 | M7 |
| F-APR-03 | Casella approvazioni | vista unica aggregata dai servizi | P0 | web (aggregazione) | BO-21 | M7 |
| F-AUD-01 | Audit log | chi, cosa, quando, prima/dopo per ogni scrittura da backoffice | P0 | tutti → INS | BO-22 | M2 |
| F-INS-01 | Flusso eventi live | coda in tempo reale di tutti i topic, filtrabile, colori per topic | P0 | INS | BO-24, rail | M2 |
| F-INS-02 | Tracciato | catena completa per `correlationId`: azione → valutazione → effetti → fatti → messaggi, con tempi | P0 | INS | BO-25 | M2 |
| F-INS-03 | KPI e serie storiche | membri, azioni per fonte, punti emessi/spesi/scaduti, passività, richieste, giocate/vincite, distribuzione tier, top campagne/premi | P0 | INS | BO-01 | M2 |
| F-INS-04 | Storico sintetico | 90 giorni di metriche fittizie coerenti per dashboard piene | P0 | INS | BO-01 | M2 |
| F-INS-05 | DLQ | messaggi non elaborabili con causa; *riprocessa* | P1 | INS | BO-27 | M7 |
| F-INS-06 | Stato pipeline | ultimo evento e volumi per topic | P1 | INS | BO-24 | M2 |

### Demo (`F-DEMO`)
| ID | Feature | Comportamento atteso | Pri | Svc | UI | M |
|---|---|---|---|---|---|---|
| F-DEMO-01 | Demo Hub | stato di servizi, Kafka e DB; "Accendi la demo"; scelta persona | P0 | web | HUB-01 | M0/M1 |
| F-DEMO-02 | Cambio persona | ruolo backoffice o membro, persistito in cookie, sempre visibile | P0 | web | tutte | M0 |
| F-DEMO-03 | Simulatore eventi | compone un'azione valida per tipo/fonte/membro e la invia; mostra il tracciato che ne deriva | P0 | ING + web | BO-28, PT-14 | M1 |
| F-DEMO-04 | Scenari guidati | sequenze di eventi con ritardi (`SCN-*`), eseguibili con un clic | P0 | ING | BO-29 | M2 |
| F-DEMO-05 | Reset dati | ogni servizio espone `POST /v1/demo/reset`; la console li orchestra | P0 | tutti | BO-30 | M1 |
| F-DEMO-06 | Job su richiesta | scadenza punti, rilascio pending, chiusura edizione, ricalcolo segmenti, con data di riferimento | P0 | WAL, MBR | BO-30 | M3 |
| F-DEMO-07 | Keep-alive gentile | finché una scheda è aperta e visibile i servizi restano svegli; a scheda chiusa tutto si spegne | P0 | web | — | M1 |

## 2. Requisiti non funzionali

| ID | Requisito | Soglia PoC |
|---|---|---|
| RNF-01 | Footprint per servizio | RSS ≤ 450 MB con `-XX:MaxRAMPercentage=65` su 512 MB |
| RNF-02 | Latenza pipeline (azione → movimento) a servizi svegli | p50 ≤ 3 s, p95 ≤ 8 s |
| RNF-03 | Idempotenza | rielaborare lo stesso evento non cambia lo stato (test per ogni consumer) |
| RNF-04 | Ordinamento | per membro garantito (chiave Kafka = `memberId`) |
| RNF-05 | Nessuna perdita | outbox transazionale; nessuna pubblicazione fuori transazione |
| RNF-06 | Resilienza al sonno | un servizio addormentato recupera l'arretrato al risveglio senza interventi |
| RNF-07 | Spazio DB | ≤ 300 MB totali; pulizia automatica di outbox, event store, log valutazioni |
| RNF-08 | Accessibilità UI | contrasto AA, focus visibile, navigazione da tastiera, `prefers-reduced-motion` |
| RNF-09 | Responsive | portale mobile-first (≥ 360 px); backoffice ≥ 1024 px, utilizzabile a 768 px |
| RNF-10 | Osservabilità minima | log JSON con `eventId`, `correlationId`, `memberId`; Actuator health/metrics |

## 3. Regola "nessuna entità senza lettore"

Una feature di configurazione è completa solo se esistono: (a) schermata di gestione, (b) servizio proprietario, (c) **almeno un consumatore** che ne renda visibile l'effetto, (d) dati seed.

## 4. Matrice entità → proprietario → lettori

| Entità configurabile | Proprietario | Gestita in | Letta da (effetto visibile) |
|---|---|---|---|
| Fonte, tipo azione | ING | BO-09 | validazione ingressi (BO-26), costruttore condizioni (BO-06), simulatore (BO-28) |
| Membro | MBR | BO-02/03 | tutti (snapshot via fatti), portale |
| Segmento | MBR | BO-04 | pubblico di campagne, premi, contenuti |
| Campagna | CMP | BO-05/06 | motore regole → movimenti; PT-02 |
| Valuta, policy scadenza, edizione | WAL | BO-08 | lotti, job scadenza, chiusura edizione |
| Tier | WAL | BO-07 | moltiplicatore, progresso PT-01/PT-08, visibilità premi |
| Fascia, premio, categoria | RWD | BO-10/11 | catalogo PT-03/04 |
| Pool coupon | RWD | BO-12 | emissione coupon PT-13 |
| Concorso, premio in palio, istanti | GAM | BO-14 | giocata PT-05/06 |
| Obiettivo, badge | GAM | BO-15 | progresso PT-09 |
| Classifica | GAM | BO-16 | PT-10 |
| Card, pop-up, banner | ENG | BO-18 | PT-01, PT-03, PT-05, PT-06 |
| Template, regola di notifica | ENG | BO-19 | inbox PT-12 |
| Tema | ENG | BO-20 | tutto il portale |
| Webhook | ENG | BO-23 | registro consegne BO-23 |
| Policy di approvazione | ciascun proprietario | BO-21 | transizioni di stato degli oggetti |
