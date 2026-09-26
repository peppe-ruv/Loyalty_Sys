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

---

## Profilo enterprise: chart Helm e compose di riferimento (Fase 2, M8.3)

Tre tagli di installazione, **una sola immagine** (`deploy/image/`, ADR-037): appliance (`LH_ROLE=all`, da M12),
compose di riferimento (`deploy/compose/reference.yml`, F2-DIST-03) e chart Helm (`deploy/helm/loyaltyhub`,
F2-DIST-02). Cambiano solo `LH_ROLE` e il posto dell'infrastruttura; `LH_MODE=external` in entrambi.

### Topologia del chart

Nel cluster ogni ruolo dell'immagine è un Deployment con repliche sparse tra le zone. L'Ingress porta il browser al
ruolo `web` (portale, backoffice, BFF) e alle pagine di login di `idp`; l'hub non è esposto: lo chiama il BFF
dentro il cluster, e le API per fonti e widget passeranno dal gateway di M8.5 (oggi un segnaposto spento). Postgres
e Kafka sono gestiti dagli operatori di default oppure, cambiando due valori, sono servizi gestiti fuori dal cluster.

```mermaid
flowchart TB
  accTitle: Topologia del chart Helm in un cluster multi-zona
  accDescr: L'Ingress porta il browser al ruolo web e al login del ruolo idp; web chiama hub dentro il cluster; hub usa Postgres di CloudNativePG e i 5 topic di Kafka gestiti da Strimzi; il Job di migrazione aggiorna gli schemi prima dei nuovi Pod; con i valori per servizi gestiti Postgres e Kafka stanno fuori dal cluster.
  USR[Browser di membri e operatori]
  ING[Ingress con TLS]
  GW[Gateway, segnaposto fino a M8.5]
  subgraph K8S["Namespace loyaltyhub, repliche su più zone"]
    WEB[web: portale, backoffice e BFF]
    HUB[hub: moduli Java con HPA]
    IDP[idp: Keycloak con realm as code]
    MIG[Job delle migrazioni Flyway]
    PG[(Postgres CloudNativePG, 3 istanze)]
    KF{{Kafka Strimzi, 3 broker, 5 topic}}
  end
  EXT[(Servizi gestiti: Postgres e Kafka)]
  USR --> ING
  ING --> WEB
  ING --> IDP
  GW -.-> HUB
  WEB -->|HTTP con token| HUB
  HUB --> PG
  HUB <--> KF
  IDP --> PG
  MIG -->|pre-upgrade| PG
  HUB -.->|valori per servizi gestiti| EXT
  classDef svc fill:#EFF6FF,stroke:#2563EB,color:#1E3A8A
  classDef store fill:#F1F5F9,stroke:#475569,color:#0F172A
  classDef topic fill:#FEF3C7,stroke:#D97706,color:#78350F
  classDef ext fill:#FFFFFF,stroke:#94A3B8,stroke-dasharray:4 2,color:#334155
  classDef human fill:#ECFDF5,stroke:#059669,color:#064E3B
  class WEB,HUB,IDP,MIG svc
  class PG,EXT store
  class KF topic
  class ING,GW ext
  class USR human
```

### Scelte del chart

| Tema | Scelta |
|---|---|
| Ruoli | un Deployment per ruolo: `hub` (tutti i moduli: `LH_SERVICES` accetta solo vuoto o `all`, Q-365 del M8.1), `web`, `idp`. `cms` e `jobs` non sono ancora nell'immagine: il chart **rifiuta** `roles.cms.enabled` e `roles.jobs.enabled` |
| Operatori | **prerequisiti**, non sottochart: Strimzi e CloudNativePG hanno CRD e controller a livello di cluster, con un ciclo di vita diverso da quello dell'applicazione; il chart crea solo le risorse (`Kafka`, `KafkaNodePool`, 5 `KafkaTopic`, `Cluster`, `Database`) |
| Servizi gestiti | `postgres.mode=external` e `kafka.mode=external` (ADR-026): nessuna risorsa degli operatori, URL e credenziali dai valori |
| Migrazioni | Job `…-migrate` con la stessa immagine: esegue solo Flyway (`io.loyaltyhub.hub.HubMigrate` dal jar dell'hub, non è un nuovo ruolo) e termina. Hook `pre-upgrade` sempre; al primo install `pre-install` con Postgres esterno e `post-install` con CloudNativePG, perché il cluster nasce con la release. L'hub all'avvio rifà le stesse migrazioni: Flyway prende un lock, le due esecuzioni non si pestano (ADR-038) |
| Esposizione | Ingress per `web` e per i soli percorsi pubblici di `idp` (`/realms/`, `/resources/`: la console di amministrazione resta fuori); `gateway.enabled` crea un `HTTPRoute` verso l'hub (`/v1/`) agganciato a un Gateway esistente, segnaposto di M8.5 |
| Identità | `idp` importa `files/realm.json`, copia di `deploy/idp/realm.json` verificata da `scripts/check-helm.mjs` (Helm non legge file fuori dal chart); tutti i segnaposto `${LH_*}` del realm sono passati come variabili; l'hub valida i token (`LH_OIDC_ISSUER` pubblico, JWKS letto dentro il cluster, `aud=hub`) |
| Sicurezza | Pod Security `restricted`: non root (uid 1000), `seccompProfile: RuntimeDefault`, niente escalation, capability rimosse, token del service account non montato, filesystem in sola lettura con `emptyDir` per `/tmp` (e per la cache di Next.js) — tranne `idp`, vedi Q-371 |
| Segreti | nessun valore nel chart: ogni credenziale è `{name, key}` di un Secret esistente; CloudNativePG genera da sé `<cluster>-app` |
| Risorse | requests e limits per ogni ruolo e per il Job; HPA su CPU per `hub` (2–6) e `web` (2–6); PDB `minAvailable: 1` |
| Valori non supportati | il chart fallisce invece di installare: profilo diverso da `enterprise`/`demo`, `global.mode=embedded`, `enterprise` senza `idp` né `oidc.issuer`, partizioni o ISR impossibili, chiavi di retention sconosciute |

### Installazione con Helm

```bash
# 1. operatori (una volta per cluster; versioni e opzioni dalle rispettive documentazioni)
helm repo add strimzi https://strimzi.io/charts/ && helm install strimzi strimzi/strimzi-kafka-operator -n strimzi --create-namespace
helm repo add cnpg https://cloudnative-pg.github.io/charts && helm install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace

# 2. Secret (valori generati o dal secret manager; External Secrets in M8.5)
kubectl create secret generic lh-idp-admin --from-literal=password="$(openssl rand -base64 24)"
kubectl create secret generic lh-idp-clients \
  --from-literal=web-client-secret="$(openssl rand -hex 32)" \
  --from-literal=widgets-client-secret="$(openssl rand -hex 32)" \
  --from-literal=cms-client-secret="$(openssl rand -hex 32)"
kubectl create secret generic lh-idp-db --type=kubernetes.io/basic-auth \
  --from-literal=username=idp --from-literal=password="$(openssl rand -base64 24)"

# 3. chart (image.tag a una versione pubblicata; host e URL pubblici propri)
helm install lh deploy/helm/loyaltyhub \
  --set image.tag=<versione> \
  --set publicUrls.web=https://loyalty.example.org --set ingress.hosts.web=loyalty.example.org \
  --set publicUrls.idp=https://idp.example.org --set ingress.hosts.idp=idp.example.org
```

Servizi gestiti al posto degli operatori:

```bash
helm install lh deploy/helm/loyaltyhub \
  --set postgres.mode=external --set postgres.external.host=pg.example.internal \
  --set kafka.mode=external --set kafka.external.bootstrapServers=kafka.example.internal:9093 \
  --set kafka.external.sasl.username.name=lh-kafka --set kafka.external.sasl.password.name=lh-kafka
```

Con Postgres esterno servono i Secret `lh-db` (`username`, `password`) e `lh-idp-db`, e i database `loyaltyhub` e
`idp` già creati.

### Compose di riferimento

`deploy/compose/reference.yml`: `postgres` 17, `kafka` KRaft (un broker), `migrate` (Flyway una volta sola, come il
Job del chart), `hub`, `web` e `idp`. I container dell'immagine unica girano in sola lettura, senza capability e senza
nuovi privilegi. Esposti sull'host solo `web` (3000) e `idp` (8180).

```bash
export LH_DB_PASSWORD=… LH_IDP_ADMIN_PASSWORD=… LH_WEB_CLIENT_SECRET=… LH_WIDGETS_CLIENT_SECRET=… LH_CMS_CLIENT_SECRET=…
docker compose -f deploy/compose/reference.yml up -d
# valutazione con i dati fittizi (seed, X-LH-Actor): LH_PROFILE=demo LH_IDENTITY_MODE=header
```

| Variabile | Default | Uso |
|---|---|---|
| `LH_IMAGE` | `ghcr.io/loyaltyhub/loyaltyhub:latest` | immagine unica (fissare una versione) |
| `LH_PROFILE`, `LH_IDENTITY_MODE` | `enterprise`, `oidc` | `demo` + `header` solo per valutare |
| `LH_DB_PASSWORD` | — (obbligatoria) | password di Postgres, usata da `migrate`, `hub` e `idp` |
| `LH_IDP_ADMIN_PASSWORD`, `LH_*_CLIENT_SECRET` | — (obbligatorie) | come in `deploy/idp/README.md` |
| `LH_IDP_PUBLIC_URL`, `LH_WEB_URL`, `LH_CMS_URL` | `http://localhost:8180`, `:3000`, `:8055` | emittente OIDC e redirect del realm |
| `LH_KAFKA_TOPIC_PARTITIONS`, `LH_KAFKA_CONSUMER_CONCURRENCY` | 12, 2 | forma dei topic e concorrenza (F2-EVT-04) |

I segreti mancanti fermano il container che li usa (`Variabile obbligatoria mancante: …`, anche con `<VAR>_FILE`
nell'immagine unica); Postgres senza password non si inizializza.

### Partizioni, concorrenza e retention (F2-EVT-04, ADR-028)

I nomi dei 5 topic non cambiano mai; si configurano forma e concorrenza. Le variabili sono lette da ogni servizio e
dall'hub (`LhEnvironmentAliases` di `lh-common`); senza variabili valgono i default di Fase 1.

| Variabile | Proprietà | Default (Fase 1) | Chart / compose |
|---|---|---|---|
| `LH_KAFKA_CONSUMER_CONCURRENCY` | `loyaltyhub.consumer.concurrency` | 2 | 2 (si scala prima con le repliche dell'hub) |
| `LH_KAFKA_TOPIC_PARTITIONS` | `loyaltyhub.topic-settings.partitions` | 2 | 12 |
| `LH_KAFKA_TOPIC_REPLICAS` | `loyaltyhub.topic-settings.replicas` | 1 | 3 (compose: 1) |
| `LH_KAFKA_TOPIC_MIN_INSYNC_REPLICAS` | `loyaltyhub.topic-settings.min-insync-replicas` | default del broker | 2 |
| `LH_KAFKA_TOPIC_RETENTION_MS` | `loyaltyhub.topic-settings.retention-ms` | 3 giorni | 7 giorni |
| `LH_KAFKA_<ACTIONS\|EFFECTS\|FACTS\|AUDIT\|DLQ>_RETENTION_MS` | `loyaltyhub.topic-settings.retention-ms-by-topic.<chiave>` | — | facts e audit: 7 giorni (Q-370) |
| `LH_KAFKA_TOPICS_CREATE` | `loyaltyhub.topic-settings.create` | `true` | `false` con Strimzi (topic dai `KafkaTopic`) |

Una forma impossibile (0 partizioni, ISR maggiore delle repliche, chiave di retention sconosciuta, concorrenza 0)
ferma l'avvio invece di creare topic sbagliati.

### Limiti noti (domande aperte)

- **Q-370** — ADR-028 vuole `facts` e `audit` a 365 giorni, lecito solo senza PII sul bus (ADR-032). Finché M8.4 non
  pubblica solo `member.*:2` e l'audit mascherato, il default è 7 giorni; `kafka.topics.retentionMsByTopic` lo alza.
- **Q-371** — l'immagine unica non contiene ancora Keycloak né Directus: `idp` usa l'immagine ufficiale di Keycloak
  alla versione bloccata del compose di sviluppo (filesystem scrivibile, perché `kc.sh start` ricompila); `cms`,
  Redis e MinIO arrivano con M10.2.
- **Q-372** — Kafka interno senza TLS né autenticazione fino a M8.5 (mTLS di mesh, principal per modulo, ACL).

### Verifica

```bash
node --test scripts/check-helm.mjs   # copia del realm, segnaposto, 5 topic, segreti, sicurezza; lint e template se c'è helm
helm lint deploy/helm/loyaltyhub --strict
helm template lh deploy/helm/loyaltyhub --kube-version 1.31.0 | kubeconform -strict -summary
docker compose -f deploy/compose/reference.yml config -q
```
