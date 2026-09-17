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

### Decisioni: LoyaltyDecisionEffectFailures / LoyaltyDecisionsStalled / LoyaltyRejectionsSpike (decision-service)
1. Effetti falliti: `loyalty_decision_effects_total{ok="false"}` per azione → il servizio di destinazione è nel nome (ledger, catalogo, engagement, tier). La decisione è nel decision log (`GET /v1/decisions/{id}`), ripetibile con l'operatore; gli effetti sono idempotenti per chiave.
2. Decisioni ferme: consumer `decision-service` (lag Kafka) o rules-engine `/v1/evaluations` lento (tracce con `correlationid`). Ripiego: `RULES_APPLY_EFFECTS=true` sul rules-engine e `DECISIONS_ENABLED=false` → modalità storica senza arbitrato, nessuna perdita (RF-136).
3. Scarti in massa per canale/consenso: non è un guasto ma una policy troppo stretta; marketing rivede cap di contatto, ore di silenzio e consensi richiesti nel backoffice (decision-policies), effetto in 30 s senza rilascio.

### LoyaltyPredictionProviderFailing
Il provider esterno risponde in errore o oltre il timeout: il composito usa le regole (nessuna decisione bloccata). Verificare URL, chiave (`apiKeyEnv`) e latenza; disattivare il provider nel backoffice (prediction-providers) se l'errore persiste.

### LoyaltyRiskCriticalSurge (fraud, con team frodi)
1. `GET /v1/risk/top` e console operatore: quali segnali (reasonCodes) dominano. Attacco reale (es. MULTI_ACCOUNT_DEVICE, CODE_ABUSE): tenere il blocco automatico, aprire l'incidente.
2. Falsi positivi (es. IMPOSSIBLE_TRAVEL da coordinate errate di una fonte): alzare soglie o disattivare il segnale nel backoffice (fraud-rules); i membri vengono rivalutati e sbloccati alla rivalutazione periodica o con `POST /v1/risk/members/{id}/assess`.

### LoyaltyDeliveryFailures
Canale in errore (fornitore email/sms/push, webhook CRM): il routing (delivery-routing) permette di spostare le consegne su un altro canale (es. inbox in app) senza rilascio; le consegne fallite sono nel `delivery_log` e non vengono ripetute da sole (evitare doppi contatti).

## Manutenzione

- Retention: Prometheus 15 giorni locali, Thanos 13 mesi; Loki 30 giorni; Tempo 14 giorni; ClickHouse 5 anni (TTL). Modifiche in `deploy/observability/*.values.yaml` e `deploy/terraform/observability.tf`.
- Nuovo cruscotto Grafana: esportare JSON in `deploy/observability/dashboards/`, aggiungerlo a `kustomization.yaml`.
- Nuovo cruscotto Superset: esportare da Superset (Dashboards → Export), scompattare in `analytics/superset/dashboards/`, aggiungere l'id a `DASHBOARDS` in `cms/src/endpoints/biGuestToken.ts`.
- Nuova metrica di business: metodo su `LoyaltyMetrics`, etichette a bassa cardinalità, mai id membro.
- Backup ClickHouse: `clickhouse-backup` notturno su S3; ripristino `clickhouse-backup restore_remote <nome>`.
- Test di carico prima di ogni concorso (D11): confrontare con i cruscotti SLO; alert in modalità silenzio (`amtool silence`) solo per la finestra del test.

## Escalation

page → on-call piattaforma (15 min) → responsabile ICT (30 min). Alert con `team=legal-contest` anche a Legal concorsi. Ticket → backlog piattaforma entro il giorno lavorativo successivo.
