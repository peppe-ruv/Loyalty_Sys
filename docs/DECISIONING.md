# Livello decisionale · Decision layer

> 🇮🇹 **Documento bilingue**: decisione spiegabile, previsioni, frodi, consegna, esperimenti, consensi, identità.
> Requisiti RF-125..RF-136, decisioni ADR-021..ADR-025. Italiano prima, inglese marcato **EN**.
> 🇬🇧 **Bilingual document**: explainable decisioning, predictions, fraud, delivery, experiments, consent, identity.
> Requirements RF-125..RF-136, decisions ADR-021..ADR-025. Italian first, English marked **EN**.

---

## 1. L'idea in una frase · The idea in one sentence

Tra l'evento e l'effetto c'è una decisione: il motore delle campagne dice cosa *potrebbe* scattare, il servizio
decisionale sceglie cosa *fare* davvero applicando la policy scritta nel backoffice (vincoli, punteggio, canali,
esperimenti, previsioni, rischio), registra il perché e lascia la consegna ai canali.

**EN** — Between the event and the effect there is a decision: the campaign engine says what *could* fire, the decision
service picks what actually *happens* by applying the policy authored in the back office (constraints, scoring,
channels, experiments, predictions, risk), records the reason, and leaves delivery to the channels.

Perché serve. Senza questo livello, ogni campagna che scatta produce il suo effetto: il cliente riceve tre messaggi lo
stesso giorno, un'offerta arriva a chi ha revocato il consenso, un premio va a un account sospetto e nessuno sa
spiegare perché una proposta è stata fatta. Con questo livello, tutto ciò diventa una policy leggibile e modificabile
senza rilasci.

**EN** — Why it exists. Without this layer, every firing campaign produces its effect: the customer gets three messages
in one day, an offer reaches someone who revoked consent, a reward goes to a suspicious account, and nobody can explain
why a proposal was made. With this layer, all of that becomes a readable policy that can be changed without a release.

---

## 2. Regola invariante · The invariant

**Gli effetti contrattuali non sono mai arbitrati. Le previsioni non toccano mai il denaro.**

`AWARD_POINTS`, `UPGRADE_TIER`, `GRANT_BADGE`, `SET_ATTRIBUTE`, `EMIT_EVENT` sono dovuti al membro: se una campagna
pubblicata dice che gli spettano, si applicano. `ISSUE_REWARD`, `ISSUE_COUPON`, `SHOW_OFFER`, `SEND_MESSAGE`,
`TRIGGER_CAMPAIGN`, `ASK_FOR_FEEDBACK` sono proposte commerciali: passano dai vincoli e dal punteggio. Previsioni,
policy ed esperimenti entrano solo nel punteggio delle azioni discrezionali: non modificano punti, saldi, denaro,
eligibilità o livelli. Spostare un'azione da una classe all'altra è una configurazione del backoffice, non una modifica
di codice.

**EN — Contractual effects are never arbitrated. Predictions never touch money.** `AWARD_POINTS`, `UPGRADE_TIER`,
`GRANT_BADGE`, `SET_ATTRIBUTE`, `EMIT_EVENT` are owed to the member: if a published campaign says they are due, they are
applied. `ISSUE_REWARD`, `ISSUE_COUPON`, `SHOW_OFFER`, `SEND_MESSAGE`, `TRIGGER_CAMPAIGN`, `ASK_FOR_FEEDBACK` are
commercial proposals: they go through constraints and scoring. Predictions, policies and experiments only feed the score
of discretionary actions: they never modify points, balances, money, eligibility or tiers. Moving an action between the
two classes is a back-office setting, not a code change.

---

## 3. Il ciclo · The loop

```
evento (CloudEvents + correlationid)
  └─▶ decision-service
        ├─▶ GET  /v1/context/{memberId}     Customer 360 in una chiamata · in one call
        ├─▶ POST /v1/evaluations            campagne candidate, nessun effetto · candidate campaigns, no effects
        ├─▶ offerte del backoffice + condizione SpEL · back-office offers plus SpEL condition
        ├─▶ PredictionProvider              churnRisk, propensioni, valore · propensities, value
        ├─▶ esperimento · experiment        assegnazione deterministica SHA-256(membro+esperimento)
        └─▶ DecisionEngine (funzione pura · pure function)
              → Decision { azioni scelte · chosen, scartate + motivo · rejected with reason, punteggi · scores }
                 ├─▶ decision log append-only  → API, backoffice, BI
                 ├─▶ effetti contrattuali · contractual effects → ledger · tier · engagement · member
                 ├─▶ DECISION_V1 → delivery (notifier) → canale → fallback → DELIVERY_V1
                 └─▶ azioni interne · internal actions → stesso topic, stesso ciclo · same topic, same loop
fraud-service   azioni, movimenti, riscatti, giocate → segnali → riskScore/riskLevel → RISK_V1 → contesto + blocco ledger
member-service  consensi con finalità e base giuridica → CONSENT_V1 → contesto (vincolo del motore)
identity-mapping grafo identità → risoluzione, merge/unmerge → IDENTITY_V1
```

`DecisionEngine` è una funzione pura senza I/O: stesso input, stessa decisione. È la ragione per cui il simulatore del
backoffice può mostrare l'esito di una policy in bozza senza effetti collaterali, e per cui la decisione è testabile
senza database.

**EN** — `DecisionEngine` is a pure, I/O-free function: same input, same decision. That is why the back-office simulator
can show the outcome of a draft policy with no side effects, and why decisioning is testable without a database.

---

## 4. Customer 360 · Customer context (RF-125, RF-126)

Un documento per membro, aggiornato dalle proiezioni di dieci topic e letto con una sola chiamata. Contiene: identità
loyalty, portafogli, livello e progresso, segmenti, ultime azioni, indicatori RFM, canale preferito, badge, achievement
e challenge, campagne completate, offerte recenti, riscatti, giocate, contatti per canale negli ultimi sette giorni,
rischio, consensi, previsioni.

**EN** — One document per member, updated by projections from ten topics and read in a single call. It contains: loyalty
identity, wallets, tier and progress, segments, recent actions, RFM indicators, preferred channel, badges, achievements
and challenges, completed campaigns, recent offers, redemptions, plays, per-channel contacts in the last seven days,
risk, consent, predictions.

Il motore decisionale legge **solo** il contesto, mai i database degli altri servizi: una decisione costa una lettura.
Quando il contesto non è disponibile, la decisione si limita alle azioni contrattuali con scarto motivato.

**EN** — The decision engine reads **only** the context, never other services' databases: one decision costs one read.
When the context is unavailable, decisioning falls back to contractual actions only, with an explicit rejection reason.

---

## 5. La policy decisionale · The decision policy (RF-127)

Documento del backoffice, versionato, con approvazione a tre livelli. La versione in uso viene riportata in ogni
decisione: un'analisi a posteriori può sempre ricostruire con quale policy è stata presa.

**EN** — A versioned back-office document with three-level approval. The policy version in force is recorded on every
decision: any later analysis can reconstruct which policy produced it.

| Sezione · Section | Cosa contiene · What it holds |
| --- | --- |
| Azioni ammesse · Allowed actions | priorità, valore, costo, consensi richiesti, rischio massimo, limiti per periodo, cooldown, canali · priority, value, cost, required consent, maximum risk, per-period limits, cooldown, channels |
| Vincoli · Constraints | tetto di contatti per canale su 7 giorni, ore di silenzio, spaziatura minima fra offerte, budget di unità, segmenti di esclusione, livello di rischio bloccante · 7-day per-channel contact cap, quiet hours, minimum spacing between offers, unit budget, exclusion segments, blocking risk level |
| Punteggio · Scoring | `PRIORITY` (ordine dichiarato), `WEIGHTED` (pesi su valore, costo, propensione, rischio), `EXPRESSION` (formula SpEL) · declared order, weights on value/cost/propensity/risk, or an SpEL formula |
| Esecuzione · Execution | azioni contrattuali sempre applicate, numero massimo di azioni discrezionali per evento, ordine dei canali · contractual actions always applied, maximum discretionary actions per event, channel order |

Codici di scarto · Rejection codes: `ACTION_DISABLED`, `RISK_LEVEL`, `RISK_BLOCK`, `CONSENT_MISSING`, `SUPPRESSED`,
`QUIET_HOURS`, `COOLDOWN`, `PERIOD_LIMIT`, `OFFER_SPACING`, `NO_CHANNEL`, `OUTRANKED`, `UNITS_BUDGET`, `NO_CONTEXT`.

`UNITS_BUDGET` merita una riga a parte: il budget di unità è **del programma, non del membro**, e vale solo per le
azioni discrezionali. Il motore riceve le unità già concesse nella giornata di programma (mezzanotte di `Europe/Rome`),
somma quelle delle azioni che sta scegliendo e scarta chi non ci sta più — ma un'azione più piccola che ci sta ancora
passa, così il tetto non spegne il resto della giornata. Le azioni contrattuali non lo consumano e non ne sono
limitate: sono effetti dovuti, non discrezionalità del motore. Due metriche (`loyalty_decision_units_granted_today`,
`loyalty_decision_units_budget`) e un alert al 90% dicono al marketing che il tetto sta per chiudere.

**EN** — `UNITS_BUDGET` deserves its own note: the units budget belongs to the **programme, not the member**, and only
applies to discretionary actions. The engine receives the units already granted during the programme day (midnight
`Europe/Rome`), adds those of the actions it is choosing and rejects whatever no longer fits — while a smaller action
that still fits goes through, so the cap does not shut down the rest of the day. Contractual actions neither consume
nor are limited by it.

Sono esposti come metrica per codice: un picco di `CONSENT_MISSING` o `QUIET_HOURS` non è un guasto, è una policy troppo
stretta, e il marketing la corregge dal backoffice con effetto in trenta secondi.

**EN** — They are exposed as a per-code metric: a spike of `CONSENT_MISSING` or `QUIET_HOURS` is not a failure, it is a
policy that is too tight, and marketing fixes it from the back office with effect in thirty seconds.

---

## 6. Offerte e Next Best Action · Offers and Next Best Action (RF-128, RF-129)

Il catalogo delle offerte è nel backoffice: ogni offerta ha una condizione di eligibilità scritta come espressione sul
contesto, un valore, un costo, i canali ammessi, una validità e i parametri per il canale. Le offerte concorrono con i
candidati delle campagne dentro la stessa decisione.

**EN** — The offer catalogue lives in the back office: each offer has an eligibility condition written as an expression
over the context, a value, a cost, allowed channels, a validity window and channel parameters. Offers compete with
campaign candidates inside the same decision.

```
POST /v1/decisions/next-best-action/{memberId}   → { action, offerId, channel, reason, expiresAt, metadata }
GET  /v1/decisions/{decisionId}                  → decisione completa: candidati, scarti, punteggi, versione policy
GET  /v1/decisions/members/{memberId}            → cronologia decisionale (keyset)
POST /v1/decisions/simulate                      → esito di una policy in bozza, nessun effetto · draft policy, no effects
```

---

## 7. Previsioni · Predictions (RF-130, ADR-022)

Porta `PredictionProvider` con chiavi standard in `[0,1]`: `churnRisk`, `purchasePropensity`, `rewardAcceptance`,
`offerPropensity`, `engagement`, `customerValue`, `categoryAffinity:<categoria>`. Tre implementazioni: un provider a
regole basato su RFM e comportamento con soglie configurabili (attivo da subito, spiegabile), un provider HTTP verso un
modello locale o un servizio di inferenza, un provider composito che instrada ogni chiave al provider scelto nel
backoffice e ripiega sulle regole in caso di errore o timeout.

**EN** — A `PredictionProvider` port with standard keys in `[0,1]`: `churnRisk`, `purchasePropensity`,
`rewardAcceptance`, `offerPropensity`, `engagement`, `customerValue`, `categoryAffinity:<category>`. Three
implementations: a rules provider based on RFM and behaviour with configurable thresholds (available immediately,
explainable), an HTTP provider pointing at a local model or an inference service, and a composite provider that routes
each key to the provider chosen in the back office and falls back to rules on error or timeout.

Spostare una chiave dalle regole a un modello è una modifica di configurazione, reversibile in un istante. Il contesto
inviato a un provider esterno è pseudonimo: nessun dato personale lascia la piattaforma.

**EN** — Moving a key from rules to a model is a configuration change, reversible in an instant. The context sent to an
external provider is pseudonymous: no personal data leaves the platform.

---

## 8. Frodi e abusi · Fraud and abuse (RF-131, ADR-023)

Nove segnali configurabili, ciascuno con abilitazione, peso, soglia e saturazione: `REDEMPTION_FREQUENCY`,
`MULTI_ACCOUNT_DEVICE`, `ABNORMAL_EARNING`, `RAPID_ACCOUNT_CREATION`, `IMPOSSIBLE_TRAVEL`, `CODE_ABUSE`,
`REFUND_RATIO`, `DEVICE_ANOMALY`, `VELOCITY`. Il risultato è un punteggio 0..100, una fascia (`LOW`, `MEDIUM`, `HIGH`,
`CRITICAL`) e i codici motivo con i valori osservati.

**EN** — Nine configurable signals, each with an enable flag, weight, threshold and saturation: `REDEMPTION_FREQUENCY`,
`MULTI_ACCOUNT_DEVICE`, `ABNORMAL_EARNING`, `RAPID_ACCOUNT_CREATION`, `IMPOSSIBLE_TRAVEL`, `CODE_ABUSE`,
`REFUND_RATIO`, `DEVICE_ANOMALY`, `VELOCITY`. The result is a 0..100 score, a band (`LOW`, `MEDIUM`, `HIGH`,
`CRITICAL`) and reason codes with the observed values.

Il livello di rischio arriva al Customer 360 e al motore come vincolo; sopra la soglia configurata il servizio chiede al
ledger il **blocco** delle unità, che resta un movimento tracciato e reversibile. Le osservazioni non contengono dati
personali (identificatore del membro, hash del dispositivo, coordinate) e sono conservate 90 giorni. Nelle prime
settimane i falsi positivi sono attesi: pesi e soglie si tarano dal backoffice con il simulatore, e una rivalutazione
periodica con decadimento sblocca automaticamente chi è tornato nella norma.

**EN** — The risk level reaches the Customer 360 and the engine as a constraint; above the configured threshold the
service asks the ledger to **block** units, which remains a tracked, reversible movement. Observations hold no personal
data (member id, device hash, coordinates) and are kept for 90 days. In the first weeks false positives are expected:
weights and thresholds are tuned from the back office with the simulator, and a periodic re-assessment with decay
automatically unblocks members who are back to normal.

---

## 9. Consegna omnicanale · Omnichannel delivery (RF-132, ADR-024)

La decisione non conosce il canale: dice *che cosa* proporre, il `notifier` decide *dove*. Sette adattatori (inbox in
app, web, push, email, SMS, webhook CRM, coda operatore) dietro la porta `ChannelAdapter`; il routing configurabile
definisce l'ordine dei canali per azione, i canali abilitati, i limiti giornalieri e le ore di silenzio con canale di
ripiego. Ogni consegna produce un evento, così la pressione commerciale diventa un dato e non un'impressione.

**EN** — The decision knows nothing about channels: it says *what* to propose, the `notifier` decides *where*. Seven
adapters (in-app inbox, web, push, email, SMS, CRM webhook, operator queue) behind the `ChannelAdapter` port;
configurable routing defines the channel order per action, enabled channels, daily caps and quiet hours with a fallback
channel. Every delivery produces an event, so contact pressure becomes data rather than an impression.

Le consegne fallite **non** sono ripetute automaticamente: un doppio contatto commerciale è peggio di un contatto
mancato. Il registro consegne è la fonte per il reinvio manuale dalla console operatore.

**EN** — Failed deliveries are **not** retried automatically: a duplicate commercial contact is worse than a missed one.
The delivery log is the source for manual resend from the operator console.

---

## 10. Esperimenti · Experiments (RF-134)

Controllo e varianti con assegnazione deterministica (`SHA-256(memberId + experimentId)`): lo stesso membro cade sempre
nello stesso gruppo, senza tabelle di assegnazione. Si configurano quota di traffico, ambito (tipi evento, segmenti) e
sovrascritture della policy per variante (pesi, formula, numero di azioni per evento). Le esposizioni sono registrate
come eventi e il calcolo dell'effetto incrementale (accettazione offerte, riscatti a sette giorni per variante) vive nel
warehouse analitico.

**EN** — Control and variants with deterministic assignment (`SHA-256(memberId + experimentId)`): the same member always
lands in the same group, with no assignment tables. Traffic share, scope (event types, segments) and per-variant policy
overrides (weights, formula, actions per event) are configurable. Exposures are recorded as events, and incremental
uplift (offer acceptance, seven-day redemptions per variant) is computed in the analytical warehouse.

---

## 11. Consensi · Consent (RF-135, ADR-025)

Modello strutturato nel `member-service`: finalità, concesso o revocato, fonte, base giuridica, versione
dell'informativa, data di concessione, scadenza e prova. Storico immutabile, scadenza automatica, revoca propagata in
pochi secondi come vincolo del motore (evento → Customer 360 → decisione). Le finalità sono catalogate nel backoffice con
approvazione legale; il sistema anagrafico di origine resta la fonte dei recapiti.

**EN** — A structured model in `member-service`: purpose, granted or revoked, source, legal basis, notice version, grant
date, expiry and evidence. Immutable history, automatic expiry, revocation propagated within seconds as an engine
constraint (event → Customer 360 → decision). Purposes are catalogued in the back office with legal approval; the
originating master-data system remains the source of contact details.

---

## 12. Identità · Identity (RF-136)

Grafo degli identificatori (`oidc_sub`, `crm_id`, id gestionale, `device_id`, `app_id`, tessera di punto vendita, id
e-commerce, hash email e telefono) verso il membro. Risoluzione deterministica sugli identificatori affidabili,
collegamenti probabilistici marcati a confidenza ridotta. Il merge trasferisce le unità con un movimento del ledger e
chiude il membro assorbito; l'annullamento del merge ripristina gli identificatori da uno snapshot ma **non** i saldi, e
la piattaforma lo dichiara esplicitamente invece di fingere una reversibilità che non ha.

**EN** — A graph of identifiers (`oidc_sub`, `crm_id`, back-end id, `device_id`, `app_id`, point-of-sale card,
e-commerce id, email and phone hashes) pointing at the member. Deterministic resolution on trusted identifiers,
probabilistic links flagged at reduced confidence. A merge transfers units through a ledger movement and closes the
absorbed member; undoing a merge restores identifiers from a snapshot but **not** balances, and the platform states this
explicitly rather than pretending to a reversibility it does not have.

---

## 13. Compatibilità · Compatibility (RF-136)

Due variabili riportano la piattaforma al comportamento precedente al livello decisionale, senza toccare codice né
dati: `RULES_APPLY_EFFECTS=true` fa applicare gli effetti direttamente al motore delle campagne e
`DECISIONS_ENABLED=false` disattiva l'arbitrato. È la via di fuga durante il primo anno di esercizio del livello
decisionale e il ripiego dei runbook in caso di incidente.

**EN** — Two variables return the platform to its pre-decision-layer behaviour, with no code or data changes:
`RULES_APPLY_EFFECTS=true` makes the campaign engine apply effects directly and `DECISIONS_ENABLED=false` disables
arbitration. This is the escape hatch during the decision layer's first year in production and the fallback used by the
runbooks during an incident.

---

## 14. Configurazione dal backoffice · Back-office configuration

| Collezione · Collection | Servizio · Service | Cosa decide l'utente · What the user decides |
| --- | --- | --- |
| `decision-policies` | `decision-service` | azioni ammesse, vincoli, strategia di punteggio, azioni per evento, ordine dei canali · allowed actions, constraints, scoring strategy, actions per event, channel order |
| `offers` | `decision-service` | catalogo offerte con condizione, valore, costo, canali, validità · offer catalogue with condition, value, cost, channels, validity |
| `experiments` | `decision-service` | controllo e varianti, quota, ambito, sovrascritture · control and variants, share, scope, overrides |
| `prediction-providers` | `decision-service` | quale provider serve quale chiave, URL, timeout, chiave da variabile d'ambiente · which provider serves which key, URL, timeout, key from an environment variable |
| `fraud-rules` | `fraud-service` | segnali, pesi, soglie, fasce, blocco automatico, decadimento · signals, weights, thresholds, bands, automatic blocking, decay |
| `delivery-routing` | `notifier` | ordine e abilitazione dei canali, limiti giornalieri, ore di silenzio, modelli per azione · channel order and enablement, daily caps, quiet hours, per-action templates |
| `consent-purposes` | `member-service`, `decision-service` | finalità, base giuridica, validità, obbligatorietà, versione dell'informativa · purposes, legal basis, validity, mandatory flag, notice version |

Tutti i documenti sono versionati con workflow a tre livelli; i servizi leggono solo i documenti pubblicati, con cache
di 30 secondi e ripiego all'ultimo valore buono o al seed di codice. Prima di pubblicare, il backoffice offre
`POST /api/simulate/decision` e `POST /api/simulate/risk`, che accettano la configurazione in bozza e un membro reale o
descritto a mano, senza produrre effetti.

**EN** — All documents are versioned with a three-level workflow; services read only published documents, with a
30-second cache and fallback to the last-known-good value or the code seed. Before publishing, the back office offers
`POST /api/simulate/decision` and `POST /api/simulate/risk`, which accept the draft configuration and a real or
hand-described member, with no effects.

---

## 15. Osservabilità del livello decisionale · Observability of the decision layer

Metriche: `loyalty_decisions_total`, `loyalty_decision_rejections_total` (per codice · per code),
`loyalty_decision_effects_total` (per azione ed esito · per action and outcome), `loyalty_predictions_total` (per
provider), `loyalty_experiment_exposures_total`, `loyalty_risk_assessments_total`, `loyalty_deliveries_total` (per
canale ed esito · per channel and outcome), `loyalty_consents_total`, `loyalty_identity_events_total`.

Warehouse: fatti `fact_decision`, `fact_delivery`, `fact_risk` e viste `kpi_decisions_daily`, `kpi_rejections_daily`,
`kpi_deliveries_daily`, `kpi_risk_daily`, `kpi_experiment_uplift` per i cruscotti del backoffice.

Alert e procedure: `docs/runbooks/observability.md` (effetti falliti, decisioni ferme, picco di scarti, provider di
previsione in errore, ondata di rischio critico, consegne in errore).

**EN** — Alerts and procedures: `docs/runbooks/observability.md` (failed effects, stalled decisions, rejection spikes,
failing prediction provider, critical-risk surge, delivery failures).
