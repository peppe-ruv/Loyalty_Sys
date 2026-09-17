# Osservabilità applicativa e BI nel backoffice

Richiesta (17 settembre 2026): livello enterprise, con uno strumento di monitoraggio delle metriche di funzionamento
applicativo e uno strumento di BI dentro il backoffice per seguire gli andamenti, tutto open source al massimo livello
di affidabilità. Decisioni: ADR-019 (osservabilità) e ADR-020 (BI). Requisiti RF-117..RF-124.

## Scelte

| Esigenza | Strumento | Licenza / maturità | Perché |
| --- | --- | --- | --- |
| Metriche e alert | Prometheus (Operator, 2 repliche) + Thanos (query deduplicata, S3, 13 mesi) + Alertmanager (3 repliche) | Apache 2.0 · CNCF graduated | standard de facto su Kubernetes; metriche già esposte dai servizi (Micrometer) |
| Dashboard tecniche | Grafana in HA (2 repliche, Postgres, SSO OIDC) | AGPL 3.0 (uso interno, nessuna redistribuzione) | un solo pannello per metriche, log e tracce; dashboard versionate nel repo |
| Log | Grafana Loki (simple scalable, 3 repliche, S3, 30 giorni) | AGPL 3.0 | log strutturati ECS con trace id, correlati alle tracce; costo per volume basso |
| Tracce | Grafana Tempo (S3, 14 giorni, service map) | AGPL 3.0 | OTLP nativo; metriche RED derivate dalle tracce |
| Raccolta | OpenTelemetry Collector (2 repliche) | Apache 2.0 · CNCF | un solo protocollo (OTLP) per tracce, metriche e log; pseudonimizzazione dell'id membro |
| Warehouse | ClickHouse (1 shard × 3 repliche, Keeper, backup S3) | Apache 2.0 | ingestione diretta da Kafka, query analitiche in millisecondi su miliardi di righe |
| BI | Apache Superset (2 web, 2 worker, beat; Postgres + Redis) | Apache 2.0 · ASF top-level | embedding ufficiale con guest token e row-level security; cruscotti importabili da repo; alert e report |

Alternative valutate (ADR-019/020): stack Elastic (licenza SSPL/Elastic, non open source OSI); SaaS (Datadog, Grafana
Cloud): fuori vincolo vendor neutral e dati fuori Italia; Metabase (AGPL, embedding interattivo solo a pagamento);
Redash (manutenzione ridotta); Grafana come BI (non pensata per analisti e drill-down); Postgres come warehouse
(regge fino a ~10 milioni di eventi al mese, poi ClickHouse).

## Come è fatto

```
servizi Java (Micrometer + OTel) ──OTLP──▶ OTel Collector ──▶ Tempo (tracce)  ──┐
     │ /actuator/prometheus                     └────────▶ Loki  (log)     ──┼─▶ Grafana (SSO) ◀── link dal backoffice
     └──scrape──▶ Prometheus ×2 ──sidecar──▶ Thanos (S3) ──▶ Alertmanager ×3 ─┘      ▲
Kafka topic di dominio ──Kafka engine──▶ ClickHouse ×3 ──▶ Apache Superset ──guest token + RLS──▶ vista "Andamenti" del backoffice
```

Componenti nel repo: `deploy/observability/` (values Helm, `alerts/loyalty-rules.yaml`, `dashboards/*.json`, profilo
locale), `deploy/terraform/observability.tf` (bucket S3 cifrati con ciclo di vita, ruolo IRSA, segreti DB),
`deploy/bi/` (ClickHouse operator CR, Superset chart), `analytics/clickhouse/schema/` (Kafka engine → fatti →
viste KPI), `analytics/superset/` (config, init idempotente, export del cruscotto), `services/common/.../metrics/`
(metriche di business, ponte log OTLP), `cms/src/views/Analytics.tsx` + `cms/src/endpoints/biGuestToken.ts`,
`make observability`, `make bi`, `make up-observability`, `make up-bi`.

## Metriche di business (RF-117)

| Metrica | Etichette | Uso |
| --- | --- | --- |
| `loyalty_actions_received_total` | source, action_type, status | volumi per fonte, tasso di scarto (alert > 5%) |
| `loyalty_campaign_effects_total` | campaign, type | effetti applicati per campagna |
| `loyalty_actions_without_campaign_total` | action_type | azioni senza campagna attiva (RF-03) |
| `loyalty_movements_total`, `loyalty_units_total` | wallet, kind | unità emesse, spese, scadute, bloccate, trasferite |
| `loyalty_outbox_pending` | service | righe outbox non pubblicate (alert > 1000) |
| `loyalty_redemptions_total` | status, reward_type | riscatti per esito |
| `loyalty_coupon_pool_remaining` | pool | scorta lotti codici (alert < 100) |
| `loyalty_contest_plays_total`, `loyalty_contest_prizes_remaining` | contest, won | giocate, vincite, montepremi residuo (alert < 5) |
| `loyalty_wheel_spins_total` | wheel, won | giri della ruota |
| `loyalty_achievements_completed_total`, `loyalty_challenges_completed_total`, `loyalty_badges_granted_total` | id | gamification |
| `loyalty_member_events_total` | event | adesioni, sospensioni, oblio, cambi tier |
| `loyalty_webhook_deliveries_total` | subscription, delivered | consegne webhook (alert > 10 fallite in 30 min) |

Più le metriche tecniche standard: HTTP (istogrammi per rotta e stato), Kafka consumer/producer, HikariCP, JVM,
Resilience4j, `bff_http_request_duration_seconds` per il BFF, lag dei consumer via kafka-exporter.

## SLO e alert (RF-120)

| SLO | Obiettivo | Alert |
| --- | --- | --- |
| Disponibilità ingresso azioni | 99,95% su 30 giorni | burn rate multi-finestra (5 min e 1 h) → page |
| Latenza ingresso p95 | < 200 ms (RI-04) | > 200 ms per 10 min → page |
| Latenza giocata / ruota p95 | < 500 ms | > 500 ms per 5 min → page + Legal concorsi |
| Latenza riscatto p95 | < 500 ms | cruscotto |
| Freschezza saldi | lag consumer < 10.000 messaggi | > 10 min → page |
| Integrità eventi | outbox drena, DLQ < 5% | > 5 min / > 15 min → page / ticket |
| Concorsi | premi residui, giocate in corso, lotti codici | ticket a Legal/marketing |

Le regole di registrazione (`slo:*`) alimentano cruscotti e alert; i runbook sono in `docs/runbooks/osservabilita.md`.

## Cruscotti

Grafana (metriche tecniche, per SRE e piattaforma): `Piattaforma (SLO)`, `Programma (RF-46)`, `Concorsi e instant win`.
Superset (andamenti, per marketing e direzione, dentro il backoffice): adesioni per giorno; punti emessi, spesi,
scaduti; transazioni e valore; riscatti; membri per tier; passività punti (unità in circolazione); retention per
coorte di adesione e canale; concorsi (giocate, vincite, giocatori, tasso); azioni per tipo. Filtro nativo per canale;
aggiornamento ogni 5 minuti; esportazione CSV; alert e report programmati di Superset per email.

## Requisiti (RF-117..RF-124)

- RF-117 Ogni servizio espone metriche tecniche e di business (Micrometer, prefisso `loyalty_`, etichette a bassa cardinalità, mai l'id membro); i servizi Node espongono `/metrics`.
- RF-118 Ogni richiesta e ogni messaggio Kafka portano un trace id (W3C); tracce, log strutturati (ECS, con trace id) e metriche arrivano via OTLP a un collector che pseudonimizza l'id membro; niente dati personali nei segnali.
- RF-119 Cruscotti versionati nel repository (Grafana JSON, Superset export) e applicati automaticamente all'installazione; cruscotti per piattaforma, programma e concorsi.
- RF-120 SLO dichiarati con alert a burn rate multi-finestra; instradamento a on-call, Legal concorsi e ticket; ogni alert cita il runbook.
- RF-121 Warehouse analitico alimentato in tempo reale dai topic di dominio (nessuna estrazione dai database dei servizi), schema a stella senza dati personali, retention 5 anni, repliche su tre zone, backup notturno.
- RF-122 Strumento di BI open source con cruscotti, esplorazione self-service per gli analisti (SSO), alert e report programmati, cache e code separate, alta disponibilità.
- RF-123 I cruscotti degli andamenti sono incorporati nel backoffice (vista "Andamenti") con token ospite di breve durata e row-level security per ruolo; l'operatore non ha credenziali della BI; l'indisponibilità della BI non blocca il backoffice.
- RF-124 Retention: metriche 15 giorni locali e 13 mesi su S3 (downsampling), log 30 giorni, tracce 14 giorni, warehouse 5 anni; bucket cifrati, accesso IRSA senza chiavi, cicli di vita Terraform.

## Costi e dimensionamento (indicativi, eu-south-1)

Osservabilità: 3 nodi m6i.xlarge dedicati (~450 €/mese) + S3 (~40 €/mese). BI: 3 nodi r6i.xlarge per ClickHouse
(~700 €/mese) + Superset su 2 pod piccoli. Tutto senza licenze.

## Verifica

`make up-observability` e `make up-bi` in locale: Grafana su :3005 con i tre cruscotti provisionati, Superset su :8088
con il cruscotto importato e incorporato in `/admin/analytics`, ClickHouse popolato dai topic dopo `make seed-local`.
In CI: lint YAML/JSON dei values, delle regole e dei cruscotti; `promtool check rules` sulle regole (job `observability`).
