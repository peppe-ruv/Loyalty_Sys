# Loyalty Hub

Piattaforma loyalty **vendor neutral** per Iren: riceve azioni premianti da qualunque sistema (CRM, billing, portali, partner), le trasforma in punti e tier, sblocca fasce di premi e genera azioni premianti dal programma annuale e dagli instant win. Un solo backoffice/CMS configura card, pop-up, catalogo, regole e concorsi.

Riferimenti: [Specifica](docs/SPECIFICA.md) · [Decisioni (ADR)](docs/adr/) · [Contratti API](docs/contracts/) · [Runbook](docs/runbooks/)

## Architettura in breve

```
Fonti (Salesforce, SAP via middleware, IrenYou/app, partner, SFTP)
  → ingress-adapters (REST/Kafka/file → evento CloudEvents, idempotenza)
  → Kafka
  → rules-engine → ledger (doppia valuta: PREMIO / STATUS) → tier-service
  → catalog-redemption · contest-service (instant win periziabile)
  → read-model → bff → site (Next.js) e widget · cms (Payload)
```

- Servizi di dominio in **Java 21/25 + Spring Boot**, eventi **CloudEvents 1.0** su Kafka, Postgres per servizio.
- Esercizio su **Amazon EKS in eu-south-1 (Milano)**: i dati dei concorsi restano in Italia (DPR 430/2001).
- Ogni componente esterno è dietro una porta sostituibile: `LoyaltyEngine`, `ContentProvider`, `EventBus`, `IdentityProvider`, `RewardFulfiller`.

## Struttura

| Cartella | Contenuto |
| --- | --- |
| `services/` | Maven multi-modulo: `common`, `ingress-adapters`, `rules-engine`, `ledger`, `tier-service`, `catalog-redemption`, `contest-service`, `identity-mapping`, `read-model`, `notifier` |
| `web/bff`, `web/site` | Backend for frontend (Node) e sito Next.js |
| `cms/` | Backoffice su Payload: collezioni e workflow per tipo di oggetto |
| `deploy/terraform` | VPC, EKS, RDS Postgres, MSK Kafka, ElastiCache, S3, Secrets Manager, backup/DR |
| `deploy/helm/loyalty-hub` | Umbrella chart: un template generico genera Deployment, Service, HPA, PDB per ogni servizio |
| `docs/` | Specifica, ADR, OpenAPI/AsyncAPI, runbook |
| `scripts/seed.sh` | Dati di esempio |

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
```

Sito http://localhost:3000 · Backoffice http://localhost:3002/admin · Ingresso API http://localhost:8081/v1/actions

## Sviluppo

```sh
make build   # mvn package
make test    # test unitari (regole, tier, generatore istanti vincenti, hash del registro giocate)
make lint    # helm lint + terraform fmt
```

CI (`ci.yml`): build e test Java, build del sito, `helm lint/template`, `terraform validate`. Release (`release.yml`): su tag `vX.Y.Z` costruisce e firma un'immagine per servizio, genera SBOM, esegue la scansione Trivy e pubblica il chart come OCI.

## Segreti

Nessuna credenziale nel repository. In cluster i segreti arrivano da AWS Secrets Manager tramite External Secrets (`<prefix>/db`, `<prefix>/instant-win`); in locale da `docker-compose.yml`. La chiave degli istanti vincenti è separata da tutto il resto.

## Modulo instant win

`services/contest-service` contiene il codice oggetto della perizia tecnica: `WinningInstantGenerator` (istanti pre-generati con `SecureRandom`, opzionalmente pesati per fascia oraria), `PlayLedger` (registro giocate con hash concatenato) e `InstantWinService` (assegnazione con `FOR UPDATE SKIP LOCKED`: nessun premio assegnato due volte). Nessun rilascio del modulo a concorso avviato.

## Stato

Scaffold iniziale (0.1.0): struttura, contratti, dominio principale e installazione. Punti aperti in `docs/SPECIFICA.md` → "Rischi, punti aperti e criteri di accettazione".
