# Warehouse analitico (ClickHouse)

Alimentazione: tabelle `Kafka` per ogni topic di dominio → viste materializzate → fatti `ReplicatedMergeTree` (3 repliche
con ClickHouse Keeper, partizioni mensili, TTL 5 anni). Nessun dato personale: solo l'id loyalty (D12).

Applicazione dello schema (una volta per ambiente, con i placeholder sostituiti):

```sh
for f in analytics/clickhouse/schema/*.sql; do
  sed "s/{kafka_bootstrap}/$KAFKA_BOOTSTRAP/" "$f" | clickhouse-client --host clickhouse.analytics --multiquery
done
```

In locale (`docker compose --profile bi`) lo schema è applicato all'avvio da `docker-entrypoint-initdb.d` con cluster a nodo singolo.
Backup: `clickhouse-backup` verso il bucket `clickhouse-backup` (Terraform), pianificato ogni notte (deploy/bi/clickhouse.values.yaml).
