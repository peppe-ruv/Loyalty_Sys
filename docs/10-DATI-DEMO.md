# 10 — Dati demo

Il PoC non ha login: **sono i dati a raccontare il prodotto**. Questo documento è la fonte di verità per il contenuto di `seed/*.json`. Brand fittizio: **Club Aurora** (tenant `aurora`), programma fedeltà di una multiutility immaginaria. Nessun riferimento ad aziende reali; e-mail su `example.org`; immagini originali o segnaposto generati.

## 1. Principi

1. **Una sola cartella** `seed/` alla radice, letta da più servizi: la coerenza è *per costruzione* (il membro `MBR-000004` è lo stesso in member, wallet, campaign, reward…). Ogni modulo Maven la include come risorsa (`<resource><directory>${maven.multiModuleProjectDirectory}/seed</directory><targetPath>seed</targetPath></resource>`); `SeedLoader` (`lh-common`) la legge dal classpath col profilo `demo`.
2. **Date relative a oggi**: nei JSON le date sono espressioni risolte al caricamento da `SeedDates.resolve(expr, clock)` — così la demo è sempre "fresca" (concorso in corso, punti in scadenza *tra 12 giorni*).

   | Espressione | Significato (fuso `Europe/Rome`) |
   |---|---|
   | `@now`, `@today` | adesso · oggi alle 00:00 |
   | `@today-20d`, `@now-3h`, `@today+2M`, `@today-1y` | scostamenti in giorni, ore, mesi, anni |
   | `@som`, `@eom`, `@soy`, `@eoy` | inizio/fine mese, inizio/fine anno (combinabili: `@eom+1M`) |
   | `@lastWeekday`, `@lastSaturday` | ultimo giorno feriale / ultimo sabato **precedente** a oggi |
   | suffisso `T10:30` | ora del giorno (es. `@today-3dT18:45`) |

   Le edizioni sono gli anni solari: l'edizione `ACTIVE` è sempre quella che contiene oggi (`@soy`…`@eoy`); codici `E<anno>` calcolati.
3. **Reset idempotente**: `POST /v1/demo/reset` riporta ogni servizio esattamente a questo stato; semi fissi per tutto ciò che è casuale (codici coupon, istanti vincenti, storico sintetico).
4. **Nessun seed senza lettore**: ogni riga di questo documento deve essere visibile in almeno una schermata (colonna "Dove si vede").
5. **Verifica automatica**: `node scripts/check-seed.mjs` (in CI) applica le regole del §11.

| File | Proprietario | Letto anche da |
|---|---|---|
| `members.json` | member | ingestion (indice), campaign, reward, gamification, engagement (snapshot) |
| `wallets.json` | wallet | member (proiezione), campaign, reward (snapshot saldo/tier) |
| `activity-history.json` | campaign | member (statistiche), gamification (progressi) |
| `segments.json`, `attribute-definitions.json` | member | campaign, reward, engagement |
| `sources.json`, `event-types.json`, `internal-mappings.json`, `scenarios.json` | ingestion | — |
| `campaigns.json` | campaign | — |
| `tiers.json`, `currencies.json`, `editions.json` | wallet | campaign, reward, gamification (tier) |
| `reward-categories.json`, `reward-bands.json`, `rewards.json`, `coupon-pools.json`, `redemptions.json` | reward | — |
| `contests.json`, `achievements.json`, `badges.json`, `leaderboards.json`, `gamification-history.json` | gamification | — |
| `contents.json`, `message-templates.json`, `notification-rules.json`, `theme.json`, `webhooks.json`, `inbox.json` | engagement | — |
| `insight-synthetic.json` | insight | — |
| `personas.json` | web (copiato in `web/lib/persona/`) | tutti i servizi (nome visualizzato nell'audit) |

## 2. Membri e storie

Dodici membri, ognuno con **una storia da raccontare in demo**. ID `MBR-00000n`, `externalId` `CRM-10n`, e-mail `nome.cognome@example.org`, nickname per le classifiche.

| ID | Nome | Stato | Tier | PTS | STS edizione | Storia (mostrata nel selettore) | Da provare |
|---|---|---|---|--:|--:|---|---|
| 000001 | Anna Rossi | ACTIVE | BASE | 100 | 0 | "Iscritta ieri: ha solo il bonus di benvenuto" | `SCN-ONBOARDING`; profilo da completare |
| 000002 | Marco Bianchi | ACTIVE | SILVER | 1.850 | 1.420 | "Cliente tipo: compra online, ha invitato un'amica" | membro predefinito del portale; `SCN-DIGITAL` |
| 000003 | Giulia Ferri | ACTIVE | SILVER | 3.240 | 2.880 | "A 120 punti status da GOLD" | acquisto 130 € → tier-up (`SCN-TIER-UP`) |
| 000004 | Davide Russo | ACTIVE | GOLD | 12.300 | 4.100 | "Ha punti da spendere e 2 coupon attivi" | richiesta premio; PT-13 |
| 000005 | Francesca Romano | ACTIVE | PLATINUM | 28.750 | 9.600 | "La cliente migliore: vede i premi riservati" | `RWD-PLATINUM-EVENT`; classifica |
| 000006 | Stefano Galli | ACTIVE | GOLD | 5.400 | 650 | "GOLD dall'anno scorso, quest'anno poco attivo" | anteprima chiusura edizione → scende a SILVER |
| 000007 | Chiara Marino | ACTIVE | SILVER | 2.400 | 1.150 | "1.900 punti in scadenza entro 30 giorni" | avviso scadenza; job scadenza con `asOf` |
| 000008 | Roberto Costa | BLOCKED | BASE | 320 | 40 | "Profilo sospeso dall'assistenza" | evento → `REJECTED/MEMBER_NOT_ACTIVE` |
| 000009 | Elisa Fontana | ACTIVE | BASE | 300 | 0 | "Invitata da Marco, non ha ancora comprato" | primo acquisto → `SCN-REFERRAL` |
| 000010 | Matteo Ricci | ACTIVE | SILVER | 1.120 | 1.060 | "Ha 3 giocate da usare" | PT-06; vincita garantita con istante piantato |
| 000011 | Sofia Greco | ACTIVE | GOLD | 6.900 | 3.350 | "Ha richiesto la borraccia: in attesa di spedizione" | BO-13 evasione manuale |
| 000012 | Alessandro Conti | ANONYMIZED | BASE | 0 | 0 | — (non selezionabile) | riga anonimizzata in BO-02, movimenti conservati |

Vincoli: **GOLD + PLATINUM = 4** (Davide, Francesca, Stefano, Sofia); ogni membro `ACTIVE` ha 8–25 movimenti storici negli ultimi 120 giorni, coerenti con tier e saldo (Σ lotti attivi = saldo PTS). Elisa: 100 PTS di benvenuto + 200 PTS da una rettifica `GOODWILL` storica (così il premio referral resta tutto da dimostrare). Chiara: lotto da 1.900 PTS con scadenza `@today+12d`. Matteo: 3 `play_grant` aperti su `IW-AUTUNNO` (2 da `CMP-SURVEY`, 1 giocata gratuita di oggi) e 6 giocate storiche (1 vinta). Profili incompleti (manca città o data di nascita): Anna, Elisa.

**Personas del backoffice** (`personas.json`): `marta.admin` Marta Villa `ADMIN` · `luca.marketing` Luca Serra `MARKETING` · `elena.legal` Elena Riva `LEGAL` · `paolo.care` Paolo Neri `CARE` · `sara.analyst` Sara Longo `ANALYST`. Ognuna ha una riga "cosa posso fare" per il selettore.

## 3. Programma: fonti, tipi azione, livelli, valute, segmenti

- **Fonti** (`sources.json`): `crm`, `app`, `ecommerce`, `billing`, `partner` (abilitate) · `internal` (ponte) · `simulator` (demo). Tipi ammessi per fonte come in `docs/05 §3`.
- **Tipi azione** (`event-types.json`): i 19 tipi `EVT-ACT-*` di `docs/05`, ognuno con nome italiano, icona `lucide`, JSON Schema di `data` e `sample_data` realistico (es. `purchase.completed`: `{orderId: "ORD-{{rand}}", amount: 64.90, currency: "EUR", channel: "ONLINE", items: [...]}`).
- **Ponte interno** (`internal-mappings.json`): le 9 mappature di `docs/05 §7`, tutte abilitate.
- **Livelli** (`tiers.json`): BASE 0 ×1,00 carta · SILVER 1.000 ×1,25 · GOLD 3.000 ×1,50 · PLATINUM 7.000 ×2,00. Vantaggi testuali 2–4 per livello (es. GOLD: "Premi riservati", "1 giocata bonus sugli acquisti ≥ 50 €", "Assistenza prioritaria").
- **Valute** (`currencies.json`): `PTS` spendibile, `ROLLING_MONTHS` 12 con arrotondamento a fine mese · `STS` non spendibile, azzerata a chiusura edizione.
- **Edizioni** (`editions.json`): anno scorso `CLOSED` · anno corrente `ACTIVE` · prossimo `PLANNED`.
- **Attributi custom** (`attribute-definitions.json`): `city` (testo), `hasGasContract` (booleano), `preferredChannel` (enum `APP/WEB/STORE`), `householdSize` (numero).
- **Segmenti** (`segments.json`):

| Codice | Tipo | Criterio | Membri attesi | Usato da |
|---|---|---|---|---|
| `SEG-DIGITAL` | DYNAMIC | etichetta `ebill` **e** `directdebit` | Davide, Francesca, Sofia | contenuto "Grazie per essere digitale" |
| `SEG-NOT-EBILL` | DYNAMIC | senza etichetta `ebill` | Anna, Marco, Giulia, Chiara, Elisa, Matteo, Stefano | card "Passa alla bolletta digitale" |
| `SEG-AT-RISK` | DYNAMIC | `lastActivityDaysAgo > 45` | Stefano | campagna `CMP-REVIEW` (in pausa), pop-up di riattivazione (DRAFT) |
| `SEG-TORINO` | DYNAMIC | `city = "Torino"` | 4 membri | premio `RWD-EBIKE-RENT` |
| `SEG-VIP-EVENT` | STATIC | elenco manuale | Francesca, Davide | `RWD-PLATINUM-EVENT` (anteprima evento) |

## 4. Campagne (`campaigns.json`)

Convenzioni: `PTS` col moltiplicatore di livello salvo diversa indicazione; `STS` mai moltiplicati. "Portale" = visibile in PT-02.

| Codice | Stato | Trigger | Condizioni | Effetti | Limiti | Portale |
|---|---|---|---|---|---|:-:|
| `CMP-WELCOME` | LIVE | `member.registered` | — | 100 PTS fissi (senza molt.) | 1 / sempre | ✓ |
| `CMP-PURCHASE-BASE` | LIVE | `purchase.completed` | `data.amount ≥ 1` | 1 PTS/€ (floor, con molt. tier) + 1 STS/€ | 3 / giorno | ✓ |
| `CMP-WEEKEND-X2` | LIVE | `purchase.completed` | `context.dayOfWeek ∈ {SAT,SUN}` | `MULTIPLIER ×2` su PTS di `CMP-PURCHASE-BASE` | — | ✓ |
| `CMP-EBILL` | LIVE | `ebill.activated` | — | 300 PTS + 150 STS | 1 / sempre | ✓ |
| `CMP-DIRECT-DEBIT` | LIVE | `directdebit.activated` | — | 400 PTS + 200 STS | 1 / sempre | ✓ |
| `CMP-SELF-READING` | LIVE | `selfreading.submitted` | — | 50 PTS + 25 STS | 1 / mese | ✓ |
| `CMP-APP-DAILY` | LIVE | `app.login.daily` | — | 5 PTS (senza molt.) | 1 / giorno | ✓ |
| `CMP-PROFILE` | LIVE | `member.profile.completed` | — | 150 PTS | 1 / sempre | ✓ |
| `CMP-SURVEY` | LIVE | `survey.completed` | — | 80 PTS + `GRANT_PLAYS` 1 su `IW-AUTUNNO` | 2 / mese | ✓ |
| `CMP-REFERRAL-REFERRER` | LIVE | `referral.completed` | `data.role = REFERRER` | 500 PTS + 250 STS | 10 / edizione | ✓ |
| `CMP-REFERRAL-REFEREE` | LIVE | `referral.completed` | `data.role = REFEREE` | 200 PTS | 1 / sempre | — |
| `CMP-TIER-UP-BONUS` | LIVE | `tier.upgraded` | — | PTS `LOOKUP` su `data.newTier`: SILVER 200 · GOLD 500 · PLATINUM 1.000 (senza molt.) | — | — |
| `CMP-IW-PRIZE-POINTS` | LIVE · system | `instantwin.won` | `data.prizeType = POINTS` | PTS `FROM_FIELD data.points` (senza molt.) | — | — |
| `CMP-IW-PRIZE-COUPON` | LIVE · system | `instantwin.won` | `data.prizeType = COUPON` | `ISSUE_COUPON` `FROM_FIELD data.rewardCode` | — | — |
| `CMP-BADGE-BONUS` | LIVE | `badge.awarded` | — | 100 PTS (senza molt.) | — | — |
| `CMP-BIRTHDAY` | LIVE | `member.birthday` | — | 250 PTS + `SEND_MESSAGE` auguri | 1 / edizione | ✓ |
| `CMP-GOLD-PURCHASE-PLAY` | LIVE | `purchase.completed` | `data.amount ≥ 50` · pubblico tier GOLD, PLATINUM | `GRANT_PLAYS` 1 su `IW-AUTUNNO` | 1 / giorno | ✓ |
| `CMP-BLACK-FRIDAY` | IN_REVIEW | `purchase.completed` | calendario 27–30 nov | `MULTIPLIER ×3`; `requiresLegal`, budget 250.000 | budget | ✓ |
| `CMP-SUMMER-QUIZ` | ENDED | `quiz.completed` | `data.correctAnswers ≥ 7` | 120 PTS | 1 / sempre | — |
| `CMP-REVIEW` | PAUSED | `review.submitted` | `data.rating ≥ 1` · pubblico `SEG-AT-RISK` (da M6) | 30 PTS | 5 / mese | ✓ |

`CMP-BLACK-FRIDAY` è inviata in revisione da `luca.marketing` (`@today-1d`): popola BO-21 per `elena.legal`. `activity-history.json` contiene i contatori d'uso coerenti coi movimenti (Marco **non** ha ancora usato `CMP-EBILL` né `CMP-DIRECT-DEBIT`: servono a `SCN-DIGITAL`) e ~60 valutazioni storiche, incluse alcune **non scattate** con motivi diversi (per la scheda `actions` di BO-03).

Esempio canonico di calcolo (usato nei test): Giulia (SILVER ×1,25), acquisto 130 € in giorno feriale → `CMP-PURCHASE-BASE`: floor(130 × 1,25) = **162 PTS** + **130 STS** → STS 3.010 ≥ 3.000 → `tier.upgraded` GOLD → azione interna → `CMP-TIER-UP-BONUS` **+500 PTS**. Nel weekend i PTS base raddoppiano prima del moltiplicatore di livello: floor(130 × 2 × 1,25) = 325.

## 5. Premi, fasce, coupon, richieste

**Fasce** (`reward-bands.json`): F1 500 · F2 1.500 · F3 3.000 · F4 6.000 · F5 12.000. **Categorie**: Casa & energia, Tempo libero, Mobilità, Solidarietà, Esperienze.

| Codice | Nome | Tipo | Fascia | Stato | Stock | Visibilità | Evasione |
|---|---|---|---|---|---|---|---|
| `RWD-COFFEE-5` | Buono colazione 5 € | COUPON | F1 | LIVE | pool 500 | tutti | AUTO |
| `RWD-DONATION-TREE` | Pianta un albero | DONATION | F1 | LIVE | illimitato | tutti | AUTO (nessun codice) |
| `RWD-SHOP-10` | Buono shopping 10 € | COUPON | F2 | LIVE | pool 300 | tutti | AUTO |
| `RWD-CINEMA-2` | 2 ingressi cinema | COUPON | F2 | LIVE | pool 200 | tutti | AUTO |
| `RWD-BORRACCIA` | Borraccia termica Aurora | PHYSICAL | F2 | LIVE | 150 | tutti | MANUAL |
| `RWD-POWERBANK` | Powerbank solare | PHYSICAL | F3 | LIVE | **0 (esaurito)** | tutti | MANUAL |
| `RWD-SHOP-25` | Buono shopping 25 € | COUPON | F3 | LIVE | pool 150, **residui 9 (in esaurimento)** | tutti | AUTO |
| `RWD-BILL-20` | Sconto 20 € in bolletta | DIGITAL | F3 | LIVE | 1.000 · max 2 per membro | tutti | MANUAL |
| `RWD-SMART-PLUG` | Presa intelligente | PHYSICAL | F4 | LIVE | 80 | tutti | MANUAL |
| `RWD-EBIKE-RENT` | Weekend in e-bike | EXPERIENCE | F4 | LIVE | 40 | `SEG-TORINO` (da M6) | MANUAL |
| `RWD-WEEKEND` | Weekend benessere per 2 | EXPERIENCE | F5 | LIVE | 25 | GOLD, PLATINUM | MANUAL |
| `RWD-PLATINUM-EVENT` | Serata evento riservata | EXPERIENCE | F5 | LIVE | 20 | PLATINUM | MANUAL |
| `RWD-THERMOSTAT` | Termostato smart | PHYSICAL | F5 | DRAFT | 30 | tutti | MANUAL |
| `RWD-GIFT-50` | Gift card 50 € | COUPON | F4 | IN_REVIEW | pool 100 | tutti | AUTO |

**Pool** (`coupon-pools.json`): uno per premio COUPON, prefissi `CAF`, `SHP10`, `CIN`, `SHP25`, `GFT50`; codici generati con seme fisso. **Richieste** (`redemptions.json`): ~25 storiche in tutti gli stati · Sofia: `RWD-BORRACCIA` `CONFIRMED` in attesa di spedizione (`@today-2d`) · Davide: 2 coupon `ISSUED` (`RWD-COFFEE-5`, `RWD-CINEMA-2`), il secondo in scadenza tra 9 giorni · Francesca: una `CANCELLED` con rimborso · una `REJECTED` per saldo insufficiente (Marco, tre mesi fa) · una con `needsAttention` (timeout simulato).

## 6. Gioco

**Concorsi** (`contests.json`):

| Codice | Nome | Meccanica | Stato | Periodo | Regole | Montepremi |
|---|---|---|---|---|---|---|
| `IW-AUTUNNO` | Ruota d'Autunno | WHEEL | LIVE | `@today-20d` → `@today+40d` | 1 giocata gratuita al giorno · max 5 al giorno | 200 × 50 punti · 100 × 100 punti · 50 × coupon `RWD-COFFEE-5` · 5 × Powerbank solare (fisico) |
| `IW-NATALE` | Gratta e vinci di Natale | SCRATCH | IN_REVIEW | 1–24 dic | 1 gratuita al giorno | 300 × 100 punti · 60 × `RWD-SHOP-10` · 3 × Weekend benessere |
| `IW-ESTATE` | Pacchi d'Estate | GIFT | ENDED | 1 lug – 31 ago | — | chiuso: 142 vincite, 18 membri vincitori nello storico (nomi di fantasia oltre ai 12) |

Istanti di `IW-AUTUNNO`: 355, distribuzione `BUSINESS_HOURS`, seme `20260901`; quelli con orario già passato e non reclamati restano `OPEN` (quindi **la prima giocata della demo tende a vincere**: è voluto, ed è spiegato in BO-14). `IW-NATALE` è in revisione da `luca.marketing` → seconda voce in BO-21.

**Obiettivi e badge** (`achievements.json`, `badges.json`):

| Codice | Nome | Metrica | Traguardo | Periodo | Badge |
|---|---|---|---|---|---|
| `ACH-FIRST-PURCHASE` | Primo acquisto | COUNT `purchase.completed` | 1 | EVER | `BDG-FIRST` Rompighiaccio |
| `ACH-3-PURCHASES-MONTH` | Tris del mese | COUNT `purchase.completed` | 3 | MONTH, ripetibile | `BDG-TRIS` |
| `ACH-STREAK-7` | Sette giorni di fila | STREAK `app.login.daily` | 7 | EVER | `BDG-STREAK` Costanza |
| `ACH-DIGITAL` | Tutto digitale | DISTINCT_TYPES `ebill.activated` + `directdebit.activated` | 2 | EVER | `BDG-DIGITAL` Zero carta |
| `ACH-SELF-READER` | Letturista | COUNT `selfreading.submitted` | 6 | EDITION | `BDG-READER` |
| `ACH-BIG-SPENDER` | Grande spesa | SUM `purchase.completed.data.amount` | 1.000 | EDITION | `BDG-SPENDER` |

Progressi seed notevoli: Marco `ACH-DIGITAL` 0/2 (lo completa `SCN-DIGITAL`) · Giulia `ACH-3-PURCHASES-MONTH` 2/3 (il tier-up lo completa: doppia soddisfazione) · Matteo `ACH-STREAK-7` 5/7 · Francesca ha tutti i badge · Anna nessuno.

**Classifiche** (`leaderboards.json`): `LDB-MONTH-PTS` (PTS guadagnati nel mese, top 10) · `LDB-EDITION-STS` (STS dell'edizione, top 10). Punteggi per tutti i membri attivi + 15 nickname di fantasia per riempire le top 10.

## 7. Contenuti, messaggi, tema

**Contenuti** (`contents.json`, immagini in `web/public/demo/`):

| Codice | Tipo | Posizione | Titolo | Pubblico | Stato | CTA |
|---|---|---|---|---|---|---|
| `CNT-HERO-AUTUNNO` | CARD | HOME_HERO | "Gira la Ruota d'Autunno" | tutti | LIVE | concorso `IW-AUTUNNO` |
| `CNT-EBILL` | CARD | HOME_GRID | "Passa alla bolletta digitale: +300 punti" | `SEG-NOT-EBILL` | LIVE | campagna `CMP-EBILL` |
| `CNT-FRIEND` | CARD | HOME_GRID | "Porta un amico, vincete in due" | tutti | LIVE | pagina PT-11 |
| `CNT-DIGITAL-THANKS` | CARD | HOME_GRID | "Grazie per essere digitale" | `SEG-DIGITAL` | LIVE | pagina PT-09 |
| `CNT-SELF-READING` | CARD | HOME_GRID | "Invia l'autolettura: +50 punti ogni mese" | tutti | LIVE | campagna `CMP-SELF-READING` |
| `POP-WELCOME` | POPUP | — | "Benvenuto nel Club Aurora" | iscritti da < 7 giorni | LIVE · `ONCE` | pagina PT-02 |
| `POP-WEEKEND` | POPUP | — | "Questo weekend i punti valgono doppio" | tutti · solo sab–dom | LIVE · `ONCE_PER_DAY` | pagina PT-02 |
| `POP-COMEBACK` | POPUP | — | "Ci sei mancato" | `SEG-AT-RISK` | DRAFT | — |
| `BNR-CATALOG` | BANNER | CATALOG_TOP | "Nuovi premi in fascia 1.500" | tutti | LIVE | — |
| `CNT-CONTEST-RULES` | CARD | CONTEST | "Una giocata gratis ogni giorno" | tutti | LIVE | — |
| `WIN-POINTS-50`, `WIN-POINTS-100`, `WIN-COFFEE`, `WIN-POWERBANK` | CARD | WIN | una per premio di `IW-AUTUNNO` | — | LIVE | attività / i miei premi |
| `CNT-BLACK-FRIDAY` | CARD | HOME_HERO | "Black Friday: punti ×3" | tutti | SCHEDULED (27 nov) | campagna |

**Template** (`message-templates.json`): gli 11 codici `MSG-*` elencati in `engagement-service §6` + `MSG-BIRTHDAY`; testi brevi con segnaposto (`"Hai guadagnato {{amount}} punti con {{campaignName}}"`). **Regole** (`notification-rules.json`): le 11 regole minime. **Inbox** (`inbox.json`): 3–8 messaggi per membro coerenti coi movimenti; non letti: Marco 3, Chiara 1 (scadenza), Sofia 1 (richiesta confermata). **Tema** (`theme.json`): "Club Aurora", colori `docs/07 §5.3`, hero "Ogni gesto conta". **Webhook**: uno, disabilitato.

## 8. Scenari guidati (`scenarios.json`)

Passo: `{delayMs, memberId, type, source, data, at?, note, expect?}`; `at` è un'espressione del §1 (default `@now`).

| Codice | Protagonista | Passi | Cosa mostra |
|---|---|---|---|
| `SCN-ONBOARDING` | Anna | `app.login.daily` → completa profilo (`member.profile.completed`) → `purchase.completed` 24,90 € | benvenuto già avuto; +5, +150, primo acquisto → badge `BDG-FIRST` → bonus badge +100 (catena di azioni interne) |
| `SCN-TIER-UP` | Giulia | `purchase.completed` 130 € `at: @lastWeekdayT10:30` | +162 PTS, +130 STS → GOLD → bonus 500 → tris del mese → messaggi; la tessera cambia materiale |
| `SCN-DIGITAL` | Marco | `ebill.activated` → `directdebit.activated` | +300/+150, +400/+200 → `ACH-DIGITAL` → badge → +100; entra in `SEG-DIGITAL` al ricalcolo |
| `SCN-REFERRAL` | Elisa (+ Marco) | `purchase.completed` 45 € | `referral.completed` ×2 → Marco +500/+250 STS, Elisa +200 |
| `SCN-WEEKEND-BURST` | 6 membri | 12 acquisti `at: @lastSaturday…` in 20 s | volume nel flusso live, moltiplicatore ×2, limite 3/giorno che scatta |
| `SCN-DUPLICATE` | Marco | stesso evento (stesso `id`) due volte | `expect: DUPLICATE`, un solo accredito |
| `SCN-BAD-EVENT` | — | `amount` negativo · fonte disabilitata · membro inesistente · membro `BLOCKED` | `REJECTED/INVALID_DATA`, `SOURCE_DISABLED`, `UNMATCHED`, `MEMBER_NOT_ACTIVE` in BO-26 |
| `SCN-POISON` | Marco | azione con flag demo `data._poison=true` | il consumer fallisce 3 volte → DLQ (BO-27), tracciato `FAILED` |
| `SCN-SMOKE` | Marco | `app.login.daily` | usato da `scripts/smoke.sh`: +5 PTS entro 15 s |

Il flag `_poison` è onorato **solo** col profilo `demo` (campaign-service lancia un'eccezione non ritentabile).

## 9. Storico sintetico (`insight-synthetic.json`)
Generatore con seme fisso per 90 giorni di `metric_daily` (`synthetic=true`): baseline per metrica (azioni/giorno 180 ± 25 %, PTS emessi 21.000, spesi 9.500, scaduti 600, richieste 14, giocate 95, vincite 11), stagionalità settimanale (+35 % sab–dom per gli acquisti), crescita lineare dei membri da 3.100 a 3.480, ripartizione per fonte (ecommerce 38 %, app 31 %, billing 14 %, crm 9 %, partner 8 %). I KPI "membri totali" del PoC sommano il sintetico ai 12 reali: l'origine è dichiarata in legenda.

## 10. Immagini
`web/public/demo/rewards/*.webp` (14), `contents/*.webp` (8), `contests/*.webp` (3), `badges/*.svg` (6), `logo-aurora.svg`. In M0–M3 bastano **segnaposto generati** (SVG con gradiente del tema + icona `lucide` + nome): script `scripts/gen-placeholders.mjs`. Nessuna immagine da banche dati con licenze restrittive, nessun marchio reale.

## 11. Regole di `check-seed.mjs`
1. JSON validi e conformi agli schemi in `seed/_schemas/`.
2. Ogni `memberId`, `campaignCode`, `rewardCode`, `contestCode`, `badgeCode`, `segmentCode`, `templateCode`, `tier`, tipo azione citato **esiste** nel file proprietario.
3. `wallets.json`: Σ lotti attivi = saldo; tier coerente con `periodSts` **o** con lo storico livelli (Stefano); esattamente 4 membri GOLD+PLATINUM.
4. `campaigns.json`: condizioni su `data.*` compatibili con lo schema del tipo azione trigger; effetti che puntano a concorsi/premi/template esistenti.
5. Premio COUPON ⇒ pool esistente con codici disponibili ≥ stock dichiarato; costo = soglia della fascia.
6. Montepremi: quantità premio coupon ≤ disponibilità del pool.
7. Ogni contenuto `WIN` punta a un premio in palio esistente; ogni premio in palio ha la sua card `WIN`.
8. Ogni espressione di data è valida per la grammatica del §1.
9. Nessuna stringa vietata (nomi di aziende reali, domini diversi da `example.org`).
