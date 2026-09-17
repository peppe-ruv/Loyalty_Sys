# Guida allo sviluppo · Development guide

> 🇮🇹 Tutto ciò che serve per compilare, eseguire, testare ed estendere la piattaforma in locale, con le
> convenzioni che rendono il codice uniforme. Pensata per sviluppatori senior: dice anche *perché* una convenzione
> esiste e cosa si rompe se la si ignora.
> 🇬🇧 Everything needed to build, run, test and extend the platform locally, plus the conventions that keep the
> code uniform. Written for senior engineers: it also states *why* a convention exists and what breaks without it.

---

## 1. Prerequisiti · Prerequisites

| Strumento · Tool | Versione · Version | Note |
| --- | --- | --- |
| JDK | 21 (target 25) | Record, pattern matching, virtual threads dove utile · where useful |
| Maven | 3.9+ | Build multi-modulo · multi-module build |
| Docker + Compose v2 | 24+ | Ambiente locale con la stessa topologia della produzione · local environment mirroring production |
| Node | 20+ | BFF, sito, CMS · BFF, site, CMS |
| `kubectl`, `helm` 3.14+, `terraform` 1.9+ | — | Solo per il deploy · deployment only |
| `make` | — | Punto d'ingresso di tutti i comandi · single entry point for all commands |

---

## 2. Comandi · Commands

```sh
make build              # compila i servizi Java · build the Java services
make test               # test unitari · unit tests
make up                 # ambiente locale completo · full local environment
make down               # ferma tutto · stop everything
make seed-local         # dati di esempio · sample data
make up-observability   # Prometheus, Alertmanager, Grafana (:3005), Loki, Tempo, OTel
make up-bi              # ClickHouse (:8123), Superset (:8088)
make lint               # helm lint + terraform validate
make help               # elenco completo · full list
```

Verifica minima prima di un commit · Minimum pre-commit check:

```sh
make build && make test
cd cms && npx tsc --noEmit      # tipi del backoffice · back-office types
node --check web/bff/src/server.js
```

---

## 3. Anatomia di un servizio · Anatomy of a service

```
services/<nome>/
├── pom.xml
├── Dockerfile                        # multi-stage, a layer · multi-stage, layered
└── src/main/
    ├── java/io/loyaltyhub/<nome>/
    │   ├── domain/                   # logica pura: record, funzioni, nessun framework, nessuna I/O
    │   ├── app/                      # servizi applicativi, accesso ai dati, job
    │   ├── api/                      # controller REST /v1/...
    │   ├── messaging/                # consumatori Kafka
    │   ├── <Nome>Application.java
    │   └── <Nome>Config.java         # bean e adattatori, @ConditionalOnMissingBean
    └── resources/
        ├── application.yml           # porta, schema, Kafka, actuator, tracing, log
        └── db/migration/V*.sql       # migrazioni Flyway · Flyway migrations
```

Perché `domain/` senza framework: la logica di dominio deve essere testabile in millisecondi, senza contesto
Spring, senza database, senza Kafka. È la parte che va testata a fondo; il resto è cablaggio.
**EN** — Why `domain/` is framework-free: domain logic must be testable in milliseconds, with no Spring context, no
database, no Kafka. It is the part worth testing deeply; everything else is wiring.

Perché `@ConditionalOnMissingBean` sugli adattatori: un test sostituisce l'adattatore HTTP con un doppio
senza toccare la configurazione di produzione.
**EN** — Why `@ConditionalOnMissingBean` on adapters: a test replaces the HTTP adapter with a double without touching
production configuration.

---

## 4. Convenzioni di codice · Coding conventions

### 4.1 Java

| Regola · Rule | Dettaglio · Detail |
| --- | --- |
| Dominio con record · Records in the domain | Immutabili, senza dipendenze; funzioni pure per le decisioni · immutable, dependency-free; pure functions for decisions |
| Accesso ai dati · Data access | SQL esplicito con template JDBC; JPA solo dove serve il mapping ricco (registro) · explicit SQL with a JDBC template; JPA only where rich mapping pays off (ledger) |
| Documenti · Documents | JSONB per contesto, decisioni, valutazioni: schema flessibile senza migrazioni continue · JSONB for context, decisions, assessments: flexible schema without constant migrations |
| Adattatori · Adapters | Interfaccia nel dominio, implementazione HTTP nella `*Config`; URL da variabile d'ambiente `<SERVIZIO>_URL` con default `http://<nome>:<porta>` (stessi nomi in Compose e Helm) |
| Kafka | Consumatore per servizio e scopo, payload `byte[]`, deserializzazione dalla busta canonica; produzione con chiave `memberId` |
| Metriche · Metrics | Solo tramite la facciata condivisa (prefisso `loyalty_`, etichette a bassa cardinalità, mai identificatori personali) |
| Tempo · Time | `Instant` in UTC nel dominio; fuso locale solo per il calendario (ore di silenzio, anno di programma, cron) |
| Migrazioni · Migrations | Numerate per servizio, mai modificate dopo il merge; schema = nome del servizio |
| Espressioni · Expressions | Contesto di sola lettura per le espressioni scritte dagli utenti: niente riflessione, niente I/O |
| Errori · Errors | Problem Details con codice motivo `UPPER_SNAKE`; niente stack trace verso il chiamante |

### 4.2 TypeScript e Node

| Regola · Rule | Dettaglio · Detail |
| --- | --- |
| Collezioni del backoffice · Back-office collections | Bozze e versioni attive, campo di stato con i livelli di approvazione, descrizioni dei campi scritte per l'operatore |
| BFF | Nessuna logica di dominio: aggrega, adatta e degrada · no domain logic: aggregate, adapt, degrade |
| Degrado · Degradation | Ultimo valore in cache, nessuna azione proposta, pulsanti disabilitati: mai un errore in faccia all'utente |
| Rotte operatore · Operator routes | Prefisso dedicato e controllo di ruolo su ogni rotta · dedicated prefix and role check on every route |

### 4.3 Lingua · Language

Identificatori in inglese; commenti, Javadoc e documentazione **bilingue** (italiano ed inglese) nei file
pubblici, in inglese nel codice quando il commento è tecnico e breve. Ogni commento che implementa un requisito cita
il codice del requisito (`RF-nn`, `RI-nn`).
**EN** — Identifiers in English; comments, Javadoc and documentation **bilingual** (Italian and English) in public
files, English in code when the comment is short and technical. Any comment implementing a requirement cites its id
(`RF-nn`, `RI-nn`).

---

## 5. Test

| Livello · Level | Cosa copre · Coverage | Dove · Where |
| --- | --- | --- |
| Unitari di dominio · Domain unit | Regole, campagne con espressioni, tier set, segmenti, achievement, challenge, classifiche, ruota, istanti vincenti, motore decisionale, motore di rischio | `src/test/java` di ogni modulo · per module |
| Contratto · Contract | Serializzazione della busta canonica, compatibilità dei payload, schemi degli eventi | `services/common`, `ingress-adapters` |
| Integrazione · Integration | Outbox del registro, ciclo decisionale end-to-end, merge di identità con trasferimento, consegna con ripiego (Testcontainers: PostgreSQL + Kafka) | pianificati · planned — [`ROADMAP.md`](ROADMAP.md) |
| Carico · Load | Lancio di un concorso, picco di azioni, latenza di giocata | Prima di ogni concorso · before every contest |

Regole di igiene: un fix di bug porta il test che lo riproduce; i test del dominio usano valori "parlanti"
(un caso di spostamento impossibile usa due città distanti, non coordinate casuali); nessun test dipende dall'ordine
di esecuzione o dall'orologio di sistema (l'istante è un parametro).
**EN** — Hygiene rules: a bug fix ships with the test that reproduces it; domain tests use meaningful values (an
impossible-travel case uses two far-apart cities, not random coordinates); no test depends on execution order or the
system clock (the instant is a parameter).

---

## 6. Ricette · Recipes

### 6.1 Nuovo tipo di azione premiante · New rewarding action type

1. Definire lo schema nel backoffice (attributi tipizzati) oppure la costante del tipo nel modulo condiviso.
2. Se la produce un servizio, emetterla sulla busta canonica verso il topic delle azioni.
3. Se è un'azione "di servizio" che non deve generare decisioni o valutazioni di rischio, aggiungerla agli elenchi di
   esclusione dei relativi servizi.
4. Una campagna con trigger su evento custom la premia senza scrivere codice.

### 6.2 Nuovo effetto di campagna · New campaign effect

1. Tipo di effetto nel modello di campagna e valutazione nel motore regole.
2. Mappatura del candidato nel servizio decisionale.
3. Esecuzione dell'effetto (o consegna, se è un contatto).
4. Opzione nella policy del backoffice.
5. Test del motore e della mappatura; riga in [`FEATURES.md`](FEATURES.md).

### 6.3 Nuovo servizio · New service

1. Copiare un servizio esistente come modello (pom, Dockerfile, `application.yml`, applicazione, configurazione).
2. Porta libera successiva all'ultima assegnata; schema PostgreSQL proprio; migrazione iniziale.
3. Aggiungere il modulo al POM padre, a `docker-compose.yml`, a `values.yaml` del chart e alla matrice del workflow di
   release.
4. Aggiungere `<NOME>_URL` nell'ambiente di chi lo chiama.
5. Aggiornare [`ARCHITECTURE.md`](ARCHITECTURE.md) §3 e la struttura in [`README.md`](../README.md).

### 6.4 Nuova collezione nel backoffice · New back-office collection

1. Definire la collezione con bozze, versioni e campo di stato; descrizioni dei campi per l'operatore.
2. Registrarla nella configurazione del CMS e assegnare i permessi ai ruoli.
3. Leggerla dal servizio con il pattern standard: solo pubblicati, cache di 30 secondi, ripiego all'ultimo valore
   buono e poi al seed.
4. Nessun parametro di business in `application.yml`: se un utente deve poterlo cambiare, vive nel backoffice.

### 6.5 Nuova metrica o KPI · New metric or KPI

1. Metodo nella facciata delle metriche, etichette a bassa cardinalità.
2. Riga in [`OBSERVABILITY.md`](OBSERVABILITY.md); se serve un alert, regola in `deploy/observability/alerts/` con
   runbook corrispondente.
3. Per un KPI di BI: fatto e vista materializzata nello schema del warehouse, poi grafico nell'export dei cruscotti.

---

## 7. Diagnostica locale · Local troubleshooting

| Sintomo · Symptom | Causa probabile · Likely cause | Verifica · Check |
| --- | --- | --- |
| I saldi non si muovono · Balances do not move | Consumatore fermo o topic senza partizioni · stopped consumer or missing topic | Log del motore regole; lag del gruppo di consumo · rules-engine logs; consumer group lag |
| Azione accettata ma nessun effetto · Action accepted, no effect | Nessuna campagna pubblicata per quel tipo · no published campaign for that type | Metrica delle azioni senza campagna; simulatore · actions-without-campaign metric; simulator |
| Evento scartato · Event rejected | Schema non rispettato o chiave di idempotenza mancante · schema violation or missing idempotency key | Coda di scarto con motivo · DLQ with reason |
| Il backoffice non influenza i servizi · Back office has no effect | Documento in bozza, non pubblicato · document in draft, not published | Stato del documento; cache di 30 s · document status; 30-second cache |
| Nessuna azione proposta · No action proposed | Vincolo di policy (consenso, ore di silenzio, cooldown) · policy constraint | Decision log: codice di scarto · decision log: rejection code |
| Compose lento all'avvio · Slow Compose start | Kafka e PostgreSQL in avvio · Kafka and PostgreSQL warming up | `docker compose ps`, attendere i controlli di integrità · wait for health checks |

---

## 8. Prestazioni: cosa guardare · Performance: what to watch

| Punto caldo · Hot spot | Sintomo · Symptom | Leva · Lever |
| --- | --- | --- |
| Ingestione · Ingestion | Latenza p95 in crescita · rising p95 | Repliche, batching del producer, pool del database |
| Motore regole · Rules engine | Lag del consumo in crescita · growing consumer lag | Repliche ≤ partizioni, concorrenza del listener, latenza dei servizi chiamati |
| Registro · Ledger | Contesa sulle scritture · write contention | Transazioni brevi, indici sulle chiavi di ricerca, pool dimensionato sul database |
| Customer 360 | Letture lente · slow reads | Documento per membro, cache, ricalcolo notturno delle finestre |
| Concorsi · Contests | Contesa al lancio · launch contention | Lock di riga breve, sala d'attesa virtuale oltre soglia |
| Warehouse | Query lente · slow queries | Viste materializzate invece di query ad hoc, partizioni mensili |

---

## 9. Definizione di "fatto" · Definition of done

Una modifica è completa quando:
**EN** — A change is complete when:

1. Compila e passa i test · it builds and tests pass;
2. Ha un test che copre il comportamento nuovo o il bug corretto · it has a test covering the new behaviour or the fixed bug;
3. Rispetta le regole non negoziabili di [`../CLAUDE.md`](../CLAUDE.md) §3 · it respects the invariants in [`../CLAUDE.md`](../CLAUDE.md) §3;
4. Aggiorna la documentazione nello stesso commit (funzionalità → [`FEATURES.md`](FEATURES.md); architettura →
   [`ARCHITECTURE.md`](ARCHITECTURE.md); scelta → nuovo ADR; API o eventi → contratti; alert → runbook) ·
   it updates documentation in the same commit;
5. Non introduce dati personali in log, metriche, tracce, eventi o warehouse · it introduces no personal data into
   logs, metrics, traces, events or the warehouse;
6. Non introduce parametri di business in file di configurazione tecnica · it introduces no business parameters into
   technical configuration files.
