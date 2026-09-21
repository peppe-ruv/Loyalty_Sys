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

### ADR-011 — Un'unica app web; nessun CMS esterno
**Contesto.** Il tentativo precedente con un CMS separato aveva prodotto entità senza lettori.
**Decisione.** Una sola app Next.js con Demo Hub, backoffice e portale. I contenuti (card, pop-up, banner, tema) sono **entità di dominio** di `engagement-service`, gestite dal backoffice unico. Regola "nessuna entità senza lettore" (`docs/02 §3`).
**Conseguenze.** + un deploy, componenti condivisi (anteprima fedele), nessuna doppia fonte di verità. − niente editor ricco né libreria media (accettato per il PoC).
**Scartate.** CMS headless; due app separate.

### ADR-012 — Osservabilità: `insight-service` nel PoC, stack dedicato nel target
**Decisione.** Nel PoC un servizio consuma tutti i topic e offre flusso live, tracciati, KPI, audit, DLQ. Target: metriche/log/tracce su stack dedicato (Prometheus, Grafana, Loki, Tempo con OpenTelemetry) e analitica su ClickHouse + Superset; `insight` si riduce ad audit e tracciati funzionali.
**Conseguenze.** + la demo *mostra* l'architettura senza infrastruttura aggiuntiva. − KPI approssimati; non è un sistema di monitoraggio.

### ADR-013 — Single-tenant, con `lhtenant` predisposto
**Decisione.** Un solo tenant (`aurora`). L'estensione `lhtenant` è sempre valorizzata negli eventi ma **non** esistono colonne `tenant_id` né filtri. Il multi-tenant, se servirà, sarà per istanza.

### ADR-014 — Hosting gratuito e keep-alive solo a scheda aperta
**Decisione.** Vercel (web), Render free (8 servizi Docker da Blueprint), Neon free, Aiven free Kafka. I servizi dormono; il Demo Hub li sveglia; il keep-alive gira solo con una scheda visibile. **Nessun pinger esterno.**
**Conseguenze.** + costo zero da spenta, quasi tutto automatizzabile dai connettori. − primo avvio di minuti; Kafka da riaccendere a mano dopo lunga inattività; piano Vercel gratuito solo per uso non commerciale.
**Scartate.** VM unica sempre accesa (non "zero da spenta" e non automatizzabile dai connettori); Kubernetes gestito (costo).

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
