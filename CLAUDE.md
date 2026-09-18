# CLAUDE.md — guida per agenti di codice · coding-agent guide

> 🇮🇹 Questo file viene letto dagli agenti di codice (e da chiunque apra il repository) prima di toccare il codice.
> Dice cos'è il progetto, dove sta cosa, come si verifica una modifica, quali regole non si rompono e come si estende.
> Le sezioni **§3 Regole** e **§7 Ricette** sono normative: un contributo che le viola va rifatto.
> 🇬🇧 This file is read by coding agents (and by anyone opening the repository) before touching the code.
> It states what the project is, where things live, how a change is verified, which invariants must hold and how to
> extend the platform. Sections **§3 Invariants** and **§7 Recipes** are normative: a contribution that breaks them
> must be redone.

Documentazione estesa · Extended documentation: [`README.md`](README.md) ·
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · [`docs/FEATURES.md`](docs/FEATURES.md) ·
[`docs/DECISIONING.md`](docs/DECISIONING.md) · [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) ·
[`docs/INTEGRATION.md`](docs/INTEGRATION.md) · [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md) ·
[`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) · [`docs/ROADMAP.md`](docs/ROADMAP.md) ·
[`docs/GLOSSARY.md`](docs/GLOSSARY.md) · [`docs/adr/`](docs/adr/)

---

## 1. Cos'è · What it is

Loyalty Hub è una piattaforma loyalty vendor neutral ed event-driven: riceve azioni premianti da qualunque
sistema, le trasforma in unità e livelli, sblocca premi, gestisce concorsi e gamification e decide cosa proporre a chi
su quale canale, con motivazione registrata. Tutto ciò che il business deve poter cambiare vive nel backoffice.
Versione corrente **0.7.0**.

**EN** — Loyalty Hub is a vendor-neutral, event-driven loyalty platform: it ingests rewarding actions from any system,
turns them into units and tiers, unlocks rewards, runs contests and gamification, and decides what to offer to whom on
which channel, with a recorded rationale. Everything the business must be able to change lives in the back office.
Current version **0.7.0**.

Lingua · Language: identificatori in inglese; documenti pubblici bilingui (IT/EN); ogni requisito ha un codice
(`RF-nn`, `RI-nn`, `RC-nn`, `RT-nn`) e ogni scelta strutturale un `ADR-nnn` in `docs/adr/`. I commenti citano il
requisito che implementano · identifiers in English; public documents bilingual; every requirement has an id and every
structural choice an ADR; comments cite the requirement they implement.

---

## 2. Mappa del repository · Repository map

| Cartella · Folder | Contenuto · Content |
| --- | --- |
| `services/` | Maven multi-modulo, Java 21 / Spring Boot 3.5: `common` + 14 servizi di dominio (§4) |
| `cms/` | Backoffice Payload CMS (TypeScript): collezioni di contenuto e configurazione, endpoint di simulazione e BI, viste |
| `web/bff/` | Backend-for-frontend Node: area membro, console operatore, prossima azione, inbox, consensi, identità |
| `web/site/` | Sito Next.js e widget incorporabili |
| `web/backoffice-design-system/` | Design system del backoffice: contratti dei pattern, componenti React, token `--lh-*` sulle primitive [Design Tokens Italia](https://github.com/italia/design-tokens-italia) (ADR-026, ADR-027), regole eseguibili |
| `web/playground/` | Playground statico (export Next.js) che mostra pattern e motore decisionale su dati finti |
| `deploy/terraform/` | Infrastruttura: rete, cluster, database, Kafka, cache, storage, segreti, osservabilità |
| `deploy/helm/loyalty-hub/` | Umbrella chart: un template generico genera Deployment/Service/HPA/PDB per ogni voce di `values.yaml → services` |
| `deploy/observability/`, `deploy/bi/` | Values, regole di alert, cruscotti; warehouse e BI |
| `analytics/` | Schema del warehouse (consumo dai topic → fatti → viste KPI) ed export dei cruscotti |
| `docs/` | Documentazione, ADR, contratti (OpenAPI/AsyncAPI), runbook |
| `docker-compose.yml`, `Makefile`, `scripts/` | Ambiente locale con la stessa topologia della produzione, comandi, dati di esempio |
| `.github/workflows/` | `ci.yml` (build, test, lint), `release.yml` (immagini firmate per servizio, SBOM, scansione) |

---

## 3. Regole che non si rompono · Invariants

1. **Il registro è l'unica verità sui saldi · The ledger is the only truth about balances.** Nessun servizio scrive
   movimenti se non tramite le API del registro; le tabelle sono append-only. Previsioni, policy ed esperimenti **non
   modificano mai** punti, saldi, denaro, eligibilità o status: entrano solo nel punteggio delle azioni discrezionali.
2. **Azioni contrattuali contro discrezionali · Contractual vs discretionary actions.** Le azioni contrattuali
   (punti, livello, badge, attributi, eventi) sono sempre applicate; quelle di contatto e di premio sono arbitrate.
   Spostare un'azione da una classe all'altra è una scelta del backoffice, non del codice.
3. **Nessun dato personale nella piattaforma · No personal data in the platform.** L'identità è un identificatore
   opaco; anagrafica e recapiti restano nel CRM. Niente email o telefoni in chiaro (solo hash come identificatori),
   niente dati personali in log, metriche, tracce, eventi, warehouse.
4. **Idempotenza ovunque · Idempotency everywhere.** Ogni scrittura ha una chiave; ogni consumatore tollera il
   replay. La chiave si considera consumata **solo dopo** che la scrittura è andata a buon fine: all'ingresso la
   pubblicazione attende la conferma del broker e, se manca, la chiave viene rilasciata e la fonte riceve un errore.
   Un duplicato è tollerato dal disegno, un'azione persa no. La chiave di un movimento identifica l'*effetto*, non
   l'azione, e comincia sempre con la chiave dell'azione (`azione:campagna`, `azione:decisione:tipo`): si deriva con
   `RulesConfig.postingKey` o `DecisionService.effectKey`, mai a mano, perché lo storno cerca per prefisso. Nel BFF
   le chiavi si compongono solo con `web/bff/src/keys.js` e dove l'operazione muove valore il `clientRef` del
   chiamante è obbligatorio.
   **EN** — Every write carries a key and every consumer tolerates replay. A key counts as consumed **only after** the
   write succeeded: at ingress, publication waits for the broker acknowledgement and, if it never comes, the key is
   released and the source gets an error. A duplicate is by design tolerable, a lost action is not. A ledger key
   identifies the *effect*, not the action, and always starts with the action key, so reversal can look it up by
   prefix; derive it with the helpers, never by hand.
5. **Concorsi · Contests.** Configurazione bloccata a concorso avviato, istanti vincenti da generatore crittografico,
   registro giocate a prova di manomissione, dati nella region prevista. Non modificare il servizio dei concorsi senza
   leggere [ADR-007](docs/adr/ADR-007.md) e i requisiti `RF-30`…`RF-36`.
6. **Configurazione dal backoffice, non da codice · Configuration from the back office, not from code.** Ciò che
   marketing, legale o antifrode devono poter cambiare vive in una collezione con workflow e versione; i servizi
   leggono solo i documenti pubblicati, con cache e ripiego. Una funzionalità nuova mette i suoi parametri in una
   collezione, mai in `application.yml`.
7. **Retro-compatibilità · Backward compatibility.** Contratti di eventi e API sono versionati; un cambio
   incompatibile è una nuova versione con periodo di coesistenza. I flag che riportano alla modalità precedente al
   livello decisionale restano disponibili.
8. **Confini di contesto · Context boundaries.** Una funzionalità che tocca due contesti passa da eventi, non da join.
   Un servizio non legge mai le tabelle di un altro.

---

## 4. Servizi · Services

| Servizio · Service | Porta | Schema | Ruolo · Role |
| --- | --- | --- | --- |
| `common` | — | — | Evento canonico, tipi, valute, metriche, ponte log |
| `ingress-adapters` | 8081 | ingress | Normalizzazione, idempotenza, schemi, codici, catalogo prodotti, quote, coda di scarto |
| `rules-engine` | 8082 | rulesengine | Campagne, espressioni, automazioni, referral, simulatore, valutazione senza effetti |
| `ledger` | 8083 | ledger | Movimenti append-only, wallet, sospensioni, blocchi, trasferimenti, outbox |
| `tier-service` | 8084 | tierservice | Tier set, soglie, discesa, benefici, override |
| `catalog-redemption` | 8085 | catalogredemption | Catalogo premi, riscatti e stati, lotti, conversione unità, paga con i punti |
| `contest-service` | 8086 | contestservice | Instant win, ruota, registro giocate |
| `identity-mapping` | 8087 | identitymapping | Grafo identità, risoluzione, merge/unmerge, alias |
| `read-model` | 8088 | readmodel | Customer 360, proiezioni, snapshot, audience |
| `notifier` | 8089 | notifier | Modelli, webhook firmati, consegna omnicanale, inbox, coda operatore |
| `segment-service` | 8090 | segmentservice | Segmenti, collezioni di valori, appartenenze |
| `member-service` | 8091 | memberservice | Adesione, stati, campi custom, identificatori, referral, consensi, GDPR |
| `engagement-service` | 8092 | engagementservice | Achievement, challenge, badge, classifiche, cicli premianti |
| `decision-service` | 8093 | decisionservice | Motore decisionale, prossima azione, decision log, previsioni, esperimenti |
| `fraud-service` | 8094 | fraudservice | Segnali di rischio, punteggio, blocco automatico |

Struttura interna di ogni servizio, convenzioni e ricette: [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) §3–§4.
**EN** — Per-service internal structure, conventions and recipes: [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) §3–§4.

Flusso principale · Main flow:

```
fonte · source → ingress-adapters → topic azioni · actions topic
   → decision-service: contesto (read-model) + valutazione (rules-engine) + offerte + previsioni + esperimento + policy
        → effetti contrattuali (ledger, tier, engagement, member)
        → decisione → notifier/delivery → canali · channels
        → azioni interne rientrano dallo stesso topic · internal actions re-enter from the same topic
   → fraud-service → rischio → Customer 360 (vincolo del motore) + blocco unità
   → read-model → bff → sito e widget · site and widgets
```

---

## 5. Eventi e contratti · Events and contracts

| Elemento · Element | Regola · Rule |
| --- | --- |
| Busta · Envelope | CloudEvents 1.0: `id` = chiave di idempotenza, `type` versionato, `source` come URN, `subject` = `member:<id>`, estensione di correlazione ereditata lungo il ciclo |
| Azione premiante · Rewarding action | `actionType` in `UPPER_SNAKE`, chiave di idempotenza, riferimento esterno, istante dell'azione, riferimento di storno, attributi validati dallo schema del backoffice |
| Azioni interne · Internal actions | Stessa forma delle esterne, stesso topic: ciò che la piattaforma produce è premiabile come il resto |
| Topic | Un topic per dominio, più coda di scarto; chiave di partizione `memberId`; consumatori idempotenti |
| API | `/v1/...` con specifica pubblicata; paginazione keyset sulle liste che crescono; chiave di idempotenza nel corpo delle scritture |
| Compatibilità · Compatibility | Un cambio incompatibile è una nuova versione di topic o percorso, mai una modifica in loco |

Dettaglio · Detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §4 e [`docs/contracts/`](docs/contracts/).

---

## 6. Costruire e verificare · Build and verify

```sh
make build            # compila tutti i moduli Java · build all Java modules
make test             # test unitari · unit tests
make up               # ambiente locale: database, Kafka, cache, servizi, CMS, BFF, sito
make seed-local       # dati di esempio · sample data
make up-observability # Prometheus, Grafana, Loki, Tempo, OTel
make up-bi            # warehouse e BI · warehouse and BI
make lint             # chart e infrastruttura · chart and infrastructure
make check            # tutto: Java, design system, sito, BFF, backoffice · everything
make check-ds         # design system: eslint, tsc, vitest, build del pacchetto
make check-web        # BFF, sito, backoffice Payload e playground · BFF, site, Payload back office and playground
make playground       # solo il playground statico · the static playground alone
```

Verifica minima prima di un commit · Minimum pre-commit check:

```sh
make build && make test          # obbligatorio · mandatory
make check-web                   # se la modifica tocca web/ o cms/ · if the change touches web/ or cms/
make check-ds                    # se tocca il design system · if it touches the design system
```

I test di ogni modulo comprendono un banco di integrazione su database reale (`PostgresIntegrationTest` in `common`,
pubblicato come test-jar): un contenitore Postgres condiviso, le migrazioni vere e un test di avvio per servizio. Serve
un demone Docker; in sua assenza i test di integrazione falliscono e non vanno disattivati.
**EN** — Each module's tests include an integration bench on a real database (`PostgresIntegrationTest` in `common`,
published as a test-jar): one shared Postgres container, the real migrations and a boot test per service. It needs a
Docker daemon; without one the integration tests fail and must not be disabled.

L'integrazione continua è l'arbitro: build e test Java, build del sito e del backoffice, validazione del
chart e dell'infrastruttura, controllo sintattico delle regole di alert e dello schema del warehouse. Non lasciare mai
un modulo che compila solo con doppi di test.
**EN** — Continuous integration is the referee: Java build and tests, site and back-office builds, chart and
infrastructure validation, alert-rule and warehouse-schema syntax checks. Never leave a module that only compiles
against test doubles.

---

## 7. Ricette · Recipes

| Obiettivo · Goal | Passi essenziali · Essential steps |
| --- | --- |
| Nuovo tipo di azione premiante | Schema nel backoffice (o costante del tipo) → emissione sulla busta canonica → eventuali esclusioni per decisioni e rischio → una campagna la premia senza codice |
| Nuovo effetto di campagna | Tipo di effetto e valutazione nel motore regole → mappatura del candidato → esecuzione dell'effetto → opzione nella policy → test |
| Nuova azione decisionale | Tipo di azione → classificazione contrattuale/discrezionale → chiave di propensione → gruppo nel motore → effetto o consegna → opzione nel backoffice → riga in `docs/DECISIONING.md` |
| Nuovo vincolo di policy | Campo nella policy → controllo nel motore con codice motivo → mappatura dal backoffice → campo nel backoffice → test del caso che scatta e di quello che non scatta |
| Nuova previsione | Chiave deterministica nel provider a regole → elenco delle chiavi standard → routing nel backoffice → uso nel punteggio o nelle condizioni |
| Nuovo segnale antifrode | Segnale nella policy → osservazione sulla finestra → valore osservato nella valutazione → opzione nel backoffice → test |
| Nuovo canale di consegna | Adattatore → registrazione → codice canale nelle opzioni di instradamento |
| Nuova collezione nel backoffice | Collezione con bozze, versioni e stato → registrazione e permessi → lettura dal servizio con cache e ripiego → descrizioni per l'operatore |
| Nuovo servizio | Copiare un servizio come modello → porta e schema propri → moduli, Compose, chart, matrice di release → variabili `<NOME>_URL` → aggiornare architettura e struttura |
| Nuova metrica o KPI | Metodo nella facciata delle metriche → riga in `docs/OBSERVABILITY.md` → eventuale alert con runbook → per la BI: fatto, vista, grafico |

Dettaglio con esempi · Detail with examples: [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) §6 e
[`docs/DECISIONING.md`](docs/DECISIONING.md) §12.

---

## 8. Documentazione da tenere allineata · Documentation to keep in sync

Nello stesso commit della modifica: capacità → [`docs/FEATURES.md`](docs/FEATURES.md); architettura o
contratti → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) e [`docs/contracts/`](docs/contracts/); livello decisionale
→ [`docs/DECISIONING.md`](docs/DECISIONING.md); metriche e alert → [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md) e
il runbook; scelta strutturale → **nuovo** ADR; stato → [`docs/ROADMAP.md`](docs/ROADMAP.md); termini nuovi →
[`docs/GLOSSARY.md`](docs/GLOSSARY.md).
**EN** — In the same commit as the change: capability → [`docs/FEATURES.md`](docs/FEATURES.md); architecture or
contracts → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/contracts/`](docs/contracts/); decision layer →
[`docs/DECISIONING.md`](docs/DECISIONING.md); metrics and alerts → [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md)
and the runbook; structural choice → a **new** ADR; status → [`docs/ROADMAP.md`](docs/ROADMAP.md); new terms →
[`docs/GLOSSARY.md`](docs/GLOSSARY.md).

---

## 9. Contesto normativo che condiziona il codice · Regulatory context that shapes the code

Concorsi e operazioni a premio (normativa italiana): comunicazione preventiva, cauzione, durata dichiarata,
server nella region prevista, perizia tecnica sul software di assegnazione, gestione dei premi non assegnati.
Protezione dei dati: consensi separati per finalità con base giuridica, diritto alla cancellazione (anonimizzazione),
portabilità (export), conservazione del registro giocate per obbligo di legge, warehouse senza dati personali con
retention dichiarata. In caso di dubbio vince la scelta conservativa e va scritta in un ADR.

**EN** — Prize promotions (Italian regulations): prior notification, bond, declared duration, servers in the required
region, technical certification of the awarding software, handling of unclaimed prizes. Data protection: separate
consents per purpose with a legal basis, right to erasure (anonymisation), portability (export), statutory retention of
the play log, a warehouse without personal data and a declared retention. Where uncertain, the conservative option
wins and is recorded in an ADR.
