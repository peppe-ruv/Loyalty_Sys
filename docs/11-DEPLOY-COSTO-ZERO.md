# 11 — Deploy a costo zero

Obiettivo: una demo **pubblica, accendibile su richiesta, che da spenta costa zero**, creata e aggiornata il più possibile da Claude (connettori Vercel, Render, Neon) senza carta di credito. I topic Kafka **restano** (ADR-004): il costo zero si ottiene con servizi che dormono, non togliendo il bus.

> I limiti dei piani gratuiti cambiano. Valori verificati il **18/09/2026**; fonti al §13. Prima di M1 ricontrollare.

## 1. Mappa dei fornitori

| Componente | Fornitore (piano) | Limiti che contano | Da spento | Creazione |
|---|---|---|---|---|
| `web` Next.js | **Vercel** Hobby | solo uso **non commerciale**; funzioni serverless senza connessioni lunghe (→ SSE diretto a insight) | 0 | connettore Vercel o import del repo |
| 8 microservizi | **Render** Free Web Service (Docker) | 512 MB / 0,1 CPU ciascuno; **sospensione dopo 15 min** senza traffico HTTP in ingresso; **750 ore-istanza/mese per workspace**; nessun background worker gratuito; nessuna rete privata tra servizi free | 0 | pannello Render o connettore (§7) |
| PostgreSQL | **Neon** Free | 0,5 GB; **100 CU-ore/mese**; scale-to-zero dopo 5 min | 0 | connettore Neon |
| Kafka | **Aiven** Free | **5 topic × 2 partizioni**, 250 KiB/s, retention 3 giorni; **si spegne per inattività e va riacceso a mano** dalla console; creazione solo da console | 0 | **manuale** (una tantum) |
| CI | GitHub Actions | gratuito su repo pubblico | 0 | file nel repo |
| Immagini Docker | GHCR | gratuito su repo pubblico (es. `ghcr.io/.../loyaltyhub`) | 0 | workflow `image.yml` |

**Conti da tenere a mente**
- 750 ore ÷ 8 servizi ≈ **93 ore di demo accesa al mese** (tutti e 8 svegli). Più che sufficiente, a patto di **non** usare pinger esterni.
- Neon: a 0,25 CU, 100 CU-ore ≈ 400 ore di DB acceso. Un keep-alive esterno 24/7 lo esaurirebbe (720 ore): per questo il keep-alive è solo "a scheda aperta" (`docs/07 §8`).
- Kafka: 5 topic = esattamente i nostri 5. **Nessun topic di retry, nessun topic per servizio** (il retry è in-process, `docs/04 §5`).

## 2. Ordine di messa in opera (M1)
1. **Aiven** (manuale, §3) → variabili `KAFKA_*`.
2. **Neon** (connettore, §4) → `DB_URL`, `DB_URL_DIRECT`, `DB_USER`, `DB_PASSWORD`.
3. **Render**: servizi creati dal pannello o con il connettore secondo §7 → gruppo di variabili `lh-shared` compilato coi valori dei passi 1–2 → primo deploy dei 4 servizi del core loop (gli altri si aggiungono per milestone).
4. **Vercel**: progetto con root `web/`, variabili §8 con gli URL `*.onrender.com`.
   Il salto della build (*Ignored Build Step*) sta in `web/vercel.json` (`ignoreCommand`): si salta solo se nulla è cambiato in `web/`, `seed/`, `contracts/` o `registry/` rispetto all'ultimo deploy. Il *Redeploy* dello stesso commit fa sempre la build: è il modo di far leggere al web una variabile d'ambiente appena impostata (per esempio `LH_HUB_ENTERPRISE_URL` del riquadro Enterprise), perché Vercel la applica solo ai deploy nuovi. Vercel clona il repository solo in parte: se il commit dell'ultimo deploy non è nel clone, il comando fa la build invece di fallire (senza questa guardia `git diff` esce con 128 e Vercel segna il deploy in errore).
   Le anteprime dei rami sono spente in `web/vercel.json` (`git.deploymentEnabled`): il piano gratuito ammette 100 deploy al giorno e ogni push su un ramo ne crea uno, anche quando l'*Ignored Build Step* poi lo salta (il limite `api-deployments-free-per-day` si è esaurito il 2026-09-30 con molte PR aperte in parallelo, bloccando anche i deploy di `main`). Si fa il deploy solo di `main`; per avere un'anteprima di una PR del web basta un ramo il cui nome finisce con `-anteprima` (per esempio `fase2/M8.14f-hub-02-anteprima`). Il web delle altre PR è verificato dal job `web` della CI (lint, typecheck, test, build).
5. Aggiornare `LH_CORS_ALLOWED_ORIGINS` di insight con l'URL Vercel → ridistribuire insight.
6. `bash scripts/smoke.sh https://<web>.vercel.app` → verde.

## 3. Kafka su Aiven (unica parte manuale)
1. Console Aiven → *Create service* → Apache Kafka → piano **Free** → regione europea.
2. *Topics* → creare **a mano** i 5 topic con 2 partizioni: `lh.actions.v1`, `lh.effects.v1`, `lh.facts.v1`, `lh.audit.v1`, `lh.dlq.v1`. Retention: massima consentita (3 giorni). `cleanup.policy=delete`.
   > I servizi **non** creano topic fuori dal profilo `local` (`spring.kafka.admin.auto-create=false`): sul piano gratuito un sesto topic farebbe fallire l'avvio in modo poco leggibile.
3. *Connection information*: scaricare `ca.pem`, `service.cert`, `service.key` (autenticazione predefinita: certificato client TLS).
4. Codificare in base64 **su una riga** e salvarli come segreti: `base64 -w0 ca.pem` → `KAFKA_SSL_CA_B64`; idem `KAFKA_SSL_CERT_B64`, `KAFKA_SSL_KEY_B64`. `KAFKA_SECURITY=SSL_PEM`. `KAFKA_BOOTSTRAP=<host>:<porta>`.
   - Se la chiave è PKCS#1 convertirla: `openssl pkcs8 -topk8 -nocrypt -in service.key -out service.pk8.key`.
   - In alternativa abilitare SASL dalla console e usare `KAFKA_SECURITY=SASL_SSL` + `KAFKA_SASL_USERNAME/PASSWORD` + `KAFKA_SSL_CA_B64` (domanda aperta `Q-04`).
5. **Riaccensione**: se il Demo Hub segnala Kafka `DOWN` per più di 2 minuti → console Aiven → servizio → *Power on*. I dati nel DB restano; gli eventi più vecchi di 3 giorni no (irrilevante: lo stato è nel DB).

Verifica locale della connessione: `deploy/kafka/check-connection.sh` (usa `kcat` in container con gli stessi PEM).

**Piano B (ADR-016)** se il piano gratuito sparisce o diventa inadatto: broker Kafka singolo (KRaft) su VM *Always Free* di un cloud pubblico, con TLS + SASL; stessi topic, stesse variabili. Non si passa a un motore in-process.

## 4. PostgreSQL su Neon
- Un progetto `loyalty-hub`, un database `loyaltyhub`, un ruolo applicativo. **Otto schemi**, creati da Flyway (ogni servizio: `spring.flyway.schemas=<schema>`, `create-schemas=true`, tabella di storia nello schema proprio).
- Due URL: **pooled** (`-pooler` nell'host) per l'applicazione → `DB_URL`; **diretto** per Flyway → `DB_URL_DIRECT` (le migrazioni usano lock di sessione, incompatibili col pooling a transazione). Entrambi con `sslmode=require`.
- Attenzione al pooling a transazione: niente `SET` di sessione, niente advisory lock **di sessione** (si usano quelli `xact`), `prepareThreshold=0` nel JDBC URL.
- Hikari (profilo `free`): `maximum-pool-size=4`, `minimum-idle=0`, `idle-timeout=60s`, `max-lifetime=10m`, `connection-timeout=20s`, `initialization-fail-timeout=-1` (il servizio parte anche se il DB si sta svegliando). 8 × 4 = 32 connessioni massime verso il pooler.
- Spazio (RNF-07): pulizie automatiche di `outbox` (pubblicati > 24 h), `processed_event` (> 7 giorni), `event_store` (14 giorni), `inbound_event` (14 giorni), log valutazioni (30 giorni).
- Con il connettore Neon: creare il progetto, leggere le stringhe di connessione, **non** creare schemi a mano.

## 5. Dockerfile dei servizi
Un file per servizio, **contesto = radice del repo** (serve il parent POM, `libs/` e `seed/`).
```dockerfile
# services/wallet-service/Dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY libs libs
COPY services services
COPY seed seed
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -pl services/wallet-service -am -DskipTests package
RUN mkdir /out && cd /out \
 && java -Djarmode=tools -jar /src/services/wallet-service/target/*.jar extract --layers --destination app

FROM eclipse-temurin:25-jre
RUN useradd -r -u 10001 lh
WORKDIR /app
COPY --from=build /out/app/dependencies/ ./
COPY --from=build /out/app/spring-boot-loader/ ./
COPY --from=build /out/app/snapshot-dependencies/ ./
COPY --from=build /out/app/application/ ./
USER lh
ENV SPRING_PROFILES_ACTIVE=demo,free
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar wallet-service.jar"]
```
Le immagini di Fase 1 (gli 8 servizi e l'hub consolidato della demo ospitata) girano come utente non root (`uid`/`gid` 10001, id numerici così che Kubernetes possa verificare `runAsNonRoot`; Q-504, F2-SEC-02, ADR-044). L'immagine unica di `deploy/image/Dockerfile` è non root dall'inizio, con `uid` 1000.
Note: da Spring Boot 4.1 il jarmode `layertools` non esiste più → si usa `-Djarmode=tools … extract --layers`. `finalName` del jar = nome del modulo (nel parent POM). Un `.dockerignore` alla radice esclude `web/node_modules`, `**/target`, `.git`, `docs`.
Ottimizzazione successiva (non in M1): cache AOT / CDS generata in build con un avvio di addestramento (`-XX:AOTCacheOutput`), attesa riduzione del tempo di avvio del 30–40 %.

## 6. JVM e Spring su 512 MB / 0,1 CPU
```
JAVA_OPTS=-XX:+UseSerialGC -XX:MaxRAMPercentage=65 -Xss512k -XX:TieredStopAtLevel=1
          -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=128m -Dfile.encoding=UTF-8
```
Profilo `free` (`docs/06 §6`): inizializzazione lazy (con `@Lazy(false)` su listener e scheduler), virtual thread, Tomcat `threads.max=20`, JMX off, springdoc attivo ma UI Swagger disattivata (`springdoc.swagger-ui.enabled=false`; resta `/v3/api-docs`), log a livello `INFO` in JSON.
**Deploy e sospensione.** Se la sospensione per inattività arriva mentre Render avvia la nuova versione, cadono entrambe le istanze e il deploy finisce in `update_failed` ("Port scan timeout"), lasciando in linea la versione precedente. Il workflow `.github/workflows/deploy-keepalive.yml` interroga la liveness dell'hub ogni 45 s dopo ogni push su `main` che cambia il backend e si ferma appena risponde un'istanza avviata dopo la prima vista (`process.uptime` dall'actuator: la nuova versione è online), al più dopo 15 minuti. Ogni minuto di hub sveglio è anche compute del database (l'outbox interroga Postgres ogni 500 ms, Neon non si sospende), quindi il keep-alive dura quanto il deploy e non di più (URL nella variabile di repository `LH_HUB_URL`, con ripiego sull'hub attuale).

**Prova di fumo online.** `.github/workflows/smoke-demo.yml` (solo manuale, *Run workflow*) sveglia l'hub e lancia `scripts/smoke.sh` contro l'hub consolidato: un acquisto sul membro scelto (default `MBR-000002`, 20 €) deve diventare punti entro 15 s. Scrive dati veri nella demo: dopo, se serve, *Reset* da BO-30.

Attese oneste: **avvio a freddo 60–150 s** per servizio su 0,1 CPU; il Demo Hub lo dichiara. Liveness: `/actuator/health/liveness` **senza** dipendenze esterne (un DB addormentato non deve far riavviare il servizio); Kafka e DB stanno solo nella readiness e nel dettaglio di `/actuator/health`.

**Conseguenza della sospensione** (RNF-06): un servizio addormentato non consuma. Al risveglio recupera dal proprio offset (retention 3 giorni). Per questo "Accendi la demo" sveglia **tutti** i servizi, e l'UI mostra "in elaborazione" finché il fatto non arriva.

## 7. Servizi Render (dal pannello, senza Blueprint)
Il repository non contiene un `render.yaml` (Q-623, decisa il 2026-10-01): i servizi Render della demo si creano e si gestiscono dal pannello o con il connettore, e le loro variabili sono elencate in §8. Il blocco seguente è solo il riferimento della configurazione da riprodurre, scritto nella sintassi Blueprint; un `render.yaml` si aggiunge solo se la vetrina passerà a Render (oggi la vetrina gira in un GitHub Codespace, ADR-050).

```yaml
envVarGroups:
  - name: lh-shared
    envVars:
      - { key: SPRING_PROFILES_ACTIVE, value: "demo,free" }
      - { key: JAVA_OPTS, value: "-XX:+UseSerialGC -XX:MaxRAMPercentage=65 -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError" }
      - { key: KAFKA_SECURITY, value: SSL_PEM }
      - { key: KAFKA_BOOTSTRAP, sync: false }
      - { key: KAFKA_SSL_CA_B64, sync: false }
      - { key: KAFKA_SSL_CERT_B64, sync: false }
      - { key: KAFKA_SSL_KEY_B64, sync: false }
      - { key: DB_URL, sync: false }
      - { key: DB_URL_DIRECT, sync: false }
      - { key: DB_USER, sync: false }
      - { key: DB_PASSWORD, sync: false }
      - { key: LH_CORS_ALLOWED_ORIGINS, sync: false }

services:
  - type: web
    name: lh-ingestion
    runtime: docker
    plan: free
    region: frankfurt
    dockerfilePath: ./services/ingestion-service/Dockerfile
    dockerContext: .
    healthCheckPath: /actuator/health/liveness
    autoDeploy: true
    buildFilter:
      paths: ["services/ingestion-service/**", "libs/**", "seed/**", "pom.xml"]
    envVars:
      - fromGroup: lh-shared
  # … stesso blocco per lh-member, lh-campaign, lh-wallet, lh-reward,
  #   lh-gamification, lh-engagement, lh-insight (cambiano name, dockerfilePath, buildFilter)
```
- Render assegna `PORT`; i servizi lo leggono (`server.port=${PORT:808x}`).
- `sync: false` = valore inserito a mano (o via connettore) e mai nel repo.
- I minuti di build gratuiti sono limitati (~500/mese, da verificare): `buildFilter` evita di ricostruire 8 immagini a ogni commit.
- **Piano B build**: workflow `images.yml` che costruisce e pubblica su GHCR solo i servizi toccati, e chiama il *deploy hook* di Render (`runtime: image`). Da attivare se i minuti di build non bastano.

## 8. Matrice delle variabili

| Variabile | Dove | Esempio / nota |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | servizi | `demo,free` (locale: `demo,local`) |
| `PORT` | servizi | assegnata da Render; locale 8081–8088 |
| `DB_URL`, `DB_URL_DIRECT`, `DB_USER`, `DB_PASSWORD` | servizi | Neon; locale `jdbc:postgresql://localhost:5432/loyaltyhub` |
| `KAFKA_BOOTSTRAP`, `KAFKA_SECURITY`, `KAFKA_SSL_*_B64` / `KAFKA_SASL_*` | servizi | Aiven; locale `localhost:9092`, `PLAINTEXT` |
| `JAVA_OPTS` | servizi | §6 |
| `LH_CONSUMER_RETRY_BACKOFF_MS` | servizi | `loyaltyhub.consumer.retry-backoff-ms` (default 1000,5000: 3 tentativi, vedi Q-131) |
| `LH_KAFKA_CONSUMER_CONCURRENCY`, `LH_KAFKA_TOPIC_*`, `LH_KAFKA_<TOPIC>_RETENTION_MS`, `LH_KAFKA_TOPICS_*` | servizi, hub | concorrenza dei listener e forma dei 5 topic (F2-EVT-04); da non impostare in demo: i default restano 2 consumer, 2 partizioni, 1 replica, 3 giorni, topic esistenti non modificati (il Kafka gratuito può rifiutare `alterConfigs`). Elenco in `deploy/README.md` |
| `LH_CORS_ALLOWED_ORIGINS` | **insight** | URL Vercel (+ `http://localhost:3000`); serve solo all'SSE |
| `LH_JOBS_ENABLED` | servizi | `true`; `false` per spegnere gli scheduler |
| `LH_APPROVAL_ENABLED` | campaign, reward, gamification, engagement | mappa `loyaltyhub.approval.enabled`; `false` fino a M7 |
| `LH_SVC_INGESTION_URL` … `LH_SVC_INSIGHT_URL` (8) | **web** (server) | `https://lh-wallet.onrender.com`; locale `http://localhost:8084` |
| `NEXT_PUBLIC_LH_INSIGHT_URL` | **web** (browser) | URL pubblico di insight per l'SSE |
| `NEXT_PUBLIC_REPO_URL` | web | link nel Demo Hub |
| `LH_HUB_ENTERPRISE_URL` | **web demo** (server) | origine `https` della vetrina enterprise (§17, ADR-049, F2-DIST-09), senza percorso. Letta solo lato server, senza `NEXT_PUBLIC_`: si cambia senza ricostruire. Vuota o assente (il caso normale, anche in locale) ⇒ il pulsante «Modalità Enterprise» di HUB-01 è nascosto, senza avvisi. Valorizzata ma non valida (non `https`, con percorso, non un URL) ⇒ pulsante nascosto e un avviso nel log del server |
| `LH_HUB_DEMO_URL` | **web vetrina** (server), solo `enterprise` | origine `https` della demo, per il collegamento di ritorno in HUB-02 (§17, ADR-049); vuota ⇒ nessun collegamento |
| `LH_VETRINA_WEB_HOST`, `LH_VETRINA_IDP_HOST`, `LH_VETRINA_ADMIN_HOST`, `LH_VETRINA_DIR` | **codespace della vetrina** (`/etc/loyaltyhub-vetrina/vetrina.env`, scritto da `vetrina.sh codespace` e letto da `deploy/vetrina/vetrina.sh`) | gli indirizzi dell'inoltro delle porte di GitHub per web (8000), Keycloak (8001) e console del realm `master` (8180, privata), la cartella di segreti, CA locale e certificati (§17). Nessun segreto nel file: i segreti li genera `vetrina.sh provision` in file `0600` |
| `LH_INGESTION_URL` | **insight** | solo per *riprocessa* DLQ (M7) |
| `LH_PROFILE` | **web** (server) | assente o `demo`: identità simulata (docs/07 §4), nessuna variabile OIDC; `enterprise`: login OIDC obbligatorio (docs/07 §4-bis); qualunque altro valore ⇒ il web non parte (`INSECURE_CONFIG`) |
| `LH_OIDC_ISSUER` | **web** (server), solo `enterprise` | emittente OIDC, es. `https://idp.example.org/realms/loyaltyhub`; `https` obbligatorio (`http` solo verso `localhost`) |
| `LH_WEB_CLIENT_ID` | **web** (server), solo `enterprise` | client confidential del realm; predefinito `web` |
| `LH_WEB_CLIENT_SECRET` / `LH_WEB_CLIENT_SECRET_FILE` | **web** (server), solo `enterprise` | segreto del client `web` (lo stesso passato all'IdP, `deploy/idp/README.md`); almeno 16 caratteri, niente segnaposto |
| `LH_OIDC_MEMBER_ISSUER` | **hub** e **web** (server), solo `enterprise`, facoltativa | emittente del realm dei membri (ADR-051), es. `https://idp.example.org/realms/loyaltyhub-members`, lo stesso valore pubblico per hub e web e diverso da `LH_OIDC_ISSUER`. Assente ⇒ un solo realm, come prima (regola 14). Presente ⇒ l'hub accetta `MEMBER` solo da questo emittente e il BFF fa entrare i membri del portale qui (`GET /api/auth/login?realm=members`, o `returnTo` che inizia con `/portal`). Con `LH_IDENTITY_MODE=header` l'hub la rifiuta (`INSECURE_CONFIG`): il compose di riferimento la toglie da sé |
| `LH_OIDC_MEMBER_JWKS_URI` | **hub** (server), facoltativa | JWKS del realm dei membri letto dentro la rete (es. `http://idp:8080/realms/loyaltyhub-members/protocol/openid-connect/certs`); vuota ⇒ quello della discovery di `LH_OIDC_MEMBER_ISSUER`; senza `LH_OIDC_MEMBER_ISSUER` l'hub non parte |
| `LH_WEB_MEMBER_CLIENT_ID` | **web** (server), solo `enterprise` con `LH_OIDC_MEMBER_ISSUER` | client confidential del realm dei membri; predefinito `portal` |
| `LH_WEB_MEMBER_CLIENT_SECRET` / `LH_WEB_MEMBER_CLIENT_SECRET_FILE` | **web** (server), solo `enterprise` con `LH_OIDC_MEMBER_ISSUER` | segreto del client `portal` (lo stesso di `LH_PORTAL_CLIENT_SECRET` dell'IdP); almeno 16 caratteri, niente segnaposto |
| `LH_TEST_USERS_ALLOWED` | **hub** e **web** (server), solo `enterprise` | utenti di test (Q-676, ADR-051): un utente con il ruolo di realm `LH_TEST_USER` è rifiutato salvo `LH_TEST_USERS_ALLOWED=true` **e** `LH_ENVIRONMENT=test`; `true` senza `LH_ENVIRONMENT=test` ⇒ l'avvio fallisce (`INSECURE_CONFIG`). Assente nel compose di riferimento e nel chart; la passa solo `deploy/vetrina/vetrina.sh` nel codespace (`CODESPACES=true`, overlay `compose.codespace.yml`) |
| `LH_ENVIRONMENT` | **hub** e **web** (server) | tipo di ambiente; `test` solo per un ambiente di prova con dati fittizi, come la vetrina nel codespace (Q-676). Assente altrove |
| `LH_PORTAL_CLIENT_SECRET` | **idp** (Keycloak) | segreto del client `portal` del realm `loyaltyhub-members` (`deploy/idp/realm-members.json`); obbligatorio, nessun default (`deploy/idp/README.md`) |
| `LH_WEB_URL` | **web** (server), solo `enterprise` | origine pubblica del web senza percorso (redirect URI, controllo `Origin`, ritorno dal logout), la stessa di `LH_WEB_URL` dell'IdP; `https` obbligatorio (`http` solo per `localhost`) |
| `LH_WEB_SESSION_KEY` / `LH_WEB_SESSION_KEY_FILE` | **web** (server), solo `enterprise` | 32 byte casuali in base64 (`openssl rand -base64 32`): cifratura delle sessioni e dello stato del login, token CSRF; cambiarla chiude tutte le sessioni |
| `LH_WEB_SESSION_IDLE_SECONDS` | **web** (server), solo `enterprise` | inattività massima della sessione del BFF; predefinito `1800` (Q-354) |
| `LH_WEB_SESSION_MAX_SECONDS` | **web** (server), solo `enterprise` | durata massima della sessione del BFF; predefinito `36000` (Q-354) |
| `LH_WEB_SESSION_MAX_COUNT` | **web** (server), solo `enterprise` | sessioni tenute in memoria al massimo (esce la meno recente); predefinito `10000` (Q-409) |
| `NODE_EXTRA_CA_CERTS` | **web** (server), solo `enterprise` | certificato PEM della CA interna che firma l'emittente, aggiunto alle CA pubbliche di Node; nel chart da `roles.web.bff.issuerCaBundle` |
| `LH_SUBJECT_KEY` / `LH_SUBJECT_KEY_FILE` | **hub** (server), obbligatoria in `enterprise` e con `LH_IDENTITY_MODE=oidc`, non usata in `demo` con `header` | chiave dello pseudonimo `subjectRef` (ADR-048, Q-552): almeno 32 byte casuali in base64 (`openssl rand -base64 32`), distinta da `LH_PSEUDONYM_KEY`; chiave assente, corta o non base64 ⇒ l'hub non parte (`INSECURE_CONFIG`, senza stampare il valore); cambiarla scollega i token dai membri, vedi §16. Nel chart da `roles.hub.subjectKey` (Secret), nel compose dall'ambiente |

File `.env.example` alla radice e in `web/` con tutte le chiavi e nessun valore reale.

## 9. Sviluppo locale — `deploy/docker-compose.yml`
| Servizio | Immagine | Porta | Note |
|---|---|---|---|
| `kafka` | `apache/kafka:4.2.2` (KRaft, nodo singolo) | 9092 | `auto.create.topics.enable=false`; i topic li crea il profilo `local` (2 partizioni, come in demo); linea 4.2 come `kafka-clients` (Q-481) |
| `postgres` | `postgres:17.11` | 5432 | DB `loyaltyhub`; volume nominato |
| `kafka-ui` | `kafbat/kafka-ui:v1.5.0` | **8090** | ispezione topic; porta solo su `127.0.0.1` (Q-479) |
| 8 servizi | build locale (`services/<nome>/Dockerfile`) | 8081–8088 | solo con `--profile all` |
| `web` | build dell'immagine unica (`deploy/image/Dockerfile`), `LH_ROLE=web` | 3000 | solo con `--profile all` (Q-484) |
Limiti di memoria nel compose (`mem_limit: 512m`, `cpus: 0.5`) per scoprire presto i problemi del piano gratuito. `scripts/wake.sh <base-url-web>` e `scripts/smoke.sh` funzionano identici in locale e in demo.

## 10. CI — `.github/workflows/ci.yml`
| Job | Passi |
|---|---|
| `backend` | Temurin 25 · cache Maven · `./mvnw -B verify` (Testcontainers: Kafka + Postgres) |
| `web` | pnpm · `pnpm lint && pnpm typecheck && pnpm test && pnpm build` |
| `seed` | `npm --prefix scripts ci --omit=dev` · `node --test scripts/check-seed.test.mjs` · `node scripts/check-seed.mjs` (validazione JSON Schema 2020-12 dei seed con ajv, docs/10 §11) |
| `contracts` | valida `contracts/examples/*` contro gli schemi |
| `docker` (solo su `main`, path-filter) | build delle immagini toccate (senza push, salvo piano B) |
| `e2e` (manuale, `workflow_dispatch`) | `docker compose --profile all up` + Playwright + `smoke.sh` |

Fuori da `ci.yml`: `testbook.yml` (notturno) e `security-nightly.yml` (notturno, manuale e sulle PR che lo toccano: fuzzing Schemathesis e ZAP API scan, consultivo, `docs/security/dast.md`).

Nessun segreto in CI tranne, nel piano B, il deploy hook di Render.

## 11. Sicurezza di una demo senza login
- Gli endpoint sono **pubblici e senza autenticazione**: è un PoC con **soli dati fittizi**. Non inserire mai dati reali; dominio e-mail `example.org`.
- `/v1/demo/**` esiste solo col profilo `demo`; il reset è richiamabile da chiunque conosca l'URL: accettato per la demo, dichiarato nel Demo Hub. Mitigazione opzionale P1: header `X-LH-Demo-Key` verificato dai servizi e aggiunto dal proxy (`LH_DEMO_KEY`).
- Limite di frequenza in ingestion (60 eventi/min per IP) per non saturare i 250 KiB/s del Kafka gratuito.
- Webhook: solo `https`, blocco di indirizzi privati (anti-SSRF).
- Segreti solo nei pannelli dei fornitori; `gitleaks` in CI.
- `robots.txt` con `Disallow: /`.

## 12. Checklist di rilascio della demo
**Prima** — [ ] CI verde su `main` · [ ] migrazioni Flyway provate su DB vuoto e su DB della release precedente · [ ] `check-seed` verde · [ ] variabili nuove aggiunte nel pannello Render, in `.env.example` e in §8 · [ ] Kafka Aiven acceso, 5 topic presenti.
**Durante** — [ ] deploy Render dei soli servizi toccati · [ ] deploy Vercel · [ ] `scripts/wake.sh` finché 10/10 · [ ] `scripts/smoke.sh` verde (azione → punti ≤ 15 s a servizi svegli).
**Dopo** — [ ] Demo Hub 10/10 · [ ] percorso consigliato eseguito a mano una volta · [ ] `docs/14` aggiornato · [ ] se lo schema di un evento è cambiato: reset dati da BO-30.
**Si torna indietro se** — smoke rosso dopo 2 tentativi · un servizio va in OOM (riavvii in serie su Render) · DLQ che cresce dopo il deploy. Come: *Rollback* del servizio su Render alla build precedente; le migrazioni sono solo additive (mai `DROP` in una release: si rimuove nella successiva).

## 13. Fonti dei limiti (verificate il 18/09/2026)
- Aiven, piano gratuito Kafka: `https://aiven.io/docs/products/kafka/free-tier/kafka-free-tier`
- Render, istanze gratuite: `https://render.com/docs/free`
- Neon, piani: `https://neon.com/docs/introduction/plans`
- Vercel, uso corretto del piano Hobby: `https://vercel.com/docs/limits/fair-use-guidelines`
- Spring Boot, estrazione a strati con `jarmode=tools`: documentazione di riferimento "Packaging OCI Images / Dockerfiles".

## 14. Immagine unica enterprise (Fase 2)
A partire da M8, la distribuzione enterprise viene generata dal file `deploy/image/Dockerfile` in un'unica immagine multi-arch pubblicata su GHCR (es. `ghcr.io/loyaltyhub/loyaltyhub`).
L'immagine contiene sia l'hub (Java) sia l'interfaccia (Node.js) e instradata in base alla variabile `LH_ROLE`.
Il deploy "a costo zero" (demo) continuerà a funzionare esattamente come descritto finché non vi sarà una transizione esplita. Vedi `deploy/image/README.md`.

## 15. Chart Helm e compose di riferimento (Fase 2, M8.3)
Il profilo `enterprise` si installa con il chart `deploy/helm/loyaltyhub` (Strimzi e CloudNativePG di default, servizi gestiti come valori, F2-DIST-02) o con `deploy/compose/reference.yml` (F2-DIST-03), dalla stessa immagine. Nulla cambia per la demo a costo zero di questo documento. Chart e compose passano al web le variabili `enterprise` di §8 (M8.2d): segreti solo da Secret (chart) o dall'ambiente (compose), emittente `https` raggiungibile dal web con l'URL del browser, una sola replica del web finché le sessioni del BFF stanno in memoria (Q-409, Q-419). Con `kafka.mode=strimzi` il chart scrive le risorse con l'API `kafka.strimzi.io/v1` e richiede Strimzi 0.51 o successivo (Q-490). Il job `helm install (kind)` di `ci.yml`, consultivo, installa il chart su un cluster kind ed esegue lo smoke attraverso il gateway (profilo `demo`, Q-491). Istruzioni, valori e limiti noti in `deploy/README.md`. Osservabilità (M8.6a, F2-OBS-01): spenta di default; chart (`observability.*`) e compose (profilo `observability`) portano il collector OpenTelemetry, la dashboard SLO e le regole (Prometheus e Grafana sono prerequisiti del chart e servizi del compose); le metriche escono in push solo verso l'URL scelto da chi installa (ADR-044, Q-520, Q-522). Nulla cambia per la demo a costo zero. Chiave dello pseudonimo del membro (F2-SEC-09, ADR-048, Q-552): chart e compose passano `LH_SUBJECT_KEY` all'hub del profilo `enterprise`, mai nella demo (§16).

## 16. Chiave dello pseudonimo del membro — `LH_SUBJECT_KEY` (Fase 2, F2-SEC-09, ADR-048, Q-552)
**Che cos'è.** Nel profilo `enterprise` il membro del portale viene solo dal token OIDC. member-service lega la coppia (`iss`, `sub`) al membro e sul bus pubblica soltanto `subjectRef`, l'HMAC-SHA256 esadecimale (64 caratteri) di `iss` e `sub` calcolato con questa chiave (`docs/06 §3.4`): il `sub` è un dato personale e non lascia mai member-service (ADR-032). Ogni servizio del portale calcola lo stesso HMAC dal token e lo cerca nella propria proiezione locale, senza chiamate sincrone. La chiave è quindi un segreto: chi la conosce può ricostruire i `subjectRef` di chi conosce `iss` e `sub`. È dedicata e distinta da `LH_PSEUDONYM_KEY` (Q-367): non si riusa e non si condivide con altri moduli o ambienti.

**Dove serve.** Solo all'hub (ruolo `hub`, tutti i moduli condividono la stessa chiave) nel profilo `enterprise`. Non serve al web, a Keycloak né al Job delle migrazioni. Nel profilo `demo` non serve e non viene passata: l'identità è simulata con `memberId` o `X-LH-Member` (regola 6-bis).

**Come si genera e si custodisce.** 32 byte casuali in base64 (la forma standard e quella URL-safe sono accettate; sotto i 32 byte decodificati l'hub rifiuta l'avvio):

```bash
openssl rand -base64 32
```

Si genera una volta per installazione e si conserva nel secret manager di chi installa (regola 20): mai in un repository, in un `values.yaml`, in un log o nel browser. Va tenuta anche fuori dai backup non cifrati. Il chart e il compose non contengono nessun valore, nemmeno di esempio.

- **Chart Helm.** `roles.hub.subjectKey` è un riferimento `{name, key}` a un Secret esistente (default `lh-subject-key`, chiave `subject-key`), letto dall'hub come `LH_SUBJECT_KEY` con `secretKeyRef`. Nel profilo `enterprise` un nome o una chiave vuoti, di soli spazi o nulli fanno fallire `helm install` e `helm template` con `INSECURE_CONFIG: roles.hub.subjectKey…` (regola 22), e così una voce `LH_SUBJECT_KEY` o `LH_SUBJECT_KEY_FILE` in `roles.hub.extraEnv`, che porterebbe un valore in chiaro nel chart; con `global.profile=demo` il chart non passa la variabile. Il chart non legge il contenuto del Secret: forma e lunghezza le verifica l'hub all'avvio. Non può nemmeno sapere se il Secret esiste: con un Secret inesistente `helm install` riesce e il Pod dell'hub resta in `CreateContainerConfigError` finché non lo si crea.

  ```bash
  kubectl create secret generic lh-subject-key --from-literal=subject-key="$(openssl rand -base64 32)"
  ```
- **Compose di riferimento.** `LH_SUBJECT_KEY` viene dall'ambiente (o da `LH_SUBJECT_KEY_FILE`, come le altre chiavi dell'hub); con `LH_PROFILE=enterprise` o `LH_IDENTITY_MODE=oidc` (entrambi i default) il container `hub` si ferma subito con `Variabile obbligatoria mancante: LH_SUBJECT_KEY (o LH_SUBJECT_KEY_FILE)`; non serve con `LH_PROFILE=demo LH_IDENTITY_MODE=header`. Il profilo `demo` con identità `oidc` la richiede perché l'hub la esige ogni volta che l'identità è oidc e ci sono endpoint del portale. Non usa `${VAR:?}`, che pretenderebbe la chiave anche per la demo: la guardia è a runtime, come per le altre chiavi.

  ```bash
  export LH_SUBJECT_KEY="$(openssl rand -base64 32)"
  ```

**Rotazione: cambiare la chiave non ricollega nulla.** `subjectRef` è una funzione della chiave. Se la chiave cambia, ogni HMAC calcolato da un token diventa diverso da quello salvato in `member_identity` e nelle proiezioni dei servizi: nessun token si risolve più nel proprio membro (404 o 409 secondo la lookup) e nessun processo ricollega da solo i riferimenti vecchi a quelli nuovi, perché il `sub` non è nelle proiezioni degli altri servizi (member-service conserva `iss` e `sub` in `member_identity`: il ricalcolo è possibile solo lì, con il job futuro). La continuità di `subjectRef` si spezza per tutti i membri già registrati, anche se i `memberId`, i saldi e i dati restano intatti. Vale lo stesso per la perdita della chiave: equivale a una rotazione. Durante un rilascio progressivo con due chiavi diverse le repliche non concordano sul membro di uno stesso token. Il ricalcolo e la ripubblicazione dei `subjectRef` sono un job di member-service **non ancora disponibile** (fuori da M8.10f, Q-552): finché non esiste, la chiave si tratta come immutabile, con una copia di riserva nel secret manager, e un cambio è una migrazione pianificata (finestra di manutenzione, ricalcolo, ripubblicazione), non un'operazione di routine. A differenza della chiave delle sessioni del BFF (`LH_WEB_SESSION_KEY`), il cui cambio chiude solo le sessioni aperte, qui il danno è sui dati.

## 17. Vetrina enterprise ospitata (Fase 2, F2-DIST-09, ADR-049)
La demo di questo documento gira nel profilo `demo` e non cambia. Per far vedere online anche il profilo `enterprise` esiste, a parte, una **vetrina enterprise**: una seconda installazione separata, con database, bus, segreti, realm e sessioni propri, in `LH_PROFILE=enterprise` e `LH_MODE=external`, costruita con lo stesso compose di riferimento (§15) più l'overlay di vetrina, in un GitHub Codespace del repository acceso su richiesta (ADR-050; bus proprio, Q-616). Il TLS pubblico è quello dell'inoltro delle porte di GitHub; l'uso si blocca a fine quota gratuita, quindi resta a costo zero (Q-660). Non è HA, non è sempre accesa (TOBE-012), usa solo dati fittizi (§11) e parte vuota a ogni codespace nuovo, senza backup (Q-663). Il Demo Hub non cambia il proprio profilo: HUB-01 mostra un pulsante verso la vetrina solo se `LH_HUB_ENTERPRISE_URL` (§8) è valorizzata, e HUB-02 della vetrina riporta alla demo con `LH_HUB_DEMO_URL`. I segreti della vetrina si generano nel codespace e non stanno mai nel repository, nei log o nel browser (regola 20). Il realm è quello di `deploy/idp/realm.json` più l'overlay `deploy/idp/vetrina/realm-vetrina-overlay.json`, applicato da `apply-overlay.sh`: registrazione chiusa (Q-619, decisa il 2026-09-30), nessuna credenziale né utente di prova, account operatore nominativi con MFA (Q-618, decisa il 2026-09-30) e il client `lh-cli` (Device Authorization Grant) per la CLI dell'operatore (Q-626, decisa il 2026-09-30); `check-realm` ne verifica le proprietà. **Overlay e runbook (M8.14c).** `deploy/vetrina/` contiene l'overlay del compose di riferimento (`compose.vetrina.yml`), il reverse proxy (nel codespace in HTTP dietro l'inoltro delle porte di GitHub, Q-661), TLS verso Kafka e Postgres con una CA locale generata nel codespace (Q-621), i segreti solo da file `0600` (regola 20), `vetrina.sh` per provisioning, controlli preliminari, avvio e azzeramento (Q-663), e il runbook per il codespace (`deploy/vetrina/README.md`). Il passo della configurazione di programma resta interattivo dopo ogni azzeramento (Q-630). Decisione, limiti e alternative scartate: ADR-049 e, per l'hosting, ADR-050; dettagli decisi in Q-617…Q-624 e Q-626 (il 2026-09-30 e il 2026-10-01), dettagli dell'hosting su Codespaces decisi in Q-660…Q-663 (il 2026-10-01); piano delle fette: `docs/18` M8.14.
