# Loyalty Hub

> 🇮🇹 **Documento bilingue.** Ogni sezione presenta prima il testo italiano, poi l'equivalente inglese marcato **EN**.
> 🇬🇧 **Bilingual document.** Each section shows the Italian text first, then its English counterpart marked **EN**.

Piattaforma di loyalty open source e **vendor neutral**: riceve azioni premianti da qualunque sistema (CRM, fatturazione,
portali, app, partner, file), le trasforma in punti e livelli, sblocca premi, gestisce concorsi e gamification e decide
cosa proporre a ciascun cliente. Un solo backoffice configura contenuti, regole, premi, concorsi e policy.

**EN** — Open source, **vendor neutral** loyalty platform: it ingests rewarding actions from any system (CRM, billing,
portals, apps, partners, files), turns them into points and tiers, unlocks rewards, runs prize contests and gamification,
and decides what to offer each customer. A single back office configures content, rules, rewards, contests and policies.

| | |
| --- | --- |
| Versione · Version | `0.7.0` |
| Licenza · License | Apache-2.0 — vedi [`LICENSE`](LICENSE) · see [`LICENSE`](LICENSE) |
| Runtime | Java 21 (target 25 LTS) · Spring Boot 3.5 · Node 20 · Next.js · PostgreSQL · Kafka · Redis |
| Esercizio · Operations | Kubernetes (AWS EKS, `eu-south-1`) · Terraform · Helm |
| Documenti · Documents | [Specifica · Specification](docs/SPECIFICATION.md) · [Architettura · Architecture](docs/ARCHITECTURE.md) · [Funzionalità · Features](docs/FEATURES.md) · [Livello decisionale · Decisioning](docs/DECISIONING.md) · [Sviluppo · Development](docs/DEVELOPMENT.md) · [Integrazione · Integration](docs/INTEGRATION.md) · [Osservabilità · Observability](docs/OBSERVABILITY.md) · [Roadmap](docs/ROADMAP.md) · [Glossario · Glossary](docs/GLOSSARY.md) · [Decisioni · ADR](docs/adr/README.md) · [Contratti · Contracts](docs/contracts/) · [Runbook](docs/runbooks/README.md) |

---

## 1. Cosa fa · What it does

In parole semplici: qualunque cosa faccia un cliente (una bolletta pagata, un acquisto, una lettura inviata, un
questionario compilato, un check-in in negozio) diventa un **evento**. La piattaforma legge l'evento, applica le regole
scritte dal marketing nel backoffice, accredita punti sul portafoglio giusto, aggiorna il livello, propone il premio o il
messaggio più adatto e registra il perché di ogni scelta.

**EN** — In plain words: anything a customer does (a bill paid, a purchase, a meter reading submitted, a survey completed,
a store check-in) becomes an **event**. The platform reads the event, applies the rules written by marketing in the back
office, credits points to the right wallet, updates the tier, proposes the most suitable reward or message, and records
the reason behind every choice.

Capacità principali · Main capabilities:

| Area | Cosa offre · What you get |
| --- | --- |
| Accumulo · Earning | Campagne con trigger, condizioni, effetti, limiti, budget, espressioni, automazioni pianificate · Campaigns with triggers, conditions, effects, limits, budget, expressions, scheduled automations |
| Portafogli · Wallets | Portafogli configurabili (punti premio, punti status, valute custom), scadenze, sospensioni, blocchi, trasferimenti tra membri · Configurable wallets (reward points, status points, custom units), expiry, pending, blocks, member-to-member transfers |
| Livelli · Tiers | Tier set con condizioni multiple, benefici continuativi e una tantum, discesa configurabile, progresso al livello successivo · Tier sets with multiple conditions, recurring and one-off benefits, configurable downgrade, progress to next tier |
| Premi · Rewards | Catalogo con 10 tipi di premio, lotti di codici, buoni a valore dinamico, conversione unità, paga con i punti, stati di evasione · Catalogue with 10 reward types, coupon pools, dynamic-value vouchers, unit conversion, pay-with-points, fulfilment states |
| Gamification | Achievement, challenge, badge, classifiche con cicli premianti, ruota della fortuna · Achievements, challenges, badges, leaderboards with rewarding cycles, fortune wheel |
| Concorsi · Prize contests | Instant win con istanti vincenti pre-generati, registro giocate a prova di manomissione, conformità DPR 430/2001 · Instant win with pre-generated winning moments, tamper-evident play log, Italian DPR 430/2001 compliance |
| Decisione · Decisioning | Next Best Action, vincoli di pressione commerciale, consensi, rischio, esperimenti A/B, decision log spiegabile · Next Best Action, contact-pressure constraints, consent, risk, A/B experiments, explainable decision log |
| Segmenti · Segments | 30 criteri, collezioni di valori riutilizzabili, ricalcolo notturno e a evento, export · 30 criteria, reusable value collections, nightly and event-driven recomputation, export |
| Consegna · Delivery | Inbox in app, push, email, SMS, webhook CRM, coda operatore, fallback e ore di silenzio · In-app inbox, push, email, SMS, CRM webhook, operator queue, fallback and quiet hours |
| Privacy | Consensi con finalità e base giuridica, storico immutabile, export di portabilità, anonimizzazione · Consent with purpose and legal basis, immutable history, portability export, anonymisation |
| Osservabilità · Observability | Metriche di business e tecniche, SLO con alert, log e tracce correlati, BI self-service nel backoffice · Business and technical metrics, SLOs with alerts, correlated logs and traces, self-service BI inside the back office |

---

## 2. Architettura in breve · Architecture at a glance

```
fonti · sources (CRM, fatturazione/billing, portale e app, partner, SFTP)
  └─▶ ingress-adapters     REST · Kafka · file → evento canonico CloudEvents, idempotenza, validazione schema
        └─▶ Kafka (loyalty.*.v1)
              └─▶ decision-service     contesto cliente + candidati + previsioni + policy → decisione spiegabile
                    ├─▶ effetti contrattuali · contractual effects  → ledger · tier-service · engagement-service · member-service
                    ├─▶ effetti di contatto · contact effects       → notifier/delivery → app, push, email, SMS, CRM, operatore
                    └─▶ azioni interne · internal actions           → rientrano dallo stesso topic · re-enter via the same topic
              └─▶ fraud-service        segnali → riskScore/riskLevel → contesto + blocco ledger
              └─▶ read-model           Customer 360 → bff → sito Next.js e widget
                    └─▶ ClickHouse → Apache Superset → vista «Andamenti» nel backoffice
```

Tre principi non negoziabili. **Uno**: il `ledger` è l'unica verità sui saldi e le sue tabelle sono append-only.
**Due**: ogni componente esterno sta dietro una porta sostituibile (`LoyaltyEngine`, `ContentProvider`, `EventBus`,
`IdentityProvider`, `RewardFulfiller`, `PredictionProvider`, `ChannelAdapter`). **Tre**: tutto ciò che il business deve
poter cambiare vive nel backoffice, non nel codice.

**EN** — Three non-negotiable principles. **One**: the `ledger` is the single source of truth for balances and its tables
are append-only. **Two**: every external component sits behind a replaceable port (`LoyaltyEngine`, `ContentProvider`,
`EventBus`, `IdentityProvider`, `RewardFulfiller`, `PredictionProvider`, `ChannelAdapter`). **Three**: anything the
business must be able to change lives in the back office, not in code.

Dettaglio per sviluppatori senior: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (contesti, consistenza, concorrenza,
modalità di guasto, scalabilità) e [`CLAUDE.md`](CLAUDE.md) (convenzioni e ricette di estensione).

**EN** — Deep dive for senior developers: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (contexts, consistency,
concurrency, failure modes, scalability) and [`CLAUDE.md`](CLAUDE.md) (conventions and extension recipes).

---

## 3. Struttura del repository · Repository layout

| Cartella · Folder | Contenuto · Content |
| --- | --- |
| `services/` | Maven multi-modulo: `common` e 14 servizi di dominio Java/Spring Boot · Maven multi-module: `common` plus 14 Java/Spring Boot domain services |
| `web/bff/` | Backend for frontend Node: area membro, console operatore, NBA, inbox, consensi · Node backend for frontend: member area, operator console, NBA, inbox, consent |
| `web/site/` | Sito e widget Next.js (app router) · Next.js site and embeddable widgets (app router) |
| `web/backoffice-design-system/` | Design system del backoffice: contratti dei 15 pattern, **componenti React** che li implementano, token di tema · back-office design system: contracts for the 15 patterns, the **React components** implementing them, theme tokens |
| `web/playground/` | Playground statico, pubblicato su <https://loyalty-hub-playground.vercel.app>: i pattern e il motore decisionale su dati finti, senza backend · static playground, live at the same URL: patterns and decision engine on fake data, no backend |
| `cms/` | Backoffice Payload CMS: contenuti, configurazione del programma, policy decisionali · Payload CMS back office: content, programme configuration, decision policies |
| `deploy/terraform/` | AWS `eu-south-1`: VPC, EKS, RDS, MSK, ElastiCache, S3, Secrets Manager, osservabilità · AWS `eu-south-1`: VPC, EKS, RDS, MSK, ElastiCache, S3, Secrets Manager, observability |
| `deploy/helm/loyalty-hub/` | Umbrella chart: un template generico genera Deployment/Service/HPA/PDB per servizio · Umbrella chart: one generic template renders Deployment/Service/HPA/PDB per service |
| `deploy/observability/`, `deploy/bi/` | Values Helm, regole di alert, cruscotti, ClickHouse e Superset · Helm values, alert rules, dashboards, ClickHouse and Superset |
| `analytics/` | Schema ClickHouse (Kafka engine → fatti → viste KPI) ed export Superset · ClickHouse schema (Kafka engine → facts → KPI views) and Superset exports |
| `docs/` | Specifica, architettura, funzionalità, integrazione, sviluppo, ADR, contratti OpenAPI/AsyncAPI, runbook · Specification, architecture, features, integration, development, ADRs, OpenAPI/AsyncAPI contracts, runbooks |
| `docker-compose.yml`, `Makefile`, `scripts/` | Ambiente locale con la stessa topologia della produzione, comandi, dati di esempio · Local environment mirroring production topology, commands, sample data |

---

## 4. Avvio locale in due comandi · Local start in two commands

Prerequisiti: Docker 24+, Docker Compose v2, `make`, 8 GB di RAM liberi. Nessun account cloud necessario.

**EN** — Prerequisites: Docker 24+, Docker Compose v2, `make`, 8 GB of free RAM. No cloud account required.

```sh
make up          # Postgres, Kafka, Redis, tutti i servizi, CMS, BFF, sito · all services, CMS, BFF, site
make seed-local  # membro demo, azioni di esempio, saldi · demo member, sample actions, balances
```

| Interfaccia · Interface | URL |
| --- | --- |
| Sito cliente · Customer site | http://localhost:3000 |
| Backoffice · Back office | http://localhost:3002/admin |
| BFF | http://localhost:3001 |
| Ingresso azioni · Action ingress | http://localhost:8081/v1/actions |
| Membri · Members | http://localhost:8091/v1/members |
| Segmenti · Segments | http://localhost:8090/v1/segments |

Estensioni opzionali · Optional add-ons:

```sh
make up-observability   # Grafana :3005 (admin/admin), Prometheus :9090, Loki, Tempo, OTel Collector
make up-bi              # ClickHouse :8123, Superset :8088 (admin/admin), vista Andamenti · Trends view
```

Prima azione premiante da riga di comando · First rewarding action from the command line:

```sh
curl -X POST http://localhost:8081/v1/actions \
  -H 'Content-Type: application/json' \
  -d '{
        "memberId": "demo-member",
        "actionType": "BILL_PAID",
        "idempotencyKey": "demo-001",
        "occurredAt": "2026-01-15T10:00:00Z",
        "channel": "web",
        "attributes": { "amount": 84.50 }
      }'

curl http://localhost:8083/v1/ledger/members/demo-member/wallets
```

---

## 5. Installazione su Kubernetes · Kubernetes install

Prerequisiti: account AWS con permessi amministrativi su `eu-south-1`, `aws` CLI autenticata, `terraform ≥ 1.9`,
`kubectl`, `helm ≥ 3.14`, `make`. Tempo stimato 30-40 minuti, di cui ~25 per l'infrastruttura.

**EN** — Prerequisites: AWS account with administrative permissions on `eu-south-1`, authenticated `aws` CLI,
`terraform ≥ 1.9`, `kubectl`, `helm ≥ 3.14`, `make`. Estimated time 30-40 minutes, ~25 of which for infrastructure.

```sh
make infra   ENV=dev   # 1/3  rete, EKS, RDS, MSK, Redis, S3, segreti · network, EKS, RDS, MSK, Redis, S3, secrets
make install ENV=dev   # 2/3  helm install di tutti i servizi dalle immagini pubblicate · all services from published images
make seed              # 3/3  dati di esempio e verifica dei saldi · sample data and balance check
```

Le immagini provengono da `ghcr.io/<organizzazione>/<servizio>:<versione>`, firmate con cosign e corredate di SBOM
(workflow `release.yml`). Per un altro registry: `--set global.registry=...`. Per la produzione: `ENV=prod` con
`deploy/terraform/environments/prod.tfvars`. Disinstallazione: `make uninstall` e poi `make destroy` (irreversibile).

**EN** — Images come from `ghcr.io/<organisation>/<service>:<version>`, cosign-signed and shipped with an SBOM
(`release.yml` workflow). For a different registry: `--set global.registry=...`. For production: `ENV=prod` with
`deploy/terraform/environments/prod.tfvars`. Teardown: `make uninstall` then `make destroy` (irreversible).

Osservabilità e BI in cluster · Observability and BI in cluster:

```sh
make observability   # Prometheus in HA + Thanos + Alertmanager + Grafana + Loki + Tempo (namespace observability)
make bi              # ClickHouse 3 repliche + Superset in HA, cruscotti importati (namespace analytics)
```

---

## 6. Sviluppo · Development

```sh
make build   # mvn -f services/pom.xml package
make test    # JUnit 5 + AssertJ su tutti i moduli · across all modules
make lint    # helm lint + terraform fmt/validate
```

La CI (`ci.yml`) esegue build e test Java, build del sito e del CMS, `helm lint/template`, `terraform validate`,
`promtool check rules` e lo schema ClickHouse su nodo singolo. La release (`release.yml`) su tag `vX.Y.Z` costruisce e
firma un'immagine per servizio, genera l'SBOM, esegue la scansione Trivy e pubblica il chart come artefatto OCI.

**EN** — CI (`ci.yml`) runs Java build and tests, site and CMS builds, `helm lint/template`, `terraform validate`,
`promtool check rules` and the ClickHouse schema on a single node. Release (`release.yml`) on a `vX.Y.Z` tag builds and
signs one image per service, generates the SBOM, runs a Trivy scan and publishes the chart as an OCI artifact.

Convenzioni, regole invarianti e ricette per estendere la piattaforma: [`CLAUDE.md`](CLAUDE.md).

**EN** — Conventions, invariants and recipes for extending the platform: [`CLAUDE.md`](CLAUDE.md).

---

## 7. Sicurezza e segreti · Security and secrets

Nessuna credenziale nel repository. In cluster i segreti arrivano da AWS Secrets Manager tramite External Secrets
(`<prefix>/db`, `<prefix>/instant-win`); in locale da `docker-compose.yml` con valori di sviluppo. La chiave che firma il
registro delle giocate dei concorsi è isolata da tutto il resto e ruotata separatamente.

**EN** — No credentials in the repository. In cluster, secrets come from AWS Secrets Manager through External Secrets
(`<prefix>/db`, `<prefix>/instant-win`); locally from `docker-compose.yml` with development values. The key that signs the
contest play log is isolated from everything else and rotated separately.

---

## 8. Modulo concorsi · Prize contest module

`services/contest-service` è progettato per essere sottoposto a perizia tecnica: `WinningInstantGenerator` estrae gli
istanti vincenti con `SecureRandom` (opzionalmente pesati per fascia oraria), `PlayLedger` tiene un registro delle
giocate con hash concatenato e `InstantWinService` assegna il premio con `SELECT ... FOR UPDATE SKIP LOCKED`, così nessun
premio viene assegnato due volte. La configurazione di un concorso è bloccata a concorso avviato e nessun rilascio del
modulo avviene mentre un concorso è in corso.

**EN** — `services/contest-service` is designed to withstand third-party technical certification:
`WinningInstantGenerator` draws winning moments with `SecureRandom` (optionally weighted by time band), `PlayLedger`
keeps a hash-chained play log, and `InstantWinService` assigns prizes with `SELECT ... FOR UPDATE SKIP LOCKED` so no
prize is ever awarded twice. Contest configuration is locked once a contest starts, and the module is never released
while a contest is running.

---

## 9. Stato e roadmap · Status and roadmap

Versione 0.7.0: struttura del monorepo, contratti, dominio completo dei 14 servizi, catalogo funzionale
(RF-01..RF-116), osservabilità e BI enterprise (RF-117..RF-124), livello decisionale configurabile (RF-125..RF-136),
installazione locale e su Kubernetes. Punti aperti e criteri di accettazione in
[`docs/SPECIFICATION.md`](docs/SPECIFICATION.md); backlog tecnico in [`docs/ROADMAP.md`](docs/ROADMAP.md).

**EN** — Version 0.7.0: monorepo layout, contracts, complete domain of the 14 services, functional catalogue
(RF-01..RF-116), enterprise observability and BI (RF-117..RF-124), configurable decision layer (RF-125..RF-136), local
and Kubernetes installation. Open points and acceptance criteria in [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md); technical
backlog in [`docs/ROADMAP.md`](docs/ROADMAP.md).

---

## 10. Contribuire · Contributing

Leggere [`CLAUDE.md`](CLAUDE.md) prima di aprire una pull request: contiene le regole che non si rompono (ledger,
idempotenza, privacy, concorsi, configurazione dal backoffice), le convenzioni di codice e le ricette per aggiungere
funzionalità senza toccare il core. Ogni modifica funzionale aggiorna la documentazione e, se cambia una scelta
strutturale, aggiunge un ADR nuovo in `docs/adr/`.

**EN** — Read [`CLAUDE.md`](CLAUDE.md) before opening a pull request: it holds the invariants (ledger, idempotency,
privacy, contests, back-office configuration), the coding conventions and the recipes for adding features without
touching the core. Every functional change updates the documentation and, if it changes a structural choice, adds a new
ADR under `docs/adr/`.
