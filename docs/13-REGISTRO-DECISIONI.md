# 13 — Registro delle decisioni (ADR)

Formato compatto: **Contesto → Decisione → Conseguenze → Alternative scartate**. Stato: `ACCETTATA` salvo diversa indicazione. Un'ADR accettata **non si riscrive**: si supera con una nuova che la cita (`Supera ADR-nnn`). Questo documento vince su tutti gli altri (`CLAUDE.md §6`).

| ADR | Titolo | Stato |
|---|---|---|
| 001 | Otto microservizi a confini di dominio, in monorepo | ACCETTATA |
| 002 | Nessuna chiamata sincrona tra servizi | ACCETTATA |
| 003 | Ingestion unico produttore delle azioni; ponte fatti → azioni | ACCETTATA |
| 004 | Cinque topic; Kafka resta anche nella demo a costo zero | ACCETTATA |
| 005 | Java 25, Spring Boot 4.1.x, Maven | ACCETTATA |
| 006 | Spring Data JDBC + `JdbcClient`; niente JPA, niente Lombok | ACCETTATA |
| 007 | Un database, uno schema per servizio | ACCETTATA |
| 008 | Outbox transazionale, consumer idempotenti, retry in-process | ACCETTATA |
| 009 | CloudEvents 1.0 JSON + JSON Schema nel repo; niente schema registry | ACCETTATA |
| 010 | Identità: personas simulate ora, OIDC come target | ACCETTATA |
| 011 | Un'unica app web; nessun CMS esterno | ACCETTATA |
| 012 | Osservabilità: `insight-service` nel PoC, stack dedicato nel target | ACCETTATA |
| 013 | Single-tenant, con `lhtenant` predisposto | ACCETTATA |
| 014 | Hosting gratuito: Vercel + Render + Neon + Aiven, keep-alive solo a scheda aperta | ACCETTATA |
| 015 | Seed unico alla radice, date relative, reset idempotente | ACCETTATA |
| 016 | Piano B per Kafka: broker singolo su VM gratuita | PROPOSTA (si attiva al bisogno) |
| 017 | Doppia valuta, lotti FIFO, tier annuali con discesa morbida | ACCETTATA |
| 018 | Instant win a istanti vincenti pre-generati con seme | ACCETTATA |
| 019 | Richiesta premio come saga coreografata | ACCETTATA |
| 020 | Tempo reale via SSE diretto da insight | ACCETTATA |
| 021 | Approvazioni: macchina a stati comune, disattivabile | ACCETTATA |
| 022 | Licenza Apache-2.0 | PROPOSTA (`Q-01`) |
| 023 | Demo ospitata consolidata: un deployable `hub` + broker Kafka/Redpanda | ACCETTATA (adeguata da 024 sul broker) |
| 024 | Demo ospitata senza broker: bus a eventi in-process nel solo profilo `inproc` | ACCETTATA |
| 025 | Broker reale opzionale per la demo ospitata: Aiven Kafka (profilo `demo` senza `inproc`) | ACCETTATA |
| 026 | Profilo `enterprise`: Kubernetes con operatori, Postgres per servizio, Kafka reale | ACCETTATA |
| 027 | Identità OIDC con Keycloak come ruolo `idp` e Backend-for-Frontend | ACCETTATA |
| 028 | Cinque topic anche in `enterprise`; scala per partizioni; nessuno schema registry | ACCETTATA |
| 029 | Element Registry: tipi nel codice, istanze nei dati | ACCETTATA |
| 030 | Directus dentro l'immagine come piano di composizione (supera in parte ADR-011) | ACCETTATA |
| 031 | `experience-service` con composizione versionata (notify-and-pull) | ACCETTATA |
| 032 | Dati personali mai sul bus | ACCETTATA |
| 033 | Multilingua a tre livelli con prefisso URL | ACCETTATA |
| 034 | Strategia di collaudo: journey, invarianti, matrice, carico, installazione | ACCETTATA |
| 035 | Agente regolamento in `assistant-service`, solo modelli self-hosted, human-in-the-loop | ACCETTATA |
| 036 | Resilienza e obiettivi di servizio | ACCETTATA |
| 037 | Distribuzione a immagine unica con ruoli e modalità | ACCETTATA |
| 038 | Politica di rilascio, aggiornamento e supply chain | ACCETTATA |
| 039 | Design system a token con profili `brand` e `pa`; accessibilità come gate | ACCETTATA |
| 040 | Mintlify unico sito di documentazione, docs-as-code con diagrammi Mermaid | ACCETTATA |
| 041 | Governance del repository: `main` protetto e ADR solo in aggiunta | ACCETTATA |
| 042 | Zero trust e sicurezza applicativa verificata dal software | ACCETTATA |
| 043 | Audit unificato: modifiche di backoffice/CMS/IdP e attività del membro | ACCETTATA |
| 044 | Il prodotto come fornitore di un'azienda ISO/IEC 27001 | ACCETTATA |
| 045 | Economia del programma, punteggi esterni, cataloghi esterni, missioni | ACCETTATA |
| 046 | OpenAPI con springdoc in lh-common (Q-341) | ACCETTATA |

---

### ADR-001 — Otto microservizi a confini di dominio, in monorepo
**Contesto.** Il progetto precedente era cresciuto senza confini chiari (collezioni di backoffice senza alcun lettore). Serve ordine, e serve che l'architettura a microservizi + Kafka sia *visibile*.
**Decisione.** Otto servizi (ingestion, member, campaign, wallet, reward, gamification, engagement, insight), ciascuno proprietario esclusivo dei propri dati; un solo repository con `libs/lh-common` condiviso.
**Conseguenze.** + confini verificabili, una scheda per servizio, deploy indipendenti. − otto JVM da 512 MB sul piano gratuito, avvii lenti.
**Scartate.** Monolite modulare (più economico, ma non dimostra il modello a eventi richiesto); 15+ servizi fini (ingestibile a costo zero); un repo per servizio (attrito inutile per un PoC).

### ADR-002 — Nessuna chiamata sincrona tra servizi
**Contesto.** I servizi gratuiti dormono e si svegliano in minuti: una catena HTTP tra servizi fallirebbe a cascata.
**Decisione.** I servizi comunicano **solo via Kafka**. Ciò che serve di un altro dominio si tiene in uno **snapshot locale** alimentato dai fatti. Le viste composte le fa il frontend (chiamate parallele, degrado per sezione).
**Eccezione 1.** *Riprocessa* DLQ: insight chiama `ingestion POST /v1/events`, solo su comando umano.
**Conseguenze.** + resilienza al sonno, disaccoppiamento reale. − consistenza eventuale ovunque (UI "in elaborazione"), dati duplicati negli snapshot.
**Scartate.** API gateway con aggregazione (un punto in più che dorme); chiamate REST con circuit breaker (complessità senza beneficio in demo).

### ADR-003 — Ingestion unico produttore delle azioni; ponte fatti → azioni
**Contesto.** Requisito: le azioni interne (tier-up, vincita, badge…) devono rientrare **dallo stesso topic** delle esterne, così una campagna può reagire a entrambe senza differenze.
**Decisione.** Solo `ingestion-service` scrive su `lh.actions.v1`. Consuma `lh.facts.v1` e, per i tipi in `internal_mapping`, genera l'azione corrispondente (`source=internal`, stesso `correlationId`, `lhhop+1`). Guardia anti-ciclo: `lhhop > 3` → DLQ `LOOP_GUARD`; elenco di fatti mai mappabili.
**Conseguenze.** + un solo punto di validazione, deduplica e audit degli ingressi; il motore regole ha un unico ingresso. − un salto in più (latenza) per le azioni interne.
**Scartate.** Ogni servizio pubblica le proprie azioni (validazione sparsa, cicli difficili da governare); il motore legge direttamente i fatti (due ingressi, due semantiche).

### ADR-004 — Cinque topic; Kafka resta anche nella demo a costo zero
**Contesto.** Il Kafka gratuito disponibile consente 5 topic × 2 partizioni. È già stato valutato e **scartato** un motore in-process senza Kafka.
**Decisione.** Cinque topic per **famiglia semantica** (`actions`, `effects`, `facts`, `audit`, `dlq`), non per servizio. Instradamento per `type` CloudEvents. Chiave = `memberId`. Nessun topic di retry.
**Conseguenze.** + sta nel piano gratuito, ordinamento per membro, flusso leggibile in una schermata. − ogni servizio legge (e scarta) tipi che non gli interessano; nessun isolamento di traffico per dominio. Nel target i topic si possono suddividere senza cambiare i `type`.
**Scartate.** Topic per servizio/entità (20+ topic); Redpanda/Upstash serverless (non più disponibili gratuitamente o fuori dall'API Kafka standard); bus in memoria.

### ADR-005 — Java 25, Spring Boot 4.1.x, Maven
**Contesto.** Competenze e target enterprise su Spring; Java 25 è LTS, con virtual thread maturi.
**Decisione.** Java 25, Spring Boot 4.1.x, Maven multi-modulo con wrapper. Virtual thread attivi.
**Conseguenze.** + stack attuale, avvio e memoria migliori delle generazioni precedenti. − librerie di terze parti a volte in ritardo su Boot 4: scegliere dipendenze minime.
**Scartate.** Gradle (nessun vantaggio qui, più variabilità); Quarkus/Micronaut o immagine nativa (avvio migliore, ma fuori dal requisito "microservizi Spring" e build native troppo pesanti per i minuti gratuiti). L'immagine nativa resta un'opzione futura per i servizi più piccoli.

### ADR-006 — Spring Data JDBC + `JdbcClient`; niente JPA, niente Lombok
**Contesto.** 512 MB e 0,1 CPU: Hibernate pesa su avvio e memoria; il dominio usa SQL esplicito (`SKIP LOCKED`, `FOR UPDATE`, upsert, JSONB).
**Decisione.** Aggregati semplici con Spring Data JDBC, query con `JdbcClient`, migrazioni Flyway, `record` al posto di Lombok.
**Conseguenze.** + avvio più rapido, SQL sotto controllo, nessuna magia di sessione. − più codice di mappatura.
**Scartate.** JPA/Hibernate; jOOQ (generazione del codice in build: attrito in CI e per l'agente).

### ADR-007 — Un database, uno schema per servizio
**Contesto.** Il Postgres gratuito è uno, con 0,5 GB.
**Decisione.** Un solo database; ogni servizio ha il proprio schema e la propria storia Flyway; **vietato** leggere lo schema altrui (verificato da un test che ispeziona le query per nomi di schema estranei). Nel target: un database per servizio, senza cambi di codice.
**Conseguenze.** + costo zero, isolamento logico. − isolamento non fisico; un servizio rumoroso impatta gli altri.

### ADR-008 — Outbox transazionale, consumer idempotenti, retry in-process
**Decisione.** Ogni pubblicazione passa da una tabella `outbox` scritta nella stessa transazione del cambiamento di stato; un relay (500 ms, `SKIP LOCKED`) pubblica. Ogni consumer registra `processed_event` nella stessa transazione del proprio effetto. Errori: 3 tentativi (1/5/15 s) poi DLQ.
**Conseguenze.** + nessuna perdita, nessun doppio effetto, *at-least-once* reso innocuo. − latenza aggiuntiva ≤ 500 ms per salto; tabelle da ripulire.
**Scartate.** Transazioni Kafka / exactly-once (non coprono il DB); CDC con Debezium (infrastruttura non disponibile a costo zero; è l'evoluzione naturale nel target).

### ADR-009 — CloudEvents 1.0 JSON + JSON Schema nel repo
**Decisione.** Envelope CloudEvents in modalità strutturata JSON; schemi in `contracts/events/`, esempi validati in CI, consumatori tolleranti. Niente Avro né schema registry.
**Conseguenze.** + payload leggibili nel flusso live (valore dimostrativo), zero infrastruttura. − nessuna verifica di compatibilità a runtime; messaggi più grandi.

### ADR-010 — Identità: personas simulate ora, OIDC come target
**Contesto.** Richiesta esplicita: sacrificare le login ora.
**Decisione.** Cookie persona → header `X-LH-Actor: <RUOLO>:<username>`; i servizi applicano `@RequiresRole` e scrivono l'attore in audit. Nessuna password, nessun token. Nel target: OIDC (fornitore esterno), ruoli dalle *claim*, stesso modello di autorizzazione — cambia solo il filtro che costruisce l'attore.
**Conseguenze.** + demo immediata con cambio ruolo in un clic; autorizzazione già modellata. − **nessuna sicurezza reale**: solo dati fittizi, dichiarato ovunque.
**Superata da ADR-027 (profilo `enterprise`).** Nel profilo `demo` le personas simulate restano.

### ADR-011 — Un'unica app web; nessun CMS esterno
**Contesto.** Il tentativo precedente con un CMS separato aveva prodotto entità senza lettori.
**Decisione.** Una sola app Next.js con Demo Hub, backoffice e portale. I contenuti (card, pop-up, banner, tema) sono **entità di dominio** di `engagement-service`, gestite dal backoffice unico. Regola "nessuna entità senza lettore" (`docs/02 §3`).
**Conseguenze.** + un deploy, componenti condivisi (anteprima fedele), nessuna doppia fonte di verità. − niente editor ricco né libreria media (accettato per il PoC).
**Scartate.** CMS headless; due app separate.
**Superata in parte da ADR-030 (profilo `enterprise`):** Directus come ruolo `cms` per la sola composizione.

### ADR-012 — Osservabilità: `insight-service` nel PoC, stack dedicato nel target
**Decisione.** Nel PoC un servizio consuma tutti i topic e offre flusso live, tracciati, KPI, audit, DLQ. Target: metriche/log/tracce su stack dedicato (Prometheus, Grafana, Loki, Tempo con OpenTelemetry) e analitica su ClickHouse + Superset; `insight` si riduce ad audit e tracciati funzionali.
**Conseguenze.** + la demo *mostra* l'architettura senza infrastruttura aggiuntiva. − KPI approssimati; non è un sistema di monitoraggio.

### ADR-013 — Single-tenant, con `lhtenant` predisposto
**Decisione.** Un solo tenant (`aurora`). L'estensione `lhtenant` è sempre valorizzata negli eventi ma **non** esistono colonne `tenant_id` né filtri. Il multi-tenant, se servirà, sarà per istanza.

### ADR-014 — Hosting gratuito e keep-alive solo a scheda aperta
**Decisione.** Vercel (web), Render free (8 servizi Docker da Blueprint), Neon free, Aiven free Kafka. I servizi dormono; il Demo Hub li sveglia; il keep-alive gira solo con una scheda visibile. **Nessun pinger esterno.**
**Conseguenze.** + costo zero da spenta, quasi tutto automatizzabile dai connettori. − primo avvio di minuti; Kafka da riaccendere a mano dopo lunga inattività; piano Vercel gratuito solo per uso non commerciale.
**Scartate.** VM unica sempre accesa (non "zero da spenta" e non automatizzabile dai connettori); Kubernetes gestito (costo).
**Superata da ADR-026 (profilo `enterprise`).** Resta valida per il profilo `demo` ospitato.

### ADR-015 — Seed unico alla radice, date relative, reset idempotente
**Decisione.** `seed/*.json` alla radice, incluso come risorsa in ogni servizio; date come espressioni relative a oggi; semi fissi per il casuale; `check-seed` in CI; `POST /v1/demo/reset` per servizio.
**Conseguenze.** + coerenza tra servizi per costruzione; demo sempre "fresca". − ogni servizio imbarca anche seed che non usa (pochi KB).

### ADR-016 — Piano B per Kafka (PROPOSTA)
Se il piano gratuito gestito viene meno: broker Kafka singolo in KRaft su una VM gratuita "sempre attiva" di un cloud pubblico, TLS + SASL, stessi 5 topic e stesse variabili. Costo operativo: aggiornamenti e certificati a carico nostro. Non si adotta finché il piano A regge.

### ADR-017 — Doppia valuta, lotti FIFO, tier annuali con discesa morbida
**Decisione.** `PTS` spendibili con lotti a scadenza e consumo FIFO; `STS` contatore per edizione che determina il tier. Salita immediata; a chiusura edizione `nuovo = max(guadagnato, attuale − 1)`. Il moltiplicatore di tier si applica nel wallet ai soli effetti che lo dichiarano.
**Conseguenze.** + separa "quanto posso spendere" da "quanto valgo"; la discesa non è punitiva. − due saldi da spiegare al membro (il portale parla di "punti" e "punti status").

### ADR-018 — Instant win a istanti vincenti pre-generati con seme
**Decisione.** Un istante per unità di premio, generati prima dell'avvio con seme salvato; vince la prima giocata successiva a un istante non reclamato (claim atomico `SKIP LOCKED`). Istanti visibili solo ad ADMIN/LEGAL, immutabili da `LIVE`.
**Conseguenze.** + montepremi garantito ed esatto, verificabilità, nessuna probabilità da tarare. − meno "casuale" di quanto sembri: per questo gli istanti sono riservati.
**Scartate.** Probabilità per giocata (montepremi non garantito; più difficile da certificare).

### ADR-019 — Richiesta premio come saga coreografata
**Decisione.** reward prenota lo stock ed emette `redemption.requested`; wallet spende o rifiuta; reward conferma ed evade, oppure ripristina. Timeout 10 min con compensazione se la spesa arriva tardi.
**Conseguenze.** + nessun orchestratore, coerente con ADR-002. − stati intermedi visibili all'utente; serve cura sulla compensazione.

### ADR-020 — Tempo reale via SSE diretto da insight
**Decisione.** Il browser apre un `EventSource` verso insight (CORS su origini note). Il resto passa dal proxy Next. Fallback a polling.
**Conseguenze.** + semplice, unidirezionale, passa ovunque; tiene sveglio insight mentre qualcuno guarda. − insight è l'unico servizio esposto al browser; una connessione per scheda (coda limitata per client).
**Scartate.** WebSocket (bidirezionalità inutile); SSE attraverso le funzioni serverless (limiti di durata).

### ADR-021 — Approvazioni: macchina a stati comune, disattivabile
**Decisione.** Un'unica macchina a stati in `lh-common` per campagne, premi, concorsi, contenuti; policy per tipo oggetto; `LH_APPROVAL_ENABLED=false` fino a M7 per non rallentare le milestone precedenti. La casella approvazioni è un'aggregazione lato web.
**Conseguenze.** + stesso comportamento ovunque; nessun "servizio approvazioni" centrale che accoppi i domini. − la casella dipende da tre servizi svegli (degrada per sezione).

### ADR-022 — Licenza Apache-2.0 (PROPOSTA)
Permissiva, con concessione esplicita di brevetti, comune nell'ecosistema Spring/Kafka. Alternativa: AGPL-3.0 se si vuole impedire l'offerta come servizio chiuso. Decisione del proprietario del progetto (`Q-01`).

### ADR-023 — Demo ospitata consolidata: un deployable `hub` + Redpanda single-node su Render
**Contesto.** ADR-001 vuole otto servizi come deployable distinti; ADR-004/014 davano per scontato un **Kafka gratuito gestito** (Aiven) e più servizi sui piani free. Nel 2026 quell'ipotesi non regge: Aiven ha tolto il piano free di Kafka, Upstash Kafka è dismesso, e otto servizi always-on non stanno nei free tier. Serve una demo **ospitata a costo (quasi) zero** senza riscrivere il modello a eventi. Le connessioni realmente disponibili all'agente sono Neon, Render, Vercel (non Koyeb né Aiven).
**Decisione.** *Solo per la demo ospitata* si impacchettano i **4 servizi del core loop** (ingestion, member, campaign, wallet) in un unico deployable `deploy/hub` che li avvia in un solo JVM, e si usa un **Redpanda single-node** (API Kafka) come servizio su Render. Il DB è **Neon** (un database, uno schema per servizio via `search_path` multiplo), il frontend è su **Vercel**. **Fuori dalla demo, ogni servizio resta un deployable a sé** (Dockerfile e `application.yml` invariati): la consolidazione è una *composizione*, non una fusione.
**Come resta corretto il modello.** I confini del codice non cambiano (moduli separati, ADR-001/007 rispettate a livello di schema). Per far convivere i servizi in un JVM: (a) **un gruppo consumer per servizio** (`groupId` esplicito sui `@KafkaListener`) così il fan-out sui topic condivisi resta corretto; (b) **un `EventRouter` per servizio** con i soli handler del servizio (niente più "un handler per type" globale che colliderebbe); (c) **migrazioni per schema** (`HubDatabase`, un Flyway per schema: comune `V0` + migrazioni del servizio, spostate in `db/migration/<servizio>/`); (d) **nomi bean pienamente qualificati** (classi omonime tra servizi); (e) `outbox`/`processed_event` risolti nel primo schema del `search_path` → un solo relay, idempotenza per (consumer, eventId). Verificato da `HubEndToEndIT`: un acquisto attraversa ingestion→campaign→wallet e accredita i punti col moltiplicatore di tier, tutto in un JVM.
**Conseguenze.** + demo ospitabile a costo quasi zero; niente Kafka gestito a pagamento; i 4 non-core (reward/gamification/engagement/insight) restano `DOWN` nella demo finché non vengono realizzati. − un solo `service` name (`hub`) sulle sorgenti URN in demo; un solo relay/idempotenza condivisi; Redpanda single-node non è HA (accettabile per una demo). Supera **ADR-016** (piano B Kafka su VM) e adegua **ADR-014** (l'hosting è Render+Neon+Vercel+Redpanda, non Aiven).
**Scartate.** Aiven/Upstash Kafka gestito free (non esistono più); 8+1 servizi su Render Starter (~7 $/servizio, non a costo zero); solo-locale senza hosting (perde la dimostrazione online); Koyeb (nessuna connessione pilotabile dall'agente in questa sessione).

### ADR-024 — Demo ospitata senza broker: bus a eventi in-process (profilo `inproc`)
**Contesto.** ADR-023 consolida i 4 servizi del core loop in un solo JVM per la demo ospitata e prevedeva un **Redpanda single-node** come broker. Ma su Render (l'unico hosting pilotabile dai connettori) un broker richiede un *private service a pagamento* (~7 $/mese + disco): non esiste un tier gratuito per un servizio TCP always-on, e nel 2026 non esiste più un Kafka gestito gratuito. La regola d'oro "costo zero" (CLAUDE.md §1.8) e il vincolo dei free tier impongono di evitare il broker **per la sola demo ospitata**, senza toccare il modello a eventi.
**Decisione.** Introdurre un **bus a eventi in-process** (`HubInProcessBus`) attivo *solo* nel profilo `inproc` del deployable `hub`. Poiché i 4 servizi girano nello stesso JVM, produttori e consumatori degli eventi sono nello stesso processo: il bus li collega in memoria, senza broker. Fuori da `inproc` (locale, `docker-compose`, e ovunque nel resto del modello) resta **Kafka/Redpanda reale**: la sostituzione è una *composizione di trasporto*, non un cambio di architettura. Il profilo di default dell'`hub` diventa `demo,inproc` così la demo ospitata gira su un **singolo web service gratuito** (Render free) + **Neon** (Postgres) + **Vercel** (frontend). Costo: **0 €**.
**Come resta corretto il modello.** Due soli punti di innesto, dietro le stesse astrazioni:
- **produzione**: tutto esce dall'`outbox` → `OutboxRelay` → `KafkaTemplate.send(...)` (unica via, anche l'audit). Nel profilo `inproc` il `KafkaTemplate` è quello del bus (vince su lh-common via `@ConditionalOnMissingBean`) e consegna al bus invece che al broker; l'outbox transazionale e la marcatura "pubblicato" restano identiche;
- **consumo**: a startup si registrano sul bus **tutti** i metodi `@KafkaListener` dei 4 servizi (topic + gruppo consumer risolti dall'annotazione), riusando esattamente il loro codice; i container Kafka reali restano spenti (`spring.kafka.listener.auto-startup=false`).
Il bus preserva le proprietà che contano: **fan-out per topic** (un gruppo consumer per servizio → una copia a testa, come ADR-023), **asincronia** (consegna su thread dedicato, fuori dalla transazione del relay → ogni consumer nella propria transazione), **ordine** (single-thread → FIFO, più forte del per-partizione), **ritentativi** (3 tentativi, backoff 200 ms, come l'error handler di lh-common). Idempotenza e routing per servizio invariati. Verificato da `HubInProcessEndToEndIT`: un acquisto di 130 € (feriale) accredita +162 PTS attraversando ingestion→campaign→wallet **senza alcun broker**.
**Conseguenze.** + Demo ospitata realmente a costo zero, un solo processo, nessun broker da gestire. − La demo ospitata non esercita il protocollo Kafka su rete (no partizioni reali, no offset/commit, no DLQ consumata): quella fedeltà resta al percorso locale/CI con Redpanda vero. − Il bus non è HA e non persiste i messaggi in volo (accettabile per una demo; la durabilità è comunque nell'outbox). Deroga **circoscritta** alla regola "i servizi comunicano solo via Kafka" (CLAUDE.md §1.3): vale **solo** dentro l'`hub` in profilo `inproc`, dove "tra servizi" è comunque in-process. Adegua **ADR-023** sul punto broker (il Redpanda a pagamento non è più necessario per la demo).
**Scartate.** Redpanda private service su Render (~7 $/mese: viola "costo zero"); Redpanda sidecar nello stesso container (JVM + broker non stanno in 512 MB); Kafka gestito gratuito (non esiste più nel 2026); embedded Kafka/KRaft nel JVM (peso in RAM, è uno strumento di test). Redpanda Serverless / Confluent free tier restano un'opzione a fedeltà piena se in futuro si vuole il protocollo Kafka reale online (richiede account e credenziali dell'owner).

### ADR-025 — Broker reale opzionale per la demo ospitata: Aiven Kafka (profilo `demo` senza `inproc`)
**Contesto.** L'owner (Giuseppe) ha provisionato un **Aiven Kafka** con i 5 topic (`lh.actions/effects/facts/audit/dlq .v1`) per esercitare il **protocollo Kafka reale** anche online (partizioni, offset/commit, DLQ consumata), fedeltà che ADR-024 lasciava al solo percorso locale/CI. Aiven Kafka **non è nel piano gratuito** (trial/paid): è una **deroga consapevole** a "costo zero" (CLAUDE.md §1.8), decisa dall'owner e circoscritta alla demo ospitata.
**Decisione.** L'hub supporta **entrambe** le modalità senza cambi di codice, scelte dal profilo: `demo,inproc` (default, bus in-process, ADR-024) **oppure** `demo` (broker reale). Con `demo` (senza `inproc`) partono i `@KafkaListener` reali (un gruppo consumer per servizio) e l'outbox pubblica sul broker. La sicurezza è **SSL con certificato client** (`KAFKA_SECURITY=SSL_PEM`): CA + cert + chiave passati in **base64 via env** (`KAFKA_SSL_CA_B64/CERT_B64/KEY_B64`, nessun file su disco), tradotti da `LhKafkaSecurity` in `ssl.truststore.type=PEM` + keystore PEM. Bootstrap da `SPRING_KAFKA_BOOTSTRAP_SERVERS`. I 5 topic sono **pre-creati su Aiven** (fuori dal profilo `local` non si auto-creano; `HubKafkaTopics` prova a dichiararli ma i topic esistenti danno `TopicExistsException` ignorata).
**Conseguenze.** + La demo ospitata può girare su Kafka vero end-to-end quando serve (fedeltà piena del protocollo). + Nessuna modifica al codice: solo profilo + env. − Costo non nullo (Aiven Kafka a pagamento): resta un **interruttore**, il default `inproc` a costo zero rimane valido e si può tornare indietro cambiando il solo profilo. − SASL_SSL è previsto ma il ramo va completato col truststore CA prima dell'uso (non necessario con SSL_PEM). Non tocca ADR-004 (5 topic fissi) né il modello a eventi.

### ADR-026 — Profilo `enterprise`: Kubernetes con operatori, Postgres per servizio, Kafka reale
**Contesto.** La Fase 1 ha ottimizzato per il costo zero (ADR-014, 023, 024, 025). La Fase 2 deve produrre un prodotto installabile da qualunque azienda, sicuro e resiliente.
**Decisione.** Introdurre il profilo `enterprise`: chart Helm con Strimzi e CloudNativePG di default e servizi gestiti come `values` alternativi; Postgres per servizio (compimento di ADR-007); Kafka reale con 3 broker; ambiente di riferimento EKS eu-south-1. Il profilo `demo` resta un profilo di deploy della stessa immagine. Supera ADR-014 per `enterprise`; attua lo stack dedicato previsto da ADR-012.
**Conseguenze.** + Portabilità reale (operatori); + un solo codice per demo e prodotto. − Il team deve saper operare gli operatori; alternativa gestita documentata.
**Scartate.** Servizi gestiti di default (lega al cloud); doppio artefatto demo/prodotto.

### ADR-027 — Identità OIDC con Keycloak come ruolo `idp` e Backend-for-Frontend
**Contesto.** ADR-010 rinviava l'identità reale. L'immagine unica deve funzionare anche senza IdP esterno. La *silent authentication* via iframe non funziona più con i cookie di terze parti bloccati e lascerebbe i token nel browser.
**Decisione.** Keycloak (Apache 2.0) come ruolo `idp` opzionale della stessa immagine, realm as code, broker verso IdP aziendale e LDAP. Portale e backoffice seguono il pattern **BFF**: il server Next.js è client confidential (Authorization Code + PKCE), il browser ha solo un cookie di sessione `__Host-` `HttpOnly`, i token restano lato server con rotazione del refresh, CSRF con `SameSite` + `Origin` + header, back-channel logout. Passkey per i membri, MFA per gli operatori. Widget: token exchange RFC 8693 dal backend dell'app ospite. Fonti e job: client credentials con `private_key_jwt` o mTLS. Servizi come resource server JWT (`aud=hub`); `ActorContext` dal token; `X-LH-Actor` solo in `demo`. Directus usa lo stesso IdP. Supera ADR-010.
**Conseguenze.** + Nessun token nel JavaScript; + nessun codice di password nostro; + funziona con i browser moderni. − Il ruolo `web` diventa stateful (sessioni nel suo database; più repliche condividono lo store); − +~500 MB RAM per `idp` nell'appliance.
**Scartate.** Silent authentication via iframe; SPA con token in memoria e refresh nel browser; IdP scritto da noi; solo OIDC esterno.

### ADR-028 — Cinque topic anche in `enterprise`; scala per partizioni; nessuno schema registry
**Contesto.** ADR-004 nasceva dal limite del Kafka gratuito, ma i nomi dei topic sono un contratto tra servizi.
**Decisione.** I 5 topic restano; in `enterprise` partizioni (default 12, chiave `memberId`) e concorrenza configurabili; retention lunga su `facts`/`audit` (possibile grazie ad ADR-032). Contratti come JSON Schema nel repo con controllo di compatibilità additiva contro l'ultimo tag in CI; nessuno schema registry. Conferma ADR-004 e ADR-009 con motivazione aggiornata.
**Conseguenze.** + Nessun cambio di contratto; + fan-out invariato. − Un consumer lento rallenta il suo gruppo: si risolve con partizioni e repliche.
**Scartate.** Topic per dominio (nuova ADR se il lag lo imporrà); Apicurio (componente in più senza beneficio finché gli schemi sono nel repo).

### ADR-029 — Element Registry: tipi nel codice, istanze nei dati
**Contesto.** Serve cambiare numero e contenuto degli elementi del portale senza rilasci, con i tipi definiti a priori.
**Decisione.** `registry/elements.yaml` + JSON Schema come unica fonte dei tipi (blocchi, pagine, navigazione, tema, strutturali); da esso si generano lo snapshot Directus, i tipi TS e la documentazione; la CI blocca il drift. Ogni tipo dichiara renderer, sorgenti dati (API pubbliche), `paRequired`, criteri WCAG.
**Conseguenze.** + CMS e codice non divergono; + i widget riusano gli stessi renderer. − Un nuovo tipo richiede un rilascio (voluto).
**Scartate.** Schema disegnato a mano in Directus (deriva); blocchi generici "HTML libero" (accessibilità e sicurezza non verificabili).

### ADR-030 — Directus dentro l'immagine come piano di composizione (supera in parte ADR-011)
**Contesto.** ADR-011 escludeva un CMS esterno per evitare entità senza lettore. La Fase 2 richiede un editor di composizione data-driven.
**Decisione.** Directus come ruolo `cms` della stessa immagine, versione bloccata, schema generato dal Registry, SSO, ruoli Editor/Marketing/Publisher. Ruolo **C1**: il dominio (campagne, premi, concorsi, livelli, obiettivi, template) resta nel backoffice; Directus compone pagine e blocchi e riferisce il dominio tramite collezioni `ref_*` in sola lettura alimentate dai fatti. Il portale non legge mai Directus a runtime (ADR-031). Licenza BSL/MSCL dichiarata a chi installa (README, wizard, `lh doctor`).
**Conseguenze.** + Marketing crea una seconda ruota o un'altra pagina senza rilascio; + un solo proprietario per entità; − onere di licenza e di aggiornamento trasferito all'installatore; − Studio non sotto il nostro controllo (accessibilità dichiarata come limitazione).
**Scartate.** C2 console unica in Directus (riscrittura dei form); C3 Directus system of record (validazioni asincrone, doppio schema); Payload MIT (scelta del proprietario per Directus).

### ADR-031 — `experience-service` con composizione versionata (notify-and-pull)
**Contesto.** Il portale deve restare disponibile e veloce anche con il CMS spento o compromesso; la selezione dei contenuti dipende da dati loyalty.
**Decisione.** engagement-service diventa `experience-service`: mantiene contenuti, inbox e selezione e acquisisce la composizione versionata: Directus notifica (`POST /v1/cms/notify`, segreto), il servizio estrae con token a scope minimo, valida contro Registry e profilo, crea una versione immutabile e la attiva; rollback = riattivare la precedente; `block_reference` per gli utilizzi. Il portale legge `GET /v1/portal/pages/{slug}`.
**Conseguenze.** + Directus fuori dal percorso critico; + rollback immediato; + validazione centralizzata. − Doppia copia della composizione (sorgente e versione attiva), per scelta.
**Scartate.** Portale che legge Directus (ISR) — dipendenza a runtime; webhook con payload fidato.

### ADR-032 — Dati personali mai sul bus
**Contesto.** `member.registered/updated` trasportano nome, e-mail, data di nascita, città; con retention lunga e topic compattati l'anonimizzazione non può ripulire il bus.
**Decisione.** Campi `x-lh-pii` negli schemi; test di contratto che vieta PII negli eventi pubblicati; `member.*` in versione `:2` con soli dati non identificativi (`birthYear`, `province`, `locale`, attributi `pii:false`); segnaposto risolti dal BFF a lettura; consegna esterna dei messaggi in un modulo `delivery` del member-service (unico proprietario dei contatti) con adattatori; audit mascherato; cifratura a colonna dei contatti. Modifica gli snapshot locali di membro (`docs/04`) e i contratti `member.*` secondo `docs/05 §9` (versione `:2`, doppia lettura temporanea).
**Conseguenze.** + Retention lunga lecita; + anonimizzazione banale sul bus; − una chiamata in più nel BFF per i nomi; − campaign perde la precisione del giorno di nascita (accettata: `birthYear`).
**Scartate.** Crypto-shredding (le chiavi finirebbero sul bus o richiederebbero chiamate sincrone); cifratura dei topic a riposo soltanto (non risolve la retention).

### ADR-033 — Multilingua a tre livelli con prefisso URL
**Contesto.** Q-07; N lingue per installazione; enti PA richiedono selettore esplicito.
**Decisione.** UI con `next-intl` e segmento `/[locale]`; backend con `MessageSource` e `LhException` a chiavi; dominio con `LocalizedText` jsonb e risoluzione per `Accept-Language` nelle API portale; `member.locale`; seed multilingua verificato. Fuso sempre Europe/Rome. Decide Q-07.
**Conseguenze.** + Lingue aggiungibili senza rilascio (testi) e con rilascio minimo (dizionari UI); − i percorsi del portale cambiano (`/it/portal/...`), redirect mantenuti.
**Scartate.** Cookie senza prefisso (non condivisibile, non conforme ai pattern PA); sottodomini per lingua.

### ADR-034 — Strategia di collaudo: journey, invarianti, matrice, carico, installazione
**Contesto.** Gli E2E di Fase 1 erano fuori dal repo e non in CI.
**Decisione.** Cartella `e2e/` con Playwright su stack reale (immagine + Postgres + Kafka in CI), journey DSL con macchina del tempo e invarianti di dominio via API, matrice device × lingua × profilo con screenshot e axe/pa11y, k6 per il carico, test di installazione e aggiornamento; `e2e-pr` smoke, `e2e-nightly` completa.
**Conseguenze.** + Il bilingue e la composizione sono verificabili per costruzione; − tempi di CI (mitigati con smoke/nightly).
**Scartate.** Dispositivi reali (costo); mock delle API per i journey (non provano il sistema).

### ADR-035 — Agente regolamento in `assistant-service`, solo modelli self-hosted, human-in-the-loop
**Contesto.** Il marketing deve poter configurare un concorso da un regolamento; i dati non devono uscire dall'installazione.
**Decisione.** `assistant-service` con job asincroni, estrazione per articolo a schema JSON contro un endpoint OpenAI-compatibile self-hosted (`LH_LLM_*`), bozza con evidenze e confidenza, applicazione in `DRAFT` via API esistenti dal BFF, `LEGAL` approva (M7.1); golden set e record/replay; PDF come input non fidato. Agente spento senza endpoint.
**Conseguenze.** + Residenza dei dati garantita; − qualità dipendente dal modello disponibile (mitigata dalla revisione umana).
**Scartate.** Provider cloud di default; estrazione nel BFF (timeout, nessun audit); Directus Flow (non testabile).

### ADR-036 — Resilienza e obiettivi di servizio
**Contesto.** Prodotto enterprise.
**Decisione.** SLO: portale 99,9 %, azione → punti p95 < 5 s, giocata p99 < 500 ms, RPO 15 min, RTO 1 h; repliche multi-AZ, PDB, HPA su lag (KEDA), Kafka RF 3 / ISR 2, Postgres HA con PITR e replica in seconda region, composizione con rollback, prova di ripristino e game day in M15.
**Conseguenze.** + Misurabile con le dashboard del chart; − costo infrastrutturale minimo dichiarato.
**Scartate.** SLO più aggressivi senza dati (si rivedono con le misure di M15).

### ADR-037 — Distribuzione a immagine unica con ruoli e modalità
**Contesto.** Requisito: installabile da una sola immagine sul Docker di ogni azienda; e insieme resilienza su Kubernetes.
**Decisione.** Un'unica immagine multi-arch con `LH_ROLE` (`all·hub·web·cms·idp·jobs`), `LH_SERVICES`, `LH_MODE` (`embedded·external`); tre tagli (appliance, compose, Helm) dalla stessa immagine; appliance dichiarata non HA; CLI `lh`; base minimale non root; firma e SBOM. Il `hub` di ADR-023 diventa `LH_ROLE=hub`.
**Conseguenze.** + Un artefatto, una pipeline; + `docker run` in minuti; − immagine grande (~2 GB) e responsabilità sulle CVE di quattro upstream (rischio §8).
**Scartate.** Un'immagine per servizio (contraddice il requisito); master container che avvia altri container (richiede il socket Docker: rischio di sicurezza).

### ADR-038 — Politica di rilascio, aggiornamento e supply chain
**Contesto.** Chi installa deve poter aggiornare senza fermo e fidarsi dell'artefatto.
**Decisione.** Semver; percorso supportato N−1 → N; migrazioni expand/contract (Flyway, snapshot CMS, realm IdP) con lock; treno mensile + patch di sicurezza fuori ciclo; changelog con note di sicurezza; SBOM, scansione bloccante, firma cosign con verifica opzionale all'ammissione, provenance dalla CI; test di aggiornamento in CI.
**Conseguenze.** + Aggiornamenti prevedibili; − disciplina di migrazione su ogni fetta.
**Scartate.** Migrazioni distruttive con finestra di fermo.

### ADR-039 — Design system a token con profili `brand` e `pa`; accessibilità come gate
**Contesto.** Il portale deve essere moderno e utilizzabile da enti affiliati alla PA (linee guida AgID: identità visiva e accessibilità); l'accessibilità è comunque obbligo per grandi imprese e per l'EAA.
**Decisione.** Token a tre livelli; profili di tema `brand` (Aurora) e `pa` (token, font e icone del Design System .italia, blocchi strutturali obbligatori) selezionati dal CMS; validatore di conformità alla pubblicazione; WCAG 2.1 AA su portale, widget, backoffice, login, verificata da axe/pa11y in CI e da audit con tecnologie assistive in M15; `lh a11y-report` per la dichiarazione. Componenti nostri sui token e pattern ufficiali (opzione C). SPID/CIE e servizi PA fuori perimetro. Integra `docs/07 §5` (che si sdoppia nei due profili).
**Conseguenze.** + Un solo codice per due profili; + conformità tracciabile token per token; − mappa "pattern AgID → blocco" da mantenere.
**Scartate.** `design-react-kit` come libreria (conflitti CSS, aspetto istituzionale non brandizzabile); tema `pa` senza validatore.

### ADR-040 — Mintlify unico sito di documentazione, docs-as-code con diagrammi Mermaid
**Contesto.** Coesistono GitBook, pagine Mintlify scritte a mano e duplicati in `docs_v2/`: tre copie delle stesse specifiche che derivano.
**Decisione.** Mintlify (deployment collegato a `main`) è l'unico sito; contenuti in `site/`; le sezioni Specifiche ed Eventi sono generate da `docs/` e `contracts/events/`, il Riferimento API da `contracts/api/`, le pagine del Registry da `registry/`; diagrammi Mermaid obbligatori per ogni concetto, con `accTitle`/`accDescr` e palette comune; job CI `docs` (drift, link rotti, validità dei diagrammi). GitBook e `docs_v2/` dismessi.
**Conseguenze.** + Una fonte per contenuto; + API ed eventi sempre allineati al codice; + concetti comprensibili a colpo d'occhio. − Dipendenza da un servizio esterno per la pubblicazione (il sorgente resta nel repo, portabile).
**Scartate.** GitBook (sincronizzazione bidirezionale che crea divergenze); sito statico costruito da noi (manutenzione).

### ADR-041 — Governance del repository: `main` protetto e ADR solo in aggiunta
**Contesto.** Con agenti in parallelo e un prodotto distribuito, un push errato su `main` diventa un rilascio errato; le ADR "non si riscrivono" ma nulla lo impediva.
**Decisione.** Ruleset `main-protetto` (solo PR, controlli obbligatori, storia lineare, niente force push né cancellazione, bypass admin solo via PR), merge squash, cancellazione automatica dei rami, secret scanning con push protection, Dependabot, `CODEOWNERS`, controllo `guard` su `docs/13` e ID dei seed; approvazioni a 0 finché c'è un solo maintainer, poi 1 con `CODEOWNERS`.
**Conseguenze.** + `main` sempre verde e rilasciabile; + regole del registro applicate dalla CI. − Ogni modifica, anche di documentazione, passa da una PR.
**Scartate.** Protezione classica per ramo (i ruleset sono più granulari e versionabili via script); approvazione obbligatoria con un solo maintainer (bloccherebbe il lavoro).
**Superata in parte da ADR-044 (approvazioni).**

### ADR-042 — Zero trust e sicurezza applicativa verificata dal software
**Contesto.** Nel PoC le letture erano aperte, il portale riceveva `memberId` dalla richiesta, l'ingresso eventi non era autenticato e qualunque modulo poteva pubblicare qualunque `type` sui topic condivisi. Un prodotto enterprise installato da terzi deve reggere un attaccante interno alla rete.
**Decisione.** Ogni chiamata porta un'identità verificata e un'autorizzazione minima: token per l'HTTP (anche tra moduli e da Directus), mTLS di mesh in Kubernetes, principal Kafka con ACL e **messaggi firmati** (Ed25519, `lhsig`/`lhkid`) con elenco dei produttori ammessi per `type`, validazione degli schemi anche in consumo, ruoli di database *owner*/*app* per servizio. Deny by default sugli endpoint (`@RequiresRole` o `@PublicEndpoint`), membro solo dal token, DTO espliciti. SQL solo parametrico con builder ad allowlist e regole Semgrep; limiti di input; template senza logica; sanitizzazione dei contenuti; difesa SSRF; `Idempotency-Key` sulle operazioni di valore; controlli sui file. Riferimento OWASP ASVS 5.0 L2 e API Top 10; verifica continua con ArchUnit, Semgrep, CodeQL, Schemathesis, ZAP, testbook `TB-SEC`, penetration test.
**Conseguenze.** + Un modulo compromesso non può falsificare fatti di altri moduli né leggere schemi altrui; + le regole sono controlli di build, non convenzioni. − Firma e verifica costano qualche decina di microsecondi per messaggio; − gestione delle chiavi per modulo (automatizzata da `lh init` e dal secret manager).
**Scartate.** Fiducia nella rete interna; firma solo sui topic esterni; topic separati per produttore al posto della firma (contraddice ADR-028).

### ADR-043 — Audit unificato: modifiche di backoffice/CMS/IdP e attività del membro
**Contesto.** `insight-service` registra già le modifiche fatte dal backoffice (`audit_entry`, alimentato da `AuditPublisher` in ogni servizio di dominio), ma non quelle fatte in Directus (pagine, blocchi, tema) né in Keycloak (utenti, ruoli, MFA): restano nei log interni di quei due prodotti. Non esiste inoltre una vista dell'attività del singolo membro finale (login, consensi, giocate, riscatti): `PortalActivityController` mostra solo i movimenti punti. La retention di `audit_entry` è 180 giorni, corta per un requisito enterprise.
**Decisione.** Un solo registro (`audit_entry`, schema invariato) per tutte le modifiche di configurazione: Directus notifica via `POST /v1/audit/external` (stesso HMAC di `/v1/cms/notify`) a `experience-service`; Keycloak notifica tramite event listener SPI (admin events e user events di cambiamento) verso member-service e verso il servizio che gestisce l'IdP. Retention estesa a 400 giorni, tabella sola-inserzione (nessun `GRANT UPDATE` sul ruolo applicativo). Attività del membro finale come capacità **separata**, non un'estensione dell'audit di backoffice: nuova tabella `member_activity_entry` in member-service, alimentata da eventi Keycloak (login), fatti di dominio (riscatti, giocate, consensi) e azioni dirette dal portale; esposta come BO-03 estesa (operatori) e PT-18 «La mia attività» (il membro, i propri dati soltanto, via `MemberPrincipal`), a copertura dell'accesso GDPR art. 15.
**Conseguenze.** + Un solo posto dove cercare "chi ha cambiato cosa", qualunque sia stato lo strumento; + il membro e chi lo assiste vedono la stessa cronologia di attività senza incrociare quattro servizi a mano; + retention coerente con un anno solare di verifiche. − Un endpoint nuovo e autenticato per servizio che riceve i bridge; − il listener Keycloak è un componente in più da mantenere aggiornato alla versione bloccata dell'immagine.
**Scartate.** Audit unico per backoffice e membro nella stessa tabella (semantiche diverse: operatore che agisce su altri vs. membro che agisce su di sé, retention e visibilità diverse); esportare i log nativi di Directus/Keycloak così come sono verso un SIEM esterno senza normalizzarli in `audit_entry` (impossibile fare una query unica "cosa ha fatto x" tra backoffice e CMS).

### ADR-044 — Il prodotto come fornitore di un'azienda ISO/IEC 27001
**Contesto.** Chi adotta il Loyalty Hub è spesso certificata ISO/IEC 27001:2022 e, nel caso di utility e PA, soggetta a NIS2; dall'11 settembre 2026 valgono gli obblighi di segnalazione del Cyber Resilience Act. Il codice attuale ammette l'auto-approvazione, ha una retention unica, ripristina i membri anonimizzati, non governa le esportazioni, e le PR degli agenti non possono essere approvate dal proprietario.
**Decisione.** Il prodotto si dichiara fornitore e fornisce: mappa Annex A con responsabilità Prodotto/Adottante/Condivisa (più ISO 27701, GDPR, NIS2); rifiuto all'avvio in `enterprise` con configurazioni insicure e nessuna telemetria in uscita; quattro occhi (niente auto-approvazione, doppio controllo configurabile), deprovisioning dall'IdP, revisione degli accessi, break-glass; classificazione `x-lh-class`, retention per categoria con rapporto, `erasure_log` riapplicato al ripristino e crypto-shredding, mascheramento per ambienti non di produzione, esportazioni controllate, dismissione con attestato; audit a catena di hash con ancoraggio immutabile, export OCSF, pacchetto forense, prova di ripristino mensile; per ogni rilascio SBOM, VEX, provenienza SLSA L3, SLA sulle vulnerabilità, processo di segnalazione CRA, politica LTS, rapporto licenze. Gli agenti aprono PR con un'identità propria e le PR richiedono un'approvazione umana prima di v1.0 (supera parzialmente ADR-041 sul numero di approvazioni).
**Conseguenze.** + L'adottante integra il prodotto nella Dichiarazione di Applicabilità senza lavoro di ricostruzione; + le evidenze le produce il software, non la memoria degli operatori; + il fornitore regge una valutazione di terza parte. − Più comandi della CLI e un job in più; − il proprietario deve revisionare ogni PR.
**Scartate.** "Certificare" il prodotto (la 27001 certifica un'organizzazione, non un software); lasciare all'adottante la cancellazione nei backup (non è in grado di farla senza supporto del prodotto); approvazioni a 0 fino al secondo maintainer (codice senza quattro occhi in produzione presso terzi).

### ADR-045 — Economia del programma, punteggi esterni, cataloghi esterni, missioni
**Contesto.** Il motore copre campagne, valute, livelli, premi, coupon, instant win, obiettivi, classifiche e referral, ma non risponde al controllo di gestione (quanto costa il programma, quanto rende per livello e campagna, quanti punti scadranno inutilizzati, quando fermare una campagna), non usa segnali predittivi, richiede la gestione manuale di codici e giacenze dei premi e non ha meccaniche di continuità nel tempo.
**Decisione.** Quattro estensioni di componenti esistenti: (1) economia nel wallet e in insight (`unit_cost` con storico, `cost_at_entry`, valore per livello e campagna, `campaign.budget` con soglie e azione a esaurimento, previsione dei punti in scadenza non spesi con soglia); (2) punteggi di propensione calcolati fuori dal prodotto e caricati come `attribute_definition.kind=SCORE` con validità, usati dalle condizioni esistenti, mai visibili al membro e mai in effetti negativi o idoneità ai premi; (3) premi da cataloghi esterni con `fulfilment=EXTERNAL`, adattatori generici configurabili in `lh-common`, saga riserva → spesa → conferma con rilascio e rimborso automatico, sincronizzazione in `DRAFT`, contatti inoltrati da member-service; (4) missioni a tempo con passi e finestra relativa al membro, serie estese con tolleranza, congelamento e avviso di rischio, premio tramite azione interna valutata dalle campagne. Tutte in M13 (dopo il minimo enterprise).
**Conseguenze.** + Il programma diventa misurabile e governabile a budget; + le campagne possono usare segnali predittivi senza portare modelli nel prodotto; + niente giacenze e codici gestiti a mano; + meccaniche di abitudine con la stessa spiegabilità del resto. − Tre nuove schermate e due nuovi blocchi del Registry; − dipendenza da fornitori esterni gestita con stato `DEGRADED` e circuit breaker.
**Scartate.** Modelli predittivi dentro il prodotto (competenza e ciclo di vita diversi da un motore di regole); missioni come tipo di campagna (avrebbero mescolato progresso a passi con effetti immediati); un adattatore per ciascun aggregatore nel nucleo (gli aggregatori cambiano: interfaccia generica a mappatura); il budget come solo `maxPoints` (non dice nulla del costo né avvisa prima).

### ADR-046 — OpenAPI con springdoc in lh-common (Q-341)
**Numero.** Registrata come ADR-026 il 2026-09-26; rinumerata prima dell'adozione di Fase 2 (M8.0), che assegna ADR-026…045 alle decisioni di `docs/18` (Appendice A). Il testo è invariato.
**Contesto.** docs/06 §2 chiede OpenAPI su `/v3/api-docs` e `/swagger-ui.html` con `summary` e tag = area su ogni endpoint; docs/11 §6 vuole la UI spenta nel profilo `free`. Nessun servizio aveva la dipendenza (TB-PLT D-22, riga TB-PLT-HLR-008). Una libreria nuova richiede l'approvazione dell'owner (CLAUDE.md §6): **approvata da Giuseppe** il 2026-09-26.
**Decisione.** `org.springdoc:springdoc-openapi-starter-webmvc-ui` (versione nel parent POM, `springdoc.version`) come dipendenza di `lh-common`, quindi di tutti i servizi e dell'hub. `OpenApiConventions` (auto-configurazione di lh-common) dà a ogni operazione **un solo tag, l'area** (nome del controller senza `Controller`, in kebab-case) e un **summary** (`@Operation` se presente, altrimenti il nome del metodo reso leggibile; un nome di una parola prende anche l'area). Profilo `free`: `springdoc.swagger-ui.enabled=false`, resta `/v3/api-docs`. Documentazione generata alla prima richiesta, non all'avvio.
**Conseguenze.** + Contratto HTTP consultabile e verificato dal testbook (HLR-008: 200, ogni operazione con summary e un tag). + Nessun componente infrastrutturale nuovo, costo zero. − Circa 6 MB di metaspace in più alla prima chiamata di `/v3/api-docs` nell'hub (misurato: 85 → 91 MB, sotto `MaxMetaspaceSize=128m`). − swagger-core porta Jackson 2 sul classpath di runtime (già presente per il validatore JSON Schema); le API restano serializzate con Jackson 3. − I summary generati sono in inglese e sintetici: un endpoint che merita una descrizione migliore la dichiara con `@Operation(summary = …)`.
