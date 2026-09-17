# Runbook osservabilità e BI

## Dove guardare

| Cosa | Dove |
| --- | --- |
| Metriche e SLO | Grafana → Loyalty Hub → Piattaforma (SLO), Programma, Concorsi |
| Log | Grafana → Explore → Loki, filtro `{namespace="loyalty", service="<servizio>"}`; clic sul trace id per la traccia |
| Tracce | Grafana → Explore → Tempo (TraceQL: `{ resource.service.name = "rules-engine" && status = error }`) |
| Alert | Alertmanager (`monitoring-alertmanager.observability:9093`) e Grafana → Alerting |
| Andamenti | Backoffice → Andamenti (Superset incorporato); Superset diretto su `bi.<dominio>` per gli analisti |

## Alert e cosa fare

### LoyaltyIngressErrorBudgetBurn / LoyaltyIngressLatency (ingresso)
1. Grafana → Piattaforma: errori 5xx per servizio e p95. Se solo `ingress-adapters`: guardare Postgres (`seen_keys`) e Kafka producer (`kafka_producer_record_error_total`).
2. Log: `{service="ingress-adapters"} |= "ERROR"`.
3. Mitigazione: scalare `ingress-adapters` (HPA fino a 20); se Kafka è lento, `KAFKA_PRODUCER_LINGER_MS`; se Postgres, verificare connessioni HikariCP.
4. Le fonti ricevono 202 comunque (RI-03): gli eventi non persi vanno rielaborati dalla coda di scarto (RF-45).

### LoyaltyConsumerLag (saldi in ritardo)
1. `kafka:consumer_lag:max` per gruppo: rules-engine lento → guardare latenza verso ledger/tier/segmenti (tracce); ledger lento → Postgres.
2. Scalare il consumer (concurrency `rules.consumer.concurrency`, repliche) o il DB. Il sito mostra «aggiornato alle» (RF-50): nessuna azione utente.

### LoyaltyOutboxStuck
1. `loyalty_outbox_pending` cresce: relay fermo o Kafka non raggiungibile. Log del ledger `OutboxRelay`.
2. Riavviare il pod ledger leader; verificare ACL/IAM MSK. Nessuna perdita: l'outbox è transazionale.

### LoyaltyPlayLatency / LoyaltyContestNoPlaysDuringRunning / LoyaltyContestPrizesLow (concorsi, con Legal)
1. Non modificare mai la configurazione di un concorso avviato (RF-35). Latenza: Postgres `contestservice.play` (lock `FOR UPDATE` sull'ultimo hash → contention attesa ai lanci; sala d'attesa virtuale se > 500 ms).
2. Nessuna giocata: verificare sito, login IAM, landing CDN; avvisare marketing.
3. Premi residui bassi: informare marketing; l'estrazione di recupero (RF-36) è a fine concorso.

### LoyaltyDlqRate / LoyaltyActionsWithoutCampaign
1. Backoffice → coda di scarto: motivo (schema, membro sconosciuto, timestamp). Correggere lo schema (RF-98) o la fonte, poi riprocessare.
2. Azioni senza campagna: pubblicare la campagna o il tipo azione mancante; gli eventi restano nel topic per il replay (RF-03).

### LoyaltyCouponPoolLow / LoyaltyWebhookFailures
Caricare un nuovo lotto (RF-74); verificare l'endpoint del partner e il segreto HMAC (RF-78); i tentativi riprendono da soli.

## Manutenzione

- Retention: Prometheus 15 giorni locali, Thanos 13 mesi; Loki 30 giorni; Tempo 14 giorni; ClickHouse 5 anni (TTL). Modifiche in `deploy/observability/*.values.yaml` e `deploy/terraform/observability.tf`.
- Nuovo cruscotto Grafana: esportare JSON in `deploy/observability/dashboards/`, aggiungerlo a `kustomization.yaml`.
- Nuovo cruscotto Superset: esportare da Superset (Dashboards → Export), scompattare in `analytics/superset/dashboards/`, aggiungere l'id a `DASHBOARDS` in `cms/src/endpoints/biGuestToken.ts`.
- Nuova metrica di business: metodo su `LoyaltyMetrics`, etichette a bassa cardinalità, mai id membro.
- Backup ClickHouse: `clickhouse-backup` notturno su S3; ripristino `clickhouse-backup restore_remote <nome>`.
- Test di carico prima di ogni concorso (D11): confrontare con i cruscotti SLO; alert in modalità silenzio (`amtool silence`) solo per la finestra del test.

## Escalation

page → on-call piattaforma (15 min) → responsabile ICT (30 min). Alert con `team=legal-contest` anche a Legal concorsi. Ticket → backlog piattaforma entro il giorno lavorativo successivo.
