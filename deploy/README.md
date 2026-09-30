# deploy — ambiente locale e note infrastruttura (docs/11)

`docker-compose.yml`: stessa topologia della demo (docs/11 §9).

```sh
# solo infrastruttura (Kafka KRaft + Postgres 17 + Kafka UI)
docker compose -f deploy/docker-compose.yml up -d kafka postgres kafka-ui
# stack completo (8 servizi + web): Dockerfile di ogni servizio; il web dall'immagine unica deploy/image
docker compose -f deploy/docker-compose.yml --profile all up --build
```

| Servizio | Immagine | Porta host | Note |
|---|---|---|---|
| `kafka` | `apache/kafka:4.2.2` | 9092 | KRaft nodo singolo; `auto.create.topics.enable=false`; linea 4.2 come `kafka-clients` (Q-481) |
| `postgres` | `postgres:17.11` | 5432 | DB `loyaltyhub`, volume `lh-postgres-data` |
| `kafka-ui` | `kafbat/kafka-ui:v1.5.0` | 8090 | ispezione topic su http://localhost:8090 (solo `127.0.0.1`, Q-479) |
| 8 servizi | build di `services/<nome>/Dockerfile` | 8081–8088 | solo con `--profile all` |
| `web` | build di `deploy/image/Dockerfile` (`loyaltyhub:local`), `LH_ROLE=web` | 3000 | solo con `--profile all`; la build compila anche l'hub (Q-484) |

Le immagini dei servizi e dell'hub (`deploy/hub/Dockerfile`) girano come utente non root con `uid`/`gid` 10001 (Q-504, F2-SEC-02).

Le immagini di terze parti hanno tag e digest (Keycloak solo il tag, Q-482). Kafka, Postgres e Keycloak hanno la stessa
versione anche nel compose di riferimento e nei values del chart: Dependabot aggiorna solo questo file e
`scripts/check-helm.mjs` fa fallire il job `helm` finché gli altri due non sono allineati. Il job è obbligatorio
nel ruleset (`scripts/setup-branch-protection.sh`): una divergenza blocca il merge (Q-483).

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
`enterprise` il web fa da BFF (F2-SEC-06): fa login su `idp` con lo stesso URL pubblico del browser, quindi passando
dall'Ingress, e chiama l'hub con l'access token dell'utente.

```mermaid
flowchart TB
  accTitle: Topologia del chart Helm in un cluster multi-zona
  accDescr: L'Ingress porta il browser al ruolo web e al login del ruolo idp; il web, con una sola replica nel profilo enterprise, fa login su idp con l'URL pubblico dell'emittente passando dall'Ingress e chiama hub dentro il cluster con l'access token; hub usa Postgres di CloudNativePG e i 5 topic di Kafka gestiti da Strimzi; il Job di migrazione aggiorna gli schemi prima dei nuovi Pod; con i valori per servizi gestiti Postgres e Kafka stanno fuori dal cluster.
  USR[Browser di membri e operatori]
  ING[Ingress con TLS]
  GW[Gateway, segnaposto fino a M8.5]
  subgraph K8S["Namespace loyaltyhub, repliche su più zone"]
    WEB[web: portale, backoffice e BFF, 1 replica]
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
  WEB -.->|OIDC con l'URL pubblico dell'emittente, TLS| ING
  WEB -->|HTTP con access token| HUB
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
| Operatori | **prerequisiti**, non sottochart: Strimzi e CloudNativePG hanno CRD e controller a livello di cluster, con un ciclo di vita diverso da quello dell'applicazione; il chart crea solo le risorse (`Kafka`, `KafkaNodePool`, 5 `KafkaTopic`, `Cluster`, `Database`). Le risorse Strimzi usano l'API `kafka.strimzi.io/v1` e il chart richiede **Strimzi 0.51 o successivo** (Q-490): 0.51 è l'ultima 0.x e la prima con la linea 4.2 di Kafka, e serve sia `v1` sia `v1beta2`; Strimzi 1.x serve solo `v1`. `kafka.strimzi.apiVersion` accetta solo `kafka.strimzi.io/v1`. La versione di Kafka la sceglie l'operatore installato (`kafka.strimzi.version` vuoto): 4.2.0 con Strimzi 0.51 e 1.0, la linea 4.3 con Strimzi 1.1 e 1.2, che i client 4.2 supportano. Per restare sulla linea dei compose e dei client fissa una patch 4.2 supportata dall'operatore (con Strimzi 1.2: `kafka.strimzi.version=4.2.1`); `scripts/check-helm.mjs` verifica che la linea coincida con i compose (Q-481, Q-483). Kafka ha la rack awareness sulla chiave di `topologySpread.topologyKey` (`topology.kubernetes.io/zone`): **i nodi devono avere quell'etichetta**, altrimenti il broker non parte |
| Servizi gestiti | `postgres.mode=external` e `kafka.mode=external` (ADR-026): nessuna risorsa degli operatori, URL e credenziali dai valori |
| Migrazioni | Job `…-migrate` con la stessa immagine: esegue solo Flyway (`io.loyaltyhub.hub.HubMigrate` dal jar dell'hub, non è un nuovo ruolo) e termina; stessi `nodeSelector`, `tolerations` e sicurezza dell'hub. Hook `pre-upgrade` sempre; al primo install `pre-install` con Postgres esterno e `post-install` con CloudNativePG, perché il cluster nasce con la release. L'hub all'avvio rifà le stesse migrazioni: Flyway prende un lock, le due esecuzioni non si pestano (ADR-038) |
| Esposizione | Ingress per `web` e, per `idp`, solo `/realms/<realm>/` e `/resources/`: la console di amministrazione e il realm `master` (con il suo endpoint dei token) restano fuori; `gateway.enabled` crea un `HTTPRoute` verso l'hub (`/v1/`) agganciato a un Gateway esistente, segnaposto di M8.5 |
| Identità | `idp` importa `files/realm.json`, copia di `deploy/idp/realm.json` verificata da `scripts/check-helm.mjs` (Helm non legge file fuori dal chart); tutti i segnaposto `${LH_*}` del realm sono passati come variabili; l'hub valida i token (`LH_OIDC_ISSUER` pubblico, JWKS letto dentro il cluster, `aud=hub`). Nel profilo `enterprise` il Pod `web` riceve la configurazione del BFF: vedi *Configurare il login del web* |
| Sicurezza | Pod Security `restricted`: non root (uid 1000), `seccompProfile: RuntimeDefault`, niente escalation, capability rimosse, token del service account non montato, filesystem in sola lettura con `emptyDir` per `/tmp` (e per la cache di Next.js) — tranne `idp`, vedi Q-374. **Nessuna NetworkPolicy** fino a M8.5 (deny-by-default e mTLS di mesh): il traffico nel namespace non è filtrato |
| Profilo `enterprise` (regola 22) | il chart **rifiuta** Kafka esterno `PLAINTEXT` e Postgres esterno senza `sslmode=require`, `verify-ca` o `verify-full`; la deroga è esplicita (`kafka.external.allowInsecure`, `postgres.external.allowInsecure`), solo per reti già cifrate. Con CloudNativePG l'URL JDBC esige TLS (`sslmode=require`; `verify-full` con la CA del cluster in M8.5) |
| Segreti | nessun valore nel chart: ogni credenziale è `{name, key}` di un Secret esistente (nel profilo `enterprise` anche `roles.hub.subjectKey`, la chiave dello pseudonimo del membro); CloudNativePG genera da sé `<cluster>-app` |
| Porte | fisse, non valori: hub 8080 e web 3000 (le impone `deploy/image/entrypoint.sh`), Keycloak 8080 e gestione 9000; `scripts/check-helm.mjs` verifica che chart, compose ed entrypoint coincidano |
| Sonde | hub: liveness e readiness di Actuator; web: liveness sulla porta (`tcpSocket`), readiness su `/api/demo/status`, che interroga l'hub e non deve far riavviare il web quando l'hub è lento; idp: `/health/*` sulla porta di gestione |
| Risorse | requests e limits per ogni ruolo e per il Job; HPA su CPU per `hub` (2–6); PDB `minAvailable: 1` per `hub` e `idp`. `web` ha **una sola replica**, senza HPA né PDB: le sessioni del BFF stanno nella memoria del Pod e nel profilo `enterprise` il chart rifiuta più repliche e il PDB (Q-409, Q-419). Nel profilo `demo`, senza sessioni, `web` si può scalare |
| Valori | `values.schema.json` rifiuta chiavi sconosciute (un refuso non passa in silenzio) e tipi sbagliati; `templates/_helpers.tpl` (`loyaltyhub.validate`) le regole fra più valori: profilo diverso da `enterprise`/`demo`, `global.mode=embedded`, `enterprise` senza `idp` né `oidc.issuer`, partizioni o ISR impossibili, repliche dei topic oltre i broker di Strimzi, chiavi di retention sconosciute, aumento di partizioni non confermato; nel profilo `enterprise` anche emittente OIDC o `publicUrls.web` non `https`, `publicUrls.web` con un percorso o con un host diverso dall'Ingress, emittente o client del BFF diversi da quelli del ruolo `idp`, Secret del BFF non indicati, più di una replica o un PDB del web (vedi *Configurare il login del web*), riferimento `roles.hub.subjectKey` vuoto (vedi *Chiave dello pseudonimo del membro*) |

### Installazione con Helm

```bash
# 1. operatori (una volta per cluster; opzioni dalle rispettive documentazioni). Servono Strimzi 0.51 o successivo
#    (API kafka.strimzi.io/v1, Q-490) e CloudNativePG con la risorsa Database (1.25 o successivo); Kubernetes 1.30 o
#    successivo. Le versioni qui sotto sono quelle provate dal job `helm install (kind)`. Con Pod Security `restricted`
#    sul namespace, Strimzi deve generare Pod conformi: STRIMZI_POD_SECURITY_PROVIDER_CLASS=restricted nell'operatore.
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi strimzi/strimzi-kafka-operator --version 1.2.0 -n strimzi --create-namespace --set watchAnyNamespace=true \
  --set 'extraEnvs[0].name=STRIMZI_POD_SECURITY_PROVIDER_CLASS' --set 'extraEnvs[0].value=restricted'
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm install cnpg cnpg/cloudnative-pg --version 0.29.1 -n cnpg-system --create-namespace   # operatore 1.30.1
# Rack awareness di Kafka: ogni nodo ha bisogno dell'etichetta di zona (i cloud la mettono da soli).
kubectl get nodes -L topology.kubernetes.io/zone

# 2. Secret (valori generati o dal secret manager; External Secrets in M8.5)
kubectl create secret generic lh-idp-admin --from-literal=password="$(openssl rand -base64 24)"
kubectl create secret generic lh-idp-clients \
  --from-literal=web-client-secret="$(openssl rand -hex 32)" \
  --from-literal=widgets-client-secret="$(openssl rand -hex 32)" \
  --from-literal=cms-client-secret="$(openssl rand -hex 32)"
kubectl create secret generic lh-idp-db --type=kubernetes.io/basic-auth \
  --from-literal=username=idp --from-literal=password="$(openssl rand -base64 24)"
# chiave delle sessioni del BFF del web: 32 byte casuali in base64 (profilo enterprise)
kubectl create secret generic lh-web-session --from-literal=session-key="$(openssl rand -base64 32)"
# chiave dello pseudonimo del membro (LH_SUBJECT_KEY, profilo enterprise): 32 byte casuali in base64. Conservala: non
# cambiarla dopo la prima registrazione di un membro (vedi «Chiave dello pseudonimo del membro»)
kubectl create secret generic lh-subject-key --from-literal=subject-key="$(openssl rand -base64 32)"

# 3. chart: immagine pubblicata (obbligatoria), host e URL pubblici propri. Con Strimzi 1.1 o 1.2 aggiungi
#    --set kafka.strimzi.version=4.2.1 per restare sulla linea dei client. Il primo avvio di Kafka, Postgres e
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

### Configurare il login del web (BFF OIDC, F2-SEC-06)

Nel profilo `enterprise` il web è il client OIDC confidential `web` del realm: fa il login, tiene i token lato server e
dà al browser solo un cookie di sessione (ADR-027). Senza una configurazione completa e sicura il web non parte
(`INSECURE_CONFIG`, regola 22). Chart e compose gli passano queste variabili; nel profilo `demo` non ne passano
nessuna.

| Variabile del web | Chart | Compose di riferimento |
|---|---|---|
| `LH_OIDC_ISSUER` | `oidc.issuer`; vuoto = `<publicUrls.idp>/realms/<global.realm>`, lo stesso dell'hub | `${LH_IDP_PUBLIC_URL}/realms/loyaltyhub`, lo stesso dell'hub |
| `LH_WEB_CLIENT_ID` | `roles.web.bff.clientId` (`web`) | `web` |
| `LH_WEB_CLIENT_SECRET` | Secret di `roles.web.bff.clientSecret`; nome vuoto = `roles.idp.clientSecrets.web`, lo stesso di Keycloak | `LH_WEB_CLIENT_SECRET`, la stessa variabile di `idp` |
| `LH_WEB_URL` | `publicUrls.web` nella forma che ne ricava il web (senza barra finale, host in minuscolo, senza `:443`), lo stesso valore dato a Keycloak | `LH_WEB_URL`, la stessa variabile di `idp` |
| `LH_WEB_SESSION_KEY` | Secret di `roles.web.bff.sessionKey` (default `lh-web-session`, chiave `session-key`) | `LH_WEB_SESSION_KEY` |
| `LH_WEB_SESSION_IDLE_SECONDS`, `LH_WEB_SESSION_MAX_SECONDS`, `LH_WEB_SESSION_MAX_COUNT` | `roles.web.bff.sessionIdleSeconds` (1800), `sessionMaxSeconds` (36000), `sessionMaxCount` (10000) | stesse variabili; vuote = default del web |
| `NODE_EXTRA_CA_CERTS` | ConfigMap di `roles.web.bff.issuerCaBundle`, montato in sola lettura | file di override con il certificato montato |

Nel chart i segreti arrivano solo come riferimenti a Secret esistenti (`secretKeyRef`), mai come valori. Il web accetta
anche `LH_WEB_CLIENT_SECRET_FILE` e `LH_WEB_SESSION_KEY_FILE`.

Il chart rifiuta di installarsi nel profilo `enterprise` quando:

- l'emittente non è `https` (`INSECURE_CONFIG: emittente OIDC …`): il browser vi fa login e il BFF lo chiama;
- con il ruolo `idp`, `oidc.issuer` è impostato ma diverso da `<publicUrls.idp>/realms/<global.realm>`, oppure
  `roles.web.bff.clientId` non è il client del realm che riceve `LH_WEB_CLIENT_SECRET` (`web`);
- `publicUrls.web` non è un'origine `https` senza percorso, anche con il web spento se c'è il ruolo `idp` (il realm la
  usa), o ha un host diverso da `ingress.hosts.web` (lo stesso vale per `publicUrls.idp` e `ingress.hosts.idp`);
- manca il Secret della chiave delle sessioni, o quello del client `web` quando il ruolo `idp` è spento;
- `roles.web.replicas` è maggiore di 1, l'HPA del web può superare una replica o il PDB del web è acceso
  (`WEB_SINGLE_REPLICA`, Q-409, Q-419).

**Raggiungere l'emittente.** Il BFF chiama l'emittente (discovery, scambio del codice, rinnovo, chiavi) con l'URL
pubblico, lo stesso del browser: la discovery rifiuta un emittente diverso da quello chiesto. Quindi i Pod `web`
devono risolvere `publicUrls.idp` e raggiungere l'Ingress, e il certificato dell'Ingress deve essere firmato da una CA
che Node riconosce. Con una CA interna crea un ConfigMap con il certificato PEM e indicalo in
`roles.web.bff.issuerCaBundle`:

```bash
# CA interna che firma il certificato di idp.example.org
kubectl create configmap lh-issuer-ca --from-file=ca.crt=./ca-interna.pem
helm upgrade lh deploy/helm/loyaltyhub --reuse-values --set roles.web.bff.issuerCaBundle.name=lh-issuer-ca
```

Keycloak, a sua volta, chiama il back-channel logout su `publicUrls.web` (Q-421).

**Realm già importato.** Keycloak importa `realm.json` solo al primo avvio. Su un realm esistente modifica a mano il
client `web` nella console: aggiungi la *post logout redirect URI* `<LH_WEB_URL>/` (senza, Keycloak rifiuta il ritorno
al web dopo il logout) e sostituisci la redirect URI `<LH_WEB_URL>/*` con quella esatta
`<LH_WEB_URL>/api/auth/callback`.

### Chiave dello pseudonimo del membro (`LH_SUBJECT_KEY`, F2-SEC-09, ADR-048, Q-552)

Nel profilo `enterprise` il portale ricava il membro dal token OIDC. member-service lega `(iss, sub)` al membro e sul bus
pubblica solo `subjectRef`, l'HMAC-SHA256 di `iss` e `sub` con questa chiave: il `sub`, un dato personale, non esce da
member-service (ADR-032). La chiave serve solo all'hub, ed è un segreto distinto da `LH_PSEUDONYM_KEY`. Regole in
`docs/11 §16`.

- **Forma.** 32 byte casuali in base64, `openssl rand -base64 32`. Assente, corta o non base64: l'hub non parte
  (`INSECURE_CONFIG`, regola 22; il valore non viene mai stampato).
- **Chart.** `roles.hub.subjectKey` è `{name, key}` di un Secret esistente (default `lh-subject-key`, chiave
  `subject-key`, creato al passo 2), passato all'hub con `secretKeyRef`. Nel profilo `enterprise` un nome o una chiave
  vuoti, di soli spazi o nulli fanno fallire `helm install` e `helm template` (`INSECURE_CONFIG: roles.hub.subjectKey…`),
  come una voce `LH_SUBJECT_KEY` o `LH_SUBJECT_KEY_FILE` in `roles.hub.extraEnv` (niente valori in chiaro); con
  `global.profile=demo` il chart non passa la variabile e il Secret non serve. Il chart non legge il contenuto del Secret
  né controlla che esista: con un Secret inesistente `helm install` riesce e il Pod dell'hub resta in
  `CreateContainerConfigError`.
- **Compose.** `LH_SUBJECT_KEY` (o `LH_SUBJECT_KEY_FILE`) dall'ambiente: con `LH_PROFILE=enterprise` o
  `LH_IDENTITY_MODE=oidc` (entrambi i default) il container `hub` si ferma con
  `Variabile obbligatoria mancante: LH_SUBJECT_KEY`; non serve con `LH_PROFILE=demo LH_IDENTITY_MODE=header`.
- **Rotazione: non ricollega nulla.** `subjectRef` dipende dalla chiave. Cambiarla scollega ogni token dal proprio
  membro (i `memberId` e i saldi restano, ma le lookup rispondono `404` o `409`) e nessun processo ricollega da solo i
  riferimenti vecchi ai nuovi (member-service conserva `iss` e `sub` in `member_identity`: il ricalcolo è possibile solo
  lì). Il job di member-service che ricalcola e ripubblica non esiste ancora (fuori da
  M8.10f): finché non c'è, la chiave si tratta come immutabile e se ne tiene una copia nel secret manager; perderla
  equivale a ruotarla.

### Fonti di ingestion: un client per fonte (F2-SEC-07, F2-IAM-02, Q-492)

Le fonti che inviano azioni a `ingestion` (`POST /v1/events`, `/v1/events/batch`, `/v1/transactions`) si autenticano come
utenze di integrazione con il ruolo `SOURCE`: client credentials con `private_key_jwt`, **un client per fonte**, con
`client_id` = `src-<codice>` (il prefisso evita scontri con `web`, `widgets`, `cms`). Ingestion accetta un'azione solo
se il suo `source` è quello del client (altrimenti `403 SOURCE_MISMATCH`). Il realm crea un client per ogni fonte del
seed che arrivano da HTTP (`src-crm`, `src-app`, `src-ecommerce`, `src-billing`, `src-partner`), ciascuno con
l'utenza di servizio `service-account-src-<codice>` che ha il solo ruolo `SOURCE` e l'audience `hub`. **Nel repository
non c'è nessuna chiave né segreto**: la chiave pubblica di ogni fonte si registra all'installazione, con l'URL del suo
JWKS (`LH_SOURCE_<FONTE>_JWKS_URL`, in Helm `roles.idp.jwks.sources.<fonte>`). Finché l'URL è il segnaposto, il client
non autentica nessuno (fail-closed).

**Procedura per una fonte creata a runtime** (da BO-09 dopo l'installazione: non ha ancora un client, Q-494, TOBE-009).
Un amministratore di Keycloak, nel realm `loyaltyhub`:

1. **Crea il client** `src-<codice>` (client OpenID Connect) con *Client authentication* attiva, *Service accounts roles*
   attivo e tutti gli altri flussi disattivati (*Standard flow*, *Direct access grants*, *Implicit flow*).
2. **Imposta l'autenticazione** in *Credentials* → *Client Authenticator* = *Signed JWT* (`private_key_jwt`).
3. **Registra la chiave pubblica della fonte**: in *Keys* attiva *Use JWKS URL* e indica il JWKS della fonte, oppure
   importa il suo certificato. Non generare tu la chiave della fonte e non salvarla nel repository.
4. **Assegna il ruolo**: in *Service accounts roles* assegna alla sola utenza di servizio il ruolo `SOURCE`, e nessun altro.
   **Togli il ruolo predefinito** `default-roles-loyaltyhub` dalla stessa utenza: Keycloak lo assegna a ogni utenza creata
   dopo l'import e contiene `MEMBER` (auto-registrazione dei membri, Q-557, ADR-048). Un token con `SOURCE` e `MEMBER`
   vale come token di membro e l'ingresso eventi della fonte risponderebbe `403`.
5. **Verifica l'audience**: in *Client scopes* devono esserci `hub-audience` e `lh-roles-scope` (sono quelli predefiniti
   del realm); un token del client ha `aud` con `hub`, `azp` = `src-<codice>` e `lh_roles` con `SOURCE`.
6. **Prova** con un token del client: `lh_roles` contiene `SOURCE` e non `MEMBER`; `POST /v1/events` con un evento della
   fonte risponde `202`; con il `source` di un'altra fonte, `403 SOURCE_MISMATCH`.

Ogni client creato così è una scrittura di configurazione dell'IdP: Keycloak la registra negli eventi di amministrazione
(`adminEventsEnabled` è attivo nel realm); il loro inoltro ad `audit_entry` arriva con il bridge Keycloak → audit
(F2-SEC-14, M8.12, ADR-043).

### Compose di riferimento

`deploy/compose/reference.yml`: `postgres` 17, `kafka` KRaft (un broker), `migrate` (Flyway una volta sola, come il
Job del chart), `hub`, `web` e `idp`. I container dell'immagine unica girano in sola lettura, senza capability e senza
nuovi privilegi. Keycloak usa un ruolo Postgres proprio (`idp`, proprietario del solo database `idp`, creato da
`postgres-init/10-idp.sh`), non il superutente dell'hub. Sull'host sono pubblicati solo `web` (3000) e `idp` (8180), e
solo su `127.0.0.1`: parlano HTTP in chiaro e sulla porta di `idp` c'è anche la console `/admin`. Per l'accesso da
altre macchine va messo davanti un reverse proxy con TLS (Q-392).
Il profilo facoltativo `observability` aggiunge collector, Prometheus e Grafana (*Osservabilità*, sotto).

```bash
export LH_IMAGE=ghcr.io/<owner>/loyaltyhub:<versione>
export LH_DB_PASSWORD=… LH_IDP_DB_PASSWORD=… LH_IDP_ADMIN_PASSWORD=…
export LH_WEB_CLIENT_SECRET=… LH_WIDGETS_CLIENT_SECRET=… LH_CMS_CLIENT_SECRET=…
# profilo enterprise: chiave delle sessioni del BFF, chiave dello pseudonimo del membro e URL https del reverse proxy (vedi sotto)
export LH_WEB_SESSION_KEY="$(openssl rand -base64 32)"
export LH_SUBJECT_KEY="$(openssl rand -base64 32)"
export LH_WEB_URL=https://loyalty.example.org LH_IDP_PUBLIC_URL=https://idp.example.org
docker compose -f deploy/compose/reference.yml up -d
# valutazione con i dati fittizi (seed, X-LH-Actor), senza reverse proxy: LH_PROFILE=demo LH_IDENTITY_MODE=header
```

| Variabile | Default | Uso |
|---|---|---|
| `LH_IMAGE` | — (obbligatoria) | immagine unica pubblicata, es. `ghcr.io/<owner>/loyaltyhub:v0.7.0` |
| `LH_PROFILE`, `LH_IDENTITY_MODE` | `enterprise`, `oidc` | `demo` + `header` solo per valutare |
| `LH_DB_PASSWORD` | — (obbligatoria) | superutente `loyaltyhub` di Postgres, usato da `migrate` e `hub` |
| `LH_IDP_DB_PASSWORD` | — (obbligatoria) | ruolo `idp` di Postgres, usato solo da Keycloak |
| `LH_IDP_ADMIN_PASSWORD`, `LH_*_CLIENT_SECRET` | — (obbligatorie) | come in `deploy/idp/README.md`; `LH_WEB_CLIENT_SECRET` va anche al web |
| `LH_WEB_SESSION_KEY` | — (obbligatoria in `enterprise`) | chiave delle sessioni del BFF: 32 byte casuali in base64 (`openssl rand -base64 32`) |
| `LH_SUBJECT_KEY` | — (obbligatoria in `enterprise` e con `LH_IDENTITY_MODE=oidc`, non serve con `LH_PROFILE=demo LH_IDENTITY_MODE=header`) | chiave dello pseudonimo `subjectRef` del membro, solo per l'hub: 32 byte casuali in base64; cambiarla scollega i token dai membri (*Chiave dello pseudonimo del membro*) |
| `LH_WEB_SESSION_IDLE_SECONDS`, `LH_WEB_SESSION_MAX_SECONDS`, `LH_WEB_SESSION_MAX_COUNT` | vuote (default del web: 1800, 36000, 10000) | inattività, durata massima e numero delle sessioni del BFF |
| `LH_BIND_ADDRESS` | `127.0.0.1` | indirizzo dell'host su cui pubblicare `web` e `idp` |
| `LH_IDP_PUBLIC_URL`, `LH_WEB_URL`, `LH_CMS_URL` | `http://localhost:8180`, `:3000`, `:8055` | emittente OIDC e redirect del realm; nel profilo `enterprise` gli URL `https` del reverse proxy, senza barra finale |
| `LH_KAFKA_TOPIC_PARTITIONS`, `LH_KAFKA_CONSUMER_CONCURRENCY` | 12, 2 | forma dei topic e concorrenza (F2-EVT-04) |
| `LH_KAFKA_TOPICS_ALLOW_PARTITION_INCREASE` | `false` | vedi *Aumentare le partizioni* |

I segreti mancanti fermano il container che li usa (`Variabile obbligatoria mancante: …`) prima che faccia qualcosa:
Postgres non inizializza il volume senza entrambe le password; l'hub, nel solo profilo `enterprise`, anche senza
`LH_SUBJECT_KEY`. Il web li controlla da sé all'avvio e, nel profilo
`enterprise`, si ferma con `INSECURE_CONFIG` elencando ogni problema (segreto corto o segnaposto, chiave che non vale
32 byte, URL non validi). `hub` e `web` accettano anche `<VAR>_FILE` (lo legge l'entrypoint dell'immagine, per cui
una variabile vuota non oscura il file); `migrate` e Keycloak no.

**Login nel profilo `enterprise` (Q-420).** Il BFF chiama Keycloak con l'URL pubblico dell'emittente, lo stesso del
browser, e accetta `http` solo verso `localhost`: dentro il container `web`, però, `localhost` è il container stesso.
Per questo il login funziona solo dietro un reverse proxy con TLS:

1. Metti il reverse proxy davanti a `web` (porta 3000) e `idp` (porta 8180) e imposta `LH_WEB_URL` e
   `LH_IDP_PUBLIC_URL` sui suoi URL `https`, senza percorso né barra finale: Keycloak confronta alla lettera la
   redirect URI e l'emittente, e una barra in più darebbe `//`. Con gli URL `http` di default, o con una forma
   diversa, il web si ferma subito e dice quale variabile correggere (guardia `x-lh-web-oidc-guard`).
2. Verifica che i container raggiungano quegli URL: `web` chiama `LH_IDP_PUBLIC_URL` e `idp` chiama
   `<LH_WEB_URL>/api/auth/backchannel-logout`. Se i nomi esistono solo nel file hosts dell'host, aggiungili ai container
   con `extra_hosts` in un file di override (per esempio `idp.example.org:host-gateway`), con il proxy in ascolto anche
   sull'interfaccia del bridge di Docker.
3. Usa un certificato di una CA pubblica, oppure monta la CA interna con un file di override: `NODE_EXTRA_CA_CERTS`
   per il web e il truststore di Keycloak (`KC_TRUSTSTORE_PATHS`) per il back-channel logout.

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

### Ancore dell'audit nei log (F2-GRC-07, ADR-043)

Ogni giorno insight verifica la catena di hash dell'audit e scrive l'ultimo hash di ogni servizio sul logger
`io.loyaltyhub.audit.anchor` (standard output del Pod o del container, righe `audit-anchor service=… seq=… entryHash=…`,
senza dati personali); la retention dell'audit fa lo stesso con le ancore `PURGE`. Queste righe sono l'unica prova
fuori dal database: con `GET /v1/audit/verify?service=&seq=&hash=` confronti la catena con una riga copiata dai log e
scopri una riscrittura fatta con le credenziali del database.

- Instrada quel logger verso un archivio durevole, fuori dal cluster o dall'host del database e con accesso separato:
  il raccoglitore di log della piattaforma basta, purché conservi le righe almeno quanto l'audit (180 giorni o più).
- Nel profilo `enterprise` insight rifiuta di partire (`INSECURE_CONFIG`) se il job è spento
  (`LOYALTYHUB_INSIGHT_AUDIT_ANCHORCRON=-`) o se il logger scrive sotto INFO
  (`LOGGING_LEVEL_IO_LOYALTYHUB_AUDIT_ANCHOR=WARN` o più alto). Nel profilo `demo` avvisa soltanto.
- Confronta ogni tanto una riga recente con la verifica: `PURGED` con un `purgedAt` precedente alla riga di log, o una
  verifica completa che non elenca più un servizio presente nei log, sono alterazioni da indagare.

I log non sono firmati né immutabili: la firma e l'esportazione su archivio immutabile sono TOBE-002 in
`docs/19-TO-BE.md`.

### Osservabilità (F2-OBS-01, M8.6a)

Spenta di default: nessuna telemetria esce senza un URL scelto da chi installa (ADR-044). Solo metriche; tracce e log
arrivano con M8.6b (Q-526). L'hub le invia in OTLP HTTP a un collector OpenTelemetry (push, Q-520) che le inoltra al
ricevitore OTLP di Prometheus; la dashboard «Loyalty Hub — SLO» e le regole con gli allarmi sono file del repository
(`deploy/helm/loyaltyhub/files/observability/`), gli stessi per chart e compose. Il percorso e le regole sono spiegati
con un diagramma nella pagina «Osservabilità» della documentazione (`operations/osservabilita`).

**Prerequisiti del chart** (Prometheus e Grafana sono dell'adottante, come gli operatori: Q-522):

- Prometheus 3 o successivo con il ricevitore OTLP (`--web.enable-otlp-receiver`), `otlp.translation_strategy:
  UnderscoreEscapingWithSuffixes` (i nomi delle metriche che regole e dashboard leggono, come
  `http_server_requests_seconds_count`) e `storage.tsdb.out_of_order_time_window` di almeno 30 minuti. Con il
  Prometheus Operator sono i campi equivalenti della risorsa `Prometheus`: verifica i nomi nella tua versione
  dell'operatore.
- Grafana con il sidecar dei dashboard (legge i ConfigMap con l'etichetta `grafana_dashboard`): il sidecar deve guardare
  il namespace della release (`sidecar.dashboards.searchNamespace` nel chart di Grafana), altrimenti il ConfigMap non si carica.
- Le regole: con il Prometheus Operator `observability.prometheusRule.enabled=true` crea la `PrometheusRule`; senza,
  carica `files/observability/slo-rules.json` come file di regole del tuo Prometheus (è JSON, valido per Prometheus).
  Le etichette della `PrometheusRule` devono combaciare con il `ruleSelector` del tuo Prometheus: con kube-prometheus-stack
  (`ruleSelectorNilUsesHelmValues=true`) l'operatore carica solo le regole con `release: <release dello stack>` e ignora le
  altre in silenzio, quindi `--set observability.prometheusRule.labels.release=<release di kube-prometheus-stack>`.

```bash
helm upgrade --install lh deploy/helm/loyaltyhub … \
  --set observability.enabled=true \
  --set observability.prometheus.otlpEndpoint=http://prometheus-operated.monitoring.svc:9090/api/v1/otlp \
  --set observability.prometheusRule.enabled=true \
  --set observability.prometheusRule.labels.release=<release di kube-prometheus-stack>
```

`otlpEndpoint` è obbligatorio, **senza `/v1/metrics`** (lo aggiunge il collector). Nel profilo `enterprise` è `https`,
oppure `http` solo verso un Service del cluster (`<nome>`, `<nome>.<namespace>.svc`); altrimenti il chart si ferma con
`INSECURE_CONFIG` (regola 22), salvo `observability.prometheus.allowInsecure=true` su una rete già cifrata. Un bearer
token (`observability.prometheus.bearerToken.name`, chiave `token`) e una CA privata
(`observability.prometheus.caBundle.name`, chiave `ca.crt`) arrivano solo da Secret e ConfigMap esistenti. Il `job`
in Prometheus è `<observability.serviceNamespace>/hub`: il valore deve iniziare con `loyaltyhub` (regole e dashboard
selezionano `job=~"loyaltyhub[^/]*/hub"`) e va cambiato, uno per installazione, se più installazioni condividono un
Prometheus (Q-527). Il ricevitore del collector non autentica: la NetworkPolicy del chart ammette solo i Pod `hub`
della release sulla porta 4318 (Q-521).

**Compose di riferimento.** Il profilo `observability` aggiunge `otelcol`, `prometheus` e `grafana` (immagini fissate
per tag e digest). Collector e Prometheus non hanno porte sull'host; Grafana è su `http://127.0.0.1:3001` (solo su
`LH_BIND_ADDRESS`), con la password obbligatoria, senza accesso anonimo e senza comunicazioni verso Grafana Labs (statistiche, aggiornamenti, chiave di firma e catalogo dei plugin, snapshot esterni: Q-528):

```bash
LH_OTEL_METRICS_ENABLED=true LH_GRAFANA_ADMIN_PASSWORD=… \
  docker compose -f deploy/compose/reference.yml --profile observability up -d
```

`LH_OTEL_METRICS_ENABLED=true` ha senso solo insieme a `--profile observability`: senza il profilo il servizio `otelcol`
non esiste, l'hub non risolve il nome e registra un avviso «Failed to publish metrics» ogni 30 secondi (innocuo, ma rumore).

| Variabile (compose) | Default | Uso |
|---|---|---|
| `LH_OTEL_METRICS_ENABLED` | `false` | l'hub invia le metriche al servizio `otelcol` |
| `LH_GRAFANA_ADMIN_PASSWORD` | — (obbligatoria con il profilo) | password dell'amministratore locale di Grafana |
| `LH_GRAFANA_ADMIN_USER` | `admin` | utente amministratore |
| `LH_PROMETHEUS_RETENTION` | `31d` | conservazione delle serie in Prometheus (i riquadri della dashboard a 30 giorni ne richiedono almeno 30) |
| `LH_OTEL_SERVICE_NAMESPACE` | `loyaltyhub` | secondo pezzo del `job` (`<valore>/hub`) |

**Cosa misura** (ADR-036): disponibilità del portale (99,9 %), giocata p99 sotto 500 ms e tempo azione → punti p95
sotto 5 s (metrica `lh_action_to_points_seconds` di insight, Q-523, Q-524), con il consumo del budget d'errore e allarmi
a burn rate veloce e lento; allarmi su DLQ, firma non valida o produttore non ammesso (pronto per la firma dei messaggi, M8.10: oggi nessun codice
produce `SIGNATURE_INVALID` né `PRODUCER_NOT_ALLOWED`), picchi di 401 e 403, arretrato dell'outbox e telemetria assente
(per installazione, se più ne condividono un Prometheus). RPO 15 min e RTO 1 h non si misurano dall'applicazione: li prova il ripristino
(M15.2, Q-525).

### Limiti noti (domande aperte)

- **Q-409, Q-419** — le sessioni del BFF stanno nella memoria del Pod `web`: nel profilo `enterprise` il web ha una
  sola replica, senza HPA né PDB, e un riavvio o un aggiornamento chiude le sessioni (si rientra con l'SSO dell'IdP),
  finché non arriva lo store condiviso.
- **Q-410** — il portale nel profilo `enterprise` non va esposto a membri reali finché M8.10 non lega il membro al
  token nei servizi (`MemberPrincipal`).
- **Q-392, Q-420** — il compose di riferimento parla HTTP in chiaro (web e Keycloak, compresa la console `/admin`):
  porte solo su `127.0.0.1` e reverse proxy con TLS davanti per altri accessi; nel profilo `enterprise` il login
  funziona solo dietro quel proxy. TLS fino ai container con M8.5.
- **Q-421** — Keycloak chiama il back-channel logout sull'URL pubblico del web, passando dall'Ingress; il chart non
  configura un truststore di Keycloak per una CA interna. Se la chiamata fallisce, la sessione del BFF dà accesso al
  più fino alla scadenza dell'access token (5 minuti): al rinnovo l'IdP la rifiuta e il BFF la chiude.
- **Q-373** — ADR-028 vuole `facts` e `audit` a 365 giorni, lecito solo senza PII sul bus (ADR-032). Finché M8.4 non
  pubblica solo `member.*:2` e l'audit mascherato, il default è 7 giorni; `kafka.topics.retentionMsByTopic` lo alza.
- **Q-374** — l'immagine unica non contiene ancora Keycloak né Directus: `idp` usa l'immagine ufficiale di Keycloak
  alla versione bloccata del compose di sviluppo (filesystem scrivibile, perché `kc.sh start` ricompila); `cms`,
  Redis e MinIO arrivano con M10.2.
- **Q-375** — Kafka interno senza TLS né autenticazione fino a M8.5 (mTLS di mesh, principal per modulo, ACL).
- **Q-494** — una fonte creata a runtime da BO-09 non hanno un client `src-<codice>` nel realm: l'amministratore lo crea a
  mano (*Fonti di ingestion: un client per fonte*, TOBE-009).
- **Q-491** — il job `helm install (kind)` prova il chart nel profilo `demo`: login OIDC del web, token delle fonti e
  Ingress con TLS del profilo `enterprise` non passano ancora da un'installazione reale in CI (TOBE-008).
- Nessuna NetworkPolicy fino a M8.5, salvo quella del collector dell'osservabilità (con `observability.enabled`).
- **Q-521** — con l'osservabilità accesa il traffico hub → collector è in chiaro dentro il cluster e il ricevitore OTLP
  non autentica: lo protegge solo la NetworkPolicy (Pod `hub` della release sulla 4318) finché M8.5 non porta l'mTLS
  di mesh. Le sonde del kubelet arrivano dal nodo: se il CNI le blocca, spegni
  `observability.collector.networkPolicy.enabled`.
- **Q-524** — gli SLI del portale e della giocata sono misurati dall'hub (risposte 5xx e latenza di
  `http.server.requests`): non includono rete, Ingress né il BFF del web, e un hub irraggiungibile lo segnala solo
  l'allarme `LoyaltyHubTelemetryAbsent`.
- **Q-526** — solo metriche: tracce (Tempo), log (Loki), strumentazione del web e di Keycloak, metriche di Strimzi e
  CloudNativePG, KEDA, login OIDC di Grafana, Alertmanager e SIEM arrivano con M8.6b.
- **Q-400, Q-403** — chi ha le credenziali applicative del database può ancora riscrivere o svuotare l'audit di insight
  (le funzioni controllate e i ruoli separati arrivano con la migrazione di contract, dopo M8.5): lo rivela solo il
  confronto con le ancore nei log (*Ancore dell'audit nei log*).

### Verifica

```bash
node --test scripts/check-helm.mjs   # statiche sempre; con helm anche lint e scenari (in CI helm è obbligatorio)
helm lint deploy/helm/loyaltyhub --strict -f deploy/helm/loyaltyhub/ci/lint-values.yaml
helm template lh deploy/helm/loyaltyhub --kube-version 1.31.0 -f deploy/helm/loyaltyhub/ci/lint-values.yaml \
  | kubeconform -strict -summary -schema-location default \
      -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
LH_IMAGE=ghcr.io/example/loyaltyhub:ci docker compose -f deploy/compose/reference.yml config -q
LH_IMAGE=ghcr.io/example/loyaltyhub:ci docker compose -f deploy/compose/reference.yml --profile observability config -q
```

`check-helm` prova anche lo scenario con l'osservabilità accesa (`ci/observability-values.yaml`): risorse rese, rifiuti, regole e dashboard contro il codice, compose e `kubeconform` con gli schemi dei CRD (con `CI=true` anche `kubeconform` e `promtool` sono obbligatori). Con `promtool` (Prometheus 3) nel PATH esegue anche `promtool check rules` e `promtool test rules` sui casi di `ci/slo-rules.test.yaml` (buco di telemetria, prima occorrenza di un contatore, telemetria assente per installazione). In CI lo fa il job `helm` di `.github/workflows/ci.yml` (helm, kubeconform e promtool a versione fissa; promtool è scaricato dal rilascio ufficiale e verificato con lo sha256, e la sua assenza fa fallire la prova), solo quando cambiano
chart, compose, immagine, realm, lo smoke, la verifica o i workflow, e sempre su `main`.

### Installazione provata in CI (kind)

Il job `helm install (kind)` di `.github/workflows/ci.yml` installa davvero il chart, con le stesse condizioni del job
`helm`. È il criterio di accettazione di M8 in `docs/18 §6`. Il job:

1. costruisce l'immagine unica da `deploy/image/Dockerfile` e la carica nel nodo di un cluster kind;
2. etichetta il nodo con `topology.kubernetes.io/zone` e impone Pod Security `restricted` sul namespace `lh`;
3. installa Strimzi 1.2.0, CloudNativePG 1.30.1 (chart 0.29.1) ed Envoy Gateway v1.9.2, poi crea un `Gateway`;
4. crea i Secret con valori casuali e installa il chart con `ci/kind-values.yaml`: un broker, un'istanza di Postgres,
   una replica per ruolo, profilo `demo`, `kafka.strimzi.version=4.2.1`, Postgres 17.11 e `gateway.enabled=true`;
5. attende `Kafka`, `KafkaTopic`, `Cluster` e `Database` pronti e i Pod di `hub`, `web` e `idp` `Ready`;
6. esegue `scripts/smoke.sh` attraverso il gateway: le API `/v1` passano dall'`HTTPRoute` del chart all'hub.

Versioni fisse nel job: kind v0.33.0, nodo `kindest/node:v1.36.4` con digest, kubectl v1.36.4, Helm v3.18.4.
`scripts/check-helm.mjs` verifica che `ci/kind-values.yaml` resti sulle linee di Kafka e Postgres dei compose.

Se il job fallisce, il passo *Diagnostica* stampa Pod, eventi, stato delle risorse degli operatori e i log di ruoli,
Job di migrazione, broker e operatori.

> **Nota:** il job è **consultivo**: non è nei controlli obbligatori del ruleset (`scripts/setup-branch-protection.sh`)
> finché non si dimostra stabile. Usa il profilo `demo` perché lo smoke non ha un token OIDC: lo smoke nel profilo
> `enterprise` resta aperto (Q-491, TOBE-008).

---

## Verificare l'immagine

Ogni immagine pubblicata con un tag `v*` è firmata, ha un SBOM allegato e una provenienza della build. Verifica questi
tre elementi prima di installare l'immagine con il chart o con il compose di riferimento. Il perché e il flusso completo
sono in `docs/security/supply-chain.md`.

### Prerequisiti

- [cosign](https://docs.sigstore.dev/cosign/) 3.0 o successivo (la pipeline usa la 3.1.3, che scrive il nuovo formato di bundle Sigstore: la 2.x non lo verifica);
- [GitHub CLI](https://cli.github.com/) (`gh`) per la provenienza;
- `docker` con buildx e `jq`, per ricavare il digest di una piattaforma;
- l'accesso in lettura all'immagine su GHCR (`docker login ghcr.io` se il pacchetto non è pubblico).

Imposta cinque variabili: sostituisci i segnaposto con i valori del repository che ha pubblicato l'immagine.

```bash
export IMAGE=ghcr.io/<owner>/loyaltyhub        # come in image.repository del chart o LH_IMAGE del compose
export VERSION=v0.7.0                          # il tag da installare
export REPO=<owner>/<repository>               # il repository GitHub del workflow, con le maiuscole di GitHub
export ISSUER=https://token.actions.githubusercontent.com
export IDENTITY_RE="^https://github\.com/${REPO}/\.github/workflows/image\.yml@refs/tags/v.+$"
```

### Verifica la firma

Esegui questo comando. Ha successo solo se l'immagine è firmata dal workflow `image.yml` di quel repository su un tag
`v*`, senza chiavi da scambiare: l'identità è nel certificato di breve durata registrato nel log di
trasparenza di Sigstore.

```bash
# Verifica la firma keyless dell'indice multi-arch; stampa il digest verificato
cosign verify "$IMAGE:$VERSION" \
  --certificate-identity-regexp "$IDENTITY_RE" \
  --certificate-oidc-issuer "$ISSUER"
```

Per un'installazione ripetibile fissa il digest verificato al posto del tag (`image.tag=sha256:…` nel chart,
`LH_IMAGE=$IMAGE@sha256:…` nel compose).

### Verifica e scarica l'SBOM

L'SBOM CycloneDX di ogni piattaforma è un'attestazione sul digest del manifest di quella piattaforma (Q-511), non
sull'indice. Ricava il digest della piattaforma che usi, poi verifica l'attestazione ed estrai l'SBOM.

```bash
# Digest del manifest linux/amd64 (usa arm64 per le macchine ARM)
DIGEST=$(docker buildx imagetools inspect --raw "$IMAGE:$VERSION" \
  | jq -r '.manifests[] | select(.platform.os=="linux" and .platform.architecture=="amd64") | .digest')

# Verifica l'attestazione e salva l'SBOM CycloneDX in un file
cosign verify-attestation --type cyclonedx "$IMAGE@$DIGEST" \
  --certificate-identity-regexp "$IDENTITY_RE" \
  --certificate-oidc-issuer "$ISSUER" \
  | head -n1 | jq -r '.payload | @base64d | fromjson | .predicate' > sbom-amd64.cdx.json
```

In alternativa scarica l'SBOM dall'esecuzione del workflow, senza verificare la firma: apri l'esecuzione del tag nella
scheda *Actions* del repository e scarica l'artefatto `sbom-e-scansione-<tag>` (`sbom-amd64.cdx.json`,
`sbom-arm64.cdx.json` e i rapporti di scansione `trivy-*.json`). Gli artefatti restano 90 giorni; l'attestazione nel
registro resta finché esiste l'immagine.

### Verifica la provenienza della build

Questo comando controlla che la provenienza della build, firmata da GitHub, indichi il repository e il workflow attesi.

```bash
# Verifica l'attestazione di provenienza (SLSA) firmata da GitHub per questo repository
gh attestation verify "oci://$IMAGE:$VERSION" --repo "$REPO"
```

### Errori comuni

> **Attenzione:** se `cosign verify` risponde `no matching signatures`, controlla nell'ordine: (1) il tag è un tag di
> rilascio `v*`, non `build-<commit>`, che è provvisorio e non si usa; (2) `REPO` ha le stesse maiuscole del
> repository su GitHub, perché l'identità le distingue; (3) l'immagine non è stata copiata in un altro registro con uno
> strumento che non copia le firme.

> **Nota:** `cosign verify-attestation` sul tag, cioè sull'indice, non trova l'SBOM: l'attestazione è sul digest della
> piattaforma. Il chart non porta ancora la verifica delle firme all'ammissione (Kyverno, docs/18 §3.11):
> per ora la verifica si fa come sopra, prima dell'installazione. Q-511 e TOBE-010 dicono cosa manca.
