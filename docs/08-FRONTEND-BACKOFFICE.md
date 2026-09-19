# 08 — Frontend: backoffice

Area `/backoffice` dell'app `web/`. Fondamenta (stack, token, stati, asincronia, proxy): `docs/07`. Qui: shell, permessi, pattern comuni e le 30 schermate `BO-01…BO-30`. Il backoffice è **unico**: gestisce programma, premi, gioco **e** i contenuti del portale (fa da CMS).

Notazione endpoint: `<servizio> <METODO> <path>` = chiamata a `/api/lh/<servizio><path>` (es. `wallet GET /v1/tiers`).

## 1. Shell e navigazione

```
┌──────────┬───────────────────────────────────────────────┬───────────┐
│ Sidebar  │ Barra alta: breadcrumb · ricerca (⌘K)         │ Rail      │
│ 232 px   │             · stato demo · selettore persona  │ eventi    │
│ scura    ├───────────────────────────────────────────────┤ live      │
│          │ Contenuto (max 1440 px, padding 24 px)        │ 320 px    │
│          │                                               │ (chiudib.)│
└──────────┴───────────────────────────────────────────────┴───────────┘
```
- **Sidebar** a gruppi; voce attiva con barra teal; contatori numerici su *Approvazioni* (oggetti `IN_REVIEW`), *Richieste premio* (da evadere + `needsAttention`), *DLQ* (`NEW`), aggiornati ogni 30 s.
- **Ricerca globale ⌘K**: membri (`member GET /v1/members?q=`), campagne, premi, concorsi per codice o nome; Invio apre la scheda.
- **Stato demo** (pillola): `10/10 svegli` verde · `7/10` ambra · popover con gli 8 servizi + Kafka + DB · clic → BO-30.
- **Selettore persona**: avatar + nome + ruolo in chiaro; menu con le 5 personas e "Vai al portale come…" (12 membri).
- **Rail eventi** (`docs/07 §5.2`): alimentato da `useLiveEvents()`; filtro rapido per topic; chiuso di default sotto 1280 px; stato aperto/chiuso in `localStorage`.
- Schema pagina: titolo + una riga di descrizione + azioni primarie a destra. I dettagli che non meritano una pagina si aprono in un **foglio laterale** (640 px).
- Sotto 1024 px la sidebar diventa un cassetto; le tabelle scorrono in orizzontale con la prima colonna fissa.

| Gruppo | Voce | ID | Route (`/backoffice…`) | M |
|---|---|---|---|---|
| Panoramica | Dashboard | BO-01 | `/` | M2 |
| Clienti | Membri | BO-02 | `/members` | M1 |
| | Scheda 360° | BO-03 | `/members/[id]?tab=` | M1→M6 |
| | Segmenti | BO-04 | `/segments`, `/segments/[id]` | M6 |
| Programma | Campagne | BO-05 | `/campaigns` | M1 |
| | Editor campagna | BO-06 | `/campaigns/new`, `/campaigns/[id]` | M1 |
| | Livelli | BO-07 | `/program/tiers` | M3 |
| | Valute ed edizioni | BO-08 | `/program/currencies?tab=` | M3 |
| | Azioni e fonti | BO-09 | `/program/actions?tab=` | M1 (custom M6) |
| Premi | Catalogo | BO-10 | `/rewards`, `/rewards/[id]` | M4 |
| | Fasce | BO-11 | `/rewards/bands` | M4 |
| | Coupon | BO-12 | `/rewards/coupons` | M4 |
| | Richieste premio | BO-13 | `/rewards/redemptions` | M4 |
| Gioco | Concorsi | BO-14 | `/game/contests`, `/game/contests/[id]` | M5 |
| | Obiettivi e badge | BO-15 | `/game/achievements` | M5 |
| | Classifiche | BO-16 | `/game/leaderboards` | M5 |
| | Referral | BO-17 | `/game/referral` | M5 |
| Contenuti | Card e pop-up | BO-18 | `/content`, `/content/[id]` | M6 |
| | Messaggi | BO-19 | `/content/messages?tab=` | M6 |
| | Tema e brand | BO-20 | `/content/theme` | M6 |
| Governance | Approvazioni | BO-21 | `/governance/approvals` | M7 |
| | Audit | BO-22 | `/governance/audit` | M2 |
| | Webhook | BO-23 | `/governance/webhooks` | M7 |
| Osservabilità | Flusso live | BO-24 | `/observe/live` | M2 |
| | Tracciati | BO-25 | `/observe/traces`, `/observe/traces/[correlationId]` | M2 |
| | Monitor ingressi | BO-26 | `/observe/inbound` | M1 |
| | DLQ | BO-27 | `/observe/dlq` | M7 |
| Demo | Simulatore eventi | BO-28 | `/demo/simulator` | M1 |
| | Scenari | BO-29 | `/demo/scenarios` | M2 |
| | Console demo | BO-30 | `/demo/console` | M1 (job M3) |

Una voce la cui milestone non è ancora realizzata **non compare** nella sidebar (flag in `lib/nav.ts`): mai pagine "in arrivo".

## 2. Matrice permessi

Tutte le personas **leggono tutto**, con una sola eccezione (istanti vincenti). La UI nasconde o disabilita; il backend rifiuta con `403` dove c'è ● (`@RequiresRole`, `docs/06 §3`). Il backend rifiuta inoltre **ogni scrittura** di `ANALYST`.

| Capacità (`capability`) | ADMIN | MARKETING | LEGAL | CARE | ANALYST |
|---|:-:|:-:|:-:|:-:|:-:|
| Lettura di tutte le schermate | ✓ | ✓ | ✓ | ✓ | ✓ |
| `instants.view` — tabella degli istanti vincenti ● | ✓ | solo istogramma | ✓ | — | — |
| `member.write` — crea, modifica, cambia stato | ✓ | — | — | ✓ | — |
| `member.anonymize` ● | ✓ | — | — | — | — |
| `points.adjust` — rettifica manuale ● | ✓ | — | — | ✓ | — |
| `segment.write` — segmenti, attributi custom | ✓ | ✓ | — | — | — |
| `object.edit` — campagne, premi, fasce, pool coupon, concorsi, obiettivi, badge, classifiche: crea/modifica/duplica e transizioni `SUBMIT, PUBLISH, PAUSE, RESUME, END, ARCHIVE` | ✓ | ✓ | — | — | — |
| `object.approve` — `APPROVE` / `REJECT` ● | ✓ (*override*, marcato in audit) | — | ✓ | — | — |
| `content.write` — contenuti, template, regole di notifica, tema | ✓ | ✓ | — | — | — |
| `program.config` — tier, valute, policy di scadenza, edizioni, fonti, ponte interno | ✓ | — | — | — | — |
| `actiontype.custom` — tipi azione custom | ✓ | ✓ | — | — | — |
| `edition.close` — anteprima / **applica** ● | ✓ / ✓ | ✓ / — | ✓ / — | ✓ / — | ✓ / — |
| `redemption.handle` — evadi, annulla con rimborso ● | ✓ | — | — | ✓ | — |
| `delivery.handle` — consegna vincite fisiche ● | ✓ | — | — | ✓ | — |
| `coupon.void` — annulla codice | ✓ | — | — | ✓ | — |
| `inbound.handle` — riprova, abbina ingressi | ✓ | — | — | ✓ | — |
| `webhook.write` | ✓ | — | — | — | — |
| `dlq.handle` — riprocessa / scarta ● | ✓ | — | — | — | — |
| `demo.simulate` — simulatore e scenari ● | ✓ | ✓ | ✓ | ✓ | — |
| `demo.admin` — reset, job, pianta istante ● | ✓ | — | — | — | — |

Implementazione: `lib/persona/permissions.ts` esporta `can(role, capability)`. Componente `<Can capability>`: **nasconde** se l'azione non ha senso per il ruolo, **disabilita con tooltip** se conviene far capire che esiste (es. "Approva — richiede ruolo LEGAL").

## 3. Pattern comuni

### 3.1 Pagina elenco (`ListPage`)
Titolo + conteggio · azione primaria a destra · `FilterBar` (chip rimovibili; stato nell'URL) · `DataTable` su TanStack (ordinamento lato server, paginazione `page/size`, scelta colonne) · riga cliccabile → dettaglio. Codici e ID in mono. Azioni di riga nel menu `⋯`.

### 3.2 Editor (`EditorPage`)
Pagina singola a sezioni con **indice ancorato** a sinistra (non wizard: tutto visibile) · piè di pagina fisso con *Salva bozza* / *Annulla* e indicatore "modifiche non salvate" · validazione zod al blur + errori backend mappati sui campi · `409` di versione → dialogo "Qualcun altro ha modificato: ricarica / sovrascrivi".
Oggetto `LIVE`: campi non sicuri in sola lettura con nota "Per cambiare le regole, duplica" (`docs/03 §3.6`).

### 3.3 Barra del ciclo di vita (`LifecycleBar`)
In testa a ogni oggetto governato (campagna, premio, concorso, contenuto): pill dello stato, calendario, e **solo i pulsanti delle transizioni valide** per stato × ruolo × policy.

| Stato | Pulsanti |
|---|---|
| `DRAFT` | *Invia in revisione* (se la policy lo richiede) **oppure** *Pubblica* · *Archivia* |
| `IN_REVIEW` | *Approva* · *Rifiuta* (commento obbligatorio) — per chi non può: "In attesa di LEGAL da 2 h" |
| `APPROVED` | *Pubblica* (con `startAt` futuro la pill mostra `SCHEDULED`) |
| `LIVE` | *Metti in pausa* · *Termina* |
| `PAUSED` | *Riprendi* · *Termina* |
| `ENDED` | *Archivia* · *Duplica* |

Ogni transizione apre un dialogo con commento → `POST …/transitions {action, comment}`. Accanto: **Storico** (chi, quando, commento) in popover. Con `approval.enabled=false` (fino a M7) da `DRAFT` compare direttamente *Pubblica*.

### 3.4 Riquadro "Dove si vede" (`ReaderPanel`)
Applicazione visibile della regola *nessuna entità senza lettore* (`docs/02 §3`): ogni editor di configurazione ha, in colonna destra, i **collegamenti ai punti in cui l'oggetto produce effetto**, presi dalla matrice `docs/02 §4` (per un premio: "Catalogo del portale — apri come Davide (GOLD)"; per un template: "Inbox di un membro"; per un tier: "Tessera nel portale"). Se un editor non ha nulla da mettere in questo riquadro, la feature è incompleta.

### 3.5 Conferme
Azioni irreversibili (anonimizza, applica chiusura edizione, reset, scarta DLQ, rigenera istanti) → dialogo con riepilogo dell'impatto e **digitazione del codice** dell'oggetto. Azioni reversibili → conferma semplice. Esito con toast; l'audit arriva come evento nel rail.

### 3.6 Componenti condivisi del backoffice
`DataTable` · `FilterBar` · `StatusPill` · `LifecycleBar` · `ReaderPanel` · `ConditionBuilder` (usato da BO-06 e BO-04) · `EffectEditor` · `AudiencePicker` (tutti / tier / segmenti) · `SchedulePicker` (inizio, fine, giorni, fasce orarie) · `SchemaForm` (form generato da JSON Schema, con vista JSON alternativa) · `TopicDot` · `MemberChip` (iniziali + nome + tier, clic → BO-03) · `PointsAmount` (segno, colore semantico, valuta) · `TierBadge` · `CodeText` (mono + copia) · `JsonViewer` · `TraceLink` (→ BO-25) · `TraceWaterfall` · `ActorStamp` ("Luca Serra · MARKETING · 3 min fa") · `KpiTile` · `DiffView` · `PhoneFrame` (anteprima 390 px) · `EmptyState` · `DegradedBox`.

## 4. Schermate

Formato: **Scopo** · **Dati** · **Layout** · **Azioni** (ruolo) · **Note**. Gli stati di `docs/07 §6` e lo schema "in elaborazione" di `docs/07 §7` valgono ovunque e non sono ripetuti.

### BO-01 — Dashboard
- **Scopo**: salute del programma in un colpo d'occhio. Feature `F-INS-03/04`, `F-WAL-09`.
- **Dati**: `insight GET /v1/kpi/overview|timeseries|breakdown`, `wallet GET /v1/liability`, `wallet GET /v1/tiers/distribution`, `reward GET /v1/rewards/stats`.
- **Layout**: selettore periodo (7/30/90 giorni, default 30). Riga 1 — 6 `KpiTile` con delta sul periodo precedente e sparkline: membri attivi, azioni ricevute, PTS emessi, PTS spesi, richieste premio, giocate/vincite. Riga 2 — area impilata *punti emessi / spesi / scaduti* + barre *azioni per fonte*. Riga 3 — *distribuzione tier* (barra orizzontale segmentata nei colori tier) + *passività per mese di scadenza* (colonne, 12 mesi) + *top 5 campagne* e *top 5 premi*. Riga 4 — **"Da guardare"**: richieste da evadere, oggetti in revisione, stock sotto il 10 %, DLQ aperte, punti in scadenza entro 30 giorni.
- **Note**: i giorni con dato sintetico (`synthetic=true`) hanno tratto più chiaro e legenda "storico dimostrativo". Ogni grafico ha la tabella alternativa (RNF-08). Clic su campagna/premio → dettaglio.

### BO-02 — Membri
- **Dati**: `member GET /v1/members`.
- **Layout**: `ListPage`. Colonne: membro (`MemberChip`), ID, e-mail, stato, tier, saldo PTS, STS edizione, ultima attività, iscritto il. Filtri: testo, stato, tier, etichetta, segmento, periodo di iscrizione.
- **Azioni**: *Nuovo membro* (`member.write`) → foglio laterale: nome, cognome, e-mail, externalId, data di nascita, codice amico; al salvataggio la riga resta "in elaborazione" finché non arrivano `member.registered` e il bonus di benvenuto.
- **Note**: `ANONYMIZED` in corsivo grigio; `BLOCKED` con pill rossa.

### BO-03 — Scheda 360°
- **Scopo**: tutto su un membro, anche quando alcuni servizi dormono (ogni scheda degrada da sola). Feature `F-MBR-02…05`, `F-WAL-02/07`, `F-TIER-06`, `F-CMP-09`.
- **Intestazione**: tessera in miniatura col materiale del tier, nome, ID, stato, saldo PTS (attivi + in attesa), STS e **barra verso il prossimo tier** ("mancano 120 STS a GOLD"), avviso scadenze ("1.900 PTS scadono entro 30 giorni"). Azioni rapide: *Rettifica punti* ● · *Simula un'azione* (BO-28 precompilato) · *Apri il portale come questo membro* · menu: *Blocca/Sblocca*, *Disattiva*, *Anonimizza* ●.

| Scheda (`?tab=`) | Contenuto | Dati |
|---|---|---|
| `overview` | profilo modificabile, consensi, etichette, ultimi 5 movimenti, ultimi 5 messaggi, obiettivi in corso | member, wallet, engagement, gamification |
| `ledger` | libro mastro con filtri valuta/tipo/periodo; riga espandibile: campagna, azione, lotto, moltiplicatore (`breakdown`), attore, `TraceLink` · sotto-scheda **Lotti** con scadenze | `wallet GET /v1/wallets/{id}/ledger`, `/lots` |
| `actions` | azioni ricevute con **esito per campagna**: scattata / non scattata e motivo (`AUDIENCE`, `CONDITION` con la condizione fallita, `LIMIT`, `BUDGET`, `EXCLUSIVE`) — risponde a "perché non ha preso i punti?" | `campaign GET /v1/evaluations?memberId=` |
| `rewards` | richieste premio con stato e cronologia; coupon con stato | reward |
| `game` | crediti, giocate, vincite, progressi, badge, posizioni in classifica | `gamification GET /v1/members/{id}/gamification` |
| `messages` | messaggi inviati (in-app e finta e-mail con anteprima) | `engagement GET /v1/messages?memberId=` |
| `segments` | segmenti di appartenenza, attributi custom modificabili, invitante e invitati | member |
| `timeline` | eventi raggruppati per tracciato, più recenti in alto | `insight GET /v1/members/{id}/timeline` |
| `tiers` | storico livelli con motivo (salita, chiusura edizione) | `wallet GET /v1/members/{id}/tier-history` |

- **Rettifica punti**: dialogo con valuta, direzione, quantità, motivo (`GOODWILL` gesto commerciale, `CORRECTION` correzione, `COMPLAINT` reclamo, `TEST`), nota ≥ 10 caratteri; anteprima "saldo dopo"; alla conferma "in elaborazione" → movimento evidenziato. Errori `INSUFFICIENT_BALANCE`, `NOTE_TOO_SHORT` sul campo.
- **Anonimizza**: conferma con digitazione dell'ID; dopo, i campi personali mostrano "Membro anonimo" e le azioni sono disabilitate.

### BO-04 — Segmenti
- **Dati**: `member /v1/segments*`, `POST /v1/segments/preview`.
- **Elenco**: nome, tipo (`STATIC`/`DYNAMIC`), membri, ultimo ricalcolo, **usato da** (n. campagne/premi/contenuti).
- **Editor**: nome, descrizione, tipo. `DYNAMIC` → `ConditionBuilder` sui campi del membro (tier, stato, anzianità in giorni, attributi, etichette, saldo PTS, giorni dall'ultima attività, acquisti e spesa nel periodo). A destra **anteprima dal vivo** (debounce 600 ms): conteggio + 10 membri campione. `STATIC` → selettore membri multi-scelta.
- **Azioni**: *Ricalcola ora* → `{entered, left, total}` in toast; i fatti `segment.*` scorrono nel rail. `ReaderPanel`: oggetti che usano il segmento.

### BO-05 — Campagne
- **Dati**: `campaign GET /v1/campaigns` (include `totals`).
- **Layout**: `ListPage`. Colonne: nome + codice, stato, trigger (icone dei tipi azione), sintesi effetti ("+1 PTS/€ · +1 STS/€"), pubblico, calendario, attivazioni, punti erogati, **budget** (barra, se presente), priorità. Filtri: stato, tipo azione, etichetta, `system`. Le campagne di sistema hanno un lucchetto e non si archiviano.
- **Azioni**: *Nuova campagna* · riga `⋯`: *Duplica*, *Metti in pausa/Riprendi*, *Archivia*. Vista alternativa **Calendario** (barre su 12 settimane) per vedere le sovrapposizioni, es. `CMP-WEEKEND-X2` e `CMP-BLACK-FRIDAY`.

### BO-06 — Editor campagna e simulazione
- **Scopo**: la schermata più importante del backoffice. Feature `F-CMP-01…08`.
- **Dati**: `campaign /v1/campaigns*`, `POST /v1/campaigns/validate|simulate`, `GET /v1/campaigns/{id}/stats`, `GET /v1/meta/condition-fields`; `ingestion GET /v1/event-types`, `/v1/event-types/{code}/fields`; `wallet GET /v1/tiers`, `/v1/currencies`; `member GET /v1/segments`; `gamification GET /v1/contests`, `/v1/badges`; `reward GET /v1/rewards`; `engagement GET /v1/message-templates`.
- **Layout** a tre colonne: indice · sezioni · pannello destro fisso con schede **Simulazione** / **Statistiche** / **Dove si vede**.
- **In testa**: `LifecycleBar` + **frase generata** che rilegge la regola in italiano a ogni modifica: *"Quando arriva **Acquisto completato** da ecommerce o app, se **importo ≥ 50 €** e il membro è **GOLD o PLATINUM**, assegna **1 giocata a Ruota d'Autunno**, al massimo **1 volta al giorno**."* — funzione pura `describeCampaign()` con test; la stessa frase è usata in BO-21.

| Sezione | Campi |
|---|---|
| 1 Generale | nome, codice (auto dal nome, modificabile solo in `DRAFT`), descrizione interna, etichette, `requiresLegal` |
| 2 Quando | tipi azione trigger (multi-scelta con icona e fonte), fonti ammesse (default tutte) |
| 3 A chi | `AudiencePicker`: tutti · tier · segmenti (M6) |
| 4 Se | `ConditionBuilder`: gruppi `TUTTE / ALMENO UNA / NESSUNA` annidabili (max 3 livelli); riga = campo (combobox raggruppato per spazio `data`, `member`, `context`, `history`) · operatore coerente col tipo · valore secondo il tipo (numero, testo, elenco, enum → select, data, booleano). Con più trigger i campi `data.*` disponibili sono l'intersezione; un campo non comune mostra un avviso |
| 5 Allora | elenco effetti ordinabile (`EffectEditor`): `GRANT_POINTS` (valuta; modalità *fisso / per importo / da campo / da tabella*; arrotondamento; applica moltiplicatore tier; `pendingDays`), `MULTIPLIER` (fattore, valuta, campagne bersaglio), `GRANT_PLAYS` (concorso, quantità), `ISSUE_COUPON` (premio), `AWARD_BADGE`, `SEND_MESSAGE` (template) |
| 6 Limiti | per membro (n per giorno/settimana/mese/edizione/sempre), tetto punti per membro, budget globale, cooldown |
| 7 Calendario | `SchedulePicker`, fuso `Europe/Rome` |
| 8 Cumulabilità | priorità, gruppo esclusivo |
| 9 Nel portale | visibile in "Guadagna", testo per il membro, icona, riepilogo premio |

- **Simulazione**: membro (combobox) **oppure** profilo ipotetico (tier, segmenti) · tipo azione · `data` in `SchemaForm` precompilato con `sample_data` · data/ora dell'azione · *Simula*. Risultato: esito **SCATTA / NON SCATTA**; albero delle condizioni con ✓/✗ e **valore osservato accanto all'atteso** ("importo 42 · atteso ≥ 50"); effetti risultanti; totali per valuta; altre campagne che scatterebbero sulla stessa azione. Lavora sulla **bozza non salvata** e non scrive nulla.
- **Azioni**: *Salva bozza* chiama prima `validate`; errori strutturali evidenziati nella sezione. *Prova dal vivo* (solo `LIVE`) apre BO-28 con tipo e membro precompilati.

### BO-07 — Livelli
- **Dati**: `wallet GET/PUT /v1/tiers`, `GET /v1/tiers/distribution`.
- **Layout**: **scala orizzontale** dei 4 livelli (soglia STS, moltiplicatore PTS, materiale, n. membri) + pannello di modifica del livello selezionato: nome, soglia, moltiplicatore, vantaggi (elenco testi), colore. Sotto: la **discesa morbida** spiegata in chiaro con l'esempio calcolato su Stefano Galli, e collegamento alla chiusura edizione (BO-08).
- **Azioni** (`program.config`): salva; `TIER_THRESHOLDS_NOT_MONOTONIC` sul campo soglia. `ReaderPanel` → PT-01, PT-08, visibilità premi.

### BO-08 — Valute, scadenze, edizioni
- **Schede**: `currencies` — PTS/STS con policy di scadenza (`ROLLING_MONTHS` n mesi + fine mese, oppure `END_OF_EDITION_PLUS_GRACE` giorni) e **esempio calcolato** ("guadagnati oggi → scadono il 30 set 2027"); nota "vale per i nuovi lotti" · `editions` — linea del tempo 2025 `CLOSED` / 2026 `ACTIVE` / 2027 `PLANNED` · `liability` — passività per valuta e per mese di scadenza.
- **Chiusura edizione** (`F-TIER-04/05`): *Anteprima chiusura* → `POST /v1/editions/{code}/close?dryRun=true` → tabella membro · tier attuale · STS del periodo · tier guadagnato · **nuovo tier** · esito (`RETAINED`/`DOWNGRADED`), riepilogo in alto, filtro per esito. *Applica chiusura* ● (ADMIN, digitazione del codice edizione) → i fatti `tier.*` scorrono nel rail.

### BO-09 — Azioni e fonti
- `types` — tipi azione: codice, nome, icona, origine (`SYSTEM`/`CUSTOM`), fonti, abilitato, **usato da n campagne**; dettaglio con schema dei campi (percorso · tipo · obbligatorio · enum) e `sample_data`; *Nuovo tipo custom* (M6): editor campi a righe → genera il JSON Schema; *Prova* apre BO-28.
- `sources` — fonti: codice, nome, tipi ammessi, stato, volumi 24 h, ultimo evento; interruttore (fonte spenta → eventi `REJECTED`, visibili in BO-26).
- `bridge` — ponte interno: tabella fatto → azione con interruttore e contatore; nota sull'anti-loop (`lhhop`).

### BO-10 — Catalogo premi
- **Dati**: `reward /v1/rewards*`, `/v1/reward-categories`, `/v1/reward-bands`, `/v1/rewards/stats`.
- **Elenco**: vista **griglia** (immagine, nome, fascia/costo, stato, stock con barra, lucchetto tier) o tabella. Filtri: stato, fascia, categoria, tipo.
- **Editor**: generale (nome, codice, tipo `PHYSICAL/COUPON/DIGITAL/DONATION/EXPERIENCE`, categoria, immagine da `public/demo/` o URL, descrizione, termini) · **fascia** (il costo è la soglia della fascia, in sola lettura) · disponibilità (stock totale, limite per membro, validità) · visibilità (`AudiencePicker`) · evasione (`AUTO` con pool coupon / `MANUAL`). `LifecycleBar` (approvazione LEGAL da M7). `ReaderPanel` → PT-03 come membro a scelta.
- **Note**: stock sotto il 10 % → pill ambra "in esaurimento"; a zero → "esaurito".

### BO-11 — Fasce
- **Scala verticale** F1…F5 con soglia, nome, n. premi per stato (l'ordine è la soglia). Modifica in linea; *Nuova fascia*; elimina solo se vuota (`BAND_IN_USE`). Cambiare una soglia mostra l'impatto prima di salvare: "cambia il costo di 3 premi LIVE".

### BO-12 — Coupon
- **Dati**: `reward /v1/coupon-pools*`, `/v1/coupons/{code}`.
- **Layout**: elenco pool (premio collegato, prefisso, totali per stato `AVAILABLE/ISSUED/USED/EXPIRED/VOID` come barra segmentata). Dettaglio: tabella codici filtrabile; *Genera codici* (≤ 5000) · *Importa* (incolla elenco → riepilogo importati/scartati).
- **Cassa simulata**: campo "Verifica codice" → stato, membro, scadenza · *Segna come usato* (`F-CPN-03`) · *Annulla* (`coupon.void`).

### BO-13 — Richieste premio
- **Dati**: `reward GET /v1/redemptions`, `POST …/fulfil`, `…/cancel`.
- **Layout**: schede di stato (`Da evadere` = `CONFIRMED` + `MANUAL`, `In attesa`, `Evase`, `Rifiutate/Annullate`, `Da verificare` = `needsAttention`). Tabella: data, membro, premio, punti, stato, evasione, età. Foglio laterale: **cronologia della saga** (richiesta → punti spesi → confermata → evasa) con orari e `TraceLink`, spedizione, coupon emesso.
- **Azioni** (`redemption.handle`): *Segna come spedito* (nota, tracking) · *Annulla e rimborsa* (motivo) → "in elaborazione" finché il wallet non emette il rimborso.

### BO-14 — Concorsi instant win
- **Dati**: `gamification /v1/contests*`, `…/instants`, `…/instants/histogram`, `…/winners`, `…/stats`, `POST /v1/demo/contests/{id}/plant-instant`.
- **Elenco**: nome, meccanica, periodo, stato, giocate, vincite, premi residui.
- **Dettaglio a schede**: `setup` — generale, meccanica (`WHEEL/SCRATCH/GIFT`), periodo, giocata gratuita giornaliera, limite giornaliero, regolamento · `prizes` — montepremi: premio, tipo (`POINTS` n / `COUPON` premio / `PHYSICAL`), quantità totale/residua, colore spicchio, card vincita collegata (→ BO-18) · `instants` — *Genera istanti* (distribuzione `UNIFORM`/`BUSINESS_HOURS`, **seme** mostrato e copiabile); istogramma per giorno; **tabella degli istanti solo ADMIN/LEGAL** (gli altri vedono *Forbidden* con la spiegazione: "chi configura il concorso non deve conoscere gli istanti") · `winners` — vincite con membro, premio, stato consegna, *Esporta CSV*, *Aggiorna consegna* (`delivery.handle`) · `stats` — giocate/vincite per giorno, tasso di vincita.
- **Aiuto demo** (`demo.admin`): *Pianta un istante adesso* → scegli premio → la prossima giocata vince; l'istante è marcato `planted`.
- **Note**: `PUBLISH` senza istanti → `INSTANTS_NOT_GENERATED` con pulsante verso `instants`. Nota normativa in fondo a `setup` (`docs/03`).

### BO-15 — Obiettivi e badge
- `achievements` — nome, metrica (`COUNT/SUM/DISTINCT_TYPES/STREAK`), tipi azione osservati, campo (per `SUM`), traguardo, periodo (`MONTH/EDITION/EVER`), ripetibile, badge collegato, completamenti e membri in corso; editor con frase generata ("Completa 3 acquisti nello stesso mese").
- `badges` — griglia icone: nome, descrizione, icona, quanti membri lo hanno. `ReaderPanel` → PT-09.

### BO-16 — Classifiche
- Elenco: nome, metrica (`PTS_EARNED/STS/ACTION_COUNT`), periodo, top N, stato. Dettaglio: configurazione + **anteprima** del periodo corrente con nickname e nome reale affiancati (il portale mostra solo il nickname), selettore periodo.

### BO-17 — Referral
- **Dati**: `member GET /v1/referral/overview`, `GET /v1/members/{id}/referrals`.
- **Layout**: KPI (inviti, completati, tasso) · imbuto invitato → registrato → prima azione qualificante → premiato · top presentatori · tabella legami (invitante, invitato, stato `PENDING/COMPLETED`, date). Riquadro "Regola di completamento" in chiaro + collegamento alle campagne `CMP-REFERRAL-*`.

### BO-18 — Card, pop-up e banner (CMS)
- **Dati**: `engagement /v1/contents*`, `GET /v1/contents/preview`.
- **Elenco**: miniatura, titolo, tipo (`CARD/POPUP/BANNER`), posizionamento, stato, calendario, pubblico, priorità. Vista **"Per posizione"**: per ogni placement l'ordine effettivo dei contenuti.
- **Editor** a due colonne: form (titolo, testo, immagine, CTA etichetta + destinazione scelta da elenco: *concorso, premio, campagna, pagina del portale, URL*; posizionamento; pubblico; calendario; priorità; per i pop-up frequenza `ONCE/ONCE_PER_DAY/ALWAYS` e chiudibile; per le card vincita il premio in palio) · **anteprima fedele** in `PhoneFrame` che usa **gli stessi componenti del portale** da `components/shared/content` (non una copia), col tema corrente.
- **Anteprima per membro** (`F-CNT-04`): scegli un membro → cosa vede ora in quel placement e **perché gli altri contenuti sono esclusi** (`NOT_IN_AUDIENCE`, `OUT_OF_SCHEDULE`, `NOT_LIVE`, `FREQUENCY`).
- I contenuti non richiedono approvazione: *Pubblica* diretto.

### BO-19 — Messaggi
- `templates` — codice, titolo, canale (`INAPP`/`EMAIL_FAKE`), categoria; editor con segnaposto `{{…}}` suggeriti in base al tipo di fatto e **anteprima renderizzata** su un evento campione (`POST …/render`).
- `rules` — regole *fatto → template* (tipo di fatto, condizione opzionale, template, abilitata) con interruttore.
- `log` — messaggi inviati, filtri membro/categoria/canale, anteprima.

### BO-20 — Tema e brand
- Form: nome programma, logo, colori `night/primary/secondary/coin/bg`, testi hero, nome delle valute nel portale. **Anteprima dal vivo** di PT-01 in `PhoneFrame` con le variabili CSS applicate. Verifica contrasto in linea (AA); blocco al salvataggio su `THEME_CONTRAST_TOO_LOW`. *Ripristina Aurora*.

### BO-21 — Approvazioni
- **Dati**: aggregazione lato web di `campaign|reward|gamification GET /v1/approvals` (`F-APR-03`); un servizio addormentato non blocca gli altri (riga *degraded* per fonte).
- **Layout**: schede *Da approvare* (per il ruolo corrente) e *Inviate da me*. Riga: tipo, nome, inviato da, quando, ruolo richiesto, sintesi. Foglio laterale con **riepilogo leggibile** (campagne: frase generata; concorsi: montepremi e periodo; premi: fascia, stock, termini) e *Approva* / *Rifiuta con commento* ●.
- Scheda `policy` (sola lettura): tipo oggetto → ruolo, con la soglia di budget.

### BO-22 — Audit
- **Dati**: `insight GET /v1/audit`, `/v1/audit/{id}`.
- **Layout**: tabella quando · attore (`ActorStamp`) · servizio · azione · oggetto. Filtri: attore, ruolo, servizio, tipo oggetto, azione, periodo. Dettaglio: `DiffView` campo per campo + JSON grezzo. *Override* di ADMIN e `RESET` sono marcati.

### BO-23 — Webhook
- Elenco: URL, tipi di fatto sottoscritti, stato, ultima consegna, tasso di successo. Editor: URL, tipi (dal catalogo fatti), attivo; il **secret compare una sola volta** alla creazione. Dettaglio: registro consegne (stato HTTP, tentativi, durata, payload, firma), *Invia evento di prova*, *Ritenta*.

### BO-24 — Flusso eventi live
- **Scopo**: rendere visibile l'architettura. Feature `F-INS-01/06`.
- **Dati**: SSE `insight /v1/stream/events`, `GET /v1/pipeline/status`.
- **Layout**: in alto la **striscia pipeline** — 5 tessere topic nel loro colore con ultimo evento, volumi 1 h/24 h, ritardo stimato; sotto il **flusso** a righe: ora (ms), `TopicDot`, tipo breve, membro, sintesi, `correlationId` abbreviato. Passando su una riga si evidenziano tutte quelle con lo stesso `correlationId`. Filtri: topic, famiglia, membro, tipo; *Pausa/Riprendi* (contatore "12 nuovi"); buffer 500 righe. Clic → evento con `JsonViewer` e *Apri tracciato*.
- **Note**: indicatore di connessione `live` / `live ridotto` (polling) / `disconnesso`.

### BO-25 — Tracciati
- **Dati**: `insight GET /v1/traces`, `/v1/traces/{correlationId}`, `/v1/events/{eventId}`; `campaign GET /v1/evaluations/{actionId}`.
- **Elenco**: ora, membro, azione radice, esito sintetico ("+162 PTS · +130 STS · tier GOLD"), durata, stato.
- **Dettaglio**: `TraceWaterfall` — una corsia per servizio, nodi posizionati per `offsetMs`, collegati dal `parentEventId`, colorati per topic; pannello esito (punti, cambio tier, messaggi, coupon, giocate, DLQ); clic su un nodo → payload. Riquadro **"Perché"** con l'esito per campagna. Stato `IN_PROGRESS` → aggiornamento via SSE filtrato.

### BO-26 — Monitor ingressi
- **Dati**: `ingestion GET /v1/inbound-events*`, `POST …/retry`, `…/match`.
- **Layout**: schede per esito (`ACCEPTED`, `DUPLICATE`, `REJECTED`, `UNMATCHED`) con conteggi; tabella: ricevuto, fonte, tipo, soggetto, membro risolto, esito, codice di rifiuto. Dettaglio: CloudEvent completo, errori di schema campo per campo, *Riprova*, *Abbina a un membro* (M7), `TraceLink` se accettato.

### BO-27 — DLQ
- **Dati**: `insight /v1/dlq*`.
- **Layout**: tabella: quando, consumer, topic d'origine, tipo, `errorCode`, tentativi, stato (`NEW/REPROCESSED/DISCARDED`). Dettaglio: messaggio d'errore, stack abbreviato, payload. *Riprocessa* / *Scarta* ● (`dlq.handle`). `LOOP_GUARD` ha una spiegazione dedicata.

### BO-28 — Simulatore eventi
- **Scopo**: inviare un'azione vera nella pipeline e vederne subito l'effetto. Feature `F-DEMO-03`.
- **Dati**: `ingestion POST /v1/demo/simulator/fire`, `GET /v1/event-types`, `/v1/sources`; `campaign POST /v1/campaigns/simulate` (anteprima).
- **Layout** a due colonne. Sinistra: membro (combobox con tier e saldo) · tipo azione (schede con icona) · fonte · `data` in `SchemaForm` precompilato · data/ora (default adesso) · ripetizioni (1–20) · **scorciatoie**: "Acquisto 130 € (tier-up di Giulia)", "Attiva bolletta digitale", "Accesso quotidiano", "Evento duplicato", "Evento malformato". Pulsanti: *Anteprima* (simulazione, non scrive) · **Invia**.
- Destra: **tracciato dal vivo** dell'evento appena inviato (`TraceWaterfall` che si popola in tempo reale) + riepilogo esito + *Apri il portale come questo membro*.
- Parametri da URL (`?memberId=&type=`) per i collegamenti da BO-03, BO-06, BO-09.

### BO-29 — Scenari
- **Dati**: `ingestion GET /v1/demo/scenarios`, `POST …/run`, `GET /v1/demo/scenario-runs/{runId}`.
- **Layout**: schede scenario (titolo, storia in due righe, protagonista, n. passi, durata, cosa guardare). *Esegui* → vista di esecuzione: passi in sequenza con stato (in attesa → inviato → elaborato), `TraceLink` per passo, suggerimento "apri il portale come Anna per vederlo dal suo lato". Uno scenario alla volta.

### BO-30 — Console demo
- **Dati**: `/api/demo/status`; per ogni servizio `GET /v1/demo/info`, `POST /v1/demo/reset`; job demo dei servizi.
- **Stato** — griglia 8 servizi + Kafka + DB (come HUB-01) con versione, profili, conteggi principali, ultimo reset.
- **Reset dati** ● — *Ripristina tutto* (insight per primo, poi gli altri in parallelo; esito per servizio; conferma digitando `RESET`) oppure per singolo servizio, con avviso sulle incoerenze temporanee.
- **Macchina del tempo** ● — job con **data di riferimento** `asOf`: scadenza punti, preavviso scadenze, rilascio punti in attesa (wallet) · ricalcolo segmenti, compleanni (member) · scadenza coupon, timeout richieste (reward) · chiusura concorsi (gamification) · collegamento alla chiusura edizione (BO-08). Ogni job mostra l'esito ("scaduti 1.900 PTS per 1 membro").
- **Limiti dell'ambiente gratuito** — promemoria leggibile dei vincoli (`docs/11 §1`).
