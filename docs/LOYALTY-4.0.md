# Loyalty 4.0: livello decisionale, previsioni, frodi, consegne, consensi, identità

Richiesta (17 settembre 2026): introdurre nel codice le migliorie proposte in `COPERTURA-LOYALTY-4.0.md`, nell'ordine
suggerito, con un vincolo esplicito: **il motore decisionale e ogni modulo che effettua valutazioni devono essere
configurabili dal backoffice**, così che gli utenti personalizzino il comportamento senza rilasci.
Requisiti RF-125..RF-136; decisioni ADR-021..ADR-025. Versione 0.5.0.

## In una frase

Tra l'evento e l'effetto ora c'è una decisione: il `rules-engine` dice cosa *potrebbe* scattare (eligibilità), il
`decision-service` sceglie cosa *fare* davvero applicando la policy configurata nel backoffice (vincoli, punteggio,
canali, esperimenti, previsioni, rischio), registra il perché e lascia ai canali la consegna.

## Il ciclo (Definition of Done)

```
evento (CloudEvents + correlationid)
  └─▶ decision-service ──▶ context-service  GET /v1/context/{id}   (Customer 360, una chiamata)
          │              ──▶ rules-engine    POST /v1/evaluations   (campagne: effetti candidati, nessun effetto collaterale)
          │              ──▶ offers (backoffice) + condizione SpEL → altri candidati
          │              ──▶ PredictionProvider (regole → ML) → churnRisk, propensioni, valore
          │              ──▶ esperimento (assegnazione deterministica) → policy/variante
          │              ──▶ DecisionEngine (funzione pura) → Decision {azioni scelte, scartate+motivo, punteggi}
          ├─▶ decision log (append-only)  ──▶ API /v1/decisions/{id}, backoffice, BI
          ├─▶ effetti contrattuali: ledger / tier / engagement / member   (AWARD_POINTS, UPGRADE_TIER, GRANT_BADGE, SET_ATTRIBUTE, EMIT_EVENT)
          ├─▶ DECISION_V1 ──▶ delivery (notifier): canale deciso → fallback → inbox/push/email/sms/webhook/operatore ──▶ DELIVERY_V1 + OFFER_PRESENTED/MESSAGE_SENT
          └─▶ azioni interne: CAMPAIGN_COMPLETED, EXPERIMENT_EXPOSED, CHURN_RISK_CHANGED → stesso topic, stesso ciclo
fraud-service: azioni/movimenti/riscatti/giocate ──▶ segnali configurati ──▶ riskScore/riskLevel/reasonCodes ──▶ RISK_V1 ──▶ Customer 360 (vincolo del motore) + blocco ledger
member-service: consensi con finalità/base giuridica/scadenza ──▶ CONSENT_V1 ──▶ Customer 360 (vincolo del motore)
identity-mapping: grafo identità (sub, CRM, SAP, dispositivo, app, POS, e-commerce) ──▶ risoluzione, merge/unmerge ──▶ IDENTITY_V1, CUSTOMER_IDENTIFIED
```

Retro-compatibilità (RF-136): con `RULES_APPLY_EFFECTS=true` e `DECISIONS_ENABLED=false` la piattaforma torna alla
modalità 0.4 (il rules-engine applica gli effetti da solo) senza toccare codice o dati.

## Cosa configura il backoffice (collezioni nuove, gruppo "Decisioni")

| Collezione | Chi la usa | Cosa decide l'utente |
| --- | --- | --- |
| `decision-policies` | decision-service | azioni ammesse con priorità, valore, costo, consensi richiesti, rischio massimo, limiti per periodo, cooldown, canali; vincoli (cap di contatto per canale a 7 giorni, ore di silenzio, spaziatura offerte, budget unità, segmenti di esclusione, livello di rischio bloccante); strategia di punteggio PRIORITY / WEIGHTED (pesi) / EXPRESSION (formula SpEL); azioni contrattuali "sempre applicate"; quante azioni discrezionali per evento; ordine dei canali |
| `offers` | decision-service (NBA) | catalogo delle offerte proponibili con condizione SpEL sul Customer 360, valore/costo, canali, validità, parametri per il canale |
| `experiments` | decision-service | controllo vs varianti, quota di traffico, tipi evento/segmenti, sovrascritture della policy per variante (pesi, formula, azioni per evento) |
| `prediction-providers` | decision-service | provider a regole (soglie RFM), modello locale o servizio esterno (URL, timeout, chiave da variabile d'ambiente), quale provider serve quale chiave, default |
| `fraud-rules` | fraud-service | nove segnali con abilitazione, peso, soglia e saturazione; fasce MEDIUM/HIGH/CRITICAL; blocco automatico; decadimento |
| `delivery-routing` | notifier (delivery) | ordine dei canali per azione, canali abilitati, limiti giornalieri per canale, ore di silenzio con canale di ripiego, modelli di messaggio per azione/offerta |
| `consent-purposes` | member-service, decision-service | finalità, base giuridica, validità in mesi, obbligatorietà, versione dell'informativa |

Ogni documento è versionato (workflow a tre livelli con Legal, come le campagne); i servizi leggono solo i documenti
pubblicati, con cache di 30 s e ripiego all'ultimo valore buono o al seed: il backoffice fermo non ferma le decisioni.
Il numero di versione finisce nel decision log e nelle valutazioni di rischio. Il backoffice offre "prova prima di
pubblicare": `POST /api/simulate/decision` e `POST /api/simulate/risk` (endpoint del CMS che proxano i simulatori dei
servizi) accettano la policy in bozza e un membro reale o descritto a mano, senza effetti.

## Requisiti

- RF-125 **Customer Context (360)**: `read-model` come context-service; documento per membro (identità loyalty, wallet, tier, segmenti, azioni recenti, RFM, canale preferito, badge/achievement/challenge, campagne completate, offerte recenti, riscatti, giocate, contatti a 7 giorni per canale, rischio, consensi, previsioni) aggiornato dai topic, letto con `GET /v1/context/{memberId}` in una chiamata.
- RF-126 Il motore decisionale legge solo il contesto (mai i database degli altri servizi); una decisione = una lettura.
- RF-127 **Decision engine** configurabile: candidati (campagne + offerte) → vincoli → punteggio → scelta; decisione strutturata (`NO_ACTION`, `AWARD_POINTS`, `ISSUE_REWARD`, `ISSUE_COUPON`, `UPGRADE_TIER`, `GRANT_BADGE`, `SET_ATTRIBUTE`, `EMIT_EVENT`, `SEND_MESSAGE`, `SHOW_OFFER`, `TRIGGER_CAMPAIGN`, `ASK_FOR_FEEDBACK`) con motivi; codici di scarto `ACTION_DISABLED`, `RISK_LEVEL`, `RISK_BLOCK`, `CONSENT_MISSING`, `SUPPRESSED`, `QUIET_HOURS`, `COOLDOWN`, `PERIOD_LIMIT`, `OFFER_SPACING`, `NO_CHANNEL`, `OUTRANKED`; le azioni contrattuali non sono mai arbitrate.
- RF-128 **Catalogo offerte** nel backoffice con condizione di eligibilità sul contesto.
- RF-129 **Next Best Action**: `POST /v1/decisions/next-best-action/{memberId}` con contesto del canale → `{customerId, action, offerId, channel, reason, expiresAt, metadata}`; **decision log** append-only con `GET /v1/decisions/{id}` e `GET /v1/decisions/members/{id}`; simulazione `POST /v1/decisions/simulate`.
- RF-130 **PredictionProvider**: porta con chiavi `churnRisk`, `purchasePropensity`, `rewardAcceptance`, `offerPropensity`, `engagement`, `customerValue`, `categoryAffinity:<cat>`; provider a regole (RFM, soglie configurabili), HTTP (modello locale o esterno), composito con routing per chiave e ripiego; l'AI non modifica mai punti, saldi, denaro, eligibilità o status.
- RF-131 **Fraud detection**: segnali `REDEMPTION_FREQUENCY`, `MULTI_ACCOUNT_DEVICE`, `ABNORMAL_EARNING`, `RAPID_ACCOUNT_CREATION`, `IMPOSSIBLE_TRAVEL`, `CODE_ABUSE`, `REFUND_RATIO`, `DEVICE_ANOMALY`, `VELOCITY` → `riskScore` 0..100, `riskLevel`, `reasonCodes` con valori osservati; `RISK_V1` al cambio di livello; blocco/sblocco automatico delle unità; rivalutazione periodica con decadimento; API `/v1/risk/*`.
- RF-132 **Delivery / Channel adapter**: la decisione è indipendente dal canale; adattatori `app`, `web` (inbox), `push`, `email`, `sms` (fornitori), `webhook` (CRM, firmato HMAC), `operator` (coda); routing con fallback, limiti giornalieri, ore di silenzio; `DELIVERY_V1`; inbox con lettura/accettazione (→ `OFFER_ACCEPTED`).
- RF-133 **Eventi**: estensione CloudEvents `correlationid` ereditata lungo tutto il ciclo; azioni `CAMPAIGN_ENTERED/COMPLETED`, `CUSTOMER_IDENTIFIED`, `CHURN_RISK_CHANGED`, `PRODUCT_VIEWED`, `PRODUCT_ADDED_TO_CART`, `OFFER_PRESENTED/ACCEPTED`, `MESSAGE_SENT`, `FEEDBACK_REQUESTED`, `EXPERIMENT_EXPOSED`, `CONSENT_CHANGED`, `IDENTITY_MERGED`; topic `loyalty.decisions|risk|deliveries|consents|identities.v1`.
- RF-134 **Esperimenti**: controllo/varianti con assegnazione deterministica (SHA-256 membro+esperimento), quota di traffico, ambito per evento/segmento, sovrascritture della policy; esposizioni registrate; metriche incrementali in ClickHouse (`kpi_experiment_uplift`: accettazione offerte, riscatti a 7 giorni per variante).
- RF-135 **Privacy by design**: consensi con `purpose`, `granted`, `source`, `legalBasis`, `version`, `grantedAt`, `expiresAt`, prova; storico immutabile; scadenza automatica; revoca propagata subito (`CONSENT_V1` → Customer 360 → vincolo del motore); decision log e valutazioni di rischio come audit.
- RF-136 **Identità e compatibilità**: grafo identità (`oidc_sub`, `crm_id`, `sap_bp_id`, `device_id`, `app_id`, `pos_card`, `ecommerce_id`, hash email/telefono) con risoluzione deterministica e collegamenti probabilistici a confidenza ridotta; merge con trasferimento unità e chiusura del membro assorbito, unmerge da snapshot, alias per gli id storici; modalità storica riattivabile con due variabili.

## API e OpenAPI

Tutti i servizi Java espongono `/v3/api-docs` e Swagger UI (springdoc); paginazione keyset dove le liste crescono
(`/v1/read/members/snapshots?after&size`), rate limiting all'ingresso (`INGRESS_RATE_LIMIT`, resilience4j), chiavi di
idempotenza ovunque (azioni, effetti, consegne, merge). BFF: `POST /api/members/{id}/next-best-action`,
`GET/POST /api/members/{id}/inbox[/…/read|accept|dismiss]`, `GET/POST /api/members/{id}/consents`,
`GET /api/consent-purposes`, `POST /api/identity/resolve`, `POST /api/members/{id}/events` (comportamentali);
console operatore: decisioni, rischio, identità e merge, coda contatti, consensi con prova.

## Osservabilità e BI

Metriche `loyalty_decisions_total`, `loyalty_decision_rejections_total`, `loyalty_decision_effects_total`,
`loyalty_predictions_total`, `loyalty_experiment_exposures_total`, `loyalty_risk_assessments_total`,
`loyalty_deliveries_total`, `loyalty_consents_total`, `loyalty_identity_events_total`; sei alert nuovi con runbook.
ClickHouse: `fact_decision`, `fact_delivery`, `fact_risk` e viste `kpi_decisions_daily`, `kpi_experiment_uplift`,
`kpi_rejections_daily`, `kpi_deliveries_daily`, `kpi_risk_daily` per i cruscotti Superset del backoffice.

## Cosa è entrato (0.5.0)

| Componente | Modifica |
| --- | --- |
| `common` | `correlationid` (CloudEvents), tipi/topic/azioni nuovi, metriche Loyalty 4.0 |
| `read-model` | context-service: `CustomerContext`, `ContextProjector`, `ContextStore` (JSONB), proiezioni da 10 topic, `GET /v1/context/{id}`, snapshot keyset |
| `rules-engine` | `POST /v1/evaluations` (valutazione pura), `rules.apply-effects` |
| `decision-service` (nuovo) | `DecisionPolicy`, `DecisionEngine`, `Candidate`, `Decision`, `Offer`, `Experiment`, `PredictionProvider` (regole, HTTP, composito, routing), `DecisionService`, decision log append-only, consumer, API NBA/simulazione, adattatori CMS con cache |
| `fraud-service` (nuovo) | `RiskPolicy`, `RiskEngine`, store dei segnali con finestre, consumer di 4 topic, blocco automatico, API e simulazione |
| `notifier` | delivery: `ChannelAdapter`, `DeliveryRouting`, `DeliveryService`, adattatori (inbox, fornitori, webhook, operatore), inbox e coda operatore |
| `member-service` | consensi con finalità/base giuridica/scadenza/prova, storico, scadenza automatica, API |
| `identity-mapping` | grafo identità, risoluzione, link/unlink, merge/unmerge con snapshot, alias, effetti su ledger e membro |
| CMS | 7 collezioni (`decision-policies`, `offers`, `experiments`, `prediction-providers`, `fraud-rules`, `delivery-routing`, `consent-purposes`), endpoint di simulazione e decision log, permessi |
| BFF / sito | NBA, inbox, consensi, identità, eventi comportamentali; console operatore estesa |
| Infra | compose, Helm, release, alert, runbook, ClickHouse schema 050 |

Verifica: Smoke4 (55 asserzioni standalone su motore, vincoli, canali, strategie, NBA, previsioni, routing,
esperimenti, candidati) verde; controllo dei tipi di tutti i servizi modificati; JUnit nei moduli per la CI.
