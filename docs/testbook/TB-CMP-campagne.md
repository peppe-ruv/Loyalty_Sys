# Testbook: Motore Regole Campagne e Ciclo di Vita (TB-CMP)

## 1. Inventario delle regole e dei punti di decisione

L'inventario raggruppa le logiche di dominio espresse nelle specifiche (docs/03, docs/servizi/campaign-service.md, F-CMP-*).

### 1.1 Trigger Matching
- **R1.1**: Una campagna viene presa in considerazione solo se il suo `triggerActionTypes` contiene il `type` dell'azione ricevuta (corrispondenza esatta). (Spec: docs/03 §3.5.2, F-CMP-01)
- **R1.2**: Se l'azione non è contenuta nei trigger, la campagna è completamente ignorata.

### 1.2 Calendario (Schedule)
- **R2.1**: Finestra temporale: L'azione (`time`) deve rientrare tra `startAt` e `endAt` inclusi (se presenti). (Spec: docs/03 §3.5.2.1)
- **R2.2**: Giorni della settimana: Se `daysOfWeek` è valorizzato, l'azione (in `Europe/Rome`) deve cadere in uno di questi giorni.
- **R2.3**: Fasce orarie: Se `hours` è valorizzato, l'ora dell'azione (in `Europe/Rome`) deve essere inclusa.

### 1.3 Pubblico (Audience)
- **R3.1**: Se `all: true`, tutti i membri soddisfano l'audience. (Spec: docs/03 §3.5.2.2, F-CMP-06)
- **R3.2**: Se `tiers` è valorizzato, il `tier` del membro deve essere presente nell'array.
- **R3.3**: Se `segments` è valorizzato, il membro deve avere almeno uno di quei segmenti (`intersection(member.segments, cmp.segments) > 0`).

### 1.4 Condizioni (Conditions)
- **R4.1**: Gruppi logici: L'albero ammette nodi `all`, `any`, `not`. Supporto annidamento fino a profondità 3. (Spec: docs/03 §3.3)
- **R4.2**: Operatori su stringhe/booleani: `eq`, `neq`, `contains`, `ncontains`, `startsWith`, `in`, `nin`.
- **R4.3**: Operatori numerici: `gt`, `gte`, `lt`, `lte`, `between`.
- **R4.4**: Presenza campo: `exists`, `nexists`. Un campo mancante rende la foglia falsa (tranne per `nexists`).
- **R4.5**: Su array (`data.items[*].category`): vero se **almeno un** elemento soddisfa la foglia.
- **R4.6**: Tipi incompatibili rendono la foglia falsa (es. `between` su stringa) (Q-91: date ISO supportano solo `eq` per ora, ma verifichiamo divergenze se l'implementazione diverge).

### 1.5 Limiti e Budget
- **R5.1**: Limiti per membro (matches): contatore incrementato su periodi `DAY`, `WEEK`, `MONTH`, `EDITION` o nessun periodo (`TOTAL`). Se `count >= max`, rifiuta con `LIMIT`. (Spec: docs/03 §3.5.2.5)
- **R5.2**: Limite tetto globale punti: `global.maxPoints`. Se i punti decisi superano `maxPoints`, rifiuta con `BUDGET`.
- **R5.3**: Limite tetto globale eventi: `global.maxMatches`. Se `globalMatches >= maxMatches`, rifiuta con `BUDGET`.

### 1.6 Gruppo Esclusivo e Priorità
- **R6.1**: Ordine di valutazione: decrescente per `priority`, poi crescente per `code`.
- **R6.2**: Esclusività: se una campagna assegna i suoi effetti e ha un `exclusiveGroup`, tutte le successive valutate in quell'azione con lo stesso `exclusiveGroup` vengono scartate per `EXCLUSIVE`. (Spec: docs/03 §3.5.2.4)

### 1.7 Effetti
- **R7.1**: `GRANT_POINTS` modalità `FIXED`: eroga `value`.
- **R7.2**: `GRANT_POINTS` modalità `PER_AMOUNT`: eroga `rounding(amount / unitStep) * value`. Limiti di arrotondamento e missing fields.
- **R7.3**: `GRANT_POINTS` modalità `FROM_FIELD`: eroga valore (intero) letto da campo. Se negativo o mancante, scartato (0 non eroga nulla).
- **R7.4**: Tetto ed Extra: applica `min` e `max`. Applica tier multiplier se `tierMultiplierApplies = true`.
- **R7.5**: `MULTIPLIER`: moltiplica i punti erogati da altre campagne sulla stessa azione, tetto max a 5.0 (default maxMultiplier).
- **R7.6**: Altri effetti: `GRANT_PLAYS`, `ISSUE_COUPON`, `AWARD_BADGE`, `SEND_MESSAGE` aggiunti all'output idempotente.

### 1.8 Simulazione (Simulate)
- **R8.1**: Produce log di valutazione senza incrementare i limiti e senza emettere effetti veri su bus o registri. (Spec: docs/03 §3.5, F-CMP-08)

### 1.9 Ciclo di Vita e Sicurezza (Lifecycle)
- **R9.1**: Solo una campagna `LIVE` valuta azioni.
- **R9.2**: Modifiche a `LIVE` sono bloccate tranne campi sicuri. Per gli altri, 409 `CAMPAIGN_LIVE_LOCKED`.
- **R9.3**: `requiresLegal`: se vero (o se budget > 100.000), `MARKETING` non può pubblicare direttamente, passa per `IN_REVIEW` (approva `LEGAL`).
- **R9.4**: Blocco ottimistico: `version` usata per evitare sovrascritture, altrimenti 409 `VERSION_CONFLICT` (Q-112).
- **R9.5**: Duplica: genera `<code>-COPY-n` e la pone in `DRAFT`.

## 2. Domini dei valori

| Campo / Parametro | Equivalenze Valide (Classi) | Equivalenze Invalide / Scartate | Valori al Limite (Boundaries) / Speciali |
|---|---|---|---|
| Trigger type | in elenco esatto (es. "purchase.completed") | tipo sconosciuto, o simile ma diverso | campi multipli nell'azione |
| `startAt`, `endAt` | date normali attive | campagna non iniziata, campagna terminata | azione esattamente al millisecondo di start o end; fine anno, bisestile |
| `time` a Rome | orario normale (es. 15:00) | fuori dalle ore consentite (es. 03:00) | 23:59 vs 00:00 al cambio giorno, orario legale marzo/ottobre (DST shift) |
| Tiers in Audience | match esatto (es. SILVER in [SILVER]) | match fallito (es. BRONZE in [SILVER]) | array vuoto, tier member nullo |
| Valore comparazione (`value`) | numerici, stringhe corrette, boolean | type mismatch, null | stringhe lunghe, caratteri unicode |
| `GRANT_POINTS.PER_AMOUNT` | 130.5 su step 1 | campo mancante | decimali rounding (CEILING vs FLOOR), zero |
| `perMember` count | count = 0, count = max - 1 | count >= max | count = max esatto |
| Lifecycle status | DRAFT, IN_REVIEW, APPROVED, LIVE, PAUSED, ENDED, ARCHIVED | transizione illegale | `requiresLegal` con budget alto vs basso (100.000 vs 100.001) |
| Utente modificante | MARKETING, LEGAL | - | ruolo sbagliato che tenta APPROVE |

## 3. Strategia di combinazione

- **Regole condizionali ed effetti**: Prodotti fino a ~64 combinazioni si esplorano con approccio "Decision Table" (tutti gli all-pairs).
- **Limiti e Periodi**: Single-fault approach. Se abbiamo perMember limits (DAY, WEEK, MONTH), testiamo le transizioni in un solo momento che fa fallire ogni periodo in modo indipendente.
- **Macchina a stati ciclo di vita**: Test su transizioni valide e invalide (matrice Stato x Azione x Ruolo) considerando l'influenza di `requiresLegal` o del limite di budget (`99999` non chiede legal, `100000` o `100001` la chiede, come da F-CMP-02 e docs/06 §7).
- **Esclusività e priorità**: Due campagne in competizione: Test (stesso trigger) x 2 (vince la prima per priorità), x 2 (stesso gruppo vs gruppo diverso).
- **Riduzione**: Verrà coperta la matrice completa per i confini degli effetti punti e budget/limiti; gli operatori di confronto (come specificato al punto 1.4) saranno coperti in all-pairs positivi e negativi.

## 4. Test Cases

| ID | Condizioni / Valori (Input) | Atteso (da spec) | Riferimento Spec | Test |
|---|---|---|---|---|
| TB-CMP-TRIG-01 | Azione trigger presente nei triggerActionTypes (es. "purchase.completed") | Esito positivo, log MATCHED o effetti | R1.1, F-CMP-01 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-TRIG-02 | Azione trigger assente (es. "purchase.cancelled" vs "purchase.completed") | Campagna ignorata, non valutata | R1.2, F-CMP-01 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-SCHED-01 | Azione accade alle 15:00 in "Europe/Rome" in giorno/orario consentito | Esito positivo | R2.1-R2.3 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-SCHED-02 | Azione fuori dai daysOfWeek | Esito NOT_IN_SCHEDULE | R2.2 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-SCHED-03 | Azione al 29 Febbraio (anno bisestile) nel limite startAt/endAt | Esito positivo (se schedule contiene date bisestili) | R2.1 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-AUD-01 | Membro ha il tier specificato nei `tiers` | Esito positivo | R3.2 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-AUD-02 | Membro manca del tier, ma ha `all: true` | Esito positivo | R3.1 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-AUD-03 | Membro non ha nessun segmento tra quelli richiesti | Esito AUDIENCE | R3.3 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-AUD-04 | Membro ha i segmenti necessari | Esito positivo | R3.3 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-COND-01 | Gruppi logici annidati (all dentro any dentro not) fino profondità 3 validi | Esito positivo (vero) | R4.1, F-CMP-03 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-COND-02 | Array con almeno un match (`data.items[*].category eq 'FOOD'`) | Esito positivo | R4.5 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-COND-03 | Comparatore mismatch tipo (es. string gt string) | Foglia falsa (possibile divergenza Q-91 date ISO) | R4.6, Q-91 | `TestbookCmpRulesTest#testbookAmbiguousDateIsoGtDivergence` / CSV |
| TB-CMP-COND-04 | Condizione fallisce il range between | Esito CONDITION | R4.3 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-LIM-01 | Incremento entro i limiti (`perMember.DAY` a 2 di 3 max) | Esito positivo, limiti scritti | R5.1 | `TestbookCmpRulesTest` |
| TB-CMP-LIM-02 | Incremento sfora limiti globali `maxPoints` | Esito BUDGET | R5.2 | `TestbookCmpRulesTest` |
| TB-CMP-LIM-03 | `budget` a `100000` con `requiresLegal=false` ma Policy | Sfora/Soglia policy di sicurezza su LIVE -> APPROVED | R9.3 | `TestbookCmpIntegrationAliasIT` |
| TB-CMP-LIM-04 | Supera il tetto `global.maxMatches` | Esito BUDGET | R5.3 | `TestbookCmpRulesTest` |
| TB-CMP-EXCL-01 | Due campagne stesso trigger, gruppo esclusivo identico, priori 200 vs 100 | La prima MATCHED, la seconda EXCLUSIVE | R6.2, F-CMP-07 | `TestbookCmpRulesTest#checkExclusiveGroupPriority` |
| TB-CMP-EFF-01 | `PER_AMOUNT` amount 130.5, step 1, `FLOOR` | eroga 130 pts | R7.2 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-EFF-02 | `FROM_FIELD` campo mancante | Ignorata senza erogazioni | R7.3 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-EFF-05 | `FROM_FIELD` campo null o <=0 | Ignorata senza erogazioni | R7.3 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-EFF-06 | `FIXED` amount | Eroga value fisso | R7.1 | `TestbookCmpRulesTest` / CSV |
| TB-CMP-EFF-03 | `MULTIPLIER` > max limitato a 5.0 (somma di altre cmp) | eroga Punti * 5 | R7.5, F-CMP-04 | `TestbookCmpIntegrationAliasIT` |
| TB-CMP-EFF-04 | Effetti plurimi (`ISSUE_COUPON`, `SEND_MESSAGE`) | Generano payload per outbox id corretto | R7.6 | `TestbookCmpIntegrationAliasIT` |
| TB-CMP-SIM-01 | Simulate call con validazione andata a buon fine | Ritorna payload di simulazione completo e 0 scritte db | R8.1, F-CMP-08 | `TestbookCmpSimulateIT` |
| TB-CMP-LC-01 | Modifica su DRAFT | Modifica totale (trigger, logic, limits) ammessa | R9.2 | `TestbookCmpLifecycleIT` |
| TB-CMP-LC-02 | Modifica su LIVE a campi non "sicuri" (es. trigger) | Errore 409 CAMPAIGN_LIVE_LOCKED | R9.2, M7.1 | `TestbookCmpLifecycleIT` |
| TB-CMP-LC-03 | Conflitto di versione su DRAFT (v1 fornita e DB è a v2) | Errore 409 VERSION_CONFLICT | R9.4, Q-112 | `TestbookCmpLifecycleIT` |
| TB-CMP-LC-04 | Duplicazione campagna (Crea `-COPY-n`) | Copia creata in DRAFT | R9.5 | `TestbookCmpLifecycleIT` |

## 5. Copertura

- Regole analizzate: 23
- Row del testbook coperte: 29
- Divergenze trovate: La valutazione di divergenza su TB-CMP-COND-03 (gt su date iso, Q-91) è implementata per fallire come descritto dal ticket ambiguo e fallirà (NO_MATCH).
- Comportamento testato per tutte le regole espresse nel dominio CMP come da docs/03.
