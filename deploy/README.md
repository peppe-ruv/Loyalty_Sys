# deploy — ambiente locale e note infrastruttura (docs/11)

`docker-compose.yml`: stessa topologia della demo (docs/11 §9).

```sh
# solo infrastruttura (Kafka KRaft + Postgres 17 + Kafka UI)
docker compose -f deploy/docker-compose.yml up -d kafka postgres kafka-ui
# stack completo (8 servizi + web): richiede i Dockerfile dei servizi (da M0.5) e del web (da M0.6)
docker compose -f deploy/docker-compose.yml --profile all up --build
```

| Servizio | Porta host | Note |
|---|---|---|
| `kafka` | 9092 | KRaft nodo singolo; `auto.create.topics.enable=false` |
| `postgres` | 5432 | DB `loyaltyhub`, volume `lh-postgres-data` |
| `kafka-ui` | 8090 | ispezione topic su http://localhost:8090 |
| 8 servizi + `web` | 8081–8088, 3000 | solo con `--profile all` |

**I 5 topic** non sono creati dal broker: li crea il **profilo Spring `local`** (bean `NewTopic` di `lh-common`,
2 partizioni) quando un servizio si avvia. Verificato da `LocalTopicsIT` (Kafka in-JVM, senza Docker).

Dall'host un servizio avviato con `./mvnw` usa `localhost:9092`; i container della rete compose usano
`kafka:29092` (listener interno).

> Nota ambiente (SPEC-GAP Q-40): in questa sessione il pull delle immagini Docker è negato dal proxy, quindi
> il `docker compose up` non è eseguibile qui; il file è validato con `docker compose config` e la creazione
> dei topic è coperta da un test in-JVM. Il run completo va fatto in un ambiente con accesso a Docker Hub.
