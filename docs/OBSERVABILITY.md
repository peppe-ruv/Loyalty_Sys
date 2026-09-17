# Osservabilità e BI · Observability and BI

> 🇮🇹 Come si capisce se la piattaforma funziona (metriche, log, tracce, SLO) e come si seguono gli andamenti del
> programma (warehouse e cruscotti nel backoffice). Tutti gli strumenti sono open source e in alta affidabilità.
> Requisiti `RF-117`…`RF-124`; decisioni [ADR-019](adr/ADR-019.md) e [ADR-020](adr/ADR-020.md).
> 🇬🇧 How to tell whether the platform is healthy (metrics, logs, traces, SLOs) and how to follow program trends
> (warehouse and dashboards inside the back office). Every tool is open source and deployed for high availability.
> Requirements `RF-117`…`RF-124`; decisions [ADR-019](adr/ADR-019.md) and [ADR-020](adr/ADR-020.md).

---

## 1. Due domande, due strumenti · Two questions, two toolchains

| Domanda · Question | Pubblico · Audience | Strumento · Tool |
| --- | --- | --- |
| "Il sistema funziona? Dove è lento? Perché ha sbagliato?" · "Is the system healthy? Where is it slow? Why did it fail?" | Piattaforma, SRE, sviluppo · platform, SRE, engineering | Metriche, log, tracce, alert su SLO · metrics, logs, traces, SLO alerts |
| "Il programma funziona? Quanti punti abbiamo emesso? Chi riscatta?" · "Is the program working? How many units did we issue? Who redeems?" | Marketing, direzione, analisti · marketing, leadership, analysts | Warehouse analitico e cruscotti nel backoffice · analytical warehouse and dashboards in the back office |

---

## 2. Componenti · Components

| Esigenza · Need | Strumento · Tool | Configurazione · Deployment | Perché · Why |
| --- | --- | --- | --- |
| Metriche e alert · Metrics and alerting | Prometheus + Thanos + Alertmanager | 2 repliche Prometheus, storage a oggetti per la retention lunga, 3 repliche Alertmanager | Standard su Kubernetes; i servizi già espongono metriche · Kubernetes standard; services already expose metrics |
| Cruscotti tecnici · Technical dashboards | Grafana | 2 repliche, database metadati, SSO | Un solo pannello per metriche, log e tracce; dashboard versionate · one pane for metrics, logs and traces; versioned dashboards |
| Log | Loki | 3 repliche, storage a oggetti, 30 giorni | Log strutturati con trace id, costo per volume basso · structured logs with trace id, low cost per volume |
| Tracce · Traces | Tempo | Storage a oggetti, 14 giorni, mappa dei servizi | Protocollo nativo OTLP, metriche derivate dalle tracce · native OTLP, span metrics |
| Raccolta · Collection | OpenTelemetry Collector | 2 repliche | Un solo protocollo per tracce, metriche e log; pseudonimizzazione degli identificatori · one protocol for all signals; identifier pseudonymisation |
| Warehouse | ClickHouse | 1 shard × 3 repliche, coordinatore, backup su storage a oggetti | Ingestione diretta dai topic, query analitiche in millisecondi · direct topic ingestion, millisecond analytics |
| BI | Apache Superset | 2 web, 2 worker, scheduler; database metadati e cache | Incorporabile con token ospite e sicurezza a livello di riga; cruscotti versionati · embeddable with guest tokens and row-level security; versioned dashboards |

---

## 3. Flusso dei segnali · Signal flow

```
servizi (metriche + OTLP) ──▶ OTel Collector ──▶ Tempo (tracce · traces)   ──┐
   │  /actuator/prometheus         └──────────▶ Loki  (log · logs)         ──┼─▶ Grafana (SSO)
   └── scrape ──▶ Prometheus ×2 ──▶ Thanos (storage) ──▶ Alertmanager ×3   ──┘        ▲
                                                                                      │ link
topic di dominio · domain topics ──▶ ClickHouse ×3 ──▶ Superset ──token ospite──▶ vista "Andamenti" nel backoffice
                                                        guest token          "Trends" view in the back office
```

Il warehouse si alimenta **dai topic**, non dai database dei servizi: nessuna estrazione notturna, nessun
accoppiamento allo schema interno, nessun carico sulle basi di dati operative.
**EN** — The warehouse is fed **from the topics**, not from service databases: no nightly extraction, no coupling to
internal schemas, no load on operational databases.

---

## 4. Metriche di business · Business metrics

| Metrica · Metric | Etichette · Labels | Uso · Usage |
| --- | --- | --- |
| `loyalty_actions_received_total` | fonte, tipo, esito · source, type, status | Volumi per fonte, tasso di scarto · volumes per source, rejection rate |
| `loyalty_actions_without_campaign_total` | tipo · type | Azioni che nessuna campagna premia · actions no campaign rewards |
| `loyalty_campaign_effects_total` | campagna, tipo · campaign, type | Effetti applicati per campagna · applied effects per campaign |
| `loyalty_movements_total`, `loyalty_units_total` | wallet, tipo · wallet, kind | Unità emesse, spese, scadute, bloccate, trasferite · units issued, spent, expired, blocked, transferred |
| `loyalty_outbox_pending` | servizio · service | Eventi non ancora pubblicati · events not yet published |
| `loyalty_redemptions_total` | stato, tipo premio · status, reward type | Riscatti per esito · redemptions by outcome |
| `loyalty_coupon_pool_remaining` | lotto · pool | Scorta dei codici · code stock |
| `loyalty_contest_plays_total`, `loyalty_contest_prizes_remaining` | concorso, vinta · contest, won | Giocate, vincite, montepremi residuo · plays, wins, remaining prizes |
| `loyalty_wheel_spins_total` | ruota, vinta · wheel, won | Giri della ruota · wheel spins |
| `loyalty_achievements_completed_total`, `loyalty_challenges_completed_total`, `loyalty_badges_granted_total` | id | Gamification |
| `loyalty_member_events_total` | evento · event | Adesioni, sospensioni, chiusure, cambi di tier · enrolments, suspensions, closures, tier changes |
| `loyalty_webhook_deliveries_total` | sottoscrizione, consegnato · subscription, delivered | Consegne webhook · webhook deliveries |
| `loyalty_decisions_total`, `loyalty_decision_rejections_total`, `loyalty_decision_effects_total` | azione, motivo, esito · action, reason, outcome | Livello decisionale · decision layer |
| `loyalty_predictions_total` | provider, chiave · provider, key | Previsioni e ripieghi · predictions and fallbacks |
| `loyalty_decision_units_granted_today`, `loyalty_decision_units_budget` | policy | Consumo del budget giornaliero di unità · daily units budget consumption |
| `loyalty_circuit_breaker_state` | nome · name | Interruttore automatico verso un servizio esterno: 0 chiuso, 1 aperto, 2 in prova · external-service circuit breaker: 0 closed, 1 open, 2 half-open |
| `loyalty_experiment_exposures_total` | esperimento, variante · experiment, variant | Esposizioni · exposures |
| `loyalty_risk_assessments_total` | livello · level | Valutazioni di rischio · risk assessments |
| `loyalty_deliveries_total` | canale, esito · channel, outcome | Consegne · deliveries |
| `loyalty_consents_total` | finalità, stato · purpose, state | Consensi e revoche · consents and revocations |
| `loyalty_identity_events_total` | tipo · type | Risoluzioni, merge, unmerge · resolutions, merges, unmerges |

Più le metriche tecniche standard: HTTP per rotta e stato, produttori e consumatori Kafka con lag, pool di
connessioni, JVM, resilienza, durata delle richieste del BFF.
**EN** — Plus standard technical metrics: HTTP by route and status, Kafka producers and consumers with lag, connection
pools, JVM, resilience, BFF request duration.

**Regola · Rule** — Etichette a bassa cardinalità e **mai** un identificatore di persona in una metrica.
**EN** Low-cardinality labels and **never** a person identifier in a metric.

---

## 5. SLO e alert · SLOs and alerts

| Obiettivo · Objective | Target | Alert |
| --- | --- | --- |
| Disponibilità dell'ingestione · Ingestion availability | 99,95% su 30 giorni · over 30 days | Burn rate multi-finestra (5 min e 1 h) → intervento immediato · immediate page |
| Latenza ingestione p95 · Ingestion p95 | < 200 ms | Oltre soglia per 10 min → intervento · above threshold for 10 min → page |
| Latenza giocata p95 · Play p95 | < 500 ms | Oltre soglia per 5 min → intervento + referente concorsi · page + contest owner |
| Latenza riscatto p95 · Redemption p95 | < 500 ms | Cruscotto · dashboard |
| Freschezza dei saldi · Balance freshness | Lag di consumo sotto soglia · consumer lag under threshold | Oltre 10 min → intervento · page |
| Integrità degli eventi · Event integrity | Outbox drena, scarti sotto il 5% · outbox drains, rejects under 5% | 5 min → intervento; 15 min → ticket |
| Concorsi · Contests | Premi residui, giocate in corso, scorte codici · remaining prizes, ongoing plays, code stock | Ticket a referente legale e marketing · ticket to legal and marketing owners |

Ogni alert cita il runbook corrispondente: un alert senza runbook non si pubblica.
**EN** — Every alert references its runbook: an alert without a runbook does not ship.
Runbook: [`runbooks/observability.md`](runbooks/observability.md).

---

## 6. Cruscotti · Dashboards

| Cruscotto · Dashboard | Strumento · Tool | Contenuto · Content |
| --- | --- | --- |
| Piattaforma (SLO) · Platform (SLO) | Grafana | Disponibilità, latenze, errori, lag di consumo, budget di errore · availability, latencies, errors, consumer lag, error budget |
| Programma · Program | Grafana | Azioni per fonte, effetti, unità emesse e spese, riscatti · actions per source, effects, units issued and spent, redemptions |
| Concorsi · Contests | Grafana | Giocate, vincite, montepremi, latenza di giocata · plays, wins, prize pool, play latency |
| Andamenti · Trends | Superset (nel backoffice · in the back office) | Adesioni per giorno; unità emesse, spese, scadute; transazioni e valore; riscatti; membri per tier; passività punti; retention per coorte e canale; concorsi; decisioni e scarti; effetto incrementale degli esperimenti · enrolments per day; units issued, spent, expired; transactions and value; redemptions; members per tier; outstanding units; retention by cohort and channel; contests; decisions and rejections; experiment uplift |

I cruscotti vivono nel repository (JSON Grafana ed export Superset) e vengono applicati all'installazione:
un cruscotto modificato a mano e non esportato è un cruscotto perso.
**EN** — Dashboards live in the repository (Grafana JSON and Superset exports) and are applied at install time: a
dashboard edited by hand and never exported is a lost dashboard.

---

## 7. Warehouse

| Aspetto · Aspect | Scelta · Choice |
| --- | --- |
| Alimentazione · Ingestion | Tabelle di consumo sui topic → viste materializzate → fatti replicati · topic-consuming tables → materialised views → replicated facts |
| Modello · Model | Schema a stella per dominio (azioni, movimenti, riscatti, giocate, decisioni, consegne, rischio) · per-domain star schema |
| Dati personali · Personal data | Nessuno: solo identificatore loyalty e attributi tecnici · none: loyalty identifier and technical attributes only |
| Partizioni e retention · Partitions and retention | Partizioni mensili, TTL 5 anni · monthly partitions, 5-year TTL |
| Affidabilità · Reliability | 3 repliche su zone distinte, backup notturno su storage a oggetti · 3 replicas across zones, nightly object-storage backup |
| Accesso · Access | Analisti via SSO su Superset; operatori solo tramite la vista incorporata · analysts via SSO in Superset; operators only through the embedded view |

Schema e comandi · Schema and commands: [`../analytics/clickhouse/README.md`](../analytics/clickhouse/README.md).

---

## 8. BI incorporata nel backoffice · BI embedded in the back office

La vista "Andamenti" del backoffice incorpora i cruscotti con un token ospite di breve durata emesso da un
endpoint del CMS; la sicurezza a livello di riga limita ciò che ogni ruolo vede. L'operatore non ha credenziali della
BI e l'indisponibilità della BI non blocca il backoffice.
**EN** — The back-office "Trends" view embeds dashboards using a short-lived guest token issued by a CMS endpoint;
row-level security limits what each role sees. Operators hold no BI credentials, and BI downtime never blocks the back
office.

---

## 9. Retention e privacy · Retention and privacy

| Segnale · Signal | Retention |
| --- | --- |
| Metriche locali · Local metrics | 15 giorni · 15 days |
| Metriche su storage a oggetti · Object-storage metrics | 13 mesi con aggregazione · 13 months with downsampling |
| Log | 30 giorni · 30 days |
| Tracce · Traces | 14 giorni · 14 days |
| Warehouse | 5 anni · 5 years |
| Osservazioni antifrode · Fraud observations | 90 giorni · 90 days |

Gli identificatori di persona sono pseudonimizzati nel collector prima di raggiungere log, tracce e metriche;
i bucket sono cifrati e l'accesso è per identità di servizio, senza chiavi statiche.
**EN** — Person identifiers are pseudonymised in the collector before reaching logs, traces and metrics; buckets are
encrypted and access uses workload identity, not static keys.

---

## 10. Dimensionamento indicativo · Indicative sizing

| Insieme · Set | Risorse · Resources | Nota · Note |
| --- | --- | --- |
| Osservabilità · Observability | 3 nodi di media taglia + storage a oggetti · 3 mid-size nodes + object storage | Nessun costo di licenza · no licence cost |
| BI e warehouse · BI and warehouse | 3 nodi con memoria abbondante per il warehouse + 2 pod per la BI · 3 memory-rich nodes for the warehouse + 2 BI pods | Dimensionare sul volume di eventi mensile · size against monthly event volume |

---

## 11. Verifica in locale · Local verification

```sh
make up-observability   # Grafana :3005 con i cruscotti provisionati · with provisioned dashboards
make up-bi              # ClickHouse :8123, Superset :8088, vista Andamenti nel backoffice
make seed-local         # genera eventi: i cruscotti si popolano · generates events: dashboards fill up
```

In integrazione continua: validazione di values, regole e cruscotti, e controllo sintattico delle regole di
alert. **EN** — In continuous integration: validation of values, rules and dashboards, plus syntax checking of alert
rules.
