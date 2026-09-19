# 15 — Domande aperte

Qui finiscono i dubbi che le specifiche non risolvono. Regola per l'agente (`CLAUDE.md §6`): **non fermarsi** su una domanda non bloccante — applicare il *default proposto*, marcare il codice con `// SPEC-GAP: Q-nn` e proseguire. Fermarsi solo sulle domande marcate **BLOCCANTE**.

Stati: `APERTA` · `DECISA` (riportare la decisione e, se architetturale, l'ADR) · `SUPERATA`.

## Domande del proprietario del progetto

| ID | Domanda | Default proposto (in uso finché non si decide) | Impatto | Blocca | Stato |
|---|---|---|---|---|---|
| Q-01 | Quale licenza? | **Apache-2.0** (ADR-022). Alternativa: AGPL-3.0 | file `LICENSE`, intestazioni, README | M0.1 (basta confermare) | APERTA |
| Q-02 | Nome del nuovo repository e dell'organizzazione GitHub? | `loyalty-hub`, repo pubblico sotto l'account personale | URL nel README, nel Demo Hub, nei nomi delle immagini | M0.1 | APERTA |
| Q-03 | Il brand demo **"Club Aurora"** va bene? (nome, colori, tono dei testi) | sì; è tutto in `seed/theme.json` e `seed/contents.json`, sostituibile senza toccare il codice | solo dati | — | APERTA |
| Q-04 | Autenticazione verso il Kafka gratuito: certificato client (`SSL_PEM`) o `SASL_SSL`? | `SSL_PEM` (predefinita dal fornitore); il codice supporta entrambe | variabili d'ambiente | M1.8 | APERTA |
| Q-05 | Il piano gratuito di Vercel è per uso **non commerciale**: la demo resta personale/open source? | sì. Se la demo servirà a fini aziendali → piano a pagamento o altro hosting statico | hosting del web | M1.8 | APERTA |
| Q-06 | Proteggere gli endpoint demo (reset, job) con una chiave condivisa? | no in M1 (dichiarato nel Demo Hub); `X-LH-Demo-Key` come P1 (`docs/11 §11`) | proxy + filtro in `lh-common` | — | APERTA |
| Q-07 | Lingua dell'interfaccia: solo italiano o anche inglese per il pubblico open source? | interfaccia solo in italiano, con dizionario unico pronto a essere tradotto; codice, API ed eventi in inglese; **README in italiano con una sezione breve in inglese** | `lib/i18n`, README | — | APERTA |
| Q-08 | Soglia oltre la quale una campagna richiede l'approvazione legale | budget > 100 000 punti o `requiresLegal=true` | policy in M7 | — | APERTA |
| Q-09 | Dominio applicativo dei dati demo: multiutility (bolletta digitale, autolettura) o generico retail? | multiutility **immaginaria**, senza alcun riferimento ad aziende reali; i tipi azione sono dati, quindi sostituibili | `seed/event-types.json`, `seed/campaigns.json` | — | APERTA |
| Q-10 | Serve un dominio personalizzato per la demo? | no: `*.vercel.app` e `*.onrender.com` | CORS, variabili | — | APERTA |

## Domande tecniche da verificare in corso d'opera

| ID | Domanda | Default proposto | Quando si verifica | Stato |
|---|---|---|---|---|
| Q-20 | Tempo reale di avvio di un servizio Spring Boot 4.1 su 0,1 CPU / 512 MB | atteso 60–150 s; oltre 180 s si attiva la cache AOT/CDS (`docs/11 §5`) | M0.5 (misurare con `docker run --cpus 0.1 -m 512m`) e M1.8 | APERTA |
| Q-21 | I minuti di build gratuiti di Render bastano per 8 immagini Docker? | sì con `buildFilter`; altrimenti piano B con immagini su GHCR (`docs/11 §7`) | M1.8, poi a ogni milestone | APERTA |
| Q-22 | Il client Kafka accetta i PEM in linea (`ssl.keystore.type=PEM`) con la chiave fornita, o serve la conversione PKCS#8? | convertire sempre in PKCS#8 senza passphrase | M1.8 | APERTA |
| Q-23 | Il pooler di Neon (modalità transazione) è compatibile con tutte le query previste (`FOR UPDATE SKIP LOCKED`, advisory lock `xact`)? | sì; Flyway usa la connessione diretta | M1.8 | APERTA |
| Q-24 | Con 2 partizioni per topic e `concurrency=2`, più istanze dello stesso servizio non servono: confermare che il piano gratuito non ne avvii più d'una | una sola istanza per servizio | M1.8 | APERTA |
| Q-25 | Un consumer che dorme più di 3 giorni perde eventi (retention Kafka). Serve un meccanismo di riallineamento? | no nel PoC: lo stato sta nei DB dei proprietari; per gli snapshot, `POST /v1/demo/reset` riallinea tutto. Nel target: retention lunga o topic compattati per gli snapshot | M2 | APERTA |
| Q-26 | L'SSE diretto verso insight regge il passaggio attraverso il proxy del fornitore (buffering, timeout a 100 s)? | `heartbeat` ogni 15 s + `X-Accel-Buffering: no`; in caso contrario polling | M2.2 | APERTA |
| Q-27 | Librerie da confermare su Boot 4.1: `springdoc-openapi`, validatore JSON Schema (`networknt`), ULID | scegliere l'ultima versione compatibile; se manca, alternativa minima scritta a mano (ULID) | M0.2 | APERTA |
| Q-28 | Generare i tipi TypeScript dall'OpenAPI dei servizi invece di scriverli a mano? | a mano fino a M4; poi valutare `openapi-typescript` in CI | M4 | APERTA |

## SPEC-GAP segnalati durante lo sviluppo
_(vuoto: l'agente aggiunge qui le voci `Q-40+` con file, riga, scelta fatta e motivo)_

| ID | Dove | Dubbio | Scelta conservativa adottata | Stato |
|---|---|---|---|---|
| Q-40 | `libs/lh-common` test (`docs/06 §9`) | La specifica indica Testcontainers (Kafka + Postgres), ma nell'ambiente di sviluppo il pull delle immagini Docker è negato dalla policy del proxy (docker.io/ECR/GHCR/Quay → 403). | Test d'integrazione con **Kafka in-JVM** (`EmbeddedKafka` KRaft, `spring-kafka-test`) e **Postgres reale in-process** (Zonky `embedded-postgres`), entrambi da Maven Central. Stesso comportamento verificato (outbox, idempotenza, DLQ, migrazioni Flyway). Se in CI il registry Docker è raggiungibile si può tornare a Testcontainers senza cambiare il codice di produzione. | APERTA |
| Q-41 | ambiente / `pom.xml` | JDK locale predefinito 21, ma la spec richiede Java 25 (ADR-005). | JDK 25 (Microsoft OpenJDK 25.0.4.1 LTS) provvisto in ambiente; `pom.xml` compila con `release=25`. In CI (M0.7) va fissato `temurin`/`msopenjdk` **25**. | APERTA |
| Q-42 | `contracts/` | docs/05 §9 colloca gli esempi in `contracts/examples/`, ma `CLAUDE.md §3` indica `contracts/events/ … + examples/`. | Seguita la struttura di `CLAUDE.md §3` (`contracts/events/examples/`) — già scaffoldata in M0.1 — mantenendo il nome file `<famiglia>.<nome>.json` di §9. Da allineare i due documenti a una milestone futura. | APERTA |
