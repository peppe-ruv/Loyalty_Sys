# 11 — Deploy a costo zero

Obiettivo: una demo **pubblica, accendibile su richiesta, che da spenta costa zero**, creata e aggiornata il più possibile da Claude (connettori Vercel, Render, Neon) senza carta di credito. I topic Kafka **restano** (ADR-004): il costo zero si ottiene con servizi che dormono, non togliendo il bus.

> I limiti dei piani gratuiti cambiano. Valori verificati il **18/09/2026**; fonti al §13. Prima di M1 ricontrollare.

## 1. Mappa dei fornitori

| Componente | Fornitore (piano) | Limiti che contano | Da spento | Creazione |
|---|---|---|---|---|
| `web` Next.js | **Vercel** Hobby | solo uso **non commerciale**; funzioni serverless senza connessioni lunghe (→ SSE diretto a insight) | 0 | connettore Vercel o import del repo |
| 8 microservizi | **Render** Free Web Service (Docker) | 512 MB / 0,1 CPU ciascuno; **sospensione dopo 15 min** senza traffico HTTP in ingresso; **750 ore-istanza/mese per workspace**; nessun background worker gratuito; nessuna rete privata tra servizi free | 0 | `render.yaml` (Blueprint) |
| PostgreSQL | **Neon** Free | 0,5 GB; **100 CU-ore/mese**; scale-to-zero dopo 5 min | 0 | connettore Neon |
| Kafka | **Aiven** Free | **5 topic × 2 partizioni**, 250 KiB/s, retention 3 giorni; **si spegne per inattività e va riacceso a mano** dalla console; creazione solo da console | 0 | **manuale** (una tantum) |
| CI | GitHub Actions | gratuito su repo pubblico | 0 | file nel repo |
| Registro immagini (piano B) | GHCR | gratuito su repo pubblico | 0 | workflow |

**Conti da tenere a mente**
- 750 ore ÷ 8 servizi ≈ **93 ore di demo accesa al mese** (tutti e 8 svegli). Più che sufficiente, a patto di **non** usare pinger esterni.
- Neon: a 0,25 CU, 100 CU-ore ≈ 400 ore di DB acceso. Un keep-alive esterno 24/7 lo esaurirebbe (720 ore): per questo il keep-alive è solo "a scheda aperta" (`docs/07 §8`).
- Kafka: 5 topic = esattamente i nostri 5. **Nessun topic di retry, nessun topic per servizio** (il retry è in-process, `docs/04 §5`).

## 2. Ordine di messa in opera (M1)
1. **Aiven** (manuale, §3) → variabili `KAFKA_*`.
2. **Neon** (connettore, §4) → `DB_URL`, `DB_URL_DIRECT`, `DB_USER`, `DB_PASSWORD`.
3. **Render**: Blueprint da `render.yaml` (§7) → gruppo di variabili `lh-shared` compilato coi valori dei passi 1–2 → primo deploy dei 4 servizi del core loop (gli altri si aggiungono per milestone).
4. **Vercel**: progetto con root `web/`, variabili §8 con gli URL `*.onrender.com`.
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
Note: da Spring Boot 4.1 il jarmode `layertools` non esiste più → si usa `-Djarmode=tools … extract --layers`. `finalName` del jar = nome del modulo (nel parent POM). Un `.dockerignore` alla radice esclude `web/node_modules`, `**/target`, `.git`, `docs`.
Ottimizzazione successiva (non in M1): cache AOT / CDS generata in build con un avvio di addestramento (`-XX:AOTCacheOutput`), attesa riduzione del tempo di avvio del 30–40 %.

## 6. JVM e Spring su 512 MB / 0,1 CPU
```
JAVA_OPTS=-XX:+UseSerialGC -XX:MaxRAMPercentage=65 -Xss512k -XX:TieredStopAtLevel=1
          -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=128m -Dfile.encoding=UTF-8
```
Profilo `free` (`docs/06 §6`): inizializzazione lazy (con `@Lazy(false)` su listener e scheduler), virtual thread, Tomcat `threads.max=20`, JMX off, springdoc attivo ma UI Swagger disattivata (`springdoc.swagger-ui.enabled=false`; resta `/v3/api-docs`), log a livello `INFO` in JSON.
**Deploy e sospensione.** Se la sospensione per inattività arriva mentre Render avvia la nuova versione, cadono entrambe le istanze e il deploy finisce in `update_failed` ("Port scan timeout"), lasciando in linea la versione precedente. Il workflow `.github/workflows/deploy-keepalive.yml` interroga la liveness dell'hub ogni 45 s per 20 minuti dopo ogni push su `main` che cambia il backend (URL nella variabile di repository `LH_HUB_URL`, con ripiego sull'hub attuale).

Attese oneste: **avvio a freddo 60–150 s** per servizio su 0,1 CPU; il Demo Hub lo dichiara. Liveness: `/actuator/health/liveness` **senza** dipendenze esterne (un DB addormentato non deve far riavviare il servizio); Kafka e DB stanno solo nella readiness e nel dettaglio di `/actuator/health`.

**Conseguenza della sospensione** (RNF-06): un servizio addormentato non consuma. Al risveglio recupera dal proprio offset (retention 3 giorni). Per questo "Accendi la demo" sveglia **tutti** i servizi, e l'UI mostra "in elaborazione" finché il fatto non arriva.

## 7. `render.yaml` (Blueprint, alla radice)
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
| `LH_CONSUMER_RETRY_BACKOFF_MS` | servizi | `loyaltyhub.consumer.retry-backoff-ms` (default 1000,5000,15000) |
| `LH_CORS_ALLOWED_ORIGINS` | **insight** | URL Vercel (+ `http://localhost:3000`); serve solo all'SSE |
| `LH_JOBS_ENABLED` | servizi | `true`; `false` per spegnere gli scheduler |
| `LH_APPROVAL_ENABLED` | campaign, reward, gamification, engagement | mappa `loyaltyhub.approval.enabled`; `false` fino a M7 |
| `LH_SVC_INGESTION_URL` … `LH_SVC_INSIGHT_URL` (8) | **web** (server) | `https://lh-wallet.onrender.com`; locale `http://localhost:8084` |
| `NEXT_PUBLIC_LH_INSIGHT_URL` | **web** (browser) | URL pubblico di insight per l'SSE |
| `NEXT_PUBLIC_REPO_URL` | web | link nel Demo Hub |
| `LH_INGESTION_URL` | **insight** | solo per *riprocessa* DLQ (M7) |

File `.env.example` alla radice e in `web/` con tutte le chiavi e nessun valore reale.

## 9. Sviluppo locale — `deploy/docker-compose.yml`
| Servizio | Immagine | Porta | Note |
|---|---|---|---|
| `kafka` | `apache/kafka` (KRaft, nodo singolo) | 9092 | `auto.create.topics.enable=false`; i topic li crea il profilo `local` (2 partizioni, come in demo) |
| `postgres` | `postgres:17` | 5432 | DB `loyaltyhub`; volume nominato |
| `kafka-ui` | `provectuslabs/kafka-ui` | **8090** | ispezione topic |
| 8 servizi + `web` | build locale | 8081–8088, 3000 | solo con `--profile all` |
Limiti di memoria nel compose (`mem_limit: 512m`, `cpus: 0.5`) per scoprire presto i problemi del piano gratuito. `scripts/wake.sh <base-url-web>` e `scripts/smoke.sh` funzionano identici in locale e in demo.

## 10. CI — `.github/workflows/ci.yml`
| Job | Passi |
|---|---|
| `backend` | Temurin 25 · cache Maven · `./mvnw -B verify` (Testcontainers: Kafka + Postgres) |
| `web` | pnpm · `pnpm lint && pnpm typecheck && pnpm test && pnpm build` |
| `seed` | `node scripts/check-seed.mjs` |
| `contracts` | valida `contracts/examples/*` contro gli schemi |
| `docker` (solo su `main`, path-filter) | build delle immagini toccate (senza push, salvo piano B) |
| `e2e` (manuale, `workflow_dispatch`) | `docker compose --profile all up` + Playwright + `smoke.sh` |
Nessun segreto in CI tranne, nel piano B, il deploy hook di Render.

## 11. Sicurezza di una demo senza login
- Gli endpoint sono **pubblici e senza autenticazione**: è un PoC con **soli dati fittizi**. Non inserire mai dati reali; dominio e-mail `example.org`.
- `/v1/demo/**` esiste solo col profilo `demo`; il reset è richiamabile da chiunque conosca l'URL: accettato per la demo, dichiarato nel Demo Hub. Mitigazione opzionale P1: header `X-LH-Demo-Key` verificato dai servizi e aggiunto dal proxy (`LH_DEMO_KEY`).
- Limite di frequenza in ingestion (60 eventi/min per IP) per non saturare i 250 KiB/s del Kafka gratuito.
- Webhook: solo `https`, blocco di indirizzi privati (anti-SSRF).
- Segreti solo nei pannelli dei fornitori; `gitleaks` in CI.
- `robots.txt` con `Disallow: /`.

## 12. Checklist di rilascio della demo
**Prima** — [ ] CI verde su `main` · [ ] migrazioni Flyway provate su DB vuoto e su DB della release precedente · [ ] `check-seed` verde · [ ] variabili nuove aggiunte a `render.yaml`, `.env.example` e §8 · [ ] Kafka Aiven acceso, 5 topic presenti.
**Durante** — [ ] deploy Render dei soli servizi toccati · [ ] deploy Vercel · [ ] `scripts/wake.sh` finché 10/10 · [ ] `scripts/smoke.sh` verde (azione → punti ≤ 15 s a servizi svegli).
**Dopo** — [ ] Demo Hub 10/10 · [ ] percorso consigliato eseguito a mano una volta · [ ] `docs/14` aggiornato · [ ] se lo schema di un evento è cambiato: reset dati da BO-30.
**Si torna indietro se** — smoke rosso dopo 2 tentativi · un servizio va in OOM (riavvii in serie su Render) · DLQ che cresce dopo il deploy. Come: *Rollback* del servizio su Render alla build precedente; le migrazioni sono solo additive (mai `DROP` in una release: si rimuove nella successiva).

## 13. Fonti dei limiti (verificate il 18/09/2026)
- Aiven, piano gratuito Kafka: `https://aiven.io/docs/products/kafka/free-tier/kafka-free-tier`
- Render, istanze gratuite: `https://render.com/docs/free`
- Neon, piani: `https://neon.com/docs/introduction/plans`
- Vercel, uso corretto del piano Hobby: `https://vercel.com/docs/limits/fair-use-guidelines`
- Spring Boot, estrazione a strati con `jarmode=tools`: documentazione di riferimento "Packaging OCI Images / Dockerfiles".
