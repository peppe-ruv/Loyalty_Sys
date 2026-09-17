# Specifica · Specification

> 🇮🇹 Requisiti funzionali e non funzionali, vincoli normativi, criteri di accettazione. È il contratto tra chi
> chiede e chi costruisce: il catalogo operativo delle capacità è in [`FEATURES.md`](FEATURES.md), le scelte tecniche
> in [`adr/`](adr/).
> 🇬🇧 Functional and non-functional requirements, regulatory constraints, acceptance criteria. This is the
> contract between requester and builder: the operational capability catalogue lives in [`FEATURES.md`](FEATURES.md),
> technical choices in [`adr/`](adr/).

---

## 1. Ambito · Scope

Una piattaforma loyalty che (a) riceve azioni premianti da sistemi eterogenei, (b) le trasforma in unità e
livelli secondo regole configurabili, (c) sblocca premi e gestisce concorsi e gamification, (d) decide quale azione
proporre a chi e su quale canale, (e) è amministrata da un unico backoffice, (f) è osservabile e misurabile.
Fuori ambito: anagrafica clienti (resta nel CRM), invio massivo di messaggi (delegato ai fornitori), multi-tenant.

**EN** — A loyalty platform that (a) ingests rewarding actions from heterogeneous systems, (b) turns them into units
and tiers under configurable rules, (c) unlocks rewards and runs contests and gamification, (d) decides which action
to offer to whom and on which channel, (e) is administered from a single back office, (f) is observable and
measurable. Out of scope: customer master data (stays in the CRM), bulk messaging (delegated to providers),
multi-tenancy.

---

## 2. Codifica dei requisiti · Requirement coding

| Prefisso · Prefix | Significato · Meaning |
| --- | --- |
| `RF-nn` | Requisito funzionale · functional requirement |
| `RI-nn` | Requisito di integrazione · integration requirement |
| `RC-nn` | Requisito di compliance · compliance requirement |
| `RT-nn` | Requisito tecnico / non funzionale · technical / non-functional requirement |
| `RP-nn` | Requisito di processo (installazione, rilascio) · process requirement (install, release) |
| `ADR-nnn` | Decisione architetturale · architecture decision |

Ogni commento di codice che implementa un requisito cita il suo codice; ogni scelta strutturale ha un ADR.
**EN** — Every code comment implementing a requirement cites its id; every structural choice has an ADR.

---

## 3. Requisiti funzionali per area · Functional requirements by area

| Intervallo · Range | Area | Dettaglio · Detail |
| --- | --- | --- |
| `RF-01`…`RF-09` | Azioni premianti e unità · rewarding actions and units | Catalogo dei tipi azione, regole punti, accrediti, storni, registro immutabile |
| `RF-10`…`RF-13` | Livelli · tiers | Soglie, upgrade immediato, verifica periodica, discesa morbida |
| `RF-14`…`RF-19` | Premi e riscatti · rewards and redemption | Catalogo per fascia, riscatto, stati, annullo, approvazione a quattro occhi |
| `RF-20`…`RF-29` | Programma annuale · annual program | Anno di programma, missioni, calendario, batch di fine anno |
| `RF-30`…`RF-39` | Concorsi e instant win · contests and instant win | Istanti vincenti, montepremi, registro giocate, blocco della configurazione, estrazione di recupero |
| `RF-40`…`RF-49` | Backoffice e governo · back office and governance | Un solo pannello, workflow di approvazione, audit, ruoli, cruscotti, coda di scarto |
| `RF-50`…`RF-59` | Esperienza del membro · member experience | Saldo con freschezza, storico, catalogo, riscatto, degrado controllato, widget |
| `RF-60`…`RF-79` | Ciclo di vita, canali, segmenti, premi, messaggistica · lifecycle, channels, segments, rewards, messaging | Azioni interne, canale canonico, righe di transazione, regole proporzionali e moltiplicatori, limiti, geolocalizzazione, referral, codici, override di tier, segmenti, adesione e stati, GDPR, tipi di premio, postazione operatore, modelli, webhook, impostazioni |
| `RF-80`…`RF-116` | Campagne, wallet, gamification, tier set, dati, premi, amministrazione · campaigns, wallets, gamification, tier sets, data, rewards, administration | Trigger e regole, effetti, limiti e budget, scadenze da formula, espressioni sicure, referral multilivello, automazioni, wallet configurabili, operazioni sulle unità, achievement, challenge, badge, classifiche, ruota, schemi, campi custom, collezioni, catalogo prodotti, valore dinamico, stati di evasione, paga con i punti, tier set, benefici, identificatori, criteri di segmento, analytics, ruoli, webhook, import/export, localizzazione, limiti |
| `RF-117`…`RF-124` | Osservabilità e BI · observability and BI | Metriche, tracce e log correlati, cruscotti versionati, SLO e alert, warehouse, BI self-service, BI incorporata, retention |
| `RF-125`…`RF-136` | Livello decisionale · decision layer | Customer 360, motore decisionale, offerte, Next Best Action e decision log, previsioni, antifrode, consegna omnicanale, eventi, esperimenti, privacy by design, identità e compatibilità |

Dettaglio capacità per capacità · Capability-level detail: [`FEATURES.md`](FEATURES.md).

---

## 4. Requisiti di integrazione · Integration requirements

| Codice · Code | Requisito · Requirement |
| --- | --- |
| `RI-01` | Ogni fonte produce un evento canonico versionato con chiave di idempotenza · every source produces a versioned canonical event with an idempotency key |
| `RI-02` | L'abbinamento al membro avviene per identificatore dichiarato o per risoluzione di identità · member matching happens by declared identifier or identity resolution |
| `RI-03` | L'ingestione risponde `202` e non perde eventi: ciò che non è elaborabile finisce in coda di scarto con motivo · ingestion answers `202` and loses nothing: unprocessable events land in the DLQ with a reason |
| `RI-04` | Ingressi sincroni, batch e file, con quote per fonte e latenza p95 dichiarata · synchronous, batch and file ingestion, with per-source quotas and a declared p95 |
| `RI-05` | API REST versionate con specifica pubblicata; eventi con specifica asincrona pubblicata · versioned REST APIs with published specs; events with a published async spec |
| `RI-06` | Integrazione event-driven verso l'esterno: topic di dominio e webhook firmati · outbound event-driven integration: domain topics and signed webhooks |
| `RI-07` | Contenuti e configurazione leggibili dai servizi con cache e ripiego · content and configuration readable by services with cache and fallback |
| `RI-08` | Gli storni sono eventi con riferimento all'originale, non cancellazioni · reversals are events referencing the original, never deletions |

---

## 5. Requisiti non funzionali · Non-functional requirements

| Codice · Code | Requisito · Requirement | Target |
| --- | --- | --- |
| `RT-01` | Scala · Scale | 2.000.000 di membri, 200 azioni/s di regime, 2.000/s di picco · 2M members, 200 actions/s steady, 2,000/s peak |
| `RT-02` | Latenza · Latency | Ingestione p95 < 200 ms; giocata e riscatto p95 < 500 ms; Customer 360 < 20 ms |
| `RT-03` | Disponibilità · Availability | 99,95% mensile sull'ingestione e sulle letture dei saldi · monthly, for ingestion and balance reads |
| `RT-04` | Continuità · Continuity | Multi-zona; ripristino in seconda region con RPO 15 min e RTO 4 h · multi-AZ; secondary-region restore |
| `RT-05` | Consistenza · Consistency | Saldo consistente con i movimenti nella stessa transazione; letture derivate eventualmente consistenti con freschezza esposta · balance consistent with movements in the same transaction; derived reads eventually consistent with exposed freshness |
| `RT-06` | Idempotenza · Idempotency | Ogni scrittura ha una chiave; ogni consumatore tollera il replay · every write has a key; every consumer tolerates replay |
| `RT-07` | Osservabilità · Observability | Metriche, log e tracce correlati; SLO con alert; ogni alert ha un runbook |
| `RT-08` | Sicurezza · Security | Nessun segreto nel repository; immagini firmate; SBOM; scansione delle dipendenze in CI |
| `RT-09` | Privacy | Nessun dato personale nel dominio loyalty, nei segnali e nel warehouse · no personal data in the loyalty domain, telemetry or warehouse |
| `RT-10` | Portabilità · Portability | Infrastruttura come codice; nessuna dipendenza da servizi proprietari non sostituibili · infrastructure as code; no dependency on non-replaceable proprietary services |
| `RT-11` | Manutenibilità · Maintainability | Un servizio, un database; dominio senza framework; contratti versionati · one service one database; framework-free domain; versioned contracts |
| `RT-12` | Configurabilità · Configurability | Ciò che cambia per decisione di business si cambia dal backoffice senza rilascio · business-driven changes happen in the back office without a release |

---

## 6. Compliance

| Codice · Code | Vincolo · Constraint | Impatto tecnico · Technical impact |
| --- | --- | --- |
| `RC-01` | Concorsi e operazioni a premio (normativa italiana: DPR 430/2001) · Italian prize-promotion regulations | Comunicazione preventiva all'autorità, cauzione, durata dichiarata, **server in Italia**, perizia tecnica sul software di assegnazione, gestione dei premi non assegnati |
| `RC-02` | Immodificabilità a concorso avviato · immutability once a contest is running | Configurazione bloccata, nessun rilascio del modulo, registro giocate a prova di manomissione |
| `RC-03` | Protezione dei dati (GDPR) · data protection (GDPR) | Consensi per finalità con base giuridica, revoca propagata, minimizzazione, portabilità, cancellazione/anonimizzazione, registro dei trattamenti per il warehouse |
| `RC-04` | Conservazione per obbligo di legge · statutory retention | Registro giocate e movimenti conservati anche dopo l'anonimizzazione, senza dati personali |
| `RC-05` | Tracciabilità delle decisioni · decision traceability | Decision log append-only con policy e versione; audit delle configurazioni |
| `RC-06` | Separazione dei poteri · separation of duties | Workflow di approvazione con revisione legale per regole, tier, concorsi e programma; doppia approvazione sopra soglia |

In caso di dubbio interpretativo vince la scelta conservativa, e va scritta in un ADR.
**EN** — Where interpretation is uncertain, the conservative option wins and is recorded in an ADR.

---

## 7. Criteri di accettazione · Acceptance criteria

| # | Criterio · Criterion |
| --- | --- |
| 1 | Un'azione inviata due volte con la stessa chiave produce un solo accredito · an action sent twice with the same key produces one credit |
| 2 | Un'azione inviata durante un guasto di Kafka non è persa: arriva quando il servizio si ristabilisce · an action sent during a Kafka outage is not lost |
| 3 | Uno storno riporta saldo e budget di campagna allo stato corretto, lasciando la traccia · a reversal restores balance and campaign budget correctly, leaving a trace |
| 4 | Spendere unità premio non fa perdere il livello · spending reward units never costs the tier |
| 5 | Nessun premio da lotto è assegnato due volte, sotto carico concorrente · no coupon from a pool is issued twice under concurrent load |
| 6 | Nessun premio del concorso è assegnato oltre il montepremi dichiarato · no contest prize is awarded beyond the declared pool |
| 7 | Il registro giocate è verificabile: un'alterazione è rilevabile · the play log is verifiable: tampering is detectable |
| 8 | Una modifica di policy pubblicata nel backoffice cambia il comportamento entro un minuto, senza rilascio · a published policy change takes effect within a minute, without a release |
| 9 | Ogni decisione è ricostruibile con policy, versione, candidati, scartati e motivo · every decision is reconstructible |
| 10 | Nessuna previsione modifica punti, saldi, eligibilità o status · no prediction changes points, balances, eligibility or status |
| 11 | Una revoca di consenso blocca le azioni discrezionali corrispondenti entro pochi secondi · a consent revocation blocks the matching discretionary actions within seconds |
| 12 | Un membro può ottenere l'export dei propri dati e l'anonimizzazione, restando conformi agli obblighi di conservazione · a member can obtain data export and anonymisation while statutory retention is preserved |
| 13 | Log, metriche, tracce e warehouse non contengono dati personali · logs, metrics, traces and the warehouse contain no personal data |
| 14 | Gli obiettivi di latenza e disponibilità sono rispettati sotto il carico di picco dichiarato · latency and availability targets hold at the declared peak load |
| 15 | La piattaforma si installa da zero con i comandi documentati · the platform installs from scratch with the documented commands |
| 16 | Disattivando il livello decisionale la piattaforma continua a funzionare in modalità storica · disabling the decision layer keeps the platform working in legacy mode |

---

## 8. Rischi principali · Main risks

| Rischio · Risk | Mitigazione · Mitigation |
| --- | --- |
| Perizia dei concorsi non superata · contest certification not granted | Modulo isolato, generatore crittografico, registro con hash concatenato, test dedicati, nessun rilascio a concorso avviato |
| Soglie di livello mal tarate · badly calibrated tier thresholds | Simulazione sui dati reali prima della pubblicazione; batch di fine anno provato in ambiente di staging |
| Pressione commerciale eccessiva · excessive contact pressure | Cap di contatto, ore di silenzio, spaziatura delle offerte come vincoli di policy, misurati dagli scarti |
| Falsi positivi antifrode · fraud false positives | Soglie e pesi configurabili con simulazione; blocco reversibile; rivalutazione periodica |
| Dipendenza dal provider di identità · identity provider dependency | Cache, pagina di cortesia, API interne indipendenti dal login |
| Costo dell'infrastruttura · infrastructure cost | Dimensionamento per ambiente, scalatura automatica, retention dichiarata, revisione periodica |
| Deriva della documentazione · documentation drift | Aggiornamento nello stesso commit come criterio di "fatto" · same-commit update as a definition-of-done criterion |
