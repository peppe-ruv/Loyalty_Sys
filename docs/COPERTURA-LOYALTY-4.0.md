# Copertura dello scenario "Loyalty 4.0"

Verifica del 17 settembre 2026 sullo scaffold 0.4.0, punto per punto rispetto allo scenario (23 punti + Definition of
Done). Stato: **coperto** = esiste ed è testato; **parziale** = esiste in parte o in forma diversa; **mancante** = da
progettare. Le evidenze citano i componenti del repository.

## Sintesi

| Area | Stato | Evidenza / gap principale |
| --- | --- | --- |
| 1. Paradigma event-driven a ciclo chiuso | parziale | Azione → Kafka → campagne → effetti → nuove azioni interne (D08) c'è; manca il passaggio "decisione" tra regole e azione |
| 2. Event layer | coperto | CloudEvents 1.0 (id, type versionato, time, subject, source), chiave di idempotenza, schemi per tipo (RF-98), AsyncAPI; manca `correlationId` esplicito e gli eventi comportamentali web |
| 3. Loyalty ledger | coperto | `ledger`: movimenti immutabili (trigger DB), saldo derivato e materializzato nella stessa transazione, EARN/SPEND/EXPIRY/REVERSAL/BLOCK/TRANSFER/MANUAL, outbox; manca solo l'estensione della scadenza |
| 4. Customer 360 | parziale | `MemberSnapshot` (profilo, tier, wallet, azioni, campi custom, badge, achievement, campagne) esiste come vista per i segmenti; `read-model` è uno stub; mancano RFM, CLV, propensity, engagement, interazioni per canale |
| 5. Identity resolution | parziale | identificatori con priorità/unicità/abbinamento e tessera (RF-108), `/v1/members/resolve`; `identity-mapping` è uno stub; mancano deviceId/appId, grafo delle identità, merge/unmerge |
| 6. Rules engine | coperto | campagne dichiarative (RF-80..RF-86): AND nella regola, OR tra regole, segmenti, periodo, frequenza, categorie/SKU, canale, tier, comportamento (espressioni su wallet e storia), premi, limiti, priorità; configurabili nel CMS, versionate, testate |
| 7. Decision engine | mancante | le regole producono effetti direttamente; nessuna arbitrazione tra azioni ammissibili con vincoli, costo, margine, propensione |
| 8. Next Best Action | mancante | nessuna interfaccia `getNextBestAction(customerId, context)` |
| 9. AI/ML layer | mancante | nessuna porta `PredictionProvider` |
| 10. AI separata dalla decisione | mancante (per costruzione) | non c'è AI; la catena previsione → decisione → regole → policy va disegnata insieme ai punti 7-9 |
| 11. Customer Context in tempo reale | parziale | i dati esistono ma `rules-engine` interroga tier, ledger, segmenti, badge uno per uno (proprio l'anti-pattern citato); manca il servizio di contesto aggregato |
| 12. Omnichannel | parziale | `channel` canonico su ogni azione, modelli per canale (email/SMS/push/in-app), webhook, widget; manca l'adattatore di canale che consegna una decisione (offerta) ad app/POS/web/CRM |
| 13. API-first | parziale (alto) | REST versionate per ogni servizio, idempotency key, OIDC, webhook, OpenAPI ingresso + AsyncAPI; mancano API Decisions/Offers/Predictions/Analytics, paginazione uniforme, OpenAPI per tutti i servizi, rate limiting nel gateway (previsto, non nel repo) |
| 14. Integrazioni event-driven | coperto | Kafka con topic per dominio, outbox, webhook HMAC, CDC verso data platform, ClickHouse che legge i topic |
| 15. Fraud & abuse detection | mancante | esistono limiti, velocità, impronta dispositivo, blocco unità (D12, RF-88); nessun servizio che produca `riskScore`/`riskLevel`/`reasonCodes` dagli eventi |
| 16. Gamification come modulo | coperto | `engagement-service` separato (missioni, badge, streak, challenge, classifiche, achievement, progresso) sullo stesso event layer |
| 17. Experimentation | mancante | nessun gruppo di controllo/varianti né misura dell'effetto incrementale; Superset misura solo i volumi |
| 18. Observability e audit | parziale (alto) | metriche, tracing, log strutturati, correlazione, SLO, audit del ledger e degli stati premi (RF-117..RF-124); il simulatore spiega regole scattate/scartate ma la spiegazione non è persistita come "decision history" |
| 19. Privacy by design | parziale | consensi per chiave, anonimizzazione, export, pseudonimizzazione nei segnali, warehouse senza anagrafica; manca il modello consenso con purpose/source/timestamp/expiration/legalBasis e la revoca propagata |
| 20. Evoluzione incrementale con ACL | coperto (diverso) | programma nuovo che coesiste con BeIren (D15), adattatori per fonte, porte sostituibili (`LoyaltyEngine`, `ContentProvider`, `EventBus`, `IdentityProvider`, `RewardFulfiller`) |
| 21. Backward compatibility | coperto | BeIren resta attivo, nessuna migrazione; le funzioni elencate esistono nel nuovo hub |
| 22. Bounded contexts | parziale | IDENTITY, CUSTOMER, LOYALTY, REWARDS, CAMPAIGNS, RULES, ENGAGEMENT, ANALYTICS presenti; DECISIONS e FRAUD mancanti; CONSENT dentro CUSTOMER |
| 23. Da transaction-centric a decision-centric | parziale | siamo a "customer + event centric"; manca il livello decisionale |

Stima complessiva: circa **due terzi** dello scenario è coperto o coperto in forma diversa; il terzo mancante è
concentrato in un unico strato, quello decisionale (7, 8, 9, 10, 11, 15, 17), più il modello consenso/identità (5, 19).

## Definition of Done: il ciclo end-to-end

| Passo | Stato | Componente |
| --- | --- | --- |
| EVENT | coperto | `ingress-adapters`, CloudEvents, schemi |
| IDENTITY | parziale | identificatori in `member-service`; `identity-mapping` da completare |
| CUSTOMER 360 | parziale | `MemberSnapshot`, `read-model` da completare |
| CONTEXT | mancante | servizio di contesto aggregato |
| ELIGIBILITY | coperto | `CampaignEvaluator` (trigger, limiti, visibilità, motivi di scarto) |
| RULES | coperto | campagne + espressioni |
| PREDICTION | mancante | `PredictionProvider` |
| DECISION | mancante | motore decisionale + NBA |
| ACTION | coperto | effetti: unità, premi, badge, tier, attributi, eventi |
| CHANNEL | parziale | notifier per canale; manca l'adattatore per offerte/esperienze |
| NEW EVENT | coperto | azioni interne (D08) |
| MEASUREMENT | parziale | metriche, ClickHouse e Superset; manca l'incrementale |

Garanzie richieste: auditabilità delle decisioni (parziale), idempotenza (coperta), consistenza del ledger (coperta),
privacy by design (parziale), osservabilità (coperta), API versioning (coperta), backward compatibility (coperta),
motore AI sostituibile (mancante), nuovi canali e premi senza toccare il core (premi sì via `RewardFulfiller`; canali
parziale), test automatici delle regole critiche (coperti: JUnit + smoke su campagne, tier, ledger, instant win).

## Dettaglio delle corrispondenze

**Eventi dello scenario → eventi nostri.** CustomerRegistered = MEMBER_ENROLLED; CustomerIdentified = da aggiungere
(identity-mapping); PurchaseCompleted = TRANSACTION; PurchaseRefunded = TRANSACTION_RETURNED; ProductViewed e
ProductAddedToCart = schemi custom da definire (eventi comportamentali dal sito/app); RewardEarned = movimento EARN;
RewardRedeemed = REDEMPTION; PointsExpired = movimento EXPIRY; TierChanged = TIER_CHANGED; CampaignEntered/Completed =
effetti di campagna (da esplicitare come eventi); CouponIssued/Redeemed = REDEMPTION (DELIVERED/USED) e CODE_REDEEMED;
CustomerInactivityDetected = SEGMENT_ENTERED (segmento inattivi); CustomerChurnRiskChanged = da aggiungere (previsioni).

**Struttura dell'evento.** `eventId` = `id`, `eventType` = `type`, `eventVersion` = suffisso `.v1` del tipo,
`timestamp` = `time`, `customerId` = `subject`, `source` = `source`, `data` = `data`: coincide con CloudEvents.
Manca `correlationId`: si aggiunge come estensione CloudEvents e si propaga con il trace id (W3C) già presente.

**Ledger.** earning, redemption, expiration, reversal, refund (= reversal), manual adjustment, bonus (= EARN con
causale), transfer: presenti; expiration extension: da aggiungere come movimento di tipo EXTEND che riassegna la scadenza.

**Regole.** cooldown: oggi espresso come limite per periodo; da rendere esplicito (`minIntervalBetweenExecutions`).

## Cosa manca e come chiuderlo (proposta, da approvare)

| Gap | Proposta | Stima |
| --- | --- | --- |
| Customer Context / 360 (4, 11) | completare `read-model` come **context-service**: vista materializzata per membro (profilo, tier, wallet, ultime N azioni, campagne attive, premi disponibili, offerte recenti, consensi, previsioni) aggiornata dai topic, API `GET /v1/context/{memberId}` < 20 ms; `rules-engine` la usa al posto delle chiamate multiple | 1,5 mesi/persona |
| Decision engine + NBA (7, 8, 10) | nuovo **decision-service**: ingresso = evento + contesto; passi eligibility (campagne, catalogo, offerte) → regole → vincoli/policy (budget, frequenza di contatto, margine, consensi, rischio) → punteggio (rule-based, poi predizioni) → decisione strutturata (`NO_ACTION`, `AWARD_POINTS`, `ISSUE_REWARD`, `ISSUE_COUPON`, `UPGRADE_TIER`, `SEND_MESSAGE`, `SHOW_OFFER`, `TRIGGER_CAMPAIGN`, `ASK_FOR_FEEDBACK`) con `reason` e `decisionId`; `getNextBestAction(memberId, context)`; **decision log** persistito e interrogabile (regole ammissibili, scartate con motivo, scelta) | 2,5 mesi/persona |
| AI/ML layer (9) | porta `PredictionProvider` (`predictChurn`, `predictPurchase`, `predictCategoryAffinity`, `predictRewardAcceptance`, `calculateCustomerValue`, `calculateOfferPropensity`) con `RuleBasedProvider` (RFM da ClickHouse) subito e adattatori per modelli locali o esterni; le previsioni entrano solo nel decision-service, mai nel ledger | 1 mese/persona (rule-based) |
| Fraud detection (15) | **fraud-service** sugli stessi topic: segnali (frequenza riscatti, account multipli per dispositivo/identificatori, earning anomalo, creazioni rapide, pattern impossibili, abuso codici, resi, anomalie dispositivo) → `riskScore`, `riskLevel`, `reasonCodes` come evento e nel contesto; il decision-service li usa come vincolo; blocco unità automatico sopra soglia | 1,5 mesi/persona |
| Channel adapter (12) | nel decision-service l'azione è indipendente dal canale; **delivery-service** (evoluzione di `notifier`) con adattatori APP (widget/pop-up), WEB, EMAIL, SMS, PUSH, POS/sportello (postazione operatore), CRM, customer service; tracciamento consegna/esposizione come eventi | 1 mese/persona |
| Experimentation (17) | assegnazione deterministica a gruppo di controllo e varianti per campagna/decisione (hash membro), esposizioni ed esiti nel warehouse, cruscotto Superset dell'effetto incrementale (conversione, ricavo, riscatti, retention, margine) | 1 mese/persona |
| Consenso e identità (5, 19) | modello consenso con purpose, source, timestamp, expiration, legalBasis, storia e revoca propagata via evento; grafo identità in `identity-mapping` (deviceId, appId, POS, e-commerce) con merge/unmerge tracciati e riflessi nel ledger | 1,5 mesi/persona |
| Event layer (2) | `correlationId` come estensione CloudEvents; eventi comportamentali `ProductViewed`, `ProductAddedToCart` come schemi; eventi espliciti `CAMPAIGN_ENTERED/COMPLETED`, `CUSTOMER_IDENTIFIED`, `CHURN_RISK_CHANGED` | 0,5 mesi/persona |
| API (13) | OpenAPI per tutti i servizi, paginazione uniforme (keyset), rate limiting nel gateway con quote per fonte, API Decisions/Offers/Predictions/Analytics | 0,5 mesi/persona |

Totale indicativo: 10-11 mesi/persona, in parallelo su due filoni (contesto+decisione+AI; frodi+consenso+canali).
Ordine consigliato, coerente con il punto 20 dello scenario: context-service → decision-service (rule-based) →
PredictionProvider rule-based → fraud-service → delivery-service → experimentation → consenso/identità. Nessun
componente esistente va riscritto: il decision-service si inserisce tra `rules-engine` e gli effetti, e il
`CampaignEvaluator` diventa il passo "eligibility + rules" della catena.
