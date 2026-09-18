# Catalogo funzionale · Feature catalogue

> 🇮🇹 Che cosa fa la piattaforma, area per area: capacità, requisito (`RF-nn`), servizio responsabile e dove si
> configura. È il documento da aggiornare quando si aggiunge o si cambia una funzionalità.
> 🇬🇧 What the platform does, area by area: capability, requirement (`RF-nn`), owning service and where it is
> configured. Update this document whenever a capability is added or changed.

Legenda stato · Status legend: **✅ disponibile · available** · **🟡 parziale · partial** · **⬜ pianificato · planned**
(dettaglio in [`ROADMAP.md`](ROADMAP.md)).

---

## 1. Ingestione e contratti di evento · Ingestion and event contracts

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Ingressi multipli | REST sincrono, consumo Kafka, file su storage/SFTP con lo stesso contratto logico | Synchronous REST, Kafka consumption, files on storage/SFTP under the same logical contract | RI-01…04 | `ingress-adapters` | ✅ |
| Evento canonico | Ogni fonte diventa un CloudEvents 1.0 con tipo versionato e chiave di idempotenza | Every source becomes a CloudEvents 1.0 message with a versioned type and idempotency key | RI-01 | `common` | ✅ |
| Idempotenza | Tabella delle chiavi viste: un doppio invio non produce doppi punti | Seen-keys table: a duplicate delivery never yields duplicate points | RI-03 | `ingress-adapters` | ✅ |
| Schemi dei tipi azione | Attributi tipizzati e obbligatori per tipo di azione; scarto motivato; modalità permissiva per fonti legacy | Typed, required attributes per action type; rejection with reason; lenient mode for legacy sources | RF-98 | `ingress-adapters` + CMS `event-schemas` | ✅ |
| Righe di transazione | Un'azione può portare righe (SKU, categoria, marca, quantità, importo, etichette) usate da regole e segmenti | An action can carry line items (SKU, category, brand, quantity, amount, labels) used by rules and segments | RF-62 | `common`, `rules-engine` | ✅ |
| Storni | `TRANSACTION_RETURNED` riferisce la chiave originale e genera il movimento inverso | `TRANSACTION_RETURNED` references the original key and produces the inverse movement | RF-04, RI-08 | `ledger` | ✅ |
| Catalogo prodotti | Copia in sola lettura (CSV) usata per arricchire le righe all'ingresso | Read-only copy (CSV) used to enrich line items at ingestion time | RF-101 | `ingress-adapters` | ✅ |
| Codici promo e QR | Lotti monouso o multiuso con validità e limiti; l'uso valido genera un'azione premiante | Single- or multi-use code batches with validity and limits; a valid use emits a rewarding action | RF-69 | `ingress-adapters` + CMS `promo-codes` | ✅ |
| Check-in geolocalizzato | Azione premiata solo entro un raggio configurato, una volta al giorno per luogo | Action rewarded only within a configured radius, once per day per place | RF-67 | `ingress-adapters` | ✅ |
| Coda di scarto | Eventi non validi in DLQ con motivo, riprocessabili dal backoffice con la stessa chiave | Invalid events land in a DLQ with a reason and can be reprocessed from the back office with the same key | RF-45 | `ingress-adapters` | ✅ |
| Limitazione di frequenza | Quote per fonte all'ingresso | Per-source quotas at the edge | RI-04 | `ingress-adapters` | ✅ |
| Azioni interne | Ciò che la piattaforma stessa produce (adesione, completamenti, vincite, esposizioni) rientra come azione premiante | What the platform itself produces (enrolment, completions, wins, impressions) re-enters as a rewarding action | RF-60 | tutti · all | ✅ |

---

## 2. Campagne e regole · Campaigns and rules

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Trigger | Transazione, reso, evento interno, evento custom da schema, achievement, codice riscattato, pianificazione | Transaction, return, internal event, schema-defined custom event, achievement, redeemed code, schedule | RF-80 | `rules-engine` | ✅ |
| Struttura | Fino a 6 regole per campagna, fino a 30 condizioni per regola (AND dentro la regola, regole indipendenti) | Up to 6 rules per campaign, up to 30 conditions per rule (AND within a rule, rules independent) | RF-80 | `rules-engine` | ✅ |
| Effetti | Aggiungi/sottrai unità (fisse, per euro, da formula), assegna premio, imposta/rimuovi attributo, assegna badge, assegna tier, emetti evento | Add/deduct units (fixed, per currency unit, formula), grant reward, set/remove attribute, grant badge, assign tier, emit event | RF-81 | `rules-engine` | ✅ |
| Limiti e budget | Esecuzioni per membro per periodo (ora, giorno, settimana, mese, anno, totale), budget globale in unità, unità per membro per periodo; gli storni restituiscono budget | Executions per member per period (hour, day, week, month, year, total), global units budget, units per member per period; reversals return budget | RF-82 | `rules-engine` | ✅ |
| Scadenza per effetto | Ogni effetto può fissare scadenza e sospensione delle unità con una formula sulla data dell'azione | Each effect can set units expiry and pending window via a formula on the action date | RF-83 | `rules-engine` → `ledger` | ✅ |
| Espressioni sicure | Contesto di sola lettura su transazione, righe, membro (tier, segmenti, badge, attributi), wallet (attive, maturate, spese, sospese, bloccate, scadute), evento, presentatore; funzioni di arrotondamento, date, collezioni, distribuzione per fasce | Read-only context over transaction, lines, member (tier, segments, badges, attributes), wallets (active, earned, spent, pending, blocked, expired), event, referrer; rounding, date, collection and tiered-distribution functions | RF-84 | `rules-engine` | ✅ |
| Targeting | Tier, segmenti, canali, finestre temporali; insiemi vuoti = tutti | Tiers, segments, channels, time windows; empty sets mean "all" | RF-65 | `rules-engine` | ✅ |
| Referral multilivello | Le azioni del presentato premiano la catena dei presentatori con effetti per livello e tetto per presentatore | Actions by the referred member reward the referrer chain with per-level effects and a per-referrer cap | RF-85 | `rules-engine`, `member-service` | ✅ |
| Automazioni pianificate | Campagne giornaliere, settimanali, mensili, compleanno, anniversario su un pubblico, con limiti di piattaforma | Daily, weekly, monthly, birthday and anniversary campaigns over an audience, with platform limits | RF-86 | `rules-engine` | ✅ |
| Campagne a catena | Segmento "campagna completata negli ultimi N giorni" come pubblico di una campagna successiva | A "campaign completed in the last N days" segment as the audience of a follow-up campaign | RF-86 | `segment-service` | ✅ |
| Simulatore | Valuta un membro reale o descritto su campagne anche in bozza, mostrando effetti e motivi di scarto, senza applicare nulla | Evaluates a real or synthetic member against campaigns (including drafts), showing effects and rejection reasons, applying nothing | RF-82 | `rules-engine` | ✅ |
| Visibilità | Tutti, segmenti, tier, nascosta (la visibilità non limita l'esecuzione) | All, segments, tiers, hidden (visibility does not limit execution) | RF-82 | CMS `campaigns` | ✅ |

---

## 3. Unità, wallet e registro · Units, wallets and ledger

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Wallet configurabili | Codice, nomi singolare/plurale, traduzioni, politica di scadenza (nessuna, dopo N giorni, fine mese, fine anno, data annuale, fine anno programma successivo), sospensione, limiti globali e per membro, saldo negativo, stato | Code, singular/plural unit names, translations, expiry policy (none, after N days, end of month, end of year, annual date, end of next program year), pending window, global and per-member caps, negative balance, status | RF-87 | `ledger` + CMS `wallets` | ✅ |
| Doppia valuta | Unità premio (si spendono) e unità status (qualificano il tier, non si spendono): spendere non fa perdere lo stato | Reward units (spendable) and status units (tier-qualifying, non-spendable): spending never costs status | RF-05 | `ledger`, `tier-service` | ✅ |
| Operazioni | Accredito, spesa, blocco/sblocco, scadenza, storno, trasferimento tra membri, rettifica manuale, etichette e commento | Earn, spend, block/unblock, expiry, reversal, member-to-member transfer, manual adjustment, labels and comment | RF-88 | `ledger` | ✅ |
| Registro immutabile | Append-only con trigger; saldo materializzato nella stessa transazione; audit completo | Append-only enforced by triggers; balance materialised in the same transaction; full audit | RF-09 | `ledger` | ✅ |
| Unità in sospeso | Punti maturati ma non spendibili prima della finestra di reso, visibili come "in sospeso" | Units earned but not spendable until the return window closes, shown as "pending" | RF-66 | `ledger` | ✅ |
| Vista wallet | Unità attive, maturate, spese, in sospeso, bloccate, scadute per membro, disponibili a espressioni e area membro | Active, earned, spent, pending, blocked and expired units per member, available to expressions and the member area | RF-89 | `ledger` | ✅ |
| Scadenze pianificate | Job di scadenza e rilascio delle sospensioni, con evento e notifica di scadenza imminente | Scheduled expiry and pending-release jobs, with events and upcoming-expiry notifications | RF-18, RF-113 | `ledger` | 🟡 |
| Passività punti | Unità in circolazione per wallet, per la contabilità | Outstanding units per wallet, for accounting | RF-46 | warehouse | ✅ |

---

## 4. Livelli · Tiers

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Tier set | Fino a 8 condizioni (unità attive, unità maturate, spesa totale, mesi dall'adesione, unità nel periodo, campo custom) in AND o OR, con soglia per tier | Up to 8 conditions (active units, earned units, total spend, months since enrolment, units in period, custom field) in AND or OR, with a threshold per tier | RF-105 | `tier-service` + CMS `tier-sets` | ✅ |
| Upgrade | Immediato al superamento della soglia | Immediate on crossing the threshold | RF-10 | `tier-service` | ✅ |
| Discesa | Nessuna, automatica, all'anniversario, a date fisse, a intervallo dall'ultima promozione, annuale di un livello ("discesa morbida") | None, automatic, on anniversary, on fixed dates, interval since last promotion, annual single-level step ("soft landing") | RF-11, RF-106 | `tier-service` | ✅ |
| Benefici | Continuativi (sconto percentuale, moltiplicatore punti) e una tantum (premi d'ingresso) | Recurring (percentage discount, points multiplier) and one-off (welcome rewards) | RF-12, RF-107 | `tier-service` | ✅ |
| Progresso | Quanto manca al tier successivo, per condizione | Distance to the next tier, per condition | RF-107 | `tier-service` | ✅ |
| Override manuale | Assegnazione con causale, autore e scadenza; sopra soglia richiede doppia approvazione | Manual assignment with reason, author and expiry; above a threshold requires four-eyes approval | RF-70, RF-18 | `tier-service` | ✅ |

---

## 5. Premi e riscatti · Rewards and redemption

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Tipi di premio | Buono, bene fisico, servizio, accredito in fattura, codice sconto percentuale, codice sconto a valore, servizio gratuito, invito a evento, omaggio, donazione | Voucher, physical good, service, bill credit, percentage discount code, fixed-value discount code, free service, event invitation, gift, donation | RF-74 | `catalog-redemption` | ✅ |
| Valore dinamico | Buoni con valore calcolato da formula sulla transazione, con filtri per categoria/marca/SKU e arrotondamento | Vouchers with a value computed by a formula over the transaction, with category/brand/SKU filters and rounding | RF-102 | `catalog-redemption` | ✅ |
| Conversione unità | Tasso unità→valore con minimo, massimo e passo | Units-to-value rate with minimum, maximum and step | RF-102 | `catalog-redemption` | ✅ |
| Lotti di codici | Codici caricati, prelievo con lock (nessun doppio uso), validità in giorni, allarme sotto scorta | Uploaded code pools, locked draw (no double issue), validity in days, low-stock alert | RF-74 | `catalog-redemption` | ✅ |
| Politica di riscatto | Costo in unità, limiti per membro (totali e giornalieri), finestre di visibilità e di riscatto, segmenti target, tier minimo, categoria, immagine, "in evidenza" | Unit cost, per-member limits (total and daily), visibility and redemption windows, target segments, minimum tier, category, image, "featured" | RF-75 | CMS `rewards` | ✅ |
| Stati di evasione | In attesa, approvato, in preparazione, pronto per la spedizione, spedito, completato, reso, rifiutato, annullato, emesso, usato | Pending, approved, packing, waiting for shipping, shipped, completed, returned, rejected, cancelled, issued, used | RF-103 | `catalog-redemption` | ✅ |
| Rimborso automatico | Annullo, rifiuto e reso restituiscono le unità con movimento tracciato | Cancellation, rejection and return refund the units with a tracked movement | RF-103 | `catalog-redemption` → `ledger` | ✅ |
| Cambio massivo | Transizione di stato su molti riscatti in una chiamata, con storico | Bulk state transition over many redemptions in one call, with history | RF-103 | `catalog-redemption` | ✅ |
| Premi automatici | Una regola o un tier possono assegnare un premio senza costo in punti | A rule or a tier can grant a reward at no point cost | RF-76 | `catalog-redemption` | ✅ |
| Paga con i punti | Unità convertite in sconto sul carrello, con chiave di storno | Units converted into a cart discount, with a reversal key | RF-104 | `catalog-redemption` | ✅ |

---

## 6. Gamification

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Achievement | Obiettivo complessivo, finestra mobile o serie consecutiva (giorno, settimana, mese, anno), progresso per occorrenze o valore di attributo, valori unici, limiti di eventi e completamenti; definizione versionata | Overall goal, rolling window or consecutive streak (day, week, month, year), progress by occurrences or attribute value, unique values, event and completion limits; versioned definition | RF-90 | `engagement-service` | ✅ |
| Challenge | Fino a 6 milestone in qualunque ordine, dirette o da referral, finestre di disponibilità, visibilità, effetti su avanzamento o completamento, limiti e budget | Up to 6 milestones in any order, direct or referral-driven, availability windows, visibility, effects on progress or completion, limits and budgets | RF-91 | `engagement-service` | ✅ |
| Badge | Codice immutabile, assegnazione manuale, da campagna o da challenge, conteggio, uso in condizioni e segmenti | Immutable code, manual, campaign- or challenge-driven grant, counting, use in conditions and segments | RF-92 | `engagement-service` | ✅ |
| Classifiche | Metrica e periodo senza retroattività, gruppi per attributo, primi 1000 per gruppo, pari merito, ricalcolo periodico, massimo di classifiche attive | Metric and period without retroactivity, groups by attribute, top 1000 per group, tie handling, periodic recomputation, cap on active leaderboards | RF-93 | `engagement-service` | ✅ |
| Cicli premianti | Premi, unità o badge ai primi N per gruppo alla chiusura di ogni ciclo, in modo idempotente | Rewards, units or badges to the top N per group at each cycle close, idempotently | RF-94 | `engagement-service` | ✅ |
| Missioni del programma | Le missioni del programma annuale sono challenge con anno di programma | Annual-program missions are challenges bound to a program year | RF-21 | `engagement-service` | ✅ |
| Aggiornamento manuale | Il progresso si può correggere da API/backoffice con causale tracciata | Progress can be corrected from API/back office with a tracked reason | RF-90 | `engagement-service` | ✅ |

---

## 7. Concorsi e instant win · Contests and instant win

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Istanti vincenti | Istanti pre-generati con generatore crittografico, opzionalmente pesati per fascia oraria; vince la prima giocata valida dopo l'istante | Winning moments pre-generated with a cryptographic RNG, optionally weighted by time band; the first valid play after a moment wins | RF-30…32 | `contest-service` | ✅ |
| Montepremi | Nessun premio assegnato due volte, nessuno sforamento: assegnazione con lock di riga | No prize issued twice, no over-award: row-locked assignment | RF-33 | `contest-service` | ✅ |
| Registro giocate | Hash concatenato: qualunque alterazione è rilevabile; export per verifica esterna | Hash-chained log: any tampering is detectable; export for external verification | RF-34, RF-37 | `contest-service` | ✅ |
| Configurazione bloccata | A concorso avviato la configurazione non è modificabile e il modulo non viene rilasciato | Once a contest is running the configuration is frozen and the module is not redeployed | RF-35 | CMS + `contest-service` | ✅ |
| Estrazione di recupero | Premi non assegnati gestiti a fine concorso secondo il regolamento | Unclaimed prizes handled at contest close per the published rules | RF-36 | `contest-service` | ✅ |
| Ruota | Spicchi pesati, costo in unità, giri per periodo, scorte, budget, vincite per membro; con premi di valore è agganciata agli istanti vincenti | Weighted segments, unit cost, spins per period, stock, budget, wins per member; when prizes have value it is backed by winning moments | RF-95 | `contest-service` | ✅ |
| Vincite come azioni | Partecipazione e vincita rientrano come azioni premianti e sono premiabili dalle regole | Participation and wins re-enter as rewarding actions and can be rewarded by rules | RF-38 | `contest-service` | ✅ |

---

## 8. Membri, identità, consensi · Members, identity, consent

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Adesione e stati | Adesione con canale, stati attivo / sospeso (matura ma non spende) / chiuso / anonimizzato; ogni cambiamento pubblica un evento | Enrolment with channel, states active / suspended (earns but cannot spend) / closed / anonymised; every change publishes an event | RF-72 | `member-service` | ✅ |
| Etichette e campi custom | Etichette chiave/valore libere e campi custom tipizzati (testo con regex, numero con intervallo, booleano, data, selezione da collezione, gruppi ripetibili) con permesso di modifica per ruolo | Free key/value labels and typed custom fields (regex text, ranged number, boolean, date, collection-backed select, repeatable groups) with per-role edit permission | RF-72, RF-99 | `member-service` | ✅ |
| Identificatori | Email/telefono in hash, tessera generata, id CRM, id ERP, identificatore OIDC; obbligatorietà, univocità e priorità di abbinamento configurabili | Hashed email/phone, generated card number, CRM id, ERP id, OIDC subject; configurable requiredness, uniqueness and matching priority | RF-108 | `member-service` | ✅ |
| Grafo identità | Identificatori multi-tipo → membro, risoluzione deterministica, collegamenti probabilistici a confidenza ridotta, merge ripartibile con chiave di idempotenza e trasferimento di unità, unmerge che rimette identificatori e unità sul membro riattivato (dichiarando l'ammanco se erano state spese), alias storici | Multi-type identifiers → member, deterministic resolution, lower-confidence probabilistic links, resumable merge with an idempotency key and units transfer, unmerge that restores identifiers and units to the reactivated member (declaring the shortfall if they were spent), historical aliases | RF-136 | `identity-mapping` | ✅ |
| Referral | Codice stabile e leggibile, trigger configurabile (adesione, prima azione, prima transazione), tetto annuo per presentatore | Stable human-readable code, configurable trigger (enrolment, first action, first transaction), yearly cap per referrer | RF-68 | `member-service` | ✅ |
| Consensi | Finalità, base giuridica, versione dell'informativa, data e scadenza, prova, storico immutabile, revoca propagata in secondi | Purpose, legal basis, notice version, timestamp and expiry, proof, immutable history, revocation propagated in seconds | RF-135 | `member-service` + CMS `consent-purposes` | ✅ |
| Diritti dell'interessato | Export di portabilità (profilo, saldi, movimenti, riscatti, giocate) e anonimizzazione che preserva gli obblighi di legge | Portability export (profile, balances, movements, redemptions, plays) and anonymisation that preserves legal obligations | RF-73 | `member-service` | ✅ |
| Import | Import CSV di membri e trasferimenti dal backoffice | CSV import of members and transfers from the back office | RF-72, RF-114 | `member-service` | ✅ |

---

## 9. Segmenti e dati di riferimento · Segments and reference data

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Segmenti | 30 criteri: anniversario e registrazione recente, numero/valore/media/recenza delle azioni, valore giornaliero, periodo, SKU/etichette/marche/categorie acquistate, canale e quota per canale, etichette, campi custom e date dei campi custom, liste statiche, tier, saldo, badge, achievement, challenge, campagne completate, collezioni, consensi | 30 criteria: anniversary and recent registration, action count/value/average/recency, daily value, period, purchased SKUs/labels/brands/categories, channel and channel share, labels, custom fields and custom-field dates, static lists, tier, balance, badges, achievements, challenges, completed campaigns, collections, consents | RF-71, RF-109 | `segment-service` | ✅ |
| Ricalcolo | Notturno e a evento; simulatore di appartenenza; export CSV dei soli identificatori | Nightly and event-driven; membership simulator; CSV export of identifiers only | RF-71 | `segment-service` | ✅ |
| Uso | Target di regole, premi, contenuti, concorsi, automazioni e policy decisionali | Used as a target by rules, rewards, content, contests, automations and decision policies | RF-71 | tutti · all | ✅ |
| Collezioni di valori | Liste riutilizzabili (fino a 1.000.000 di valori) usate in condizioni, espressioni e selezioni | Reusable value lists (up to 1,000,000 entries) used in conditions, expressions and selects | RF-100 | `segment-service` | ✅ |

---

## 10. Decisioni e personalizzazione · Decisioning and personalisation

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Customer 360 | Documento per membro con identità, wallet, tier, segmenti, azioni recenti, RFM, canale preferito, gamification, campagne, offerte, riscatti, giocate, contatti recenti per canale, rischio, consensi, previsioni; una sola lettura | Per-member document with identity, wallets, tier, segments, recent actions, RFM, preferred channel, gamification, campaigns, offers, redemptions, plays, recent contacts per channel, risk, consents, predictions; a single read | RF-125, RF-126 | `read-model` | ✅ |
| Motore decisionale | Candidati → vincoli → punteggio → scelta, come funzione pura testabile; strategie di punteggio a priorità, a pesi o a formula | Candidates → constraints → scoring → choice, as a testable pure function; priority, weighted or formula scoring strategies | RF-127 | `decision-service` | ✅ |
| Vincoli | Cap di contatto per canale, ore di silenzio, spaziatura delle offerte, budget di unità, cooldown, limiti per periodo, segmenti di esclusione, consensi richiesti, livello di rischio bloccante | Per-channel contact caps, quiet hours, offer spacing, units budget, cooldown, period limits, exclusion segments, required consents, blocking risk level | RF-127 | `decision-service` + CMS `decision-policies` | ✅ |
| Catalogo offerte | Offerte con condizione di eligibilità sul contesto, valore, costo, canali, validità | Offers with a context-based eligibility condition, value, cost, channels and validity | RF-128 | CMS `offers` | ✅ |
| Next Best Action | Chiamata sincrona per canale: azione, offerta, canale, motivo, scadenza | Synchronous per-channel call: action, offer, channel, reason, expiry | RF-129 | `decision-service` | ✅ |
| Decision log | Registro append-only con policy e versione, candidati, scelti, scartati e motivo; interrogabile da API, backoffice e BI | Append-only log with policy and version, candidates, selected, rejected and reason; queryable from API, back office and BI | RF-129 | `decision-service` | ✅ |
| Previsioni | Porta con chiavi standard (rischio abbandono, propensione all'acquisto, accettazione premi, propensione all'offerta, coinvolgimento, valore cliente, affinità di categoria); provider a regole, modello locale o servizio esterno, con routing per chiave e ripiego | Port with standard keys (churn risk, purchase propensity, reward acceptance, offer propensity, engagement, customer value, category affinity); rule-based, local-model or external providers, with per-key routing and fallback | RF-130 | `decision-service` | ✅ |
| Confine dell'AI | Le previsioni influenzano solo il punteggio delle azioni discrezionali: mai punti, saldi, eligibilità o status | Predictions only influence the scoring of discretionary actions: never points, balances, eligibility or status | RF-130 | `decision-service` | ✅ |
| Esperimenti | Controllo e varianti con assegnazione deterministica, quota di traffico, ambito per evento o segmento, sovrascritture della policy, esposizioni registrate, misura dell'effetto incrementale | Control and variants with deterministic assignment, traffic share, scope by event or segment, policy overrides, recorded exposures, uplift measurement | RF-134 | `decision-service` + warehouse | ✅ |
| Simulazione | Prova di una policy in bozza su un membro reale o descritto, senza effetti | Dry-run of a draft policy against a real or synthetic member, with no effects | RF-129 | `decision-service`, CMS | ✅ |

---

## 11. Antifrode e abusi · Fraud and abuse

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Segnali | Frequenza di riscatto, account multipli per dispositivo, accumulo anomalo, creazione rapida di account, spostamenti impossibili, abuso di codici, rapporto resi, anomalie di dispositivo, velocità | Redemption frequency, multi-account per device, abnormal earning, rapid account creation, impossible travel, code abuse, refund ratio, device anomaly, velocity | RF-131 | `fraud-service` | ✅ |
| Punteggio | Peso, soglia e saturazione per segnale → punteggio 0…100, livello e motivi con valori osservati | Weight, threshold and saturation per signal → score 0…100, level and reason codes with observed values | RF-131 | `fraud-service` + CMS `fraud-rules` | ✅ |
| Effetti | Evento al cambio di livello, vincolo per il motore decisionale, blocco automatico e reversibile delle unità | Event on level change, constraint for the decision engine, automatic and reversible units block | RF-131 | `fraud-service` → `ledger` | ✅ |
| Rivalutazione | Periodica con decadimento; rivalutazione puntuale su richiesta dell'operatore | Periodic with decay; on-demand reassessment from the operator console | RF-131 | `fraud-service` | ✅ |
| Governo dei falsi positivi | Soglie, pesi e attivazione per segnale modificabili dal backoffice, con simulazione | Thresholds, weights and per-signal enablement editable from the back office, with simulation | RF-131 | CMS `fraud-rules` | ✅ |

---

## 12. Consegna e messaggistica · Delivery and messaging

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Adattatori di canale | App, sito (inbox), push, email, SMS, webhook (CRM), coda operatore; push/email/SMS parlano con il fornitore configurato e ripiegano sull'invio su log quando non c'è | App, site (inbox), push, email, SMS, webhook (CRM), operator queue; push/email/SMS call the configured provider and fall back to log delivery when none is set | RF-132 | `notifier` | ✅ |
| Instradamento | Ordine dei canali per azione, canali abilitati, limiti giornalieri, ore di silenzio con canale di ripiego | Channel order per action, enabled channels, daily caps, quiet hours with fallback channel | RF-132 | CMS `delivery-routing` | ✅ |
| Modelli di messaggio | Per tipo evento, canale e lingua, con segnaposto sul payload | Per event type, channel and language, with payload placeholders | RF-77 | `notifier` | ✅ |
| Webhook | Sottoscrizioni per tipo evento, firma HMAC-SHA256, intestazioni statiche, tentativi con backoff, rotazione del segreto | Per-event-type subscriptions, HMAC-SHA256 signature, static headers, backoff retries, secret rotation | RF-78, RF-113 | `notifier` | 🟡 |
| Tracciamento | Ogni consegna è un evento (esposizione, invio, accettazione) e alimenta pressione commerciale e BI | Every delivery is an event (impression, send, acceptance) feeding contact pressure and BI | RF-132 | `notifier` | ✅ |
| Nessun doppio contatto | Le consegne fallite non sono ripetute automaticamente: restano nel registro per un reinvio deciso | Failed deliveries are not auto-retried: they stay in the log for a deliberate resend | RF-132 | `notifier` | ✅ |
| Reinvio manuale | Coda delle consegne fallite in console; il reinvio ri-renderizza dal modello corrente, può cambiare canale e non si ripete due volte | Failed-delivery queue in the console; a resend re-renders from the current template, may switch channel and cannot run twice | RF-132 | `notifier` | ✅ |

---

## 13. Backoffice e amministrazione · Back office and administration

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Un solo pannello | Contenuti (card, pop-up, landing) e configurazione (regole, campagne, premi, tier, wallet, segmenti, policy) nello stesso backoffice | Content (cards, pop-ups, landing pages) and configuration (rules, campaigns, rewards, tiers, wallets, segments, policies) in one back office | RF-40 | `cms` | ✅ |
| Workflow di approvazione | Due livelli per i contenuti; tre livelli con revisione legale per regole, tier, concorsi e programma; blocco a concorso avviato | Two-level for content; three-level with legal review for rules, tiers, contests and the program; frozen once a contest is running | RF-42 | `cms` | ✅ |
| Versioni e audit | Ogni documento è versionato con autore e data; la versione finisce nelle decisioni e nelle valutazioni | Every document is versioned with author and date; the version is recorded in decisions and assessments | RF-41 | `cms` | ✅ |
| Ruoli e permessi | La collezione `roles` esiste con i suoi 26 permessi, ma **nessuna collezione la usa**: ogni operatore autenticato può modificare tutto e crearne altri con gli stessi poteri. Dare un accesso al backoffice oggi significa dare l'intero backoffice (vedi [pubblicazione su Vercel](DEPLOY-VERCEL.md#punti-aperti-che-il-rilascio-non-risolve)) | The `roles` collection exists with its 26 permissions, but **no collection enforces it**: any authenticated operator can change anything and create more operators | RF-43, RF-112 | `cms` | 🟡 |
| Postazione operatore | Ricerca membro, registrazione azione, uso di un codice, stato di un premio, tier manuale, coda contatti, rischio, identità e merge, consensi con prova | Member lookup, action capture, code use, reward state, manual tier, contact queue, risk, identity and merge, consents with proof | RF-75 | `web/bff` | ✅ |
| Localizzazione | Contenuti e modelli in italiano e inglese | Content and templates in Italian and English | RF-115 | `cms` | ✅ |
| Limiti di piattaforma | Massimi configurabili (classifiche attive, automazioni, dimensione del pubblico) e report d'uso | Configurable caps (active leaderboards, automations, audience size) and usage reports | RF-116 | `cms` | ✅ |
| Import ed export | Import di membri, transazioni, trasferimenti, codici, coupon, stati premi, collezioni, catalogo; export CSV, export di configurazione in JSON, export verso storage | Import of members, transactions, transfers, codes, coupons, reward states, collections, catalogue; CSV export, JSON configuration export, storage export | RF-114 | `cms`, servizi · services | 🟡 |
| Simulazione prima della pubblicazione | Prova di campagne, policy decisionali e regole di rischio in bozza | Dry-run of draft campaigns, decision policies and risk rules | RF-82, RF-129 | `cms` | ✅ |

---

## 14. Esperienza del membro · Member experience

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Area membro | Saldo con istante di aggiornamento, storico movimenti, tier e progresso, catalogo premi, riscatti, referral, gamification, campi custom, consensi | Balance with freshness timestamp, movement history, tier and progress, reward catalogue, redemptions, referral, gamification, custom fields, consents | RF-50…55 | `web/site`, `web/bff` | 🟡 |
| Inbox | Offerte e messaggi decisi dalla piattaforma, con lettura, accettazione e rifiuto | Platform-decided offers and messages, with read, accept and dismiss | RF-132 | `notifier`, `web/bff` | ✅ |
| Widget incorporabili | Componenti esportati come web component per portale e app | Components exported as web components for portal and app | RF-56 | `web/site` | ✅ |
| Degrado controllato | Se un servizio non risponde: ultimo saldo in cache, nessuna azione proposta, pulsanti disabilitati invece di errori | If a service is down: last cached balance, no proposed action, disabled buttons instead of errors | RF-54 | `web/bff` | ✅ |
| Eventi comportamentali | Prodotto visto, aggiunto al carrello e simili, inviati dal sito/app e utilizzabili nelle regole | Product viewed, added to cart and similar, sent from site/app and usable in rules | RF-133 | `web/bff` | ✅ |

---

## 15. Osservabilità e BI · Observability and BI

| Capacità · Capability | IT | EN | RF | Owner | Stato |
| --- | --- | --- | --- | --- | --- |
| Metriche | Metriche tecniche e di business con etichette a bassa cardinalità, mai identificatori personali | Technical and business metrics with low-cardinality labels, never personal identifiers | RF-117 | `common` | ✅ |
| Tracce e log | Trace id su ogni richiesta e messaggio, log strutturati correlati, pseudonimizzazione nel collector | Trace id on every request and message, correlated structured logs, pseudonymisation in the collector | RF-118 | OTel Collector | ✅ |
| Cruscotti versionati | Dashboard tecniche e di programma nel repository, applicate all'installazione | Technical and program dashboards in the repository, applied at install time | RF-119 | `deploy/observability` | ✅ |
| SLO e alert | Obiettivi dichiarati con alert a burn rate multi-finestra e runbook citato in ogni alert | Declared objectives with multi-window burn-rate alerts and a runbook referenced in every alert | RF-120 | `deploy/observability` | ✅ |
| Warehouse | Alimentato dai topic (nessuna estrazione dai database dei servizi), schema a stella senza dati personali, retention 5 anni | Topic-fed (no extraction from service databases), star schema without personal data, 5-year retention | RF-121 | `analytics` | ✅ |
| BI nel backoffice | Cruscotti degli andamenti incorporati con token ospite di breve durata e sicurezza a livello di riga | Trend dashboards embedded with short-lived guest tokens and row-level security | RF-122, RF-123 | `cms`, `analytics` | ✅ |
| Retention | Metriche brevi in locale e lunghe su storage, log, tracce e warehouse con cicli di vita dichiarati | Short-term local metrics and long-term object storage, logs, traces and warehouse with declared lifecycles | RF-124 | Terraform | ✅ |

Dettaglio · Detail: [`OBSERVABILITY.md`](OBSERVABILITY.md).

---

## 16. Fuori ambito (per ora) · Out of scope (for now)

| Voce · Item | IT | EN |
| --- | --- | --- |
| Multi-tenant | Un solo programma per installazione: nessuna separazione per tenant nei dati | One program per installation: no tenant partitioning in the data |
| Anagrafica clienti | Non duplicata: resta nel CRM | Not duplicated: it stays in the CRM |
| Invio massivo di email/SMS | Delegato ai fornitori aziendali tramite adattatori | Delegated to corporate providers through adapters |
| Motore di raccomandazione prodotti | La piattaforma decide azioni loyalty, non catalogo prodotti | The platform decides loyalty actions, not product catalogue recommendations |
| Migrazione da un programma preesistente | Possibile con un adattatore di import sul registro, non incluso nel primo rilascio | Possible through an import adapter on the ledger, not included in the first release |
