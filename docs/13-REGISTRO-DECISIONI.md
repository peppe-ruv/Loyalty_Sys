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
| 047 | Flusso di integrazione: CI per aree, auto-merge, stato cumulativo | ACCETTATA |
| 048 | Membro dal token: `MemberPrincipal`, legame in `member_identity`, pseudonimo `subjectRef` sul bus, proiezioni locali, `/v1/portal/me` | ACCETTATA |
| 049 | Vetrina enterprise ospitata: seconda istanza separata raggiunta dal Demo Hub | ACCETTATA |
| 050 | Vetrina enterprise su GitHub Codespaces, accesa su richiesta (supera ADR-049 decisione 2) | ACCETTATA |

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

### ADR-047 — Flusso di integrazione: CI per aree, auto-merge, stato cumulativo
**Contesto.** Con ADR-041 ogni fetta apre una PR verso `main` e il proprietario la unisce. Nella pratica di M8 le PR aperte insieme erano molte (fette, Dependabot, agenti), ognuna eseguiva la CI due volte (push e pull_request) con l'intero `./mvnw verify` in un solo job anche per modifiche di sola documentazione o solo web, e ogni fetta toccava la stessa riga di `docs/14`, generando conflitti a catena dopo ogni merge. Il proprietario, collo di bottiglia dell'unione, ha chiesto di unire in automatico le PR verdi.
**Decisione.** (1) La CI gira una volta per PR (`pull_request`; `push` solo su `main`) con cancellazione delle esecuzioni superate. (2) Un job `aree` calcola le aree toccate: il backend gira se cambia qualcosa fuori da `docs/`, `web/`, `site/` e dai `.md`; il web se cambia `web/`; il workflow stesso fa girare tutto. (3) Il backend è diviso in gruppi paralleli di moduli; il job aggregatore mantiene il nome `backend (Java 25)`, controllo obbligatorio del ruleset, verde se i gruppi sono verdi o saltati. (4) Le PR di fetta si aprono con **auto-merge (squash)** attivo: si uniscono da sole quando i controlli obbligatori sono verdi. (5) Le fette non toccano più `docs/14`: lo aggiorna una PR di stato cumulativa (`docs(stato): …`) dopo un gruppo di merge. (6) Dependabot: al più una PR aperta per ecosistema, minor e patch raggruppate, azioni di GitHub mensili. (7) Gli agenti esterni (Jules) solo su richiesta del proprietario e per compiti piccoli; di norma sottoagenti in worktree isolati.
**Conseguenze.** + Tempo di CI per PR ridotto e proporzionato alle aree toccate; + nessun conflitto ricorrente su `docs/14`; + il proprietario non è più nel percorso critico dell'unione. − `docs/14` è in ritardo di qualche merge rispetto a `main`; − un modulo nuovo va aggiunto a un gruppo della matrice, altrimenti non viene verificato; − l'auto-merge si affida interamente ai controlli obbligatori, che restano quelli di `main-protetto`. Supera in parte ADR-041 (unione da parte del proprietario) e CLAUDE.md §4; l'approvazione umana richiesta da ADR-044 prima di v1.0 resta valida quando il ruleset la impone.
**Scartate.** Coda di merge di GitHub (disponibile solo per repository di organizzazioni); un solo job backend con cache più aggressiva (non riduce il tempo sulle PR di sola documentazione); Dependabot con cadenza legata al numero di PR (non supportato: la cadenza è solo temporale).

### ADR-048 — Membro dal token: `MemberPrincipal`, legame in `member_identity`, pseudonimo `subjectRef` sul bus, proiezioni locali, `/v1/portal/me`
**Contesto.** ADR-027 dà ai membri un account OIDC e ADR-042 vuole il membro «solo dal token», ma le API `/v1/portal/**` legano ancora `memberId` da percorso, query e corpo. Nel profilo `enterprise` un token di solo `MEMBER` vale `ANALYST` e passa tutti gli handler `@RequiresRole` del portale: l'unico argine è il controllo del BFF (`memberScope.ts`), che è difesa in profondità e non sostituisce il controllo nei servizi (Q-410). Le specifiche dicevano `member.external_id = sub`, ma `external_id` è `UNIQUE` e porta l'id del CRM (seed `CRM-101…`, ingestion risolve `external:<id>`, US-E01-06). Un servizio non può chiedere a member-service chi sia un `sub` (regola 3, ADR-002) e il `sub` non può stare sul bus (ADR-032): con un IdP esterno può essere un'e-mail o una matricola. Serve quindi un meccanismo di identità sul bus, cioè un caso «Fermati e chiedi» (CLAUDE.md §6-§7). Decisioni del proprietario del 2026-09-29: Q-550…Q-559 (opzione raccomandata in ogni caso), Q-410, Q-493 (in parte) e Q-157; una PR per servizio per proiezione e API del portale.
**Decisione.**
1. **Terza dichiarazione di accesso.** `@MemberEndpoint` (`REQUIRED`, `OPTIONAL`, `REGISTRATION`) affianca `@RequiresRole` e `@PublicEndpoint`: l'elenco chiuso passa a tre e un `@MemberEndpoint` insieme a `@RequiresRole` o `@PublicEndpoint` sullo stesso elemento è una combinazione non valida. `EndpointAccessInterceptor` risolve il membro e passa al controller un `MemberPrincipal` (o un `MemberSubject` per la registrazione); il controller non legge mai `memberId` da parametri. Le letture di programma aperte ai membri (tema, livelli, edizioni, categorie) dichiarano `@RequiresRole(..., members = true)`. Dalla prima fetta un token di solo membro raggiunge soltanto queste due famiglie di handler: il BOLA si chiude subito, a livello di servizio.
2. **Legame.** Tabella `member_identity(member_id PK, issuer, subject, subject_ref UNIQUE, linked_at)` in member-service, con `UNIQUE(issuer, subject)`; `external_id` resta l'id CRM. Il legame nasce con la registrazione dal portale, nella stessa transazione del membro e dell'outbox, idempotente su `(issuer, subject)`; si cancella con l'anonimizzazione. `subject` è un dato personale e non lascia member-service.
3. **Sul bus solo un pseudonimo.** `subjectRef` = HMAC-SHA256 esadecimale (64 caratteri) di `len(iss):iss len(sub):sub` (codifica esatta in `docs/06 §3.4`) con la chiave dedicata `LH_SUBJECT_KEY` (base64, almeno 32 byte). Campo opzionale con `x-lh-pii: false` di `member.registered` e `member.updated`, versioni `:1` e `:2`; stesso regime di `emailHash` (fuori da `PersonalData.KEYS`, mai «sicuro» per lo scrubber, tolto da insight all'anonimizzazione). Assente = legame invariato, `null` = legame rimosso. Nessun nuovo `type`, topic o produttore; lo schema si aggiorna prima che member-service emetta il campo.
4. **Proiezioni locali.** Ogni servizio del portale (wallet, reward, gamification, engagement, campaign) copia `subjectRef` in tre colonne additive della propria tabella snapshot (`subject_ref`, `subject_ref_at`, `subject_erased`) con indice unico parziale, e risolve il membro con l'HMAC del token e una ricerca locale. La decisione pura sta in `lh-common` (`MemberSubjectRules`), senza SQL: l'SQL resta costante nei repository di ciascun servizio (regole 2 e 19). Regole: anonimizzazione = lapide definitiva; `subjectRef` assente = nessun effetto; un replay vecchio non ri-lega; se lo stesso riferimento è già di un altro membro vince l'istante più recente (una nuova registrazione dopo un'anonimizzazione converge da sola, Q-558). `sub` senza legame: `404 MEMBER_NOT_REGISTERED` in member-service (lookup autorevole), `409 MEMBER_NOT_LINKED` con `Retry-After: 2` negli altri servizi: consistenza eventuale che fallisce chiusa.
5. **API solo additive** (Q-410, alternativa A). I percorsi con query o corpo restano; `memberId` esce dal contratto come parametro di query e i campi `memberId` dei DTO di richiesta restano con `deprecated: true` (togliere una proprietà di schema è incompatibile); i cinque percorsi con l'id del membro ottengono `/v1/portal/me/{profile,referral,wallet,wallet/activity}` (i vecchi restano deprecati, validi solo in demo, mai rimossi); la registrazione è `POST /v1/portal/members`; `GET /v1/portal/editions` e `GET /v1/portal/reward-categories` sono alias per le letture del portale. `/v1/portal/me/activity` resta riservato a PT-18.
6. **Errori** (Q-553). In `oidc`: `memberId` in query, campo form o corpo (a qualunque profondità), oppure header `X-LH-Member` ⇒ `400 MEMBER_FROM_TOKEN`, anche se è l'id del titolare; id nel percorso legacy ⇒ `403 MEMBER_FROM_TOKEN`; oggetto di un altro membro ⇒ `404 NOT_FOUND`; operatore o token misto su una funzione del membro ⇒ `403 MEMBER_REQUIRED`. Un operatore non agisce mai come membro (Q-554): `OPTIONAL` dà la vista generica; `MEMBER` con `SOURCE` resta `403` sul portale.
7. **Profilo `demo` invariato** (regola 6-bis, Q-555). Vale il `memberId` esplicito oppure l'header `X-LH-Member` messo dal BFF demo dalla persona; due fonti in disaccordo ⇒ `400 MEMBER_MISMATCH`; in `oidc` l'header è rifiutato e non compare mai nell'OpenAPI (regola 12). Gli errori 400 di demo restano identici a oggi.
8. **Attore** (Q-556). `member:<memberId>` (`member:-` prima della risoluzione; `preferred_username` non entra mai nell'attore, regola 20) tramite un flag `member` su `ActorContext`, con ruolo `ANALYST`; `Role` non cambia. Reward e gamification allineano il `lhactor` oggi scritto come `MEMBER:<id>`.
9. **Classifiche** (Q-559). `resolve=ids` è ignorato quando il principal viene dal token.
10. **Scritture** (Q-493, Q-157). Le scritture del portale funzionano solo col token del membro; la registrazione passa a `POST /v1/portal/members` e `POST /v1/members` diventa `@RequiresRole({ADMIN, CARE})` nell'ultima fetta, dopo un rilascio del web migrato (N−1, ADR-038). Restano fuori le transizioni delle campagne e la chiusura delle edizioni (Q-493, residui).
11. **Realm** (Q-557, Q-558). Registrazione aperta senza verifica dell'e-mail (`verifyEmail: false` esplicito, `MEMBER` nei ruoli di default); nessun collegamento automatico dei membri esistenti e nessun blocco della ri-registrazione; SMTP solo con un'ADR dopo il modulo `delivery`. `MEMBER` non deve arrivare alle utenze di servizio `src-*` (un token `MEMBER`+`SOURCE` è confinato al portale): R1 verifica in `check-realm` che i ruoli effettivi delle service account escludano `MEMBER`, e `verify.sh` che un token `client_credentials` di `src-*` abbia `lh_roles=[SOURCE]` soltanto; il runbook di Q-494 toglie `default-roles-loyaltyhub` dall'utenza di servizio; se Keycloak non lo consente, `MEMBER` si assegna dal flusso di registrazione invece che dal composito di default.
12. **Avvio e verifica.** In `enterprise`, con almeno un `@MemberEndpoint`, una `LH_SUBJECT_KEY` assente o corta impedisce l'avvio (`INSECURE_CONFIG`, regola 22). Un test di build verifica le dichiarazioni (ArchUnit) e, nell'hub, i parametri risolti a runtime (ArchUnit può non vedere i nomi impliciti) e il contratto generato; una matrice BOLA con token RS256 veri, generata da `portal.openapi.yaml`, prova A contro B su ogni operazione.

**Integra e non supera** ADR-027, ADR-032 e ADR-042. **Scostamenti dal testo di Fase 2**, registrati qui e nelle pagine corrispondenti: (a) `member.external_id = sub` (`docs/18` §3.2, F2-IAM-03, M8.2; `docs/servizi/member-service.md`; `docs/17`) diventa `member_identity`; (b) i campi `memberId` dei DTO di richiesta non spariscono dal contratto ma restano `deprecated`, validi solo in demo: in `enterprise` un valore dà `400`; (c) la parte «portale con `memberId` da parametri» del controllo di `docs/18 §3.10` riga «ArchUnit» passa dal solo ArchUnit al controllo dei handler nell'hub (`OpenApiExportIT`), che vede i nomi impliciti a runtime e il contratto generato, mentre ArchUnit applica le regole sulle dichiarazioni; (d) le righe di accettazione di `docs/18 §6` («→ 403» e «ignorato o `400`») si riscrivono con i codici di Q-553.
**Attuazione.** Una PR per fetta (regola 16), in ordine di dipendenza: decisioni e documenti; `lh-common` (`MemberPrincipal`, `@MemberEndpoint`, attore, guardie, regole ArchUnit); `LH_SUBJECT_KEY` nel chart e nel compose; realm; `subjectRef` negli schemi e in insight; BFF demo (`X-LH-Member`); member-service (`member_identity`, registrazione dal portale); una PR per servizio con proiezione e API del portale (wallet, reward, gamification, engagement, campaign); verifica nell'hub; portale web; restrizione di `POST /v1/members`. Lo stato è in `docs/14`. Il passaggio del produttore `member.*` a `:2` (M8.4) porta con sé `subjectRef`; la validazione in consumo (F2-SEC-08) arriva dopo l'aggiornamento degli schemi.
**Conseguenze.** + Il BOLA sul portale è chiuso nei servizi, non solo nel BFF; + nessuna chiamata sincrona, nessun componente e nessuna destinazione di rete nuovi, e funziona con qualunque emittente OIDC; + solo aggiunte (ADR-038): colonne nullable, una tabella, un campo evento opzionale, percorsi nuovi; l'unico restringimento è `POST /v1/members`, dopo un rilascio del web migrato; + demo, seed e smoke intatti. − Consistenza eventuale: `409` finché il fatto non è arrivato; − `subjectRef` è un pseudonimo (dato personale per il GDPR) su topic a retention lunga: non rivela il `sub` senza la chiave, ma la chiave va custodita (secret manager, regola 20) e la sua rotazione richiede un job di member-service che ricalcola e ripubblica (fuori da M8.10f); − il ripristino di un servizio da backup perde i legami fino al prossimo `member.updated`; − la registrazione aperta senza verifica dell'e-mail espone all'enumerazione delle e-mail (`email` è `UNIQUE`), mitigata dal rate limit per membro (F2-SEC-11); − il legame si fida dei fatti `member.*` sul bus: finché F2-SEC-08 (firma e `producers.yaml`, solo member-service produce `member.registered` e `member.updated`) o le ACL Kafka su `lh.facts.v1` non sono attive, un produttore non ammesso potrebbe ri-legare un `subjectRef` a un altro membro; il portale `enterprise` non si espone a membri reali prima di F2-SEC-08; − dopo la prima fetta di codice un token di membro riceve `403` su tutto il portale tranne tema e livelli: è voluto e finché il web non è migrato il portale `enterprise` non si espone a membri reali.
**Scartate.** `member.external_id = sub` (collide col CRM e rompe US-E01-06); una colonna `member.subject` senza emittente; un claim `lh_member_id` scritto con la Admin API di Keycloak (nuova destinazione di rete e account `manage-users`, assente con un IdP esterno, rinnovo del token dopo la registrazione, BOLA se l'utente può modificare l'attributo); il BFF che risolve e inoltra `memberId` (viola ADR-042); il `sub` grezzo sul bus (contro ADR-032; chiamarlo `subject` collide con l'attributo dell'envelope); il riuso di `LH_PSEUDONYM_KEY` (allarga a sei moduli una chiave limitata a member e ingestion, Q-367); `Role.MEMBER` con attore `MEMBER:<id>` (tocca Q-298, `isOperatorRole` e il contratto di piattaforma); un token misto che vale come membro (impersonazione, contro Q-365); tutto sotto `/v1/portal/me/**` con rimozione dei vecchi percorsi, o il segmento letterale `me` nei percorsi esistenti (rottura di contratto, catena di fette in serie); ignorare in silenzio i `memberId` (nasconde gli errori del client e l'intento ostile); `403` ovunque anche per il `sub` non legato (il web non saprebbe se ritentare); `verifyEmail` con SMTP subito (nuova destinazione di rete); restringere `POST /v1/members` nella prima fetta (rompe il web N−1 e i test che registrano senza ruolo).

### ADR-049 — Vetrina enterprise ospitata: seconda istanza separata raggiunta dal Demo Hub
**Stato.** ACCETTATA il 2026-09-30 per le decisioni 1–3 (istanza separata, hosting A deciso in Q-615, pulsante) e per i vincoli conservativi 4–7 (dati solo fittizi, nessuna password pubblicata, nessuna esenzione dalle guardie di `enterprise`, portale chiuso). I meccanismi indicati come «default proposto» nei punti 4–7 non sono decisi: restano nelle Q-617…Q-624 (APERTE) e, se il proprietario sceglierà un'alternativa, servirà una nuova ADR.
**Contesto.** La demo ospitata (ADR-014, ADR-023, ADR-024) gira nel profilo `demo`: personas, `X-LH-Actor`, seed. Il profilo `enterprise` (ADR-026, ADR-027, ADR-037, ADR-042, ADR-044, ADR-048) non si può vedere online: si prova solo installandolo. Il proprietario chiede un pulsante nel Demo Hub per «accendere la modalità Enterprise». Il profilo si fissa all'avvio e fallisce chiuso (`INSECURE_CONFIG`). I due profili si escludono sui dati: il profilo `demo` rifiuta un DB con membri non di seed e `/v1/demo/**` non esiste in `enterprise` (docs/18 §3.15). I cookie `__Host-` valgono per un'origine sola. Un interruttore sull'istanza pubblica cambierebbe la modalità a tutti i visitatori. Keycloak richiede circa 1,3 GB (guida ufficiale) e non entra nei 512 MB / 0,1 CPU dei piani gratuiti usati dalla demo. Il cluster Kafka della demo non si può condividere: `groupId` fissi, topic immutabili (ADR-028), firma e ACL assenti (F2-SEC-02, F2-SEC-08). Le sessioni del BFF stanno in memoria (Q-409, Q-419) e non reggono un runtime serverless. Decisione del proprietario del 2026-09-30 sulla proposta di ADR-049: opzione A per l'hosting (Q-615) e, di conseguenza, per il bus (Q-616).
**Decisione.**
1. **Istanza separata, non interruttore.** La «vetrina enterprise» è una seconda installazione in `LH_PROFILE=enterprise`, `LH_MODE=external`, con l'immagine unica (`LH_ROLE=hub`, `LH_ROLE=web`), Keycloak come `idp` (Q-374) e il taglio compose di riferimento (F2-DIST-03) più un overlay di vetrina con reverse proxy TLS (Q-392, Q-420). Non condivide con la demo database, bus, segreti, realm né sessioni.
2. **Hosting.** Un host Oracle Cloud Always Free A1 (Q-615, deciso il 2026-09-30: opzione A), a costo zero, senza sospensione, entro i limiti Always Free attuali delle istanze A1: 2 OCPU e 12 GB di memoria in tutto (il fornitore li ha dimezzati il 15 giugno 2026, da 4 OCPU e 24 GB, senza preavviso). Lo stack della vetrina è stimato in 5–7 GB; l'overlay fissa un limite di memoria per ogni container (fetta V3). Il bus è un broker Kafka KRaft proprio sullo stesso host, nel compose (Q-616, conseguenza di Q-615 = A): nessun cluster condiviso con la demo. Riserva, non decisa: Keycloak su Cloud Run con DB dedicato su Neon, che riaprirebbe la questione del bus con un'ADR.
3. **Pulsante.** HUB-01 mostra un collegamento alla vetrina solo se `LH_HUB_ENTERPRISE_URL` (lato server, origine https) è valorizzata; HUB-02 della vetrina porta il collegamento di ritorno `LH_HUB_DEMO_URL`. È un collegamento tra due origini, non uno stato condiviso.
4. **Portata a tappe.** Primo passo: solo il backoffice, con login reale, MFA e registrazione chiusa. Il portale membri si apre solo dopo la migrazione del web al membro dal token (W2) e dopo F2-SEC-08 o le ACL Kafka su `lh.facts.v1` (ADR-048), con registrazione limitata a indirizzi fittizi (default proposto, Q-619 APERTA).
5. **Dati.** Solo fittizi (docs/11 §11). Nessun `/v1/demo`, nessun membro `MBR-*` e nessun legame automatico. Default proposto, Q-617 APERTA: la configurazione di programma arriva da `seed/` attraverso le API del backoffice con un token di operatore, con una voce di audit per ogni scrittura (regola 21). Azzeramento periodico dichiarato (default proposto, Q-624 APERTA).
6. **Credenziali.** Nessuna password pubblicata e MFA invariata. Default proposto, Q-618 APERTA: account operatore nominativi creati su richiesta. Segreti generati sull'host, fuori da repository, log e browser (regola 20); `bootstrap.sh` non scrive le password su stdout.
7. **Conformità.** L'istanza parte solo se le guardie di `enterprise` passano (ADR-044): nessuna esenzione di vetrina. TLS anche verso bus e DB (default proposto, Q-621 APERTA). Nessuna telemetria in uscita. Voce in `docs/compliance/iso27001-annex-a.md` (A.5.23, A.8.9, A.8.20, A.8.24, A.5.34).

**Integra e non supera** ADR-014 e ADR-023/024 (la demo resta invariata), ADR-026 (la vetrina usa il taglio compose, non il riferimento Kubernetes) e ADR-037.
**Scostamenti dichiarati:** gli SLO di ADR-036 non valgono per la vetrina; un solo broker e una replica del web (Q-419).
**Conseguenze.**
- + Il profilo `enterprise` si vede e si prova online, e la vetrina fa anche da prova d'installazione del compose, oggi assente (Q-491).
- + Nessun nuovo `type`, topic, servizio o endpoint pubblico; demo, seed e smoke della demo sono intatti.
- + Nessun avvio a freddo e nessuna condivisione di confini tra le istanze.
- − Un nuovo fornitore (Oracle Cloud), con registrazione a carta, limiti gratuiti che il fornitore può ridurre senza preavviso (già dimezzati nel giugno 2026), rischio di capacità A1 e recupero per inattività (l'istanza inattiva può essere recuperata dal fornitore: la memoria residente di Keycloak, Kafka e JVM è la mitigazione, non una garanzia).
- − Gestione operativa a carico del proprietario: TLS, aggiornamenti dell'immagine, reset.
- − Un nuovo componente dichiarato solo nell'overlay di vetrina: il reverse proxy (regola 8-bis).
- − Le schermate che dipendono da SSE (BO-24) restano *degraded* fino al proxy SSE (Q-622).
- − Le schermate che dipendono da azioni in ingresso restano vuote finché le fonti non hanno JWKS reali.
- − Account operatore creati a mano.
**Scartate.**
- Interruttore di profilo nella demo (profilo fissato all'avvio, dati incompatibili, stato globale).
- Seconda istanza su Render e Vercel gratuiti (Keycloak non entra in 512 MB; sessioni in memoria su serverless; sospensione dopo 15 minuti).
- Cluster Aiven condiviso (partizioni rubate tra le istanze, nessuna firma o ACL).
- `enterprise,inproc` (estende ADR-024 fuori dalla demo).
- IdP gestito diverso da Keycloak (supererebbe ADR-027).
- Credenziali operatore pubbliche o MFA esentata (contro docs/18 §3.15 e la regola 22).
- Seed di vetrina nel profilo `enterprise` (contro docs/18 §3.15).
- Piani a pagamento (Koyeb, Fly.io; regola 8: resta come riserva con un'ADR di deroga).
**Decisioni successive (Giuseppe, 2026-09-30).** Q-617, Q-618, Q-619 e Q-626 decise con il default proposto: configurazione di programma da `seed/` via API con token di operatore (Q-617), account operatore nominativi con `MFA_REQUIRED_ROLE` verificato da `deploy/idp/vetrina/apply-overlay.sh` (Q-618), registrazione chiusa nel primo passo (Q-619), client pubblico `lh-cli` con solo Device Authorization Grant nell'overlay di vetrina (Q-626). Le decisioni 5 e 6 valgono quindi come decise; restano aperte Q-620…Q-624.

**Decisioni successive (Giuseppe, 2026-10-01).** Q-620…Q-624 decise con il default proposto: sottodominio gratuito di un servizio DNS con certificati ACME del reverse proxy per `web` e `idp` (Q-620); CA locale generata sull'host, Kafka `SSL_PEM` e Postgres `sslmode=require`, senza eccezioni di vetrina alla regola 22 (Q-621); BO-24 in stato *degraded* nel primo passo e proxy SSE nel BFF in una fetta successiva (Q-622); nessun `render.yaml`, documentazione corretta sui servizi Render gestiti dal pannello (Q-623); azzeramento settimanale pianificato sull'host senza backup, annunciato nel banner di HUB-02 (Q-624). Le decisioni 4 e 7 valgono quindi come decise; le domande di ADR-049 sono tutte chiuse.

**Superata in parte da ADR-050 (decisione 2, hosting).** Le decisioni 1, 3–7 restano valide.

### ADR-050 — Vetrina enterprise su GitHub Codespaces, accesa su richiesta
**Stato.** ACCETTATA il 2026-10-01 (Giuseppe), con i dettagli di attuazione Q-660…Q-663 decisi lo stesso giorno con il default proposto. **Supera ADR-049 decisione 2** (host Oracle Cloud Always Free A1, Q-615 = A); le decisioni 1 e 3–7 di ADR-049 restano valide.
**Contesto.** Il 2026-10-01 il proprietario ha rinunciato all'host Oracle Cloud A1: nel suo account l'istanza non risultava utilizzabile a costo zero, e la regola 8 non ammette costi senza un'ADR di deroga. Le altre strade gratuite note non reggono lo stack della vetrina (circa 6,3 GB a regime: hub 2 GB, Keycloak 1,5 GB, Postgres e Kafka 1 GB ciascuno, web 768 MB): Render e Vercel gratuiti sono a 512 MB (scartati in ADR-049), Aiven offre solo servizi di dati e il suo Kafka gratuito della demo non si condivide (Q-616), un runner di GitHub Actions vive al massimo 6 ore e non ha un URL pubblico senza un tunnel. GitHub Codespaces, incluso nell'account personale gratuito, offre macchine da 8 o 16 GB, Docker al loro interno, inoltro delle porte in https su `*.app.github.dev` con visibilità pubblica senza autenticazione, e blocca l'uso a fine quota se l'account non ha un metodo di pagamento ([fatturazione](https://docs.github.com/en/billing/concepts/product-billing/github-codespaces), [inoltro delle porte](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace)). Il proprietario non vuole dare comandi dal proprio PC: un codespace si crea e si avvia dal browser.
**Decisione.**
1. **Hosting a richiesta.** La vetrina gira in un GitHub Codespace del repository, creato da una configurazione dev container dedicata, con lo stesso compose di riferimento e lo stesso overlay di vetrina (`deploy/vetrina/`), in `LH_PROFILE=enterprise`, `LH_MODE=external`. Non è sempre accesa: il proprietario la avvia dal browser prima di una demo e si ferma da sola dopo il periodo di inattività del codespace. Macchina e quota: Q-660.
2. **Esposizione.** Il TLS pubblico è quello dell'inoltro delle porte di GitHub, con due porte pubbliche per `web` e `idp`; il reverse proxy con certificati ACME e il sottodominio DNS di Q-620 non servono nel codespace. Gli URL https restano identici per browser e container (Q-392, Q-420). Dettagli: Q-661.
3. **Bus e database invariati.** Broker Kafka KRaft e Postgres propri nel compose (Q-616), TLS con la CA locale generata nel codespace (Q-621): nessuna eccezione di vetrina alla regola 22.
4. **Pulsante.** HUB-01 continua a mostrare il collegamento solo con `LH_HUB_ENTERPRISE_URL` valorizzata; l'URL resta stabile finché il codespace esiste. Come si comporta quando la vetrina è spenta: Q-662.
5. **Azzeramento.** Ogni codespace nuovo parte vuoto; fermare e riavviare conserva i dati. Il timer settimanale dell'host non si applica a un codespace fermo: Q-663.
6. **Costo zero verificabile.** L'account che ospita il codespace non ha metodi di pagamento per Codespaces oppure ha un budget a 0 per Codespaces, così l'uso si blocca a fine quota invece di generare addebiti.

**Integra e non supera** ADR-049 (decisioni 1, 3–7), ADR-026, ADR-037. Resta la regola 8: nessun costo.
**Conseguenze.**
- + Costo zero senza carta di credito, senza capacità da conquistare e senza recupero dell'istanza da parte del fornitore.
- + Nessun comando dal PC del proprietario: creazione, avvio e arresto dal browser.
- + Nessun nuovo fornitore oltre GitHub, già usato per repository, CI e immagini; nessun nuovo componente: l'inoltro delle porte di GitHub sostituisce il reverse proxy.
- + Stessa immagine, stesso compose e stesso overlay della vetrina: resta una prova d'installazione.
- − Non sempre accesa: la quota gratuita copre decine di ore al mese, non un servizio 24/7 (TOBE-012); l'indisponibilità fuori demo va dichiarata in HUB-01 e HUB-02.
- − Un codespace è pensato per lo sviluppo: la vetrina lo usa solo per demo a richiesta, non come servizio pubblicato in modo permanente.
- − Architettura amd64 nel codespace: il controllo arm64 di `vetrina.sh preflight` va reso dipendente dall'host (fetta V7).
- − Lo smoke pianificato contro la vetrina (V6) trova la vetrina spenta per la maggior parte del tempo e va eseguito solo a vetrina accesa.
- − Gli URL dipendono dal nome del codespace: un codespace nuovo cambia `LH_HUB_ENTERPRISE_URL`.
**Scartate.**
- Oracle Cloud Always Free A1 (ADR-049 decisione 2): non utilizzabile a costo zero dall'account del proprietario.
- VPS a pagamento sempre acceso: rompe la regola 8; resta la condizione di attivazione di TOBE-012, con un'ADR di deroga.
- Vetrina effimera in un job di GitHub Actions: massimo 6 ore per job, nessun URL pubblico senza un tunnel (componente e destinazione di rete nuovi).
- Riserva B di ADR-049 (Keycloak su Cloud Run, DB su Neon, hub e web su Render): tre fornitori, bus da ripensare con un'ADR, hub in `enterprise` oltre i 512 MB di Render.
- Aiven come host: offre solo servizi di dati, non esegue hub, web e Keycloak.

### ADR-051 — Vetrina enterprise come ambiente di test, tutta dal browser; due realm di Keycloak
**Stato.** ACCETTATA il 2026-10-02 (Giuseppe), decisioni 1–10. I dettagli di attuazione sono in Q-670…Q-677, con il default proposto in uso finché il proprietario non decide. **Supera, solo per la vetrina,** Q-618 (account nominativi), Q-619 (portale chiuso), Q-630 (programma da terminale), la parte di Q-663 sugli account creati da un elenco segreto, e il vincolo di ADR-049 decisione 6 («nessuna password pubblicata»); **supera** la decisione 4 di ADR-049 per la sola vetrina (portale aperto ai membri fittizi prima di F2-SEC-08) e la modalità host fisso rimasta dopo ADR-050 (timer di Q-624, proxy ACME di Q-620). Le decisioni 6 e 7 valgono per tutte le installazioni e **precisano** ADR-027.
**Contesto.** Il proprietario vuole fare tutto dal browser, partendo dalla demo pubblica su Vercel e senza terminale: accendere la vetrina, scegliere un utente di test, autenticarsi come operatore o come membro, usare il backoffice con un programma di esempio, accumulare punti e aprire la console di Keycloak. Con ADR-049 e ADR-050 questo non regge: le credenziali degli operatori nascono da un segreto del codespace e si leggono con `sudo cat`, il programma si carica con `vetrina.sh programma` e il Device Authorization Grant (Q-630), il portale è chiuso (Q-619), in `enterprise` le azioni entrano solo da una fonte `SOURCE` con chiave propria e la console di Keycloak è solo sulla porta privata 8180 (#199). La vetrina contiene solo dati fittizi e si azzera ricreando il codespace: è un **ambiente di test**, non una produzione. Un ambiente di produzione vergine è un tema separato e questa ADR non lo progetta. Oggi Keycloak ha un solo realm con un solo client `web` per backoffice e portale: SSO, policy delle password e anagrafica sono condivisi tra operatori e membri, e l'MFA distingue gli operatori solo per ruolo (`MFA_REQUIRED_ROLE`).
**Decisione.**
1. **Credenziali fisse e pubbliche per gli utenti di test.** Operatori `marta.admin` (`ADMIN`), `luca.marketing` (`MARKETING`), `elena.legal` (`LEGAL`), `paolo.care` (`CARE`), `sara.analyst` (`ANALYST`) e tre membri legati alle storie del seed (Anna Rossi, appena iscritta; Marco Bianchi, Silver; Giulia Ferri, vicina alla soglia), con la registrazione già completata, più Laura, membro senza profilo per lo scenario di registrazione da zero, hanno password fisse, non temporanee, scritte in chiaro nel runbook della vetrina e nella pagina Mintlify «Vetrina enterprise» sotto l'avviso «ambiente di test, dati fittizi». Le importa **solo** l'overlay di vetrina dei realm; `realm.json` di base e il chart Helm restano senza credenziali e `check-realm` lo verifica. In ogni installazione `enterprise` hub e BFF rifiutano gli utenti di test, salvo nell'ambiente di test dichiarato del codespace (Q-676).
   - 1a. **OTP degli operatori con un seme TOTP fisso e documentato** (chiave e QR nel runbook e su Mintlify): il login resta password più codice a 6 cifre. L'overlay toglie l'azione richiesta «Configura OTP» e permette il riuso del codice nei 30 secondi (`otpPolicyCodeReusable`), così due visitatori sullo stesso account non si respingono.
   - 1b. **Password dell'amministratore di Keycloak fissa e documentata**; la console del realm `master` resta privata (decisione 8).
2. **Portale aperto ai soli membri fittizi della vetrina**, prima di M8.10f e F2-SEC-08: eccezione a ADR-048, Q-410 e Q-619 limitata alla vetrina. La registrazione libera di account nel realm resta chiusa; la registrazione del profilo dal portale (PT-16) è lo scenario di Laura. HUB-02 mostra il portale e il login del membro. Legame tra utenti di test e membri: Q-673.
3. **Programma di esempio da un pulsante.** In HUB-02 e nel backoffice, solo per `ADMIN` e solo nella vetrina, «Carica il programma di esempio»: il BFF applica la configurazione di `seed/` con il token dell'operatore collegato, quindi attore reale e una voce di audit per scrittura (regola 21), con la logica di `scripts/vetrina-programma.mjs`. I premi restano in `DRAFT` e li approva un altro operatore (regola 22). Sostituisce `vetrina.sh programma` (Q-630).
4. **Punti da una fonte di test dichiarata sull'API di ingestion vera**, con attore reale e audit; `/v1/demo/**` resta spento in `enterprise` e il menu Demo è nascosto. Meccanismo: Q-675.
5. **Niente host fisso.** La modalità host di `deploy/vetrina/` (Oracle Cloud, Ampere A1, tunnel SSH, timer di systemd, proxy ACME) si rimuove da script e documenti; il runbook è solo per Codespaces. Le ADR e le domande già decise restano come storia.
6. **Due realm nella stessa istanza di Keycloak.** `loyaltyhub` per operatori, fonti e job (com'è oggi) e `loyaltyhub-members` per i membri del portale e i widget, con flusso di login, policy di password, OTP, passkey, blocco dei tentativi e sessioni propri. Il resource server accetta due emittenti, `LH_OIDC_ISSUER` e il nuovo `LH_OIDC_MEMBER_ISSUER`, e per ciascuno solo i suoi ruoli: operatori, `SOURCE` e job dal primo, `MEMBER` dal secondo. Un token del realm membri con un ruolo da operatore è rifiutato. Il secondo emittente è facoltativo: senza, il comportamento resta quello di oggi (regola 14). Il BFF usa il realm operatori per il backoffice e quello dei membri per il portale, con due cookie di sessione distinti. Nessuna istanza in più: stessa memoria, nessun componente nuovo (regola 8-bis).
7. **Nessun legame tra i due realm.** Nessun broker né collegamento di account: operatori e membri sono popolazioni distinte, e chi è sia dipendente sia membro ha due registrazioni. La console dei membri si apre con un amministratore di test del realm `loyaltyhub-members`, documentato.
8. **Console dei due realm pubbliche, ma limitate.** Il proxy pubblico apre la console di amministrazione dei soli realm `loyaltyhub` e `loyaltyhub-members`, ciascuna con un amministratore di test che gestisce utenti, gruppi, ruoli, sessioni ed eventi, non la configurazione del realm, i client, i provider d'identità né la posta (Q-671). Il realm `master` resta chiuso al pubblico: con una password pubblica chiunque potrebbe configurare un server di posta o un provider esterno e far partire richieste di rete dal codespace a nome del proprietario. Indirizzi e accesso privato a `master`: Q-670.
9. **Pulsante pubblico «Accendi la modalità Enterprise» sulla demo.** HUB-01 su Vercel avvia il codespace della vetrina con l'API di GitHub, lato server, con un token del proprietario limitato al ciclo di vita dei codespace di questo repository, custodito come segreto di Vercel (regola 20). È una nuova destinazione di rete in uscita del ruolo `web` nel profilo `demo`, solo verso `api.github.com` (Q-674). Impostare il segreto su Vercel è una modifica esterna: la fa il proprietario o Claude con il suo consenso esplicito.
10. **Keycloak ripristinato a ogni avvio.** A ogni avvio del codespace Keycloak torna alla configurazione del repository: i tre realm (`master` compreso, con le impostazioni del proprietario in `deploy/idp/vetrina/master.json`), gli utenti di test con identificativi fissi, le password e il seme OTP. I dati del programma nel database dell'hub restano; si azzerano solo ricreando il codespace. Una modifica fatta nelle console e non portata nel repository sparisce al riavvio. Meccanismo: Q-672.

**Integra e non supera** ADR-049 (decisioni 1, 2 come superata da ADR-050, 3, 5, 7), ADR-050, ADR-042 e ADR-044: la vetrina parte solo se le guardie di `enterprise` passano, senza esenzioni. La protezione dalle credenziali di test vale in **ogni** installazione `enterprise`: gli utenti di test sono riconoscibili dal token e l'hub e il BFF li rifiutano, salvo in un ambiente dichiarato di test e coordinato con il codespace (Q-676).
**Limite dichiarato (regola 21).** Le modifiche fatte nelle console di Keycloak restano negli eventi di amministrazione di Keycloak, attivi in entrambi i realm, ma non arrivano in `audit_entry` finché non c'è il ponte di M8.12 (Q-677).
**Conseguenze.**
- + Il requisito si soddisfa tutto dal browser: accensione, login di operatori e membri con MFA vera, programma, punti, console.
- + Il modello a due realm è già nella forma giusta per una produzione futura: `member_identity` lega `(iss, sub)` e `subjectRef` è l'HMAC di emittente e soggetto (ADR-048), quindi un secondo emittente non cambia lo schema.
- + Nessun componente, servizio, topic o `type` nuovo; nessun costo.
- − Sicurezza della vetrina pari a zero per scelta: chiunque abbia il link entra come qualunque utente di test e può modificare i dati fittizi o gli utenti nelle console; si rimedia riavviando (Keycloak) o ricreando il codespace (dati).
- − Il pulsante pubblico permette a chiunque di consumare le ore gratuite di Codespaces del proprietario; il codespace si spegne da solo dopo l'inattività e il budget a 0 lo ferma a fine quota senza costi (ADR-050 decisione 6).
- − Più lavoro una tantum: hub con due emittenti, BFF con due configurazioni OIDC, due file di realm, due overlay di vetrina.
- − Una modifica utile fatta in una console va riportata nel repository con una PR, altrimenti si perde al riavvio.
**Attuazione.** Una PR per fetta (regola 16), in ordine: questa ADR con Q-670…Q-677; **R1** hub con due emittenti (`lh-common`); **R2** realm `loyaltyhub-members`, BFF con due login, compose, Helm, `check-realm` e smoke; **V8** utenti di test, credenziali documentate e ripristino di Keycloak a ogni avvio; **V9** ingressi dal browser (riquadro Enterprise in HUB-01 con il pulsante di accensione, schede degli utenti in HUB-02, console dei due realm nel proxy, voce «Utenti membri» nel backoffice); **V10** programma di esempio dal pulsante; **V11** punti dalla fonte di test; pulizia dei riferimenti all'host fisso. Dettagli in `docs/18` M8.14.
**Scartate.**
- Account nominativi con password temporanea consegnata fuori banda (Q-618): non si usa dal browser senza il proprietario.
- OTP spento nella vetrina: la vetrina non mostrerebbe la MFA e servirebbe un'eccezione alla regola 22.
- Un realm con due client: SSO, policy e anagrafica condivisi tra operatori e membri; separare dopo vorrebbe dire migrare utenti veri.
- Due istanze di Keycloak: doppia memoria e gestione, contro la regola 8.
- Broker dal realm operatori verso quello dei membri, utenti in `master` con permessi sul solo realm membri, schermata nativa nel backoffice per gestire i membri (decisione 7).
- Console `master` pubblica con la password documentata: abuso della rete del codespace a nome del proprietario.
- Riaccendere `/v1/demo/**` in `enterprise`: rompe la separazione dei profili (docs/18 §3.15).
- Ripristino di Keycloak solo ricreando il codespace: le modifiche di un visitatore resterebbero per tutti fino alla ricreazione.

### ADR-052 — Strategia di test a tre livelli: cancello Playwright, collaudo di release con un agente nel browser, log dei servizi
**Stato.** ACCETTATA il 2026-10-02 (Giuseppe, opzione «Tre livelli»). I dettagli di attuazione sono in Q-680…Q-685, decise il 2026-10-02 con il default proposto (opzione A). **Integra** M9 (`docs/18`) e ADR-051 (la vetrina è l'ambiente di test); non supera nessuna decisione.
**Contesto.** Il proprietario vuole usare Claude in Chrome, l'estensione del browser che lavora nella sua scheda di claude.ai, come *automation tester*: interazioni vere sul Demo Hub, sul backoffice e sul portale, con i log del browser e delle altre componenti, a ogni major release. Oggi il repository ha: il testbook funzionale (`docs/16`, test unitari e vitest, ogni notte); lo smoke enterprise con login OIDC reale ma solo via HTTP, senza browser (`scripts/smoke-enterprise.mjs`); lo smoke pianificato della vetrina, solo `GET` e senza credenziali. L'harness Playwright di M9 (F2-QA-01, F2-QA-02) è pianificato ma non esiste ancora. Claude in Chrome legge il DOM, la console e le richieste di rete della sola scheda: non vede i log dei servizi, che stanno nei container. Le sue esecuzioni non sono ripetibili al 100%, durano minuti e richiedono il Chrome del proprietario aperto, quindi non può fare da cancello automatico. Dal 2026-10-02 la politica di rete dell'ambiente cloud di Claude ammette gli indirizzi `*.app.github.dev` della vetrina (prima `403` dal proxy): una sessione cloud può guidare un browser senza interfaccia contro la vetrina, senza il Chrome del proprietario.
**Decisione.**
1. **Livello 1, cancello deterministico.** L'unico cancello automatico resta Playwright di M9: si anticipa M9.1 (harness `e2e/`) con il job `e2e-pr` sul compose di riferimento in `enterprise`, con l'overlay di test del realm già usato dallo smoke enterprise. Una major release non si tagga con `e2e-pr` o `e2e-nightly` rossi.
2. **Livello 2, collaudo di release con un agente nel browser.** Prima di ogni major release (Q-681) un agente nel browser (una sessione cloud di Claude con Chromium senza interfaccia, oppure Claude in Chrome nella scheda del proprietario, Q-682) esegue un **copione di collaudo** versionato nel repository (Q-680) sulla vetrina, con le sole credenziali di test pubbliche di ADR-051. Per ogni passo raccoglie esito, errori della console, richieste di rete fallite e uno screenshot, e produce un rapporto che entra nel repository con una PR (Q-682). Il collaudo è **consultivo**: il proprietario decide il rilascio. Ogni difetto trovato diventa un test di regressione deterministico (Q-685), così l'esplorazione alimenta il livello 1.
3. **Livello 3, log dei servizi.** Un workflow di GitHub Actions `collaudo` alza lo stack `enterprise` di riferimento, esegue le journey Playwright e allega i log di tutti i container come artefatto (Q-683). I log non contengono dati personali né credenziali (regola 20) e il job li controlla con gitleaks prima di caricarli. Per legare un errore visto nel browser a una riga di log, le risposte d'errore RFC 9457 portano un `correlationId` mostrato negli stati d'errore delle schermate (Q-684); quella parte cambia l'interfaccia e si implementa solo dopo l'approvazione di mockup e flusso.
4. **Nessun componente nuovo.** Claude in Chrome non è una dipendenza del prodotto: nessun servizio, topic, endpoint o costo infrastrutturale in più (regole 5-bis e 8). I log non si espongono dalla vetrina: nessun endpoint pubblico dei log.

**Conseguenze.**
- + Il collaudo esplorativo trova i difetti che nessun test ha previsto (testi, flussi spezzati, stati vuoti, errori in console) con interazioni vere e la MFA vera della vetrina.
- + Ogni difetto trovato torna come test deterministico: il cancello cresce a ogni release.
- + Costo zero: Actions nei minuti gratuiti, vetrina su Codespaces (ADR-050), l'agente nel piano del proprietario.
- − Il collaudo dipende dalla vetrina accesa e dalla rete dell'ambiente cloud che la raggiunge; dura più di un test automatico.
- − I log del livello 3 vengono dallo stack di CI, non dalla vetrina: un difetto che si vede solo nella vetrina si riproduce in CI o si legge dal terminale del codespace.
- − Due esecuzioni dello stesso copione possono dare rapporti diversi; il rapporto è un'evidenza, non un esito binario.
**Attuazione.** Una PR per fetta (regola 16), in ordine: **T0** questa ADR con Q-680…Q-685; **T1** M9.1 anticipata con il job `e2e-pr`; **T2** copione di collaudo, formato del rapporto, prompt di avvio e `scripts/check-collaudo.mjs`; **T3** workflow `collaudo` con i log dei container; **T4** `correlationId` negli errori e negli stati d'errore, dopo l'approvazione di mockup e flusso. Dettagli in `docs/18` M9.
**Scartate.**
- Claude in Chrome come cancello di CI: non ripetibile, richiede un browser con una persona collegata.
- Endpoint pubblico dei log nella vetrina: un nuovo `@PublicEndpoint` che esporrebbe dati operativi a chiunque abbia il link.
- Solo Playwright, senza collaudo esplorativo: copre solo ciò che qualcuno ha già previsto.
- Solo Claude in Chrome, senza cancello: nessuna protezione dalle regressioni tra una release e l'altra.

### ADR-053 — Test in CI per i bug non noti: proprietà generative, mutation testing sul diff, fuzz delle API nelle PR, journey casuali con invarianti
**Stato.** ACCETTATA il 2026-10-02 (Giuseppe, opzione «Tutti e quattro»). I dettagli sono in Q-690…Q-694, decise il 2026-10-02 con il default proposto (opzione A). **Integra** ADR-052 (livello 1, il cancello deterministico) e `docs/06 §9`; non supera nessuna decisione.
**Contesto.** Il proprietario chiede che dalle prossime fette la CI abbia test più rigorosi, capaci di intercettare anche i bug non noti. Oggi la CI ha test unitari e d'integrazione scritti a esempi (circa 250 classi), ArchUnit, controlli di compatibilità dei contratti e il fuzz delle API con Schemathesis solo nel job notturno (`security-nightly.yml`). Un test a esempi verifica solo i casi che l'autore ha previsto; non c'è una misura di quanto i test colgano un difetto, e le journey e2e di M9 non esistono ancora.
**Decisione.**
1. **Proprietà generative.** Ogni fetta che tocca una regola numerata di `docs/03` o una regola di una scheda servizio aggiunge almeno una proprietà generativa che la verifica su input generati: jqwik per Java, fast-check per il web, entrambi solo con scope di test. Il seme di ogni esecuzione è stampato e un controesempio trovato diventa un test a esempi permanente (Q-690).
2. **Mutation testing sul codice cambiato.** Un job `mutation` esegue PIT (Java) e Stryker (web) solo sulle classi e sui file cambiati dalla PR rispetto a `main` e scrive nel riepilogo i mutanti sopravvissuti. Parte consultivo; diventa obbligatorio con una soglia sul diff (Q-691).
3. **Fuzz delle API nelle PR.** Una corsa breve di Schemathesis, con lo script del job notturno, sulle OpenAPI dei soli servizi toccati dalla PR; la corsa lunga resta notturna (Q-692).
4. **Journey casuali con invarianti.** Il fuzz delle journey di M9.2 (sequenze casuali di azioni con seme, poi le invarianti di M9: Σ lotti = saldo, stock ≤ totale, nessun doppione) si anticipa subito dopo T1 di ADR-052; un seme che fallisce diventa una journey di regressione (Q-693).
5. **Nessun componente nuovo.** Solo librerie di test e minuti di GitHub Actions; nessuna dipendenza nel codice di produzione (regola 8).

**Conseguenze.**
- + I test cercano attivamente il controesempio invece di confermare il caso previsto.
- + Il mutation testing misura l'efficacia dei test sul codice nuovo, dove nascono i bug.
- + Ogni bug trovato da un generatore resta come test a esempi: il cancello cresce.
- − CI più lunga: il job `mutation` e il fuzz breve aggiungono minuti alle PR (budget in Q-694).
- − Proprietà scritte male possono essere lente o instabili; il seme stampato le rende riproducibili.
**Attuazione.** Una PR per fetta (regola 16): **T0b** questa ADR con Q-690…Q-694; **U1** jqwik e fast-check, prime proprietà del wallet; **U2** job `mutation` consultivo; **U3** fuzz breve nelle PR; **U4** journey casuali con invarianti (dopo T1). Il codice lo scrivono subagenti Sonnet e la revisione la fa Opus (indicazione del proprietario del 2026-10-02). Dettagli in `docs/18` M9.
**Scartate.**
- Soglia di copertura delle righe: misura il codice eseguito, non quello verificato.
- Mutation testing su tutto il repository a ogni PR: troppo lento per i minuti gratuiti; resta possibile in un job notturno.
- Fuzz delle API solo notturno: un difetto arriva in `main` prima di essere visto.

### ADR-054 — Cypress con Cypress Cloud sulla demo pubblica, ogni cinque giorni, accanto a Playwright
**Stato.** ACCETTATA il 2026-10-02 (Giuseppe, opzione «Demo notturna, PR»; cadenza poi portata a una corsa ogni cinque giorni, Q-711). I dettagli sono in Q-710…Q-713, con il default proposto in uso finché il proprietario non decide. **Integra** ADR-052 e ADR-053; non supera nessuna decisione.
**Contesto.** Il proprietario vuole usare Cypress, con la registrazione delle corse su Cypress Cloud (piano gratuito, progetto `v1g3bz`), come secondo strumento accanto a Playwright. Playwright è già il cancello delle PR sul profilo `enterprise` (ADR-052 livello 1, T1). Il profilo `demo` (persone simulate con `X-LH-Actor`, demo pubblica su Vercel) oggi ha solo `scripts/smoke.sh` e lo smoke della demo, senza browser. Il piano gratuito di Cypress Cloud conta i risultati di test registrati ogni mese, con un tetto basso. La guida di Cypress prevede che il comando di registrazione, che contiene la *record key*, lo lanci una persona dal terminale; il proprietario lavora solo dal browser.
**Decisione.**
1. **Divisione dei compiti.** Playwright verifica il profilo `enterprise` nelle PR (login vero, MFA, invarianti). Cypress verifica il profilo `demo` sulla demo pubblica, con le persone simulate: Demo Hub, «Accendi la demo», backoffice e portale per persona, un'azione che produce punti (Q-710).
2. **Quando gira.** Un workflow `cypress-demo` ogni cinque giorni e a richiesta (`workflow_dispatch`), non nelle PR: la demo pubblica è quella in produzione su Vercel, non la build della PR. Registra su Cypress Cloud solo con la *record key* presente (Q-712).
3. **Budget del piano gratuito.** Al più 10 test per corsa e una corsa programmata ogni cinque giorni (Giuseppe, 2026-10-02); nessun retry automatico registrato (Q-711).
4. **Record key solo come segreto.** `CYPRESS_RECORD_KEY` sta solo nei segreti del repository GitHub, mai in un file, in un log o nella configurazione (regola 20); senza il segreto la corsa avviene lo stesso, senza registrazione. Impostarlo è una modifica esterna: la fa il proprietario, o Claude con il suo consenso esplicito.
5. **Dove sta.** Nel pacchetto `e2e/` di T1, con `e2e/cypress.config.ts` (`projectId: 'v1g3bz'`) e i test in `e2e/cypress/` (Q-713). Cypress entra solo come dipendenza di sviluppo di `e2e/`, mai nel web o nell'immagine.
6. **Nessun componente nuovo del prodotto.** Cypress Cloud è un servizio esterno degli strumenti di sviluppo: riceve solo esiti, screenshot e registrazioni della demo con dati fittizi, mai credenziali o dati personali (regola 7, regola 20).

**Conseguenze.**
- + La demo pubblica ha un controllo dal browser ogni cinque giorni, con la Test Replay e lo storico consultabili su Cypress Cloud senza terminale.
- + Nessuna sovrapposizione con Playwright: due profili, due strumenti.
- − Due strumenti di test da mantenere; Cypress aggiunge un binario da scaricare nel workflow programmato.
- − Il piano gratuito limita i risultati registrati: oltre il tetto le corse continuano senza registrazione.
- − Una corsa programmata scrive dati fittizi nella demo pubblica; si rimedia con il reset della demo (`POST /v1/demo/reset`).
**Attuazione.** **T0c** questa ADR con Q-710…Q-713; **C1** `fase2/M9.8a-cypress-demo` — Cypress in `e2e/`, `projectId`, i primi test della demo, il workflow `cypress-demo` (dipende da T1).
**Scartate.**
- Cypress nelle PR: consumerebbe il piano gratuito in pochi giorni e duplicherebbe il cancello Playwright.
- Test dei componenti React con Cypress: si sovrappone ai test vitest del web.
- Configurazione solo locale senza commit, come nella guida di Cypress: il proprietario non usa il terminale.
