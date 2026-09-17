# Architettura · Architecture

> 🇮🇹 Documento di riferimento per chi progetta o modifica la piattaforma: confini, contratti, garanzie,
> punti di rottura. Presuppone familiarità con sistemi distribuiti, Kafka e Kubernetes.
> 🇬🇧 Reference document for anyone designing or changing the platform: boundaries, contracts, guarantees,
> breaking points. Assumes familiarity with distributed systems, Kafka and Kubernetes.

Indice · Contents: [1. Principi](#1-principi--principles) · [2. Contesto](#2-contesto--context) ·
[3. Servizi](#3-servizi-di-dominio--domain-services) · [4. Eventi](#4-eventi-e-contratti--events-and-contracts) ·
[5. Dati](#5-dati-e-consistenza--data-and-consistency) · [6. Ciclo decisionale](#6-ciclo-decisionale--decision-cycle) ·
[7. Configurazione](#7-configurazione-a-runtime--runtime-configuration) · [8. Scalabilità](#8-scalabilità-e-capacità--scalability-and-capacity) ·
[9. Guasti](#9-modalità-di-guasto-e-degrado--failure-modes-and-degradation) · [10. Sicurezza](#10-sicurezza-e-privacy--security-and-privacy) ·
[11. Deploy](#11-topologia-di-esercizio--deployment-topology) · [12. Alternative](#12-alternative-scartate--rejected-alternatives)

---

## 1. Principi · Principles

1. **Vendor neutral.** Ogni dipendenza esterna è dietro una porta (interfaccia) del dominio: motore loyalty,
   contenuti, bus eventi, identità, evasione premi. Sostituire un fornitore è scrivere un adattatore.
2. **Event-driven a ciclo chiuso.** Tutto ciò che accade è un evento; anche gli effetti della piattaforma (vincite,
   completamenti, esposizioni) rientrano come eventi e sono premiabili con le stesse regole.
3. **Il registro è la verità.** I saldi non sono uno stato modificabile ma la somma di movimenti immutabili.
4. **Decidere è un atto separato dal valutare.** Il motore regole dice cosa *può* scattare; il motore decisionale
   sceglie cosa *fare*, con vincoli e spiegazione.
5. **Configurazione, non rilascio.** Ciò che marketing, legale o antifrode devono poter cambiare vive nel backoffice
   con versione e workflow di approvazione.
6. **Un servizio, un database.** Nessun accesso incrociato alle tabelle: solo eventi o API.
7. **Idempotenza obbligatoria.** Ogni scrittura ha una chiave; ogni consumatore tollera il replay.
8. **Nessun dato personale nel dominio loyalty.** L'identità è un identificatore opaco; l'anagrafica resta nel CRM.

**EN**

1. **Vendor neutral.** Every external dependency sits behind a domain port: loyalty engine, content, event bus,
   identity, reward fulfilment. Replacing a vendor means writing an adapter.
2. **Closed-loop event driven.** Everything that happens is an event; platform effects (wins, completions,
   impressions) re-enter as events and can be rewarded by the same rules.
3. **The ledger is the truth.** Balances are not mutable state but the sum of immutable movements.
4. **Deciding is separate from evaluating.** The rules engine states what *may* fire; the decision engine chooses
   what to *do*, with constraints and an explanation.
5. **Configuration, not deployment.** Anything marketing, legal or fraud teams must change lives in the back office
   with versioning and an approval workflow.
6. **One service, one database.** No cross-service table access: events or APIs only.
7. **Idempotency is mandatory.** Every write carries a key; every consumer tolerates replay.
8. **No personal data in the loyalty domain.** Identity is an opaque identifier; profile data stays in the CRM.

---

## 2. Contesto · Context

```
                     ┌───────────────────────── Loyalty Hub ─────────────────────────┐
  CRM ───────┐       │                                                               │
  Billing ───┼──────▶│ ingress-adapters ──▶ Kafka ──▶ rules-engine ──▶ decision-svc  │──▶ app / site / push / email / SMS
  Portal ────┤  REST │        ▲                │            │              │         │──▶ CRM (webhook)
  App ───────┤  Kafka│        │                │            ▼              ▼         │──▶ operatore · operator console
  Partner ───┘  SFTP │        │                │        ledger · tier · rewards      │
                     │        │                │            │                        │
  IdP (OIDC) ───────▶│ identity-mapping        └──▶ engagement · contests · fraud     │
                     │        │                                    │                  │
  Object storage ◀───│ read-model (Customer 360) ◀─────────────────┘                  │
  Warehouse (BI) ◀───│ analytics pipeline                                             │
                     └───────────────────────────────────────────────────────────────┘
```

| Attore esterno · External actor | Ruolo · Role | Contratto · Contract |
| --- | --- | --- |
| CRM | Anagrafica, recapiti, consensi commerciali · Profile, contact details, marketing consents | Eventi in ingresso + webhook in uscita · Inbound events + outbound webhooks |
| Billing / ERP | Transazioni, pagamenti, resi · Transactions, payments, returns | Eventi in ingresso (batch o streaming) · Inbound events (batch or streaming) |
| Portale e app · Portal and app | Azioni utente, eventi comportamentali · User actions, behavioural events | REST via BFF |
| Identity provider (OIDC) | Autenticazione di membri e operatori · Member and operator authentication | OIDC / JWT |
| Partner | Azioni di terze parti, evasione premi · Third-party actions, reward fulfilment | REST + webhook firmati · REST + signed webhooks |

---

## 3. Servizi di dominio · Domain services

14 servizi Java più il modulo condiviso. Ogni servizio ha: porta HTTP, schema PostgreSQL proprio, migrazioni
Flyway, package `domain/` (logica pura, senza framework), `app/` (servizi applicativi e accesso ai dati), `api/`
(REST `/v1/...`), `messaging/` (consumatori Kafka), `*Config` (bean e adattatori sostituibili nei test).
**EN** — 14 Java services plus a shared module. Each service has: an HTTP port, its own PostgreSQL schema, Flyway
migrations, a `domain/` package (pure logic, framework-free), `app/` (application services and data access), `api/`
(REST `/v1/...`), `messaging/` (Kafka consumers), `*Config` (beans and adapters overridable in tests).

| Servizio · Service | Porta | Schema | Responsabilità · Responsibility | Requisiti · Reqs |
| --- | --- | --- | --- | --- |
| `common` | — | — | Evento canonico, tipi, valute, metriche, ponte log · Canonical event, types, currencies, metrics, log bridge | RI-01, RF-117 |
| `ingress-adapters` | 8081 | ingress | REST/Kafka/file → evento canonico; idempotenza, validazione schema, codici promo, catalogo prodotti, rate limit, DLQ · normalisation, idempotency, schema validation, promo codes, product catalogue, rate limiting, DLQ | RI-01…04, RF-69, RF-98 |
| `rules-engine` | 8082 | rulesengine | Campagne (trigger → condizioni → effetti), espressioni, automazioni, referral, simulatore, valutazione senza effetti · campaigns, expressions, automations, referral, simulator, effect-free evaluation | RF-80…86 |
| `ledger` | 8083 | ledger | Movimenti append-only, wallet configurabili, sospensioni, scadenze, blocchi, trasferimenti, outbox · append-only movements, configurable wallets, pending, expiry, blocks, transfers, outbox | RF-87…89, RI-08 |
| `tier-service` | 8084 | tierservice | Tier set, valutazione soglie, discesa, benefici, override manuali · tier sets, threshold evaluation, downgrade, benefits, manual overrides | RF-10…13, RF-105…107 |
| `catalog-redemption` | 8085 | catalogredemption | Catalogo premi, riscatti e stati, lotti di codici, conversione unità, paga con i punti · reward catalogue, redemptions and states, coupon pools, units conversion, pay-with-points | RF-102…104 |
| `contest-service` | 8086 | contestservice | Instant win a istanti pre-generati, ruota, registro giocate · instant win on pre-generated moments, wheel, play log | RF-30…36, RF-95 |
| `identity-mapping` | 8087 | identitymapping | Grafo identità, risoluzione, merge/unmerge, alias · identity graph, resolution, merge/unmerge, aliases | RF-136 |
| `read-model` | 8088 | readmodel | Customer 360 per membro, proiezioni da 10 topic, snapshot, audience · per-member Customer 360, projections from 10 topics, snapshots, audiences | RF-125, RF-126 |
| `notifier` | 8089 | notifier | Modelli di messaggio, webhook firmati, consegna omnicanale, inbox, coda operatore · message templates, signed webhooks, omnichannel delivery, inbox, operator queue | RF-77, RF-78, RF-132 |
| `segment-service` | 8090 | segmentservice | Segmenti dinamici e statici (30 criteri), collezioni di valori, appartenenze · dynamic and static segments (30 criteria), value collections, memberships | RF-65, RF-100, RF-109 |
| `member-service` | 8091 | memberservice | Adesione, stati, etichette, campi custom, identificatori, referral, consensi, GDPR · enrolment, states, labels, custom fields, identifiers, referral, consents, GDPR | RF-72, RF-99, RF-135 |
| `engagement-service` | 8092 | engagementservice | Achievement, challenge, badge, classifiche e cicli premianti · achievements, challenges, badges, leaderboards and rewarding cycles | RF-90…96 |
| `decision-service` | 8093 | decisionservice | Motore decisionale, Next Best Action, decision log, previsioni, esperimenti · decision engine, Next Best Action, decision log, predictions, experiments | RF-127…130, RF-134 |
| `fraud-service` | 8094 | fraudservice | Segnali di rischio → punteggio, livello, motivi; blocco automatico · risk signals → score, level, reason codes; automatic block | RF-131 |

Componenti non-Java · Non-Java components:

| Componente · Component | Tecnologia | Ruolo · Role |
| --- | --- | --- |
| `cms` | Payload CMS (TypeScript) | Backoffice: contenuti, configurazione, workflow di approvazione, vista BI · back office: content, configuration, approval workflows, BI view |
| `web/bff` | Node | Aggregazione per area membro e console operatore, degrado controllato · aggregation for member area and operator console, graceful degradation |
| `web/site` | Next.js | Sito e widget incorporabili · site and embeddable widgets |
| `analytics` | ClickHouse + Superset | Warehouse alimentato dai topic e cruscotti degli andamenti · topic-fed warehouse and trend dashboards |

### Bounded context

| Contesto · Context | Servizi · Services |
| --- | --- |
| IDENTITY | `identity-mapping` |
| CUSTOMER | `member-service`, `read-model` |
| LOYALTY | `ledger`, `tier-service` |
| REWARDS | `catalog-redemption` |
| CAMPAIGNS / RULES | `rules-engine` |
| DECISIONS | `decision-service` |
| ENGAGEMENT | `engagement-service`, `contest-service` |
| FRAUD | `fraud-service` |
| CONSENT | `member-service` |
| DELIVERY | `notifier` |
| ANALYTICS | `read-model`, warehouse |

Una funzionalità che tocca due contesti passa da eventi, non da join. **EN** — A feature spanning two
contexts travels through events, never through joins.

---

## 4. Eventi e contratti · Events and contracts

### 4.1 Busta canonica · Canonical envelope

Un evento è un **CloudEvents 1.0 JSON**. Campi rilevanti:
**EN** — An event is a **CloudEvents 1.0 JSON** document. Relevant fields:

| Campo · Field | Uso · Usage |
| --- | --- |
| `id` | Chiave di idempotenza end-to-end · end-to-end idempotency key |
| `type` | Tipo versionato, es. `io.loyaltyhub.action.v1` · versioned type |
| `source` | URN della fonte, es. `urn:loyaltyhub:ingress:crm` · source URN |
| `subject` | `member:<id>` — chiave di partizione Kafka · Kafka partition key |
| `time` | Istante di produzione (UTC) · production instant (UTC) |
| `correlationid` | Estensione: correla evento → decisione → effetto → consegna · extension correlating event → decision → effect → delivery |
| `data` | Payload tipizzato · typed payload |

```json
{
  "specversion": "1.0",
  "id": "a3f1…",
  "type": "io.loyaltyhub.action.v1",
  "source": "urn:loyaltyhub:ingress:portal",
  "subject": "member:9f2c…",
  "time": "2026-01-15T10:00:00Z",
  "correlationid": "c-8812…",
  "data": {
    "actionType": "TRANSACTION",
    "idempotencyKey": "portal-2026-01-15-8812",
    "externalRef": "INV-2026-8812",
    "occurredAt": "2026-01-15T09:58:11Z",
    "attributes": { "amount": 84.50, "channel": "web" },
    "lines": [ { "sku": "SRV-01", "category": "service", "amount": 84.50, "quantity": 1 } ]
  }
}
```

### 4.2 Topic

| Topic | Produttori · Producers | Consumatori · Consumers |
| --- | --- | --- |
| `loyalty.actions.v1` | `ingress-adapters`, tutti i servizi (azioni interne · internal actions) | `rules-engine`, `decision-service`, `fraud-service`, `read-model` |
| `loyalty.movements.v1` | `ledger` | `tier-service`, `read-model`, `fraud-service`, warehouse |
| `loyalty.tiers.v1` | `tier-service` | `read-model`, `notifier`, warehouse |
| `loyalty.redemptions.v1` | `catalog-redemption` | `read-model`, `fraud-service`, warehouse |
| `loyalty.contests.v1` | `contest-service` | `read-model`, `fraud-service`, warehouse |
| `loyalty.members.v1` | `member-service` | `read-model`, `segment-service`, warehouse |
| `loyalty.segments.v1` | `segment-service` | `read-model`, `rules-engine` |
| `loyalty.decisions.v1` | `decision-service` | `notifier`, `read-model`, warehouse |
| `loyalty.risk.v1` | `fraud-service` | `read-model`, `ledger` (blocco · block) |
| `loyalty.deliveries.v1` | `notifier` | `read-model`, warehouse |
| `loyalty.consents.v1` | `member-service` | `read-model`, `decision-service` |
| `loyalty.identities.v1` | `identity-mapping` | `read-model`, `ledger` |
| `loyalty.actions.dlq.v1` | `ingress-adapters` | Backoffice (riprocessamento · reprocessing) |

Chiave di partizione: `memberId`, così l'ordine per membro è garantito. Consumatori idempotenti (chiave
applicativa oppure `ON CONFLICT DO NOTHING`). Un cambio incompatibile è un nuovo topic `.v2` con periodo di
coesistenza, non una modifica in loco.
**EN** — Partition key: `memberId`, guaranteeing per-member ordering. Consumers are idempotent (application key or
`ON CONFLICT DO NOTHING`). A breaking change means a new `.v2` topic with a coexistence window, never an in-place edit.

### 4.3 API REST

Ogni servizio espone `/v1/...` con OpenAPI su `/v3/api-docs`. Regole trasversali:
**EN** — Every service exposes `/v1/...` with OpenAPI at `/v3/api-docs`. Cross-cutting rules:

| Regola · Rule | Dettaglio · Detail |
| --- | --- |
| Idempotenza · Idempotency | Chiave nel corpo (`actionKey`, `grantKey`, `transferKey`, `mergeId`); ritorno della stessa risposta al replay · key in the body; replay returns the same response |
| Paginazione · Pagination | Keyset (`after`, `size`) su tutte le liste che crescono · keyset on every growing list |
| Errori · Errors | Problem Details (RFC 9457) con codice motivo `UPPER_SNAKE` · Problem Details with an `UPPER_SNAKE` reason code |
| Versioni · Versioning | Prefisso di percorso `/v1`; deprecazione annunciata nell'header `Sunset` · `/v1` path prefix; deprecation announced via `Sunset` header |
| Autorizzazione · Authorisation | JWT OIDC; ambiti separati per membro, operatore, fonte di integrazione · OIDC JWT; separate scopes for member, operator, integration source |

---

## 5. Dati e consistenza · Data and consistency

### 5.1 Registro movimenti · Movement ledger

- Tabella append-only protetta da trigger: nessun `UPDATE`, nessun `DELETE`.
- Il saldo è materializzato nella **stessa transazione** del movimento: lettura O(1), riconciliazione sempre possibile
  ricalcolando dalla somma.
- Tipi di movimento: `EARN`, `SPEND`, `EXPIRY`, `REVERSAL`, `BLOCK`, `UNBLOCK`, `TRANSFER_OUT`, `TRANSFER_IN`,
  `MANUAL`.
- Le rettifiche non cancellano: si registra un movimento inverso con riferimento all'originale.
- Unità in sospeso (`available_at`) e scadenze (`expires_at`) sono attributi del movimento, non stati separati.

**EN**

- Append-only table enforced by triggers: no `UPDATE`, no `DELETE`.
- The balance is materialised in the **same transaction** as the movement: O(1) reads, always reconcilable by
  recomputing the sum.
- Movement kinds: `EARN`, `SPEND`, `EXPIRY`, `REVERSAL`, `BLOCK`, `UNBLOCK`, `TRANSFER_OUT`, `TRANSFER_IN`, `MANUAL`.
- Corrections never delete: an inverse movement is written with a reference to the original.
- Pending units (`available_at`) and expiry (`expires_at`) are movement attributes, not separate states.

### 5.2 Outbox transazionale · Transactional outbox

```
BEGIN
  INSERT INTO movement (...)          -- fatto di dominio · domain fact
  UPDATE balance SET ...              -- proiezione sincrona · synchronous projection
  INSERT INTO outbox (topic, payload) -- evento da pubblicare · event to publish
COMMIT
        │
        └──▶ OutboxRelay ──▶ Kafka ──▶ (segna come pubblicato · mark as published)
```

Nessun evento perso, nessun evento senza fatto: se Kafka è indisponibile la coda cresce (metrica
`loyalty_outbox_pending`) e si drena al ripristino. **EN** — No lost events, no events without a fact: if Kafka is
unavailable the queue grows (`loyalty_outbox_pending` metric) and drains on recovery.

### 5.3 Letture e proiezioni · Reads and projections

| Lettura · Read | Sorgente · Source | Freschezza · Freshness |
| --- | --- | --- |
| Saldo di un membro · Member balance | `ledger` (proiezione sincrona · synchronous projection) | Immediata · immediate |
| Customer 360 | `read-model` (proiezioni da 10 topic · projections from 10 topics) | Secondi · seconds |
| Segmenti · Segments | `segment-service` (ricalcolo notturno + a evento · nightly and event-driven) | Minuti/ore · minutes/hours |
| Andamenti · Trends | Warehouse (ingestione dai topic · topic ingestion) | ~5 minuti · ~5 minutes |

L'interfaccia mostra sempre l'istante di aggiornamento delle letture eventualmente consistenti.
**EN** — The UI always shows the freshness timestamp of eventually consistent reads.

### 5.4 Concorrenza · Concurrency

| Caso · Case | Tecnica · Technique |
| --- | --- |
| Assegnazione di un premio da lotto · Coupon pool draw | `SELECT … FOR UPDATE SKIP LOCKED` (nessun codice assegnato due volte · no code issued twice) |
| Assegnazione instant win · Instant win award | Lock riga sull'istante vincente + registro con hash concatenato · row lock on the winning moment + hash-chained log |
| Riscatto · Redemption | Transazione singola: debito nel registro + creazione riscatto · single transaction: ledger debit + redemption creation |
| Doppio invio dalla fonte · Duplicate source delivery | Tabella `seen_keys` sulla chiave di idempotenza · `seen_keys` table on the idempotency key |
| Merge di identità · Identity merge | Macchina a stati registrata (`merge_history.status`): ogni passo è idempotente, la chiave di merge è di idempotenza, uno scheduler riprende i merge interrotti · recorded state machine: every step idempotent, the merge key is the idempotency key, a scheduler resumes interrupted merges |

---

## 6. Ciclo decisionale · Decision cycle

```
evento · event
  └─▶ decision-service
        ├─▶ read-model      GET /v1/context/{id}        Customer 360 in una chiamata · in one call
        ├─▶ rules-engine    POST /v1/evaluations        candidati, senza effetti · candidates, no side effects
        ├─▶ offers          catalogo + condizione · catalogue + condition
        ├─▶ predictions     churn, propensioni, valore · churn, propensities, value
        ├─▶ experiment      assegnazione deterministica · deterministic assignment
        └─▶ DecisionEngine  (funzione pura · pure function)
              ├─▶ effetti contrattuali sempre applicati · contractual effects always applied
              │     ledger · tier · engagement · member
              ├─▶ decision log (append-only)  →  API, backoffice, BI
              └─▶ azioni discrezionali arbitrate · discretionary actions arbitrated
                    notifier → canale · channel → fallback → inbox / push / email / SMS / webhook / operatore
```

Garanzie: (a) le azioni contrattuali (punti, tier, badge, attributi, eventi) non sono mai arbitrate;
(b) previsioni ed esperimenti influenzano solo il punteggio delle azioni discrezionali; (c) ogni decisione è
ricostruibile dal decision log con policy, versione, candidati, scartati e motivo.
**EN** — Guarantees: (a) contractual actions (points, tiers, badges, attributes, events) are never arbitrated;
(b) predictions and experiments only influence the scoring of discretionary actions; (c) every decision is
reconstructible from the decision log with policy, version, candidates, rejects and reason.

Dettaglio · Detail: [`DECISIONING.md`](DECISIONING.md).

---

## 7. Configurazione a runtime · Runtime configuration

Due sorgenti, ruoli distinti:
**EN** — Two sources, distinct roles:

| Sorgente · Source | Cosa contiene · Contents | Chi la cambia · Who changes it |
| --- | --- | --- |
| `application.yml` / variabili d'ambiente · env vars | Endpoint, credenziali (da gestore segreti), dimensioni di pool, flag di modalità · endpoints, secrets (from a secret manager), pool sizes, mode flags | Piattaforma, con rilascio · platform, via release |
| Backoffice (CMS) | Regole, campagne, premi, tier set, wallet, segmenti, policy decisionali, offerte, esperimenti, segnali antifrode, instradamento consegne, finalità di consenso, contenuti · business rules and policies, content | Marketing, legale, antifrode, senza rilascio · business teams, no release |

Lettura dal backoffice: solo documenti `status=published`, cache di 30 secondi, ripiego all'ultimo valore
buono e poi al seed di codice. Un backoffice fermo non ferma la piattaforma. Il numero di versione del documento è
riportato in ogni decisione e valutazione, per l'audit.
**EN** — Back-office reads: `status=published` documents only, 30-second cache, fallback to last known good and then
to the code seed. A back office that is down does not stop the platform. The document version number is recorded in
every decision and assessment for audit purposes.

---

## 8. Scalabilità e capacità · Scalability and capacity

| Dimensione · Dimension | Riferimento di progetto · Design target | Leva · Lever |
| --- | --- | --- |
| Membri · Members | 2.000.000 | Partizioni Kafka per `memberId`; sharding non necessario a questa scala · Kafka partitions by `memberId`; no sharding needed at this scale |
| Azioni · Actions | 50–200/s di regime, picchi 2.000/s al lancio di un concorso · steady 50–200/s, peaks of 2,000/s at contest launch | HPA su `ingress-adapters` (fino a 20 repliche), `linger.ms` del producer · HPA and producer batching |
| Giocate concorso · Contest plays | 500/s con p95 < 500 ms | Lock di riga breve, sala d'attesa virtuale oltre soglia · short row lock, virtual waiting room above threshold |
| Letture Customer 360 · Customer 360 reads | 1.000/s | Documento JSONB per membro, cache di processo, cache distribuita opzionale · per-member JSONB document, in-process cache, optional distributed cache |
| Warehouse | Miliardi di righe · billions of rows | Partizioni mensili, viste materializzate, TTL 5 anni · monthly partitions, materialised views, 5-year TTL |

Regole di dimensionamento: repliche di un consumatore ≤ numero di partizioni del topic; ogni servizio ha
`PodDisruptionBudget` e `HorizontalPodAutoscaler`; i pool di connessioni sono dimensionati sul limite del database,
non sul numero di pod.
**EN** — Sizing rules: consumer replicas ≤ topic partitions; every service has a `PodDisruptionBudget` and a
`HorizontalPodAutoscaler`; connection pools are sized against the database limit, not the pod count.

---

## 9. Modalità di guasto e degrado · Failure modes and degradation

| Guasto · Failure | Effetto · Effect | Degrado previsto · Designed degradation |
| --- | --- | --- |
| Kafka non disponibile · unavailable | Nessuna propagazione · no propagation | L'ingresso risponde 202 e accoda nell'outbox; i saldi restano leggibili · ingestion returns 202 and queues in the outbox; balances stay readable |
| Backoffice non disponibile · unavailable | Nessuna nuova configurazione · no new configuration | Ultimo valore pubblicato in cache, poi seed di codice · last published value from cache, then code seed |
| Identity provider non disponibile · unavailable | Login impossibile · no login | Pagina di cortesia; le API interne restano operative · courtesy page; internal APIs keep running |
| `decision-service` fermo · down | Nessuna arbitrazione · no arbitration | Modalità storica: il motore regole applica gli effetti (`RULES_APPLY_EFFECTS=true`, `DECISIONS_ENABLED=false`) · legacy mode |
| Provider di previsione lento · slow prediction provider | Punteggi meno informati · less informed scoring | Timeout breve, nessun retry, ripiego al provider a regole · short timeout, no retry, rule-based fallback |
| Canale di consegna in errore · delivery channel failing | Messaggi non recapitati · undelivered messages | Fallback di canale da configurazione; consegne fallite nel registro, non ripetute automaticamente · configured channel fallback; failures logged, not auto-retried |
| Perdita della region primaria · primary region loss | Fermo del servizio · service outage | Ripristino in seconda region da backup continui (RPO 15 min, RTO 4 h) · restore in a secondary region from continuous backups |

Interruttori di emergenza: portare a zero le repliche di `contest-service` ferma le giocate (il sito
disabilita i pulsanti e il BFF risponde 503); i flag di modalità disattivano il livello decisionale senza toccare dati.
**EN** — Kill switches: scaling `contest-service` to zero replicas stops plays (the site disables the buttons and the
BFF answers 503); mode flags disable the decision layer without touching data.

---

## 10. Sicurezza e privacy · Security and privacy

| Aspetto · Aspect | Scelta · Choice |
| --- | --- |
| Identità membro · Member identity | Identificatore opaco del provider OIDC; nessuna credenziale nella piattaforma · opaque OIDC subject; no credentials stored |
| Dati personali · Personal data | Fuori dal dominio loyalty: anagrafica e recapiti restano nel CRM; qui solo id, hash e attributi tecnici · out of the loyalty domain; only ids, hashes and technical attributes |
| Segnali · Telemetry | Nessun dato personale in log, metriche, tracce e warehouse; l'id membro è pseudonimizzato nel collector · no personal data in logs, metrics, traces or warehouse; member id pseudonymised in the collector |
| Segreti · Secrets | Mai nel repository: gestore segreti del cloud + External Secrets in cluster · never in the repository: cloud secret manager + External Secrets in-cluster |
| Espressioni utente · User expressions | Valutate in contesto di sola lettura, senza accesso a riflessione o I/O · evaluated in a read-only context, no reflection or I/O access |
| Webhook | Firma HMAC-SHA256, segreto rotabile, tentativi con backoff · HMAC-SHA256 signature, rotatable secret, backoff retries |
| Consensi · Consents | Finalità, base giuridica, versione dell'informativa, storico immutabile, revoca propagata in secondi · purpose, legal basis, notice version, immutable history, revocation propagated in seconds |
| Residenza dei dati · Data residency | Region unica configurabile; per i concorsi a premio italiani i dati restano in Italia · single configurable region; Italian prize promotions keep data in Italy |
| Catena di fornitura · Supply chain | Immagini firmate (cosign), SBOM, scansione vulnerabilità in CI · signed images, SBOM, CI vulnerability scanning |

---

## 11. Topologia di esercizio · Deployment topology

```
Region (default eu-south-1)
├── Kubernetes (managed)         3 zone · 3 availability zones
│   ├── namespace loyalty        14 servizi Java + BFF + sito + CMS
│   ├── namespace observability  Prometheus ×2 + Thanos, Alertmanager ×3, Grafana ×2, Loki, Tempo, OTel ×2
│   └── namespace analytics      ClickHouse (1 shard × 3 repliche) + Keeper, Superset (web ×2, worker ×2, beat)
├── PostgreSQL gestito · managed multi-AZ, backup continui · continuous backups
├── Kafka gestito · managed      3 broker, replicazione 3, min.insync 2
├── Cache gestita · managed      Redis (sessioni, cache di lettura · sessions, read caches)
├── Object storage               metriche lunghe, log, tracce, backup, export · long-term metrics, logs, traces, backups, exports
└── Secret manager               credenziali applicative e chiavi dei concorsi · application credentials and contest keys
```

Tutto è descritto in Terraform (`deploy/terraform`) e Helm (`deploy/helm/loyalty-hub`): un template generico
genera Deployment, Service, HPA e PDB per ogni voce di `values.yaml → services`. Aggiungere un servizio è una riga di
configurazione, non un nuovo manifesto.
**EN** — Everything is described in Terraform (`deploy/terraform`) and Helm (`deploy/helm/loyalty-hub`): a generic
template renders Deployment, Service, HPA and PDB for each entry in `values.yaml → services`. Adding a service is one
configuration line, not a new manifest.

---

## 12. Alternative scartate · Rejected alternatives

| Alternativa · Alternative | Perché scartata · Why rejected | ADR |
| --- | --- | --- |
| Motore loyalty SaaS · SaaS loyalty engine | Canone per membro, residenza dei dati e perizia dei concorsi fuori controllo · per-member fees, data residency and contest certification out of our control | [ADR-001](adr/ADR-001.md) |
| PaaS senza region locale · PaaS without a local region | Vincolo di residenza dei dati per i concorsi · data residency constraint for prize promotions | [ADR-002](adr/ADR-002.md) |
| Vincoli e punteggio dentro il motore regole · constraints and scoring inside the rules engine | Due responsabilità in un modulo, nessuna separazione contrattuale/discrezionale · two responsibilities in one module, no contractual/discretionary split | [ADR-021](adr/ADR-021.md) |
| Antifrode nelle campagne · fraud rules in campaigns | Le campagne premiano, non giudicano; mancano finestre temporali e correlazioni tra membri · campaigns reward, they do not judge; no time windows or cross-member correlation | [ADR-023](adr/ADR-023.md) |
| PostgreSQL come warehouse | Regge fino a ~10 milioni di eventi al mese · holds up to ~10M events per month | [ADR-020](adr/ADR-020.md) |
| Stack di osservabilità a licenza non OSI · non-OSI observability stack | Vincolo di licenza e costo dello storage dei log · licensing constraint and log storage cost | [ADR-019](adr/ADR-019.md) |
