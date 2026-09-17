# Loyalty Hub

Piattaforma loyalty **vendor neutral** per Iren: riceve azioni premianti da qualunque sistema (CRM, billing, portali, partner), le trasforma in punti e tier, sblocca fasce di premi e genera azioni premianti dal programma annuale e dagli instant win. Un solo backoffice/CMS configura card, pop-up, catalogo, regole e concorsi.

Riferimenti: [Specifica](docs/SPECIFICA.md) · [Parità con Open Loyalty](docs/PARITA-OPEN-LOYALTY.md) · [Catalogo funzionale (edizione attuale)](docs/CATALOGO-FUNZIONALE.md) · [Osservabilità e BI](docs/OSSERVABILITA-BI.md) · [Decisioni (ADR)](docs/adr/) · [Contratti API](docs/contracts/) · [Runbook](docs/runbooks/)

Copertura funzionale: **almeno quella di Open Loyalty**, edizione open source (ADR-017) ed edizione attuale (ADR-018): campagne con
trigger, effetti, limiti, espressioni e automazioni; referral multilivello; wallet configurabili con blocchi e trasferimenti; achievement,
challenge, badge, classifiche con cicli premianti, ruota della fortuna; tier set a condizioni multiple; eventi con schema, campi custom,
collezioni, catalogo prodotti; segmenti a 30 criteri; premi con stati di evasione completi, buoni a conversione, paga con i punti;
postazione operatore, modelli di messaggio, webhook — più doppia valuta, tier annuali e instant win conforme al DPR 430.

## Architettura in breve

```
Fonti (Salesforce, SAP via middleware, IrenYou/app, partner, SFTP)
  → ingress-adapters (REST/Kafka/file → evento CloudEvents, idempotenza)
  → Kafka
  → rules-engine (campagne: trigger, condizioni ed effetti con espressioni) → ledger (wallet configurabili) → tier-service (tier set)
  → engagement-service (achievement, challenge, badge, classifiche) · catalog-redemption · contest-service (instant win, ruota)
  · member-service (adesione, referral, campi custom, GDPR) · segment-service (segmenti, collezioni)
  → read-model → bff (area membro, postazione operatore) → site (Next.js) e widget · cms (Payload) · notifier (modelli, webhook)
```

- Servizi di dominio in **Java 21/25 + Spring Boot**, eventi **CloudEvents 1.0** su Kafka, Postgres per servizio.
- Esercizio su **Amazon EKS in eu-south-1 (Milano)**: i dati dei concorsi restano in Italia (DPR 430/2001).
- Ogni componente esterno è dietro una porta sostituibile: `LoyaltyEngine`, `ContentProvider`, `EventBus`, `IdentityProvider`, `RewardFulfiller`.

## Struttura

| Cartella | Contenuto |
| --- | --- |
| `services/` | Maven multi-modulo: `common`, `ingress-adapters`, `rules-engine`, `ledger`, `tier-service`, `segment-service`, `member-service`, `engagement-service`, `catalog-redemption`, `contest-service`, `identity-mapping`, `read-model`, `notifier` |
| `web/bff`, `web/site` | Backend for frontend (Node) e sito Next.js |
| `cms/` | Backoffice su Payload: collezioni e workflow per tipo di oggetto |
| `deploy/terraform` | VPC, EKS, RDS Postgres, MSK Kafka, ElastiCache, S3, Secrets Manager, backup/DR |
| `deploy/helm/loyalty-hub` | Umbrella chart: un template generico genera Deployment, Service, HPA, PDB per ogni servizio |
| `docs/` | Specifica, ADR, OpenAPI/AsyncAPI, runbook |
| `scripts/seed.sh` | Dati di esempio |

## Osservabilità e BI (enterprise, tutto open source)

Metriche di business e tecniche da ogni servizio (Micrometer), tracce e log via OpenTelemetry; **Prometheus** in HA con
**Thanos** su S3, **Alertmanager**, **Grafana** (SSO) con tre cruscotti e alert SLO, **Loki** per i log, **Tempo** per le tracce.
Andamenti del programma nel backoffice: **ClickHouse** alimentato in tempo reale dai topic Kafka e **Apache Superset**
incorporato nella vista «Andamenti» con token ospite e row-level security. Dettagli: `docs/OSSERVABILITA-BI.md`, ADR-019/020.

```sh
make observability   # stack di monitoraggio in HA nel namespace observability (dopo make infra e make install)
make bi              # ClickHouse a 3 repliche + Superset in HA nel namespace analytics, cruscotti importati
```

## Installazione su AWS EKS in tre comandi

Prerequisiti: account AWS con permessi amministrativi sulla region `eu-south-1`, `aws` CLI autenticata, `terraform ≥ 1.9`, `kubectl`, `helm ≥ 3.14`, `make`. Tempo stimato: 30–40 minuti, di cui ~25 per l'infrastruttura.

```sh
make infra   ENV=dev        # 1/3  terraform apply: rete, EKS, RDS, MSK, Redis, S3, segreti, External Secrets, LB controller
make install ENV=dev        # 2/3  helm install: tutti i servizi dalle immagini pubblicate su GHCR
make seed                   # 3/3  membro demo, azioni premianti di esempio, verifica dei saldi
```

Le immagini vengono da `ghcr.io/<organizzazione>/<servizio>:<versione>` (workflow `release.yml`, firmate con cosign). Per usare un registry diverso: `--set global.registry=...`. Per produzione: `ENV=prod` e `deploy/terraform/environments/prod.tfvars`.

Disinstallazione: `make uninstall` poi `make destroy` (irreversibile).

## Ambiente locale (senza AWS)

```sh
make up          # docker compose: Postgres, Kafka, Redis, tutti i servizi, CMS, BFF, sito
make seed-local  # azioni di esempio → saldi
make up-observability   # Grafana http://localhost:3005 (admin/admin), Prometheus :9090, Loki, Tempo, OTel Collector
make up-bi              # ClickHouse :8123, Superset http://localhost:8088 (admin/admin) e vista Andamenti nel backoffice
```

Sito http://localhost:3000 · Backoffice http://localhost:3002/admin · Ingresso API http://localhost:8081/v1/actions · Membri http://localhost:8091/v1/members · Segmenti http://localhost:8090/v1/segments

## Sviluppo

```sh
make build   # mvn package
make test    # test unitari (campagne con SpEL, regole, tier set, segmenti, achievement/challenge/classifiche, ruota, schemi, referral, riscatti, istanti vincenti)
make lint    # helm lint + terraform fmt
```

CI (`ci.yml`): build e test Java, build del sito, `helm lint/template`, `terraform validate`. Release (`release.yml`): su tag `vX.Y.Z` costruisce e firma un'immagine per servizio, genera SBOM, esegue la scansione Trivy e pubblica il chart come OCI.

## Segreti

Nessuna credenziale nel repository. In cluster i segreti arrivano da AWS Secrets Manager tramite External Secrets (`<prefix>/db`, `<prefix>/instant-win`); in locale da `docker-compose.yml`. La chiave degli istanti vincenti è separata da tutto il resto.

## Modulo instant win

`services/contest-service` contiene il codice oggetto della perizia tecnica: `WinningInstantGenerator` (istanti pre-generati con `SecureRandom`, opzionalmente pesati per fascia oraria), `PlayLedger` (registro giocate con hash concatenato) e `InstantWinService` (assegnazione con `FOR UPDATE SKIP LOCKED`: nessun premio assegnato due volte). Nessun rilascio del modulo a concorso avviato.

## Stato

Scaffold 0.4.0: struttura, contratti, dominio principale, parità funzionale con Open Loyalty (RF-60..RF-116), osservabilità enterprise e BI nel backoffice (RF-117..RF-124), installazione. Punti aperti in `docs/SPECIFICA.md` → "Rischi, punti aperti e criteri di accettazione".
