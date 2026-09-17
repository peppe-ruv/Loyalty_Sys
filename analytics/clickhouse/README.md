# Warehouse analitico · Analytical warehouse

> 🇮🇹 Il warehouse si alimenta **dai topic di dominio**, non dai database dei servizi: nessuna estrazione
> notturna, nessun accoppiamento agli schemi interni, nessun carico sulle basi dati operative.
> 🇬🇧 The warehouse is fed **from the domain topics**, not from service databases: no nightly extraction, no
> coupling to internal schemas, no load on operational databases.

---

## 1. Come è costruito · How it is built

```
topic di dominio · domain topics
   └─▶ tabelle di consumo · consuming tables
          └─▶ viste materializzate · materialised views
                 └─▶ fatti replicati · replicated facts   (partizioni mensili · monthly partitions, TTL 5 anni · 5 years)
                        └─▶ viste KPI · KPI views   →   cruscotti della BI · BI dashboards
```

| Proprietà · Property | Valore · Value |
| --- | --- |
| Repliche · Replicas | 3, su zone distinte, con coordinatore dedicato · 3 across zones, with a dedicated coordinator |
| Partizioni · Partitions | Mensili · monthly |
| Retention | 5 anni via TTL · 5 years via TTL |
| Dati personali · Personal data | Nessuno: solo identificatore loyalty e attributi tecnici · none: loyalty identifier and technical attributes only |
| Backup | Notturno verso storage a oggetti · nightly to object storage |

---

## 2. Applicare lo schema · Applying the schema

I file dello schema sono numerati e vanno applicati in ordine, una volta per ambiente, sostituendo i
segnaposto di connessione. **EN** — Schema files are numbered and must be applied in order, once per environment,
substituting the connection placeholders.

```sh
for f in analytics/clickhouse/schema/*.sql; do
  sed "s/{kafka_bootstrap}/$KAFKA_BOOTSTRAP/" "$f" \
    | clickhouse-client --host "$CLICKHOUSE_HOST" --multiquery
done
```

In locale (`make up-bi`) lo schema viene applicato all'avvio in configurazione a nodo singolo.
**EN** — Locally (`make up-bi`) the schema is applied at startup in a single-node configuration.

---

## 3. Aggiungere un KPI · Adding a KPI

1. **Evento** — assicurarsi che il fatto esista come evento di dominio su un topic · make sure the fact exists as a
   domain event on a topic.
2. **Ingestione** — tabella di consumo sul topic e vista materializzata verso il fatto · consuming table on the topic
   and a materialised view into the fact.
3. **Fatto** — colonne tipizzate, chiave di ordinamento utile alle query, nessun dato personale · typed columns, a
   sort key useful to queries, no personal data.
4. **Vista KPI** — aggregazione pronta per la BI: i cruscotti non contengono query ad hoc · BI-ready aggregation:
   dashboards contain no ad-hoc queries.
5. **Cruscotto** — grafico nell'export versionato nel repository · chart in the export versioned in the repository.
6. **Documentazione** — riga in [`../../docs/OBSERVABILITY.md`](../../docs/OBSERVABILITY.md).

---

## 4. Regole · Rules

- Nessuna query ad hoc nei cruscotti: se serve un'aggregazione, diventa una vista.
- Nessun dato personale, nemmeno in colonne "temporanee".
- Le viste materializzate non si modificano in loco: si crea la nuova e si sposta la vista KPI.
- La retention è dichiarata nello schema, non decisa a mano sul cluster.

**EN**

- No ad-hoc queries in dashboards: if an aggregation is needed, it becomes a view.
- No personal data, not even in "temporary" columns.
- Materialised views are not edited in place: create the new one and repoint the KPI view.
- Retention is declared in the schema, not decided by hand on the cluster.
