# ingestion-service — porta 8081, schema `ingestion` (docs/04 §3, docs/servizi/ingestion-service.md)

Unica porta d'ingresso delle azioni e unico produttore di `lh.actions.v1`.

**M0.5 (archetipo ridotto):** `POST /v1/events` riceve un CloudEvent, valida la forma, deduplica su
`(source, id)`, arricchisce gli attributi `lh*` e pubblica su `lh.actions.v1` via outbox (`202 ACCEPTED` /
`DUPLICATE`); un consumer di prova instrada le azioni; Flyway (`V0` comune + `V1` `inbound_event`), Actuator,
Dockerfile multi-stage. Fonti, tipi con JSON Schema, risoluzione membro, ponte e simulatore: **M1.1+**.

```sh
# test (Spring Boot su EmbeddedKafka + Postgres in-process, senza Docker)
./mvnw -pl services/ingestion-service -am verify
# immagine
docker build -f services/ingestion-service/Dockerfile -t lh-ingestion .
```
