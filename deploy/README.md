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
ruolo `web` (portale e backoffice) e alle pagine di login di `idp`; l'hub non è esposto: lo chiama il web dentro il
cluster, e le API per fonti e widget passeranno dal gateway di M8.5 (oggi un segnaposto spento). Postgres e Kafka sono
gestiti dagli operatori di default oppure, cambiando due valori, sono servizi gestiti fuori dal cluster. Nel profilo
`enterprise` la chiamata web → hub non porta ancora un token: arriverà con il BFF OIDC (F2-SEC-06, Q-393).

```mermaid
flowchart TB
  accTitle: Topologia del chart Helm in un cluster multi-zona
  accDescr: L'Ingress porta il browser al ruolo web e al login del ruolo idp; web chiama hub dentro il cluster, con il token solo quando esisterà il BFF; hub usa Postgres di CloudNativePG e i 5 topic di Kafka gestiti da Strimzi; il Job di migrazione aggiorna gli schemi prima dei nuovi Pod; con i valori per servizi gestiti Postgres e Kafka stanno fuori dal cluster.
  USR[Browser di membri e operatori]
  ING[Ingress con TLS]
  GW[Gateway, segnaposto fino a M8.5]
  subgraph K8S["Namespace loyaltyhub, repliche su più zone"]
    WEB[web: portale e backoffice]
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
  WEB -->|HTTP, token dal BFF F2-SEC-06| HUB
  HUB -->|TLS| PG
  HUB <--> KF
  IDP -->|TLS| PG
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
| Immagine | `image.repository` e `image.tag` **obbligatori**, senza default: `.github/workflows/image.yml` pubblica `ghcr.io/<owner>/loyaltyhub` solo sui tag git `v*`, e un nome inventato finirebbe in `ImagePullBackOff`. Il tag può essere un digest `sha256:…` |
| Ruoli | un Deployment per ruolo: `hub` (tutti i moduli: `LH_SERVICES` accetta solo vuoto o `all`, Q-376 del M8.1), `web`, `idp`. `cms` e `jobs` non sono ancora nell'immagine: il chart **rifiuta** `roles.cms.enabled` e `roles.jobs.enabled` |
| Operatori | **prerequisiti**, non sottochart: Strimzi e CloudNativePG hanno CRD e controller a livello di cluster, con un ciclo di vita diverso da quello dell'applicazione; il chart crea solo le risorse (`Kafka`, `KafkaNodePool`, 5 `KafkaTopic`, `Cluster`, `Database`) |
| Servizi gestiti | `postgres.mode=external` e `kafka.mode=external` (ADR-026): nessuna risorsa degli operatori, URL e credenziali dai valori |
| Migrazioni | Job `…-migrate` con la stessa immagine: esegue solo Flyway (`io.loyaltyhub.hub.HubMigrate` dal jar dell'hub, non è un nuovo ruolo) e termina; stessi `nodeSelector`, `tolerations` e sicurezza dell'hub. Hook `pre-upgrade` sempre; al primo install `pre-install` con Postgres esterno e `post-install` con CloudNativePG, perché il cluster nasce con la release. L'hub all'avvio rifà le stesse migrazioni: Flyway prende un lock, le due esecuzioni non si pestano (ADR-038) |
| Esposizione | Ingress per `web` e, per `idp`, solo `/realms/<realm>/` e `/resources/`: la console di amministrazione e il realm `master` (con il suo endpoint dei token) restano fuori; `gateway.enabled` crea un `HTTPRoute` verso l'hub (`/v1/`) agganciato a un Gateway esistente, segnaposto di M8.5 |
| Identità | `idp` importa `files/realm.json`, copia di `deploy/idp/realm.json` verificata da `scripts/check-helm.mjs` (Helm non legge file fuori dal chart); tutti i segnaposto `${LH_*}` del realm sono passati come variabili; l'hub valida i token (`LH_OIDC_ISSUER` pubblico, JWKS letto dentro il cluster, `aud=hub`). Il Pod `web` non riceve segreto né emittente finché il BFF non li legge (Q-393) |
| Sicurezza | Pod Security `restricted`: non root (uid 1000), `seccompProfile: RuntimeDefault`, niente escalation, capability rimosse, token del service account non montato, filesystem in sola lettura con `emptyDir` per `/tmp` (e per la cache di Next.js) — tranne `idp`, vedi Q-374. **Nessuna NetworkPolicy** fino a M8.5 (deny-by-default e mTLS di mesh): il traffico nel namespace non è filtrato |
| Profilo `enterprise` (regola 22) | il chart **rifiuta** Kafka esterno `PLAINTEXT` e Postgres esterno senza `sslmode=require`, `verify-ca` o `verify-full`; la deroga è esplicita (`kafka.external.allowInsecure`, `postgres.external.allowInsecure`), solo per reti già cifrate. Con CloudNativePG l'URL JDBC esige TLS (`sslmode=require`; `verify-full` con la CA del cluster in M8.5) |
| Segreti | nessun valore nel chart: ogni credenziale è `{name, key}` di un Secret esistente; CloudNativePG genera da sé `<cluster>-app` |
| Porte | fisse, non valori: hub 8080 e web 3000 (le impone `deploy/image/entrypoint.sh`), Keycloak 8080 e gestione 9000; `scripts/check-helm.mjs` verifica che chart, compose ed entrypoint coincidano |
| Sonde | hub: liveness e readiness di Actuator; web: liveness sulla porta (`tcpSocket`), readiness su `/api/demo/status`, che interroga l'hub e non deve far riavviare il web quando l'hub è lento; idp: `/health/*` sulla porta di gestione |
| Risorse | requests e limits per ogni ruolo e per il Job; HPA su CPU per `hub` (2–6) e `web` (2–6); PDB `minAvailable: 1` |
| Valori | `values.schema.json` rifiuta chiavi sconosciute (un refuso non passa in silenzio) e tipi sbagliati; `templates/_helpers.tpl` (`loyaltyhub.validate`) le regole fra più valori: profilo diverso da `enterprise`/`demo`, `global.mode=embedded`, `enterprise` senza `idp` né `oidc.issuer`, partizioni o ISR impossibili, repliche dei topic oltre i broker di Strimzi, chiavi di retention sconosciute, aumento di partizioni non confermato |

### Installazione con Helm

```bash
# 1. operatori (una volta per cluster; versioni e opzioni dalle rispettive documentazioni).
#    Servono Strimzi con KRaft e KafkaNodePool (0.46 o successivo) e CloudNativePG con la risorsa Database (1.25 o
#    successivo); Kubernetes 1.29 o successivo. Con Pod Security `restricted` sul namespace, Strimzi deve generare Pod
#    conformi: STRIMZI_POD_SECURITY_PROVIDER_CLASS=restricted nell'operatore.
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi strimzi/strimzi-kafka-operator -n strimzi --create-namespace --set watchAnyNamespace=true \
  --set 'extraEnvs[0].name=STRIMZI_POD_SECURITY_PROVIDER_CLASS' --set 'extraEnvs[0].value=restricted'
helm repo add cnpg https://cloudnative-pg.github.io/charts && helm install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace

# 2. Secret (valori generati o dal secret manager; External Secrets in M8.5)
kubectl create secret generic lh-idp-admin --from-literal=password="$(openssl rand -base64 24)"
kubectl create secret generic lh-idp-clients \
  --from-literal=web-client-secret="$(openssl rand -hex 32)" \
  --from-literal=widgets-client-secret="$(openssl rand -hex 32)" \
  --from-literal=cms-client-secret="$(openssl rand -hex 32)"
kubectl create secret generic lh-idp-db --type=kubernetes.io/basic-auth \
  --from-literal=username=idp --from-literal=password="$(openssl rand -base64 24)"

# 3. chart: immagine pubblicata (obbligatoria), host e URL pubblici propri. Il primo avvio di Kafka, Postgres e
#    Keycloak richiede alcuni minuti: --wait con un margine ampio, altrimenti Helm segna fallita un'installazione sana.
helm install lh deploy/helm/loyaltyhub --wait --timeout 15m \
  --set image.repository=ghcr.io/<owner>/loyaltyhub --set image.tag=<versione, es. v0.7.0> \
  --set publicUrls.web=https://loyalty.example.org --set ingress.hosts.web=loyalty.example.org \
  --set publicUrls.idp=https://idp.example.org --set ingress.hosts.idp=idp.example.org
```

Servizi gestiti al posto degli operatori (nel profilo `enterprise` Kafka con `SSL_PEM` o `SASL_SSL` e Postgres con
`sslmode=require` o più severo, altrimenti il chart non si installa):

```bash
helm install lh deploy/helm/loyaltyhub --wait --timeout 15m \
  --set image.repository=ghcr.io/<owner>/loyaltyhub --set image.tag=<versione> \
  --set postgres.mode=external --set postgres.external.host=pg.example.internal \
  --set postgres.external.jdbcParams='sslmode=verify-full' \
  --set kafka.mode=external --set kafka.external.bootstrapServers=kafka.example.internal:9093 \
  --set kafka.external.sasl.username.name=lh-kafka --set kafka.external.sasl.password.name=lh-kafka
```

Con Postgres esterno servono i Secret `lh-db` (`username`, `password`) e `lh-idp-db`, e i database `loyaltyhub` e
`idp` già creati.

### Compose di riferimento

`deploy/compose/reference.yml`: `postgres` 17, `kafka` KRaft (un broker), `migrate` (Flyway una volta sola, come il
Job del chart), `hub`, `web` e `idp`. I container dell'immagine unica girano in sola lettura, senza capability e senza
nuovi privilegi. Keycloak usa un ruolo Postgres proprio (`idp`, proprietario del solo database `idp`, creato da
`postgres-init/10-idp.sh`), non il superutente dell'hub. Sull'host sono pubblicati solo `web` (3000) e `idp` (8180), e
solo su `127.0.0.1`: parlano HTTP in chiaro e sulla porta di `idp` c'è anche la console `/admin`. Per l'accesso da
altre macchine va messo davanti un reverse proxy con TLS (Q-392).

```bash
export LH_IMAGE=ghcr.io/<owner>/loyaltyhub:<versione>
export LH_DB_PASSWORD=… LH_IDP_DB_PASSWORD=… LH_IDP_ADMIN_PASSWORD=…
export LH_WEB_CLIENT_SECRET=… LH_WIDGETS_CLIENT_SECRET=… LH_CMS_CLIENT_SECRET=…
docker compose -f deploy/compose/reference.yml up -d
# valutazione con i dati fittizi (seed, X-LH-Actor): LH_PROFILE=demo LH_IDENTITY_MODE=header
```

| Variabile | Default | Uso |
|---|---|---|
| `LH_IMAGE` | — (obbligatoria) | immagine unica pubblicata, es. `ghcr.io/<owner>/loyaltyhub:v0.7.0` |
| `LH_PROFILE`, `LH_IDENTITY_MODE` | `enterprise`, `oidc` | `demo` + `header` solo per valutare |
| `LH_DB_PASSWORD` | — (obbligatoria) | superutente `loyaltyhub` di Postgres, usato da `migrate` e `hub` |
| `LH_IDP_DB_PASSWORD` | — (obbligatoria) | ruolo `idp` di Postgres, usato solo da Keycloak |
| `LH_IDP_ADMIN_PASSWORD`, `LH_*_CLIENT_SECRET` | — (obbligatorie) | come in `deploy/idp/README.md` |
| `LH_BIND_ADDRESS` | `127.0.0.1` | indirizzo dell'host su cui pubblicare `web` e `idp` |
| `LH_IDP_PUBLIC_URL`, `LH_WEB_URL`, `LH_CMS_URL` | `http://localhost:8180`, `:3000`, `:8055` | emittente OIDC e redirect del realm |
| `LH_KAFKA_TOPIC_PARTITIONS`, `LH_KAFKA_CONSUMER_CONCURRENCY` | 12, 2 | forma dei topic e concorrenza (F2-EVT-04) |
| `LH_KAFKA_TOPICS_ALLOW_PARTITION_INCREASE` | `false` | vedi *Aumentare le partizioni* |

I segreti mancanti fermano il container che li usa (`Variabile obbligatoria mancante: …`) prima che faccia qualcosa:
Postgres non inizializza il volume senza entrambe le password. Solo `hub` accetta anche `<VAR>_FILE` (lo legge
l'entrypoint dell'immagine, per cui una variabile vuota non oscura il file); `migrate` e Keycloak no.

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
| `LH_KAFKA_<ACTIONS\|EFFECTS\|FACTS\|AUDIT\|DLQ>_RETENTION_MS` | `loyaltyhub.topic-settings.retention-ms-by-topic.<chiave>` | — | facts e audit: 7 giorni (Q-373) |
| `LH_KAFKA_TOPICS_CREATE` | `loyaltyhub.topic-settings.create` | `true` | `false` con Strimzi (topic dai `KafkaTopic`) |
| `LH_KAFKA_TOPICS_MODIFY_CONFIGS` | `loyaltyhub.topic-settings.modify-configs` | spento, acceso nel profilo `enterprise` | `true` |
| `LH_KAFKA_TOPICS_ALLOW_PARTITION_INCREASE` | `loyaltyhub.topic-settings.allow-partition-increase` | `false` | `false` |

Una forma impossibile (0 partizioni, ISR maggiore delle repliche, chiave di retention sconosciuta, concorrenza 0)
ferma l'avvio invece di creare topic sbagliati.

**Topic che esistono già.** Kafka non cambia da solo un topic creato: una retention nuova (per esempio i 365 giorni
di Q-373) arriva ai topic esistenti solo con `modify-configs`, acceso nel profilo `enterprise` e spento nella demo,
dove il Kafka gratuito può rifiutare `alterConfigs`. Con Strimzi la applica l'operatore dai `KafkaTopic`.

**Aumentare le partizioni.** La partizione di un evento dipende dalla chiave `memberId` e dal numero di partizioni:
aumentarle sposta le chiavi, e gli eventi ancora in volo di uno stesso membro possono essere consumati fuori ordine.
Per questo l'hub rifiuta di partire (`PARTITION_INCREASE_NOT_ACKNOWLEDGED`) e il chart rifiuta l'upgrade dei
`KafkaTopic` esistenti finché non c'è la conferma esplicita. Procedura: fermare i produttori (fonti in ingresso e
hub), attendere lag 0 su tutti i gruppi di consumer, applicare con `LH_KAFKA_TOPICS_ALLOW_PARTITION_INCREASE=true`
(chart: `kafka.topics.allowPartitionIncrease=true`), riportarla a `false` subito dopo. Le partizioni non si possono
diminuire.

### Limiti noti (domande aperte)

- **Q-393** — nel profilo `enterprise` portale e backoffice non funzionano ancora: il proxy `/api/lh` inoltra solo
  `X-LH-Actor` e l'hub rifiuta le chiamate senza token finché non c'è il BFF OIDC (F2-SEC-06, M8.2). Per valutare si
  usa `global.profile=demo` (chart) o `LH_PROFILE=demo LH_IDENTITY_MODE=header` (compose).
- **Q-392** — il compose di riferimento parla HTTP in chiaro (web e Keycloak, compresa la console `/admin`): porte solo
  su `127.0.0.1` e reverse proxy con TLS davanti per altri accessi; TLS fino ai container con M8.5.
- **Q-373** — ADR-028 vuole `facts` e `audit` a 365 giorni, lecito solo senza PII sul bus (ADR-032). Finché M8.4 non
  pubblica solo `member.*:2` e l'audit mascherato, il default è 7 giorni; `kafka.topics.retentionMsByTopic` lo alza.
- **Q-374** — l'immagine unica non contiene ancora Keycloak né Directus: `idp` usa l'immagine ufficiale di Keycloak
  alla versione bloccata del compose di sviluppo (filesystem scrivibile, perché `kc.sh start` ricompila); `cms`,
  Redis e MinIO arrivano con M10.2.
- **Q-375** — Kafka interno senza TLS né autenticazione fino a M8.5 (mTLS di mesh, principal per modulo, ACL).
- Nessuna NetworkPolicy fino a M8.5.

### Verifica

```bash
node --test scripts/check-helm.mjs   # statiche sempre; con helm anche lint e scenari (in CI helm è obbligatorio)
helm lint deploy/helm/loyaltyhub --strict -f deploy/helm/loyaltyhub/ci/lint-values.yaml
helm template lh deploy/helm/loyaltyhub --kube-version 1.31.0 -f deploy/helm/loyaltyhub/ci/lint-values.yaml \
  | kubeconform -strict -summary -schema-location default \
      -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
LH_IMAGE=ghcr.io/example/loyaltyhub:ci docker compose -f deploy/compose/reference.yml config -q
```

In CI lo fa il job `helm` di `.github/workflows/ci.yml` (helm e kubeconform a versione fissa), solo quando cambiano
chart, compose, immagine, realm o la verifica, e sempre su `main`.
