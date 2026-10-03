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
| | Import | BO-32 | `/observe/imports` | M8 (M8.7) |
| Demo | Simulatore eventi | BO-28 | `/demo/simulator` | M1 |
| | Scenari | BO-29 | `/demo/scenarios` | M2 |
| | Console demo | BO-30 | `/demo/console` | M1 (job M3) |

Una voce la cui milestone non è ancora realizzata **non compare** nella sidebar (flag in `lib/nav.ts`): mai pagine "in arrivo".

**Voce esterna «Utenti membri»** (ADR-051 decisione 7): nel gruppo *Clienti*, subito dopo *Membri*, un collegamento `↗` in nuova scheda (`rel="noopener noreferrer"`) alla console Keycloak del realm dei membri, `<origine IdP dei membri>/admin/loyaltyhub-members/console/`. Visibile solo a `ADMIN` e `CARE` e solo nel profilo `enterprise` con `LH_OIDC_MEMBER_ISSUER` configurata; l'indirizzo lo calcola il layout server (`memberConsoleUrl`) e lo passa ai componenti client come prop del contesto, mai con `NEXT_PUBLIC_`. **Non è una schermata**: nessun ID `BO-nn`, nessuna milestone, non sta in `NAV` e non rientra nella regola «nessuna pagina in arrivo». Lo stesso collegamento compare in un riquadro sopra la tabella di BO-02 («Account di accesso dei membri… Apri gli utenti membri ↗»), per gli stessi ruoli.

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
| `inbound.handle` — riprova, abbina ingressi; carica un import e riprova i suoi non abbinati (BO-32, Q-372) | ✓ | — | — | ✓ | — |
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
- **Riquadro "Carica il programma di esempio"** (`F2-DIST-09`, ADR-051, Q-617/673/675/722/723): visibile solo a `ADMIN`, solo nel profilo `enterprise` e solo in un ambiente di prova (`testMode()`; altrimenti la rotta risponde 404 e il riquadro non c'è). `GET /api/vetrina/programma` mostra l'anteprima (cosa si crea, cosa è escluso); `POST` con `{scope: "program"|"stories"}` avvia un lavoro in memoria (uno solo alla volta, 409 `JOB_RUNNING`) e l'avanzamento si legge ogni 1,5 s. Le scritture passano dalle API dei servizi con un token fresco per chiamata e producono una voce in `audit_entry` ciascuna, con l'attore reale. Campagne e premi nascono `DRAFT`: non si sottomettono né si approvano da qui, serve un altro operatore. Le storie dei tre membri di prova (`seed/vetrina-test.json`) si caricano in un solo import dalla fonte `vetrina-test` e restano "in attesa delle campagne attive" finché le campagne di acquisto e lettura non sono `LIVE`.

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
| 2 Quando | tipi azione trigger (multi-scelta con icona e fonte), fonti ammesse (default tutte; salvate come regola `context.source in [URN…]` alla radice delle condizioni, docs/03 §3.3, Q-208). **Scorciatoia verso BO-09** (`actiontype.custom`, Q-432): *+ Crea una nuova azione* in fondo alla scelta, *Crea «{ricerca}» come nuova azione* quando la ricerca non trova nulla, azione dello stato vuoto e link «Azioni e fonti» negli avvisi. L'editor di BO-09 si apre in un foglio laterale **dentro** la campagna, così la bozza non si perde; i link verso altre schermate (simulatore, Azioni e fonti) si aprono in una nuova scheda per lo stesso motivo. Al salvataggio la nuova azione entra nei trigger («… è stata creata e aggiunta a Quando.»); se l'abilitazione chiesta su una fonte non riesce, un avviso lo dice. Se un trigger scelto non ha `amount` ma la bozza usa ancora `data.amount` (condizione o `PER_AMOUNT`), compare un avviso che indica la sezione da correggere; *Togli la condizione d'esempio* toglie solo la condizione `data.amount ≥ 1` di partenza. *Aggiorna* rilegge i tipi creati in un'altra scheda. Se una fonte ammessa non accetta un trigger scelto, avviso: per quell'azione la campagna non scatterà da quella fonte; se non ne accetta nessuno, da quella fonte non scatterà mai (Q-437). `?trigger=<codice>` precompila il trigger (da *Crea una campagna con questa azione* di BO-09) |
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
- **Scopo**: dire al programma quali azioni dei membri esistono (tipi azione), da quali sistemi possono arrivare (fonti) e quali fatti del programma rientrano come azioni (ponte interno). Feature `F-ING-05`, `F-ING-06`.
- **In testa**: riquadro richiudibile **«Come funziona»** (stato per operatore in `localStorage`) con lo schema *Fonte → Azione → Campagna (Quando · Se · Allora) → Punti e premi* e, solo nel profilo `demo`, la nota «In demo, il ripristino dei dati elimina le azioni personalizzate.» (il profilo arriva dal layout del backoffice, Q-438). Nel profilo `enterprise` gli stati vuoti non rimandano alla Console demo.
- **Schede**: `types` «Azioni» · `sources` «Fonti» · `bridge` «Azioni generate dal programma». Origine scritta come *Di sistema* / *Personalizzata*; un'unica tabella di etichette delle categorie per BO-09 e BO-06. Ogni stato vuoto spiega perché è vuoto e offre l'azione primaria (docs/07 §6).
- `types` — azioni: icona, nome, codice, categoria, origine, **fonti** («solo simulatore» in ambra se nessuna fonte esterna la accetta), abilitata, **usata da n campagne**. Dettaglio: campi (percorso · tipo · obbligatorio · enum) e `sample_data`; tabella **«Da dove può arrivare»** con una riga per fonte (*Accetta* · *Non accetta: serve un amministratore* · *Accetta tutte le azioni* · *Spenta*); *Abilita* sulle fonti esterne accese che non la accettano e *Togli* dove la accettano, tranne l'ultima azione ammessa (solo `program.config`; ogni scrittura rilegge la fonte e manda l'elenco completo). Un'azione generata dal programma (categoria `INTERNAL`) arriva solo dal ponte interno: le fonti esterne dicono *Non la invia: la genera il programma* e non offrono *Abilita*; *Usata da* con i link alle campagne; *Crea una campagna con questa azione* (BO-06 con `?trigger=`); *Prova* apre BO-28. Dopo la creazione un avviso dice che l'azione arriva solo dal simulatore finché un amministratore non la abilita su una fonte.
- *Nuova azione* (`actiontype.custom`): editor a **sezioni numerate** nello stesso foglio laterale (non wizard: tutto visibile, §3.2), con una colonna *Guida* accanto a ogni sezione.
  1. **Che cosa fa il membro**: nome, descrizione, categoria, icona da una **griglia visiva chiusa** (ricerca, etichette in italiano; un valore fuori elenco resta visibile con un avviso, Q-431).
  2. **Codice tecnico**: proposto dal nome (Q-429), modificabile con *Personalizza codice* solo prima del salvataggio e poi in sola lettura; elenco di controlli dal vivo (solo minuscole e cifre, da 2 a 4 parole separate da un punto, al massimo 60 caratteri, non già usato, nessun prefisso riservato `io.` o `loyaltyhub.`, Q-430); anteprima di ciò che invia l'integratore (`type = <codice>` oppure `io.loyaltyhub.action.<codice>`); avviso di permanenza vicino a *Crea* (codice immutabile, nessuna eliminazione: si disattiva).
  3. **Informazioni che arrivano con l'azione**: campi a righe → genera il JSON Schema. Etichetta in italiano → nome tecnico proposto (i campi standard riusano i nomi dei tipi di sistema: `amount`, `currency`, `channel`, `orderId`, `productId`, `storeId`); tipi spiegati con un esempio; obbligatorio e facoltativo spiegati; quattro domande guida; etichetta e descrizione salvate come `title` e `description` del campo nello schema; `x-lh-pii: false` su ogni campo e blocco dei nomi, e delle chiavi dell'esempio scritto a mano, che fanno pensare a dati personali (Q-435). Uno schema con regole che le righe non sanno riscrivere (`minimum`, `pattern`, `x-lh-pii: true`, oggetti, elenchi) resta intero e si modifica solo come JSON. *Parti da un modello* (che prima propone l'azione di sistema equivalente, se esiste) e *Duplica da un'azione esistente* (Q-434).
  4. **Esempio e anteprima**: «Ecco cosa ci manderà il tuo sistema» (`type` e `data`) e «Come la vedrà la campagna»; l'esempio si rigenera da solo finché non lo modifichi a mano; JSON Schema e JSON grezzo sotto *Avanzate (per sviluppatori)*.
  5. **Da dove può arrivare**: le fonti che la accetteranno e chi può abilitarla; con `program.config` caselle per abilitarla subito sulle fonti esterne (dopo la creazione, `PUT` con l'elenco completo di ciascuna).

  *Crea* disattivato dice che cosa manca. Prima di cambiare lo schema o disattivare un'azione usata, l'editor mostra «Usata da N campagne (M attive)» con i link; salva sempre il contenuto completo (la `PUT` sostituisce, Q-436). Un tipo di sistema cambia solo nome, descrizione, icona e abilitazione (Q-89).
- `sources` — fonti: nome e codice, tipo (*Esterna (HTTP)* / *Interna*), descrizione, azioni ammesse, stato, **volumi 24 h** (totale e accettate, `ingestion GET /v1/inbound-events/counts?source=&from=`) e **ultimo evento** (`GET /v1/inbound-events?source=&limit=1`, in tempo relativo; «—» se il servizio non risponde); un solo interruttore *Accesa/Spenta* con conferma prima di spegnere (focus su *Annulla*); link al Monitor ingressi (BO-26). **Azioni ammesse modificabili** solo sulle fonti esterne (`program.config`, Q-433): il ponte interno e il simulatore accettano sempre tutte le azioni. Si sceglie da un elenco di caselle, senza le azioni generate dal programma; si invia sempre l'elenco completo (`ingestion PUT /v1/sources/{code}`), e un elenco vuoto («accetta tutte le azioni») solo come scelta esplicita con avviso, mai togliendo l'ultima casella. Prima di salvare la fonte si rilegge: se l'elenco è cambiato nel frattempo, l'editor lo dice e chiede se ricaricarlo o salvare comunque.
- `bridge` — azioni generate dal programma: tabella fatto → azione con interruttore e contatore; spiegazione in chiaro, con l'anti-loop (`lhhop`, DLQ, `LOOP_GUARD`) sotto *Dettagli tecnici*.

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
- **Storico** sotto l'anteprima (contenuto già salvato): chi ha pubblicato, messo in pausa, ripreso, terminato o archiviato, quando e da/verso, dal più recente (`GET /v1/contents/{id}/approval-history`, docs/03 §3.6, F-APR-01); stesso componente del foglio di BO-21.

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
- **Scopo**: raccontare un giro azione → esito a chi non è tecnico (MARKETING, CARE, LEGAL, ANALYST): cosa ha fatto il membro, cosa ha ottenuto e perché. La cascata per servizio resta per l'IT in un pannello ripiegato (issue #204).
- **Dati**: `insight GET /v1/traces`, `/v1/traces/{correlationId}`, `/v1/events/{eventId}`; `campaign GET /v1/evaluations/{actionId}`; nomi da `member GET /v1/members` e `wallet GET /v1/currencies` (se non rispondono restano ID e codici). Nessun campo nuovo lato servizio: le etichette leggibili si ricavano nel frontend (`web/lib/observe/`).
- **Layout**: elenco a sinistra (5/12), dettaglio a destra (7/12); sotto `lg` si impilano.
- **Elenco**: riga = azione in italiano (`actionLabel()`), membro (nome e ID), ora relativa, stato in italiano su una sola riga e mai troncato (*Completato*, *In corso*, *Bloccato*), esito in chip leggibili («+124 punti», «+24 status», «livello GOLD», «nessun premio», «in elaborazione»). Filtri: membro (nome o ID), tipo di azione, stato; interruttore **Nascondi eventi solo tecnici**, attivo di default (tracciati che partono da un tipo interno, es. `member.segment.entered`, e non hanno esito). Il deep-link `?c=<correlationId>` preseleziona il tracciato.
- **Dettaglio**, dall'alto:
  1. **Sintesi**: «*Anna Rossi* ha completato un acquisto da € 24,90», stato, «elaborato in 6,4 secondi» (secondi con un decimale, mai ms; «fermo dopo…» se bloccato, «in corso da…» se in corso) e link alla scheda 360° (BO-03).
  2. **Riquadri KPI**: punti per valuta con il nome di Valute ed edizioni, livello, badge, obiettivi completati, messaggi, coupon, giocate.
  3. **Passo per passo**: timeline in quattro fasi, sempre presenti: *Azione ricevuta* (ACTION radice) → *Regole controllate* (`campaign.evaluated`) → *Effetti decisi e registrati* (EFFECT e `wallet.*`) → *Obiettivi, badge e messaggi* (gli altri FACT). I nodi uguali di una fase si raggruppano («Obiettivo avanzato ×3»); un'azione derivata (es. `badge.awarded` reimmessa in ingestion) e i suoi discendenti si annidano sotto il passo che l'ha innescata, «Ha innescato a sua volta…», seguendo `parentEventId`. Ogni passo ha il tempo relativo in secondi.
  4. **Perché questi punti**: per ogni azione del giro (la radice e le derivate) le campagne valutate con nome, esito (*Applicata* / *Non applicata*), motivo in parole semplici (`NOT_IN_SCHEDULE`, `AUDIENCE`, `CONDITION`, `EXCLUSIVE`, `LIMIT`, `BUDGET`) e punti assegnati. Se `campaign` non risponde il riquadro mostra un `DegradedBox` locale; il resto della pagina resta usabile.
  5. **Bloccato**: il passo con una voce DLQ è in rosso con il motivo in linguaggio semplice e il link *Apri in DLQ* (`/backoffice/observe/dlq?status=ALL&e=<id>`, BO-27); le fasi successive dicono «Non partito».
  6. **Dettaglio tecnico (per IT)**, ripiegato di default: ID correlazione copiabile e `TraceWaterfall`, una corsia per servizio con marcatori a punto di larghezza fissa su un asse in secondi, senza etichette inline; codice evento, servizio e tempo stanno nel nome accessibile e nel tooltip. Clic su un punto → servizio, `eventId`, sintesi e payload in `JsonViewer` (`insight GET /v1/events/{eventId}`).
- **Aggiornamento**: elenco ogni 5 s, dettaglio ogni 3 s (polling); l'aggiornamento via SSE dei tracciati `IN_PROGRESS` resta da fare.
- **Testi**: tutti in `web/lib/i18n/it.ts` (`traces`); un tipo evento dei contratti senza etichetta fa fallire `web/lib/observe/eventLabels.test.ts`.

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

### BO-32 — Import (Fase 2, M8.7)
- **Scopo**: caricare un file di eventi e seguirne l'esito riga per riga. Feature `F2-ING-02` (docs/18 §3.6, §5).
- **Dati**: `ingestion POST /v1/imports` (multipart, header `Idempotency-Key`), `GET /v1/imports`, `/v1/imports/{id}`, `/v1/imports/{id}/rows`, `/v1/imports/{id}/report.csv`, `POST /v1/imports/{id}/retry-unmatched`; `GET /v1/sources` per la fonte predefinita.
- **Layout**: riquadro di caricamento (file `.csv`, `.ndjson`, `.jsonl`, `.json` fino a 1 MB; fonte predefinita facoltativa; *Carica*) con l'aiuto «Formato del file» (colonne CSV); tabella dei lavori, più recenti prima: caricato, file (formato, dimensione), fonte, stato (`QUEUED/RUNNING/DONE/FAILED` con barra di avanzamento e righe elaborate), esiti (accettate, duplicate, respinte, non abbinate, non valide), autore; si aggiorna da sola ogni 3 s finché un lavoro è attivo. Dettaglio in foglio laterale (`?i=<id>`): cinque conteggi, motivo del fallimento, *Scarica rapporto CSV*, *Riprova non abbinati* con il numero di righe ancora `UNMATCHED`, tabella delle righe non accettate filtrabile per esito con pulsanti a due stati (`aria-pressed`): riga e linea del file, id evento, esito e codice, dettaglio, esito attuale con *Monitor →* verso BO-26.
- **Azioni**: *Carica* e *Riprova non abbinati* ● (`inbound.handle`: ADMIN, CARE; disabilitati con il ruolo richiesto per gli altri, Q-372). Il file è controllato anche prima dell'invio (estensione, vuoto, dimensione); gli errori `422` del servizio (`IMPORT_*`) compaiono sotto il modulo con il loro `detail`.
- **Note**: le righe accettate sono solo contate (si vedono in BO-26 e BO-25); il rapporto CSV neutralizza le formule e non contiene il soggetto dei non abbinati. Stati della vista come `docs/07 §6` (scheletro, vuoto con invito a caricare, errore con correlazione, *degraded* con riprova automatica).
- **Invia un'azione** (V11, F2-ING-02, ADR-051, Q-675, Q-725): solo nell'ambiente di test dichiarato (`enterprise` con `LH_TEST_USERS_ALLOWED` e `LH_ENVIRONMENT=test`, Q-676) e solo per `ADMIN` e `CARE`; lo decide il layout server (`sendAction` in `BoPersona`, come `sampleProgram` di BO-01). Modulo di una riga sopra l'elenco: *Membro* (Anna, Marco, Giulia con il livello letto dal portafoglio; chi non è ancora registrato è disattivato), *Azione* (i tipi ammessi dalla fonte `vetrina-test`, con etichetta italiana) e *Importo (€)* solo per acquisto e reso; gli altri tipi hanno solo il pulsante *Invia l'azione*. Con la fonte `vetrina-test` assente il modulo dice «Carica prima il programma di esempio» con il collegamento alla Dashboard. L'invio passa dal BFF (`POST /api/vetrina/azione`, stesse guardie di BO-01 ma per `ADMIN` e `CARE`): una riga NDJSON con la fonte in forma di URN, id `vt-act-…`, soggetto `member:<id>` solo tra i tre membri di test, file `vetrina-azione.ndjson` (mai il nome delle storie), `POST /v1/imports` con `Idempotency-Key` casuale. Sotto il modulo, *Ultimo invio*: membro · azione · importo → esito (in elaborazione, accettata, già presente, non accettata), variazione dei punti e nuovo livello letti dal portafoglio dopo l'elaborazione (mai inventati: se non arrivano entro 20 s lo dice), controllo della voce di audit `CREATE` dell'import a nome dell'operatore (regola 21) e i collegamenti *Apri il dettaglio* (foglio laterale dell'import) e *Apri i membri*. Regione viva (`role="status"`, `aria-live="polite"`); stati *loading*, senza programma, errore, *degraded* (servizio che dorme, con *Riprova*). La stima dei punti non c'è (Q-725).
- **Menu Demo** (V11): in `enterprise` il gruppo *Demo* della sidebar (BO-28, BO-29, BO-30) e il pannello demo del portale non compaiono, perché chiamano `/v1/demo`, che in quel profilo non esiste.
